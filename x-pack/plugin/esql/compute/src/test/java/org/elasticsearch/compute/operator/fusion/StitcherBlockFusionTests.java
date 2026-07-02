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
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Differential proof for iter 11's block path: an expression tree fused into a single position-by-position loop
 * over {@code *Block} inputs (which may carry nulls and multi-valued positions) must produce, position-by-position,
 * exactly the same VALUES and the same NULL positions as an unfused kernel chain over the same inputs.
 *
 * <p><b>Null / multi-value semantics under test.</b> The reference here mirrors the generated unfused evaluator
 * (e.g. {@code AddLongsEvaluator.eval(int, LongBlock, LongBlock)}): a position is {@code null} whenever any consumed
 * input is null ({@code getValueCount(p) == 0}) or multi-valued ({@code getValueCount(p) > 1}); otherwise the single
 * value at {@code getFirstValueIndex(p)} feeds the kernel chain. Because every leaf's null/multi-value state
 * propagates up through each kernel in an unfused chain, the composed result is null at {@code p} iff any leaf is
 * null/multi-value at {@code p} — which is exactly the guard the fused block loop emits. The random trees generated
 * here always reference every input in {@code [0, arity)} (indices are compacted after generation), so the fused
 * "check all inputs" guard and the unfused "check each operand" chain coincide.
 *
 * <p><b>Mandatory carried-forward differentials (iter-10 dual review).</b> {@link #testNonCommutativeDivVectorPath()}
 * / {@link #testNonCommutativeDivBlockPath()} fuse the NON-COMMUTATIVE {@code (a - b) / c} (bounded non-zero
 * {@code c}) to prove operand ORDER is preserved — a commutative-only test cannot. {@link #testDepth3VectorPath()} /
 * {@link #testDepth3BlockPath()} fuse the DEPTH-3 {@code ((a + b) * c) - d} to prove deep-tree slot disjointness.
 * Both run on the iter-10 vector fast path and the iter-11 block path.
 *
 * <p>Everything runs on a real breaker-backed {@link BlockFactory} with real emitted bytecode — no mocks — and the
 * {@code @After} breaker-balance assert proves every produced block/vector is released. The kernels are plain
 * (non-{@code Exact}) arithmetic so no kernel throws on overflow; wraparound (long/int) is deterministic and shared
 * by the fused body and the reference, so values match bit-for-bit without invoking iter-12's overflow handling.
 */
public class StitcherBlockFusionTests extends ESTestCase {

    /**
     * {@code long} kernels that are byte-for-byte shaped like the production {@code Add}/{@code Sub}/{@code Mul}/
     * {@code Div} long kernels: {@code Math.*Exact} delegates (a {@code java.base} {@code INVOKESTATIC} in the spliced
     * body, under the fused loop's try/catch) and a zero-guarded {@code /} (a forward branch + {@code new
     * ArithmeticException}). The production kernels cannot be on this module's test classpath (they live in the
     * downstream {@code :x-pack:plugin:esql} module), so these fixtures compile to the same instruction sequence the
     * stitcher will splice for the real kernels; the real non-{@code java.base} double kernel is proven separately by
     * {@code StitcherBlockCrossModuleTests} in esql-proper. Inputs in every test are bounded so none of these throws.
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

        public static long div(long a, long b) {
            if (b == 0L) {
                throw new ArithmeticException("/ by zero");
            }
            return a / b;
        }
    }

    /** {@code int} kernels mirroring {@link LongKernels} with the int {@code Math.*Exact} variants. */
    public static final class IntKernels {
        public static int add(int a, int b) {
            return Math.addExact(a, b);
        }

        public static int mul(int a, int b) {
            return Math.multiplyExact(a, b);
        }

        public static int sub(int a, int b) {
            return Math.subtractExact(a, b);
        }

        public static int div(int a, int b) {
            if (b == 0) {
                throw new ArithmeticException("/ by zero");
            }
            return a / b;
        }
    }

    /**
     * {@code double} kernels. {@code double} has no {@code Math.*Exact} form, so these are plain IEEE arithmetic (no
     * {@code java.base} {@code INVOKESTATIC}); the production-shaped double body with a non-{@code java.base} invoke
     * ({@code NumericUtils.asFiniteNumber} in the real {@code Add.processDoubles}) under the fused try/catch is proven
     * cross-module by {@code StitcherBlockCrossModuleTests}. Inputs are bounded so no {@code Infinity}/{@code NaN}
     * arises and fused vs reference are bit-identical.
     */
    public static final class DoubleKernels {
        public static double add(double a, double b) {
            return a + b;
        }

        public static double mul(double a, double b) {
            return a * b;
        }

        public static double sub(double a, double b) {
            return a - b;
        }

        public static double div(double a, double b) {
            return a / b;
        }
    }

    private enum Fill {
        NO_NULL,
        ALL_NULL,
        MIXED
    }

    /** A generated expression tree plus the number of distinct inputs it references (all of {@code [0, arity)}). */
    private record GenTree(FusionNode tree, int arity) {}

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

    // ---------------------------------------------------------------------------------------------------------------
    // Random block-path differentials over long / int / double, with random null + multi-value placements.
    // ---------------------------------------------------------------------------------------------------------------

    public void testLongRandomTreesBlockDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(LongKernels.class, "mul", "(JJ)J", true, true);
        FusionDescriptor sub = new FusionDescriptor(LongKernels.class, "sub", "(JJ)J", true, true);
        Map<FusionDescriptor, LongBinaryOperator> ops = new HashMap<>();
        ops.put(add, (a, b) -> a + b);
        ops.put(mul, (a, b) -> a * b);
        ops.put(sub, (a, b) -> a - b);
        List<FusionDescriptor> kernels = List.of(add, mul, sub);

        int shapes = 200;
        for (int s = 0; s < shapes; s++) {
            GenTree gen = randomExpression(kernels);
            int arity = gen.arity();
            MethodHandle handle = blockHandle(gen, LongBlock.class);

            int positionCount = positionCountFor(s);
            Fill fill = fillFor(s);
            long[][][] values = new long[arity][][];
            int[][] counts = new int[arity][];
            for (int i = 0; i < arity; i++) {
                counts[i] = valueCounts(positionCount, fill);
                values[i] = new long[positionCount][];
                for (int p = 0; p < positionCount; p++) {
                    long[] vv = new long[counts[i][p]];
                    for (int v = 0; v < vv.length; v++) {
                        // Bounded so an all-multiplyExact tree of <= 8 leaves (depth <= 3) cannot overflow long.
                        vv[v] = randomLongBetween(-30, 30);
                    }
                    values[i][p] = vv;
                }
            }

            LongBlock[] blocks = new LongBlock[arity];
            LongBlock result = null;
            try {
                Object[] args = new Object[3 + arity];
                args[0] = blockFactory;
                args[1] = Warnings.NOOP_WARNINGS;
                args[2] = positionCount;
                for (int i = 0; i < arity; i++) {
                    blocks[i] = buildLongBlock(positionCount, values[i]);
                    args[3 + i] = blocks[i];
                }
                result = (LongBlock) handle.invokeWithArguments(args);
                assertThat("s=" + s, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    long[] single = new long[arity];
                    boolean nullPos = false;
                    for (int i = 0; i < arity; i++) {
                        if (counts[i][p] != 1) {
                            nullPos = true;
                        } else {
                            single[i] = values[i][p][0];
                        }
                    }
                    if (nullPos) {
                        assertThat("s=" + s + " p=" + p + " expected null", result.isNull(p), is(true));
                    } else {
                        assertThat("s=" + s + " p=" + p + " expected non-null", result.isNull(p), is(false));
                        long expected = evalLong(gen.tree(), single, ops);
                        assertThat("s=" + s + " p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected));
                    }
                }
            } finally {
                closeAll(blocks, result);
            }
        }
    }

    public void testIntRandomTreesBlockDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(IntKernels.class, "add", "(II)I", true, true);
        FusionDescriptor mul = new FusionDescriptor(IntKernels.class, "mul", "(II)I", true, true);
        FusionDescriptor sub = new FusionDescriptor(IntKernels.class, "sub", "(II)I", true, true);
        Map<FusionDescriptor, IntBinaryOperator> ops = new HashMap<>();
        ops.put(add, (a, b) -> a + b);
        ops.put(mul, (a, b) -> a * b);
        ops.put(sub, (a, b) -> a - b);
        List<FusionDescriptor> kernels = List.of(add, mul, sub);

        int shapes = 200;
        for (int s = 0; s < shapes; s++) {
            GenTree gen = randomExpression(kernels);
            int arity = gen.arity();
            MethodHandle handle = blockHandle(gen, IntBlock.class);

            int positionCount = positionCountFor(s);
            Fill fill = fillFor(s);
            int[][][] values = new int[arity][][];
            int[][] counts = new int[arity][];
            for (int i = 0; i < arity; i++) {
                counts[i] = valueCounts(positionCount, fill);
                values[i] = new int[positionCount][];
                for (int p = 0; p < positionCount; p++) {
                    int[] vv = new int[counts[i][p]];
                    for (int v = 0; v < vv.length; v++) {
                        // Bounded so an all-multiplyExact tree of <= 8 leaves (depth <= 3) cannot overflow int.
                        vv[v] = randomIntBetween(-10, 10);
                    }
                    values[i][p] = vv;
                }
            }

            IntBlock[] blocks = new IntBlock[arity];
            IntBlock result = null;
            try {
                Object[] args = new Object[3 + arity];
                args[0] = blockFactory;
                args[1] = Warnings.NOOP_WARNINGS;
                args[2] = positionCount;
                for (int i = 0; i < arity; i++) {
                    blocks[i] = buildIntBlock(positionCount, values[i]);
                    args[3 + i] = blocks[i];
                }
                result = (IntBlock) handle.invokeWithArguments(args);
                assertThat("s=" + s, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    int[] single = new int[arity];
                    boolean nullPos = false;
                    for (int i = 0; i < arity; i++) {
                        if (counts[i][p] != 1) {
                            nullPos = true;
                        } else {
                            single[i] = values[i][p][0];
                        }
                    }
                    if (nullPos) {
                        assertThat("s=" + s + " p=" + p + " expected null", result.isNull(p), is(true));
                    } else {
                        assertThat("s=" + s + " p=" + p + " expected non-null", result.isNull(p), is(false));
                        int expected = evalInt(gen.tree(), single, ops);
                        assertThat("s=" + s + " p=" + p, result.getInt(result.getFirstValueIndex(p)), equalTo(expected));
                    }
                }
            } finally {
                closeAll(blocks, result);
            }
        }
    }

    public void testDoubleRandomTreesBlockDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(DoubleKernels.class, "add", "(DD)D", false, true);
        FusionDescriptor mul = new FusionDescriptor(DoubleKernels.class, "mul", "(DD)D", false, true);
        FusionDescriptor sub = new FusionDescriptor(DoubleKernels.class, "sub", "(DD)D", false, true);
        Map<FusionDescriptor, DoubleBinaryOperator> ops = new HashMap<>();
        ops.put(add, (a, b) -> a + b);
        ops.put(mul, (a, b) -> a * b);
        ops.put(sub, (a, b) -> a - b);
        List<FusionDescriptor> kernels = List.of(add, mul, sub);

        int shapes = 200;
        for (int s = 0; s < shapes; s++) {
            GenTree gen = randomExpression(kernels);
            int arity = gen.arity();
            MethodHandle handle = blockHandle(gen, DoubleBlock.class);

            int positionCount = positionCountFor(s);
            Fill fill = fillFor(s);
            double[][][] values = new double[arity][][];
            int[][] counts = new int[arity][];
            for (int i = 0; i < arity; i++) {
                counts[i] = valueCounts(positionCount, fill);
                values[i] = new double[positionCount][];
                for (int p = 0; p < positionCount; p++) {
                    double[] vv = new double[counts[i][p]];
                    for (int v = 0; v < vv.length; v++) {
                        // Bounded so add/mul/sub over depth <= 4 never reaches Infinity (and thus never inf-inf=NaN).
                        vv[v] = randomDoubleBetween(-100.0, 100.0, true);
                    }
                    values[i][p] = vv;
                }
            }

            DoubleBlock[] blocks = new DoubleBlock[arity];
            DoubleBlock result = null;
            try {
                Object[] args = new Object[3 + arity];
                args[0] = blockFactory;
                args[1] = Warnings.NOOP_WARNINGS;
                args[2] = positionCount;
                for (int i = 0; i < arity; i++) {
                    blocks[i] = buildDoubleBlock(positionCount, values[i]);
                    args[3 + i] = blocks[i];
                }
                result = (DoubleBlock) handle.invokeWithArguments(args);
                assertThat("s=" + s, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    double[] single = new double[arity];
                    boolean nullPos = false;
                    for (int i = 0; i < arity; i++) {
                        if (counts[i][p] != 1) {
                            nullPos = true;
                        } else {
                            single[i] = values[i][p][0];
                        }
                    }
                    if (nullPos) {
                        assertThat("s=" + s + " p=" + p + " expected null", result.isNull(p), is(true));
                    } else {
                        assertThat("s=" + s + " p=" + p + " expected non-null", result.isNull(p), is(false));
                        double expected = evalDouble(gen.tree(), single, ops);
                        double actual = result.getDouble(result.getFirstValueIndex(p));
                        // Identical op order => bit-identical IEEE result (also correctly equates NaN and signed zeros).
                        assertThat("s=" + s + " p=" + p, Double.doubleToLongBits(actual), equalTo(Double.doubleToLongBits(expected)));
                    }
                }
            } finally {
                closeAll(blocks, result);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MANDATORY: non-commutative (a - b) / c — proves operand ORDER — on BOTH the vector and block paths.
    // ---------------------------------------------------------------------------------------------------------------

    public void testNonCommutativeDivVectorPath() throws Throwable {
        FusionNode tree = subDivTree();
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo("org.elasticsearch.compute.data"));
        MethodType type = MethodType.methodType(LongVector.class, BlockFactory.class, int.class, Vector.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 120; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            long[] c = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                a[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                b[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                c[p] = nonZeroDivisor();
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector v2 = blockFactory.newLongArrayVector(c, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1, v2);
                for (int p = 0; p < positionCount; p++) {
                    // (a - b) / c, NOT (b - a) / c: a swap would fail here.
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo((a[p] - b[p]) / c[p]));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, result);
            }
        }
    }

    public void testNonCommutativeDivBlockPath() throws Throwable {
        FusionNode tree = subDivTree();
        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            LongBlock.class,
            LongBlock.class,
            LongBlock.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 120; shape++) {
            int positionCount = positionCountFor(shape);
            Fill fill = fillFor(shape);
            long[][] a = column(positionCount, fill, () -> randomLongBetween(-1_000_000L, 1_000_000L));
            long[][] b = column(positionCount, fill, () -> randomLongBetween(-1_000_000L, 1_000_000L));
            long[][] c = column(positionCount, fill, this::nonZeroDivisor);

            LongBlock ba = null, bb = null, bc = null, result = null;
            try {
                ba = buildLongBlock(positionCount, a);
                bb = buildLongBlock(positionCount, b);
                bc = buildLongBlock(positionCount, c);
                result = (LongBlock) handle.invokeWithArguments(blockFactory, Warnings.NOOP_WARNINGS, positionCount, ba, bb, bc);
                for (int p = 0; p < positionCount; p++) {
                    boolean nullPos = a[p].length != 1 || b[p].length != 1 || c[p].length != 1;
                    if (nullPos) {
                        assertThat("shape=" + shape + " p=" + p, result.isNull(p), is(true));
                    } else {
                        assertThat("shape=" + shape + " p=" + p, result.isNull(p), is(false));
                        assertThat(
                            "shape=" + shape + " p=" + p,
                            result.getLong(result.getFirstValueIndex(p)),
                            equalTo((a[p][0] - b[p][0]) / c[p][0])
                        );
                    }
                }
            } finally {
                Releasables.closeExpectNoException(ba, bb, bc, result);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MANDATORY: depth-3 ((a + b) * c) - d — proves deep-tree slot disjointness — on BOTH paths.
    // ---------------------------------------------------------------------------------------------------------------

    public void testDepth3VectorPath() throws Throwable {
        FusionNode tree = depth3Tree();
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(
            LongVector.class,
            BlockFactory.class,
            int.class,
            Vector.class,
            Vector.class,
            Vector.class,
            Vector.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 120; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            long[] c = new long[positionCount];
            long[] d = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                a[p] = randomLongBetween(-1_000L, 1_000L);
                b[p] = randomLongBetween(-1_000L, 1_000L);
                c[p] = randomLongBetween(-1_000L, 1_000L);
                d[p] = randomLongBetween(-1_000_000_000L, 1_000_000_000L);
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector v2 = blockFactory.newLongArrayVector(c, positionCount);
            LongVector v3 = blockFactory.newLongArrayVector(d, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1, v2, v3);
                for (int p = 0; p < positionCount; p++) {
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(((a[p] + b[p]) * c[p]) - d[p]));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, v3, result);
            }
        }
    }

    public void testDepth3BlockPath() throws Throwable {
        FusionNode tree = depth3Tree();
        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            LongBlock.class,
            LongBlock.class,
            LongBlock.class,
            LongBlock.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 120; shape++) {
            int positionCount = positionCountFor(shape);
            Fill fill = fillFor(shape);
            long[][] a = column(positionCount, fill, () -> randomLongBetween(-1_000L, 1_000L));
            long[][] b = column(positionCount, fill, () -> randomLongBetween(-1_000L, 1_000L));
            long[][] c = column(positionCount, fill, () -> randomLongBetween(-1_000L, 1_000L));
            long[][] d = column(positionCount, fill, () -> randomLongBetween(-1_000_000_000L, 1_000_000_000L));

            LongBlock ba = null, bb = null, bc = null, bd = null, result = null;
            try {
                ba = buildLongBlock(positionCount, a);
                bb = buildLongBlock(positionCount, b);
                bc = buildLongBlock(positionCount, c);
                bd = buildLongBlock(positionCount, d);
                result = (LongBlock) handle.invokeWithArguments(blockFactory, Warnings.NOOP_WARNINGS, positionCount, ba, bb, bc, bd);
                for (int p = 0; p < positionCount; p++) {
                    boolean nullPos = a[p].length != 1 || b[p].length != 1 || c[p].length != 1 || d[p].length != 1;
                    if (nullPos) {
                        assertThat("shape=" + shape + " p=" + p, result.isNull(p), is(true));
                    } else {
                        assertThat("shape=" + shape + " p=" + p, result.isNull(p), is(false));
                        assertThat(
                            "shape=" + shape + " p=" + p,
                            result.getLong(result.getFirstValueIndex(p)),
                            equalTo(((a[p][0] + b[p][0]) * c[p][0]) - d[p][0])
                        );
                    }
                }
            } finally {
                Releasables.closeExpectNoException(ba, bb, bc, bd, result);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Over-nullify guard: an input the tree never references must NOT nullify positions (Reviewer B WARNING).
    // ---------------------------------------------------------------------------------------------------------------

    public void testUnusedInputDoesNotOverNullify() throws Throwable {
        // add(in0, in2): input index 1 exists in [0, arity) (arity = 3) but is never consumed. Its nulls/multi-values
        // must be ignored — only in0 and in2 gate the result.
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionNode tree = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(2)));

        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            LongBlock.class,
            LongBlock.class,
            LongBlock.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        int positionCount = 256;
        long[][] c0 = new long[positionCount][];
        long[][] c1 = new long[positionCount][]; // the UNUSED input: fill with nulls and multi-values throughout
        long[][] c2 = new long[positionCount][];
        for (int p = 0; p < positionCount; p++) {
            c0[p] = new long[] { randomLongBetween(-1000, 1000) };
            c2[p] = new long[] { randomLongBetween(-1000, 1000) };
            // Alternate null and multi-value for the unused input — either would over-nullify if it were guarded.
            c1[p] = (p % 2 == 0) ? new long[0] : new long[] { 1L, 2L, 3L };
        }

        LongBlock b0 = null, b1 = null, b2 = null, result = null;
        try {
            b0 = buildLongBlock(positionCount, c0);
            b1 = buildLongBlock(positionCount, c1);
            b2 = buildLongBlock(positionCount, c2);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, Warnings.NOOP_WARNINGS, positionCount, b0, b1, b2);
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p + " must not be nullified by the unused input", result.isNull(p), is(false));
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(Math.addExact(c0[p][0], c2[p][0])));
            }
        } finally {
            Releasables.closeExpectNoException(b0, b1, b2, result);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code (a - b) / c} : Div(Sub(in0, in1), in2). Div at the root makes the tree non-commutative. */
    private static FusionNode subDivTree() {
        FusionDescriptor sub = new FusionDescriptor(LongKernels.class, "sub", "(JJ)J", true, true);
        FusionDescriptor div = new FusionDescriptor(LongKernels.class, "div", "(JJ)J", true, true);
        return new FusionNode.Kernel(
            div,
            List.of(new FusionNode.Kernel(sub, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    /** {@code ((a + b) * c) - d} : Sub(Mul(Add(in0, in1), in2), in3) — kernel depth 3. */
    private static FusionNode depth3Tree() {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(LongKernels.class, "mul", "(JJ)J", true, true);
        FusionDescriptor sub = new FusionDescriptor(LongKernels.class, "sub", "(JJ)J", true, true);
        FusionNode addNode = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode mulNode = new FusionNode.Kernel(mul, List.of(addNode, new FusionNode.Input(2)));
        return new FusionNode.Kernel(sub, List.of(mulNode, new FusionNode.Input(3)));
    }

    private MethodHandle blockHandle(GenTree gen, Class<?> blockType) throws Throwable {
        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), gen.tree());
        // The block path touches only public compute.data API, so the hidden class is defined into the caller's own
        // package/module (no privateLookupIn teleport) — here, this test's package.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        Class<?>[] params = new Class<?>[3 + gen.arity()];
        params[0] = BlockFactory.class;
        params[1] = Warnings.class;
        params[2] = int.class;
        for (int i = 0; i < gen.arity(); i++) {
            params[3 + i] = blockType;
        }
        return MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(blockType, params));
    }

    private long nonZeroDivisor() {
        long c = randomLongBetween(-1_000L, 1_000L);
        return c == 0 ? 1 : c;
    }

    /** Builds a per-position value column matching {@code fill}: null (0 values), single (1), or multi (2-3). */
    private long[][] column(int positionCount, Fill fill, java.util.function.LongSupplier value) {
        int[] counts = valueCounts(positionCount, fill);
        long[][] out = new long[positionCount][];
        for (int p = 0; p < positionCount; p++) {
            out[p] = new long[counts[p]];
            for (int v = 0; v < counts[p]; v++) {
                out[p][v] = value.getAsLong();
            }
        }
        return out;
    }

    private GenTree randomExpression(List<FusionDescriptor> kernels) {
        int maxInputs = randomIntBetween(1, 4);
        // Kernel depth <= 3 (<= 8 leaves). With the bounded leaf values this guarantees the Math.*Exact kernels
        // (addExact/subtractExact/multiplyExact) never overflow, so no kernel throws — overflow handling is iter 12.
        int depth = randomIntBetween(1, 3);
        FusionNode raw = new FusionNode.Kernel(
            kernels.get(randomInt(kernels.size() - 1)),
            List.of(buildChild(kernels, maxInputs, depth - 1), buildChild(kernels, maxInputs, depth - 1))
        );
        // Compact the referenced input indices to 0..k-1 so every input the fused method takes is actually used;
        // that makes the fused "check every input" guard coincide with the unfused "check each operand" chain.
        TreeSet<Integer> used = new TreeSet<>();
        collectInputs(raw, used);
        Map<Integer, Integer> remap = new HashMap<>();
        int next = 0;
        for (int idx : used) {
            remap.put(idx, next++);
        }
        return new GenTree(remapInputs(raw, remap), used.size());
    }

    private FusionNode buildChild(List<FusionDescriptor> kernels, int maxInputs, int depth) {
        if (depth <= 0 || randomBoolean()) {
            return new FusionNode.Input(randomIntBetween(0, maxInputs - 1));
        }
        return new FusionNode.Kernel(
            kernels.get(randomInt(kernels.size() - 1)),
            List.of(buildChild(kernels, maxInputs, depth - 1), buildChild(kernels, maxInputs, depth - 1))
        );
    }

    private static void collectInputs(FusionNode node, Set<Integer> used) {
        if (node instanceof FusionNode.Input input) {
            used.add(input.index());
        } else {
            for (FusionNode child : ((FusionNode.Kernel) node).children()) {
                collectInputs(child, used);
            }
        }
    }

    private static FusionNode remapInputs(FusionNode node, Map<Integer, Integer> remap) {
        if (node instanceof FusionNode.Input input) {
            return new FusionNode.Input(remap.get(input.index()));
        }
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        List<FusionNode> children = new ArrayList<>();
        for (FusionNode child : kernel.children()) {
            children.add(remapInputs(child, remap));
        }
        return new FusionNode.Kernel(kernel.descriptor(), children);
    }

    private static long evalLong(FusionNode node, long[] values, Map<FusionDescriptor, LongBinaryOperator> ops) {
        if (node instanceof FusionNode.Input input) {
            return values[input.index()];
        }
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        long l = evalLong(kernel.children().get(0), values, ops);
        long r = evalLong(kernel.children().get(1), values, ops);
        return ops.get(kernel.descriptor()).applyAsLong(l, r);
    }

    private static int evalInt(FusionNode node, int[] values, Map<FusionDescriptor, IntBinaryOperator> ops) {
        if (node instanceof FusionNode.Input input) {
            return values[input.index()];
        }
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        int l = evalInt(kernel.children().get(0), values, ops);
        int r = evalInt(kernel.children().get(1), values, ops);
        return ops.get(kernel.descriptor()).applyAsInt(l, r);
    }

    private static double evalDouble(FusionNode node, double[] values, Map<FusionDescriptor, DoubleBinaryOperator> ops) {
        if (node instanceof FusionNode.Input input) {
            return values[input.index()];
        }
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        double l = evalDouble(kernel.children().get(0), values, ops);
        double r = evalDouble(kernel.children().get(1), values, ops);
        return ops.get(kernel.descriptor()).applyAsDouble(l, r);
    }

    private LongBlock buildLongBlock(int positionCount, long[][] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                long[] vv = values[p];
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

    private IntBlock buildIntBlock(int positionCount, int[][] values) {
        try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                int[] vv = values[p];
                if (vv.length == 0) {
                    builder.appendNull();
                } else if (vv.length == 1) {
                    builder.appendInt(vv[0]);
                } else {
                    builder.beginPositionEntry();
                    for (int x : vv) {
                        builder.appendInt(x);
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }

    private DoubleBlock buildDoubleBlock(int positionCount, double[][] values) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                double[] vv = values[p];
                if (vv.length == 0) {
                    builder.appendNull();
                } else if (vv.length == 1) {
                    builder.appendDouble(vv[0]);
                } else {
                    builder.beginPositionEntry();
                    for (double x : vv) {
                        builder.appendDouble(x);
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }

    private void closeAll(Releasable[] blocks, Releasable result) {
        List<Releasable> toClose = new ArrayList<>();
        for (Releasable block : blocks) {
            toClose.add(block);
        }
        toClose.add(result);
        Releasables.closeExpectNoException(toClose.toArray(new Releasable[0]));
    }

    /** Guarantees coverage of empty, single, small, and &ge; 1024 position counts across the shape sweep. */
    private int positionCountFor(int shape) {
        if (shape == 0) {
            return 0;
        }
        if (shape == 1) {
            return 1;
        }
        if (shape % 25 == 0) {
            return 1024 + randomIntBetween(0, 512);
        }
        return randomIntBetween(2, 128);
    }

    /** Cycles through no-null, mixed, and all-null fills so every density (incl. large ones) is covered. */
    private Fill fillFor(int shape) {
        return switch (shape % 4) {
            case 0 -> Fill.NO_NULL;
            case 2 -> Fill.ALL_NULL;
            default -> Fill.MIXED;
        };
    }

    private int[] valueCounts(int positionCount, Fill fill) {
        int[] counts = new int[positionCount];
        for (int p = 0; p < positionCount; p++) {
            counts[p] = switch (fill) {
                case NO_NULL -> 1;
                case ALL_NULL -> 0;
                case MIXED -> mixedCount();
            };
        }
        return counts;
    }

    private int mixedCount() {
        int r = randomIntBetween(0, 9);
        if (r < 2) {
            return 0; // null
        }
        if (r == 2) {
            return randomIntBetween(2, 3); // multi-value
        }
        return 1; // single value
    }
}
