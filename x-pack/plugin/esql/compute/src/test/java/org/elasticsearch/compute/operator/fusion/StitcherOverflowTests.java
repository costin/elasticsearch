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
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.test.TestWarningsSource;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Overflow proof for iter 12 (binding rule #3). The block path ({@link Stitcher#compileBlockLoop}) and the
 * overflow-aware checked vector path ({@link Stitcher#compileVectorLoopChecked}) must, for an overflowing
 * position, produce a {@code null} AND register a warning on the supplied {@link Warnings} — byte-for-byte the
 * behaviour the generated unfused evaluator emits ({@code try { result.appendLong(process(...)); } catch
 * (ArithmeticException e) { warnings().registerException(e); result.appendNull(); }}, see
 * {@code AddLongsEvaluator.eval}).
 *
 * <p>The fixture kernels below are byte-for-byte shaped like the production long {@code Add}/{@code Mul}/{@code Sub}
 * kernels — {@code Math.*Exact} delegates that throw {@link ArithmeticException} with message {@code "long overflow"}
 * on overflow — so the stitched body splices the same instruction sequence the real overflow-checked kernels do. The
 * descriptors are built {@code overflowChecked = true}, so the convenience constructor derives
 * {@code overflowExceptionType = "java.lang.ArithmeticException"}, exactly what the annotation processor emits onto
 * the real evaluators' {@code FUSION_DESCRIPTOR}, and what the stitcher catches around each spliced kernel body.
 *
 * <p>Everything runs on a real breaker-backed {@link BlockFactory}; the {@code @After} asserts the breaker is
 * balanced (every produced/consumed block released). Warnings are collected through a real
 * {@link Warnings#createWarnings COLLECT} {@code Warnings} and asserted via {@link #assertWarnings}.
 */
public class StitcherOverflowTests extends ESTestCase {

    /**
     * {@code long} kernels shaped exactly like the production {@code Add.processLongs} etc.: {@code Math.*Exact}
     * delegates (a {@code java.base} {@code INVOKESTATIC} in the spliced body) that throw
     * {@code ArithmeticException("long overflow")} on overflow.
     */
    public static final class LongKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        public static long sub(long a, long b) {
            return Math.subtractExact(a, b);
        }
    }

    /** The exact overflow exception type/message {@code Math.*Exact} raises, mirrored into the warning assertions. */
    private static final String OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: long overflow";
    private static final String FIRST_EXCEPTION_HEADER =
        "Line 1:1: evaluation of [overflow] failed, treating result as null. Only first 20 failures recorded.";
    private static final String MULTI_VALUE_WARNING =
        "Line 1:1: java.lang.IllegalArgumentException: single-value function encountered multi-value";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private Stitcher stitcher;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
        stitcher = new Stitcher(new TemplateRegistry());
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    private Warnings collectingWarnings() {
        return Warnings.createWarnings(DriverContext.WarningsMode.COLLECT, new TestWarningsSource("overflow"));
    }

    /**
     * The per-source {@code Warnings[]} the fused methods now take (one slot per warning-source kernel, indexed by
     * {@link Stitcher#warningsSourceIndices}). Every slot points at the same collecting instance so these fixtures —
     * which build all kernels from a single {@code TestWarningsSource("overflow")} — accumulate into one collector and
     * keep asserting via {@link #assertWarnings}, exactly as before the per-source threading landed.
     */
    private static Warnings[] warningsArray(FusionNode tree, Warnings w) {
        Warnings[] arr = new Warnings[Stitcher.warningsSourceCount(tree)];
        Arrays.fill(arr, w);
        return arr;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: deep expression where an intermediate (and separately the root) kernel overflows.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code (a + b) * c} over long blocks. Positions are hand-picked so that: an INTERMEDIATE add overflows,
     * the ROOT mul overflows, a clean position computes, and a clean position mixes in. Each overflowing position
     * must be null with a registered warning; the fused output must equal the unfused reference position-by-position.
     */
    public void testDeepExpressionIntermediateOverflowBlockPath() throws Throwable {
        FusionNode tree = addThenMulTree();
        MethodHandle handle = blockHandle(tree, 3);
        Warnings warnings = collectingWarnings();

        // a, b, c per position. p0: intermediate add overflows. p1: clean. p2: root mul overflows. p3: clean.
        long[] a = { Long.MAX_VALUE, 3, 2, 10 };
        long[] b = { 1, 4, 3, 20 };
        long[] c = { 7, 5, Long.MAX_VALUE, 2 };
        int positionCount = a.length;

        LongBlock ba = null, bb = null, bc = null, result = null;
        try {
            ba = singleValued(a);
            bb = singleValued(b);
            bc = singleValued(c);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, ba, bb, bc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            // p0 and p2 overflow; both raise ArithmeticException("long overflow"), deduplicated to a single header line.
            assertThat("p0 must be null", result.isNull(0), is(true));
            assertThat("p2 must be null", result.isNull(2), is(true));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(35L));
            assertThat("p3 value", result.getLong(result.getFirstValueIndex(3)), equalTo(60L));
        } finally {
            Releasables.closeExpectNoException(ba, bb, bc, result);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: mixed overflow / non-overflow, randomized, differential vs the unfused reference.
    // ---------------------------------------------------------------------------------------------------------------

    public void testMixedOverflowBlockPathMatchesUnfused() throws Throwable {
        FusionNode tree = addThenMulTree();
        MethodHandle handle = blockHandle(tree, 3);
        Warnings warnings = collectingWarnings();

        int positionCount = 512;
        long[] a = new long[positionCount];
        long[] b = new long[positionCount];
        long[] c = new long[positionCount];
        boolean anyOverflow = false;
        for (int p = 0; p < positionCount; p++) {
            // ~1/4 of positions seed an operand at an extreme so (a+b)*c can overflow; the rest stay bounded/clean.
            a[p] = randomBoolean() ? randomLongBetween(-1_000, 1_000) : Long.MAX_VALUE - randomLongBetween(0, 5);
            b[p] = randomLongBetween(-1_000, 1_000);
            c[p] = randomBoolean() ? randomLongBetween(-1_000, 1_000) : Long.MAX_VALUE - randomLongBetween(0, 5);
            if (refAddThenMul(a[p], b[p], c[p]) == null) {
                anyOverflow = true;
            }
        }

        LongBlock ba = null, bb = null, bc = null, result = null;
        try {
            ba = singleValued(a);
            bb = singleValued(b);
            bc = singleValued(c);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, ba, bb, bc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
        } finally {
            Releasables.closeExpectNoException(ba, bb, bc, result);
        }
        // Every overflow raises ArithmeticException("long overflow"), so identical warnings deduplicate to one line.
        if (anyOverflow) {
            assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: multi-value position -> null + carried-forward single-value warning.
    // ---------------------------------------------------------------------------------------------------------------

    public void testMultiValueNullPlusWarningBlockPath() throws Throwable {
        // add(in0, in1): p0 multi-value lhs, p1 clean, p2 null lhs (no warning), p3 multi-value rhs.
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionNode tree = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        MethodHandle handle = blockHandle(tree, 2);
        Warnings warnings = collectingWarnings();

        int positionCount = 4;
        long[][] lhs = { { 1L, 2L }, { 3L }, {}, { 9L } };
        long[][] rhs = { { 5L }, { 4L }, { 8L }, { 1L, 2L, 3L } };

        LongBlock lb = null, rb = null, result = null;
        try {
            lb = multiValued(lhs);
            rb = multiValued(rhs);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, lb, rb);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            assertThat("p0 multi-value lhs -> null", result.isNull(0), is(true));
            assertThat("p1 clean", result.isNull(1), is(false));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(7L));
            assertThat("p2 null lhs -> null", result.isNull(2), is(true));
            assertThat("p3 multi-value rhs -> null", result.isNull(3), is(true));
        } finally {
            Releasables.closeExpectNoException(lb, rb, result);
        }
        // p0 and p3 register the multi-value warning (deduplicated); p2's plain null registers NO warning.
        assertWarnings(FIRST_EXCEPTION_HEADER, MULTI_VALUE_WARNING);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Checked vector path: overflow -> nulls-capable *Block output + warning (ArrayVector cannot hold nulls).
    // ---------------------------------------------------------------------------------------------------------------

    public void testCheckedVectorPathOverflowProducesNullsAndWarnings() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        // The checked vector path reads backing arrays via the public VectorUnsafe forwarder (no teleport), so the
        // hidden class is defined into the caller's own package.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            Vector.class,
            Vector.class,
            Vector.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        // Dense single-valued vectors (no nulls/multi-values by construction): overflow is the only null source.
        long[] a = { Long.MAX_VALUE, 3, 2, 10 };
        long[] b = { 1, 4, 3, 20 };
        long[] c = { 7, 5, Long.MAX_VALUE, 2 };
        int positionCount = a.length;

        LongVector va = null, vb = null, vc = null;
        LongBlock result = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);
            result = (LongBlock) handle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            assertThat("p0 overflow -> null", result.isNull(0), is(true));
            assertThat("p2 overflow -> null", result.isNull(2), is(true));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(35L));
            assertThat("p3 value", result.getLong(result.getFirstValueIndex(3)), equalTo(60L));
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, result);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    /** No-overflow sanity for the checked vector path: every position is a value, no warning is registered. */
    public void testCheckedVectorPathNoOverflowProducesAllValues() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            Vector.class,
            Vector.class,
            Vector.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        int positionCount = 256;
        long[] a = new long[positionCount];
        long[] b = new long[positionCount];
        long[] c = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            a[p] = randomLongBetween(-1_000, 1_000);
            b[p] = randomLongBetween(-1_000, 1_000);
            c[p] = randomLongBetween(-1_000, 1_000);
        }

        LongVector va = null, vb = null, vc = null;
        LongBlock result = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);
            result = (LongBlock) handle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            for (int p = 0; p < positionCount; p++) {
                // Bounded operands cannot overflow, so the reference is always present.
                long expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                assertThat("p=" + p, result.isNull(p), is(false));
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected));
            }
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, result);
        }
        // No overflow occurred, so nothing was registered.
        assertWarnings();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code (a + b) * c} : Mul(Add(in0, in1), in2). Add overflow is intermediate; Mul overflow is at the root. */
    private static FusionNode addThenMulTree() {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(LongKernels.class, "mul", "(JJ)J", true, true);
        return new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    /** Unfused reference for {@code (a + b) * c}: null iff any {@code Math.*Exact} in the post-order chain overflows. */
    private static Long refAddThenMul(long a, long b, long c) {
        long sum;
        try {
            sum = Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
        try {
            return Math.multiplyExact(sum, c);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private MethodHandle blockHandle(FusionNode tree, int arity) throws Throwable {
        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        Class<?>[] params = new Class<?>[3 + arity];
        params[0] = BlockFactory.class;
        params[1] = Warnings[].class;
        params[2] = int.class;
        for (int i = 0; i < arity; i++) {
            params[3 + i] = LongBlock.class;
        }
        return MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(LongBlock.class, params));
    }

    private LongBlock singleValued(long[] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(values.length)) {
            for (long v : values) {
                builder.appendLong(v);
            }
            return builder.build();
        }
    }

    private LongBlock multiValued(long[][] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(values.length)) {
            for (long[] vv : values) {
                if (vv.length == 0) {
                    builder.appendNull();
                } else if (vv.length == 1) {
                    builder.appendLong(vv[0]);
                } else {
                    builder.beginPositionEntry();
                    for (long x : vv) {
                        builder.appendLong(x);
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }
}
