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
import org.elasticsearch.compute.data.IntVector;
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
 * Proves the stitcher can splice a <b>loop-bodied</b> kernel and run it correctly (#8a). {@code processSumTo}
 * ({@code sum(0..n-1)}) has a real {@code for} back-edge; when spliced into the per-position vector loop, each
 * position must run the inner loop and produce {@code sum(0..n-1)}. This exercises {@link SlotRemapper} shifting a
 * loop body's locals (the {@code i}/{@code s} slots) into the kernel's disjoint range and the frame computation
 * across the back-edge — the precursor to fusing code-point/byte-loop string kernels.
 */
public class StitcherLoopBodyTests extends ESTestCase {

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private final Stitcher stitcher = new Stitcher(new TemplateRegistry());

    @Before
    public void setUpBlockFactory() {
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

    public void testLoopBodyKernelSplicesAndComputes() throws Throwable {
        FusionDescriptor sumTo = new FusionDescriptor(KernelFixtures.class, "processSumTo", "(I)I", false, true);
        FusionNode tree = new FusionNode.Kernel(sumTo, List.of(new FusionNode.Input(0)));

        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(IntVector.class, BlockFactory.class, int.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int positionCount : new int[] { 0, 1, 5, 33, 128 }) {
            int[] n = new int[positionCount];
            for (int p = 0; p < positionCount; p++) {
                n[p] = randomIntBetween(0, 40); // non-negative so the loop runs 0..n-1
            }
            IntVector in = blockFactory.newIntArrayVector(n, positionCount);
            IntVector out = null;
            try {
                out = (IntVector) handle.invoke(blockFactory, positionCount, in);
                assertThat(out.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    int expected = 0;
                    for (int i = 0; i < n[p]; i++) {
                        expected += i;
                    }
                    assertThat("pc=" + positionCount + " p=" + p + " n=" + n[p], out.getInt(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(in, out);
            }
        }
    }
}
