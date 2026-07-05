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
 * Differential proof for iter 10's vector fast path: a depth-2 tree {@code (a + b) * c} fused into a single
 * counted loop must produce, position-by-position, exactly what the unfused kernel chain
 * ({@code Math.multiplyExact(Math.addExact(a, b), c)}) would — over many random shapes including empty, single,
 * small, and &ge; 1024 position counts.
 *
 * <p>The fused hidden class is defined into the <b>caller's</b> own package/module (no {@code privateLookupIn}
 * teleport): it reads each dense {@code *ArrayVector}'s backing array through the public
 * {@code VectorUnsafe} forwarder ({@code INVOKESTATIC}) rather than the package-private {@code rawValues()}.
 * Everything runs on real breaker-backed {@link BlockFactory} vectors and real emitted bytecode — no mocks — and
 * the {@code @After} breaker-balance assert proves every produced vector is released.
 */
public class StitcherVectorFusionTests extends ESTestCase {

    /** Local {@code int} kernels mirroring the production {@code Add}/{@code Mul} int kernels (java.base-only bodies). */
    public static final class IntKernels {
        public static int processIntsAdd(int lhs, int rhs) {
            return Math.addExact(lhs, rhs);
        }

        public static int processIntsMul(int lhs, int rhs) {
            return Math.multiplyExact(lhs, rhs);
        }
    }

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    public void testLongAddMulTreeDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(KernelFixtures.class, "processLongsMul", "(JJ)J", true, true);
        // (a + b) * c
        FusionNode tree = new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        // The hidden class lands in the caller's own package (no teleport); it reads backing arrays via VectorUnsafe.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(LongVector.class, BlockFactory.class, int.class, Vector.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        int shapes = 120;
        for (int shape = 0; shape < shapes; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            long[] c = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so (a + b) * c cannot overflow long; overflow semantics are an iter-12 concern.
                a[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                b[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                c[p] = randomLongBetween(-1_000L, 1_000L);
            }

            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector v2 = blockFactory.newLongArrayVector(c, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1, v2);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    long expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, result);
            }
        }
    }

    public void testIntAddMulTreeDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(IntKernels.class, "processIntsAdd", "(II)I", true, true);
        FusionDescriptor mul = new FusionDescriptor(IntKernels.class, "processIntsMul", "(II)I", true, true);
        // (a + b) * c
        FusionNode tree = new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(IntVector.class, BlockFactory.class, int.class, Vector.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        int shapes = 100;
        for (int shape = 0; shape < shapes; shape++) {
            int positionCount = positionCountFor(shape);
            int[] a = new int[positionCount];
            int[] b = new int[positionCount];
            int[] c = new int[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so (a + b) * c cannot overflow int.
                a[p] = randomIntBetween(-1_000, 1_000);
                b[p] = randomIntBetween(-1_000, 1_000);
                c[p] = randomIntBetween(-1_000, 1_000);
            }

            IntVector v0 = blockFactory.newIntArrayVector(a, positionCount);
            IntVector v1 = blockFactory.newIntArrayVector(b, positionCount);
            IntVector v2 = blockFactory.newIntArrayVector(c, positionCount);
            IntVector result = null;
            try {
                result = (IntVector) handle.invoke(blockFactory, positionCount, v0, v1, v2);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    int expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                    assertThat("shape=" + shape + " p=" + p, result.getInt(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, result);
            }
        }
    }

    /**
     * B1 proof: a chain far too deep to inline within the 325-bytecode budget must still fuse on the plain-vector
     * path — by SPLITTING the expression into a {@code private static compute()} helper the tiny top-level loop calls
     * (rather than degrading to the block path). Build {@code (((in0 + in1) + in1) + …)} with 60 adds (its inline body
     * would be ~900 bytecodes), compile the vector loop, and assert it computes {@code a + 60*b} position-by-position —
     * proving the split class defines, verifies, links, and runs correctly.
     */
    public void testDeepChainSplitsAndComputesCorrectly() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        int adds = 60;
        FusionNode tree = new FusionNode.Input(0);
        for (int i = 0; i < adds; i++) {
            tree = new FusionNode.Kernel(add, List.of(tree, new FusionNode.Input(1)));
        }

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(LongVector.class, BlockFactory.class, int.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 40; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so a + 60*b cannot overflow long.
                a[p] = randomLongBetween(-1_000L, 1_000L);
                b[p] = randomLongBetween(-100L, 100L);
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    long expected = a[p];
                    for (int i = 0; i < adds; i++) {
                        expected = Math.addExact(expected, b[p]);
                    }
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, result);
            }
        }
    }

    /**
     * B1 split with embedded CONSTANTS: a deep chain {@code (((in0 + 1) + 1) + …)} with 60 constant-add levels forces
     * the split, and each level's {@code 1} is a distinct {@link FusionNode.Constant} — so the split {@code compute}
     * helper takes 60 trailing {@code long} constant params and the top-level loop must marshal all 60 into the call in
     * canonical emit order. Asserting the result is {@code a + 60} proves the split's constant plumbing is correct.
     */
    public void testDeepChainWithConstantsSplitsAndComputesCorrectly() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        int adds = 60;
        FusionNode tree = new FusionNode.Input(0);
        for (int i = 0; i < adds; i++) {
            tree = new FusionNode.Kernel(add, List.of(tree, new FusionNode.Constant(1L, 'J')));
        }

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);

        // (BlockFactory, int, Vector, long x adds) -> LongVector
        Class<?>[] params = new Class<?>[3 + adds];
        params[0] = BlockFactory.class;
        params[1] = int.class;
        params[2] = Vector.class;
        for (int i = 0; i < adds; i++) {
            params[3 + i] = long.class;
        }
        MethodHandle handle = MethodHandles.lookup()
            .findStatic(fused, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(LongVector.class, params));

        for (int shape = 0; shape < 30; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                a[p] = randomLongBetween(-1_000_000L, 1_000_000L);
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector result = null;
            try {
                Object[] args = new Object[3 + adds];
                args[0] = blockFactory;
                args[1] = positionCount;
                args[2] = v0;
                for (int i = 0; i < adds; i++) {
                    args[3 + i] = 1L;
                }
                result = (LongVector) handle.invokeWithArguments(args);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(a[p] + adds));
                }
            } finally {
                Releasables.closeExpectNoException(v0, result);
            }
        }
    }

    /** Guarantees coverage of empty, single, small, and &ge; 1024 position counts across the shape sweep. */
    private int positionCountFor(int shape) {
        if (shape == 0) {
            return 0;
        }
        if (shape == 1) {
            return 1;
        }
        if (shape % 20 == 0) {
            return 1024 + randomIntBetween(0, 2048);
        }
        return randomIntBetween(2, 256);
    }
}
