/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.fusion.BodyExtractorTests.KernelFixtures;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Differential proof for the opt-in SIMD (Panama Vector-API) fast path (B2): the fused {@code a > b} comparison loop
 * emitted with {@code IntVector}/{@code LongVector} lane compares + a scalar tail must produce, position-by-position,
 * exactly the scalar result {@code a[p] > b[p]}. The test FORCES the SIMD path (it calls
 * {@link Stitcher#compileVectorLoopSimd} directly and invokes it), so any wrong Vector-API descriptor surfaces as a
 * link/verify failure here rather than silently degrading. Skipped when the Vector API is unavailable in this runtime
 * ({@link FusionSimd#available()} — e.g. a non-HotSpot VM or a runtime without {@code jdk.incubator.vector}).
 */
public class StitcherSimdFusionTests extends ESTestCase {

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;

    @Before
    public void setUpBlockFactory() {
        assumeTrue("Vector API (jdk.incubator.vector) not available in this runtime", FusionSimd.available());
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
    }

    @After
    public void checkBreakerReleased() {
        if (breaker != null) {
            assertThat(breaker.getUsed(), is(0L));
        }
    }

    public void testLongGreaterThanSimdDifferential() throws Throwable {
        FusionDescriptor gt = new FusionDescriptor(KernelFixtures.class, "processLongsGreaterThan", "(JJ)Z", false, true);
        FusionNode tree = new FusionNode.Kernel(gt, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        // Grant this test class's module a read edge to jdk.incubator.vector so the hidden class (defined via this
        // lookup, hence in this module) links its Vector-API calls — the production FusionSimd.enableReadsFrom does
        // the same for the esql module.
        FusionSimd.enableReadsFrom(getClass().getModule());

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoopSimd(MethodHandles.lookup(), tree, "GT");
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(BooleanVector.class, BlockFactory.class, int.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        // Sweep sizes so we exercise below-one-SPECIES (all-tail), exact-multiple (all-SIMD), and mixed shapes.
        for (int positionCount : new int[] { 0, 1, 3, 7, 16, 17, 64, 129, 1024, 1000 + randomIntBetween(0, 500) }) {
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                a[p] = randomLongBetween(-50, 50);
                b[p] = randomLongBetween(-50, 50); // overlapping ranges so both true and false occur
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            BooleanVector result = null;
            try {
                result = (BooleanVector) handle.invoke(blockFactory, positionCount, v0, v1);
                assertThat("pc=" + positionCount, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    assertThat("pc=" + positionCount + " p=" + p, result.getBoolean(p), is(a[p] > b[p]));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, result);
            }
        }
    }

    /**
     * Double SIMD parity: the Vector-API lane compare must equal the scalar Java operator for EVERY IEEE special value
     * — NaN (false for &lt;/&lt;=/&gt;/&gt;=/==, true for !=), +0.0/-0.0 (equal), ±Inf — so a crafted grid of these
     * against normals is compared to the scalar result. Runs the SIMD body directly (forcing the path). Covers GT and
     * EQ (the ordered and the unordered-sensitive operators).
     */
    public void testDoubleSimdParityAcrossIeeeSpecials() throws Throwable {
        FusionSimd.enableReadsFrom(getClass().getModule());
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        double[] specials = { Double.NaN, 0.0, -0.0, 1.0, -1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 3.5, -3.5 };

        for (String[] opKernel : new String[][] { { "GT", "processDoublesGreaterThan" }, { "EQ", "processDoublesEqual" } }) {
            String op = opKernel[0];
            FusionDescriptor cmp = new FusionDescriptor(KernelFixtures.class, opKernel[1], "(DD)Z", false, true);
            FusionNode tree = new FusionNode.Kernel(cmp, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
            Class<?> fused = stitcher.compileVectorLoopSimd(MethodHandles.lookup(), tree, op);
            MethodType type = MethodType.methodType(BooleanVector.class, BlockFactory.class, int.class, Vector.class, Vector.class);
            MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

            // A grid of every (special x special) pair, padded past one SPECIES so both the SIMD body and the tail run.
            int positionCount = specials.length * specials.length;
            double[] a = new double[positionCount];
            double[] b = new double[positionCount];
            for (int i = 0; i < specials.length; i++) {
                for (int j = 0; j < specials.length; j++) {
                    a[i * specials.length + j] = specials[i];
                    b[i * specials.length + j] = specials[j];
                }
            }
            var v0 = blockFactory.newDoubleArrayVector(a, positionCount);
            var v1 = blockFactory.newDoubleArrayVector(b, positionCount);
            BooleanVector result = null;
            try {
                result = (BooleanVector) handle.invoke(blockFactory, positionCount, v0, v1);
                for (int p = 0; p < positionCount; p++) {
                    boolean expected = op.equals("GT") ? a[p] > b[p] : a[p] == b[p];
                    assertThat(op + " p=" + p + " a=" + a[p] + " b=" + b[p], result.getBoolean(p), is(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, result);
            }
        }
    }
}
