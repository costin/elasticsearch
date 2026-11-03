/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.compute.test.TestWarningsSource;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Cross-module overflow acceptance for the overflow lookup contract, the overflow counterpart to
 * {@link StitcherBlockCrossModuleTests}. Fuses the <b>real</b> overflow-checked {@code Add} kernels (whose
 * {@code FUSION_DESCRIPTOR} the annotation processor now stamps with
 * {@code overflowExceptionType = "java.lang.ArithmeticException"}) from an esql-proper caller and proves that an
 * overflowing position becomes {@code null} with a registered warning, byte-for-byte matching the unfused
 * evaluator ({@code AddLongsEvaluator.eval}/{@code AddDoublesEvaluator.eval}).
 *
 * <p>It also pins the overflow-aware unification: the overflow-aware checked vector path
 * ({@link Stitcher#compileVectorLoopChecked}) now works from esql-proper for these kernels too. It reads each dense
 * {@code *ArrayVector}'s backing array through the public {@code VectorUnsafe} forwarder (an {@code INVOKESTATIC})
 * instead of the package-private {@code rawValues()}, so its hidden class is defined in the <b>caller's</b> own
 * (esql-proper) module — which reads {@code org.elasticsearch.xpack.esql.core} — and therefore links
 * {@code NumericUtils.asFiniteNumber}. The old {@code double}+overflow hard gate (route only through
 * {@link Stitcher#compileBlockLoop}) is gone: a double overflow tree may now vectorize.
 */
public class StitcherOverflowCrossModuleTests extends ESTestCase {

    private static final String FIRST_EXCEPTION_HEADER =
        "Line 1:1: evaluation of [overflow] failed, treating result as null. Only first 20 failures recorded.";
    private static final String LONG_OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: long overflow";
    private static final String DOUBLE_OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: not a finite double number: Infinity";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private DriverContext warningsContext;
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
        warningsContext = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, blockFactory, null);
        return warningsContext.createWarnings(new TestWarningsSource("overflow"));
    }

    /** Assert the warnings collected into the {@link DriverContext} backing the most recent {@link #collectingWarnings()}. */
    private void assertCollectedWarnings(String... expected) {
        warningsContext.finish();
        if (expected.length == 0) {
            assertThat(warningsContext.warnings(), empty());
        } else {
            assertThat(warningsContext.warnings(), contains(expected));
        }
    }

    /**
     * The per-source {@code Warnings[]} the fused methods now take, sized by {@link Stitcher#warningsSourceCount} and
     * all pointing at the single collecting instance so these single-kernel fixtures accumulate into one collector.
     */
    private static Warnings[] warningsArray(FusionNode tree, Warnings w) {
        Warnings[] arr = new Warnings[Stitcher.warningsSourceCount(tree)];
        Arrays.fill(arr, w);
        return arr;
    }

    /**
     * Block path over the real {@code Add.processLongs} ({@code Math.addExact}): overflowing positions are null +
     * warning, clean positions equal the reflectively-invoked unfused kernel. Proves the descriptor's
     * {@code overflowExceptionType} drives a correct cross-module overflow try/catch.
     */
    public void testRealAddProcessLongsBlockPathOverflowMatchesUnfused() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(
            Add.class,
            "processLongs",
            "(JJ)J",
            true,
            true,
            "java.lang.ArithmeticException",
            false,
            false,
            true,
            true
        );
        assertThat(descriptor.overflowExceptionType(), equalTo("java.lang.ArithmeticException"));
        FusionNode tree = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        Class<?> fused = stitcher.compileBlockLoop(lookup, tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            LongBlock.class,
            LongBlock.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        Method unfused = addKernel("processLongs", long.class, long.class);

        long[] lhs = { Long.MAX_VALUE, 100, Long.MAX_VALUE, -50 };
        long[] rhs = { 1, 23, 10, -70 };
        int positionCount = lhs.length;

        LongBlock lb = null, rb = null, result = null;
        try {
            lb = singleValuedLong(lhs);
            rb = singleValuedLong(rhs);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, lb, rb);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refLong(unfused, lhs[p], rhs[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            assertThat("p0 overflow -> null", result.isNull(0), is(true));
            assertThat("p2 overflow -> null", result.isNull(2), is(true));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(123L));
            assertThat("p3 value", result.getLong(result.getFirstValueIndex(3)), equalTo(-120L));
        } finally {
            Releasables.closeExpectNoException(lb, rb, result);
        }
        assertCollectedWarnings(FIRST_EXCEPTION_HEADER, LONG_OVERFLOW_WARNING);
    }

    /**
     * Block path over the real {@code Add.processDoubles} ({@code NumericUtils.asFiniteNumber(lhs + rhs)}): a
     * non-finite (Infinity) sum overflows to null + warning, matching the unfused kernel. This is the route the
     * caller must use for overflow-checked double trees, since the checked vector path cannot link
     * {@code NumericUtils} in {@code compute.data} (see below).
     */
    public void testRealAddProcessDoublesBlockPathOverflowMatchesUnfused() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(
            Add.class,
            "processDoubles",
            "(DDZ)D",
            true,
            true,
            "java.lang.ArithmeticException",
            false,
            false,
            true,
            true
        );
        FusionNode tree = new FusionNode.Kernel(
            descriptor,
            List.of(new FusionNode.Input(0), new FusionNode.Input(1), FusionNode.Constant.baked(false, 'Z'))
        );

        Class<?> fused = stitcher.compileBlockLoop(lookup, tree);
        MethodType type = MethodType.methodType(
            DoubleBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            DoubleBlock.class,
            DoubleBlock.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        Method unfused = addKernel("processDoubles", double.class, double.class, boolean.class);

        double[] lhs = { Double.MAX_VALUE, 1.5, Double.MAX_VALUE, -2.0 };
        double[] rhs = { Double.MAX_VALUE, 2.5, Double.MAX_VALUE, 6.0 };
        int positionCount = lhs.length;

        DoubleBlock lb = null, rb = null, result = null;
        try {
            lb = singleValuedDouble(lhs);
            rb = singleValuedDouble(rhs);
            result = (DoubleBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, lb, rb);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Double expected = refDouble(unfused, lhs[p], rhs[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat(
                        "p=" + p,
                        Double.doubleToLongBits(result.getDouble(result.getFirstValueIndex(p))),
                        equalTo(Double.doubleToLongBits(expected))
                    );
                }
            }
            assertThat("p0 overflow -> null", result.isNull(0), is(true));
            assertThat("p2 overflow -> null", result.isNull(2), is(true));
            assertThat("p1 value", result.getDouble(result.getFirstValueIndex(1)), equalTo(4.0));
            assertThat("p3 value", result.getDouble(result.getFirstValueIndex(3)), equalTo(4.0));
        } finally {
            Releasables.closeExpectNoException(lb, rb, result);
        }
        assertCollectedWarnings(FIRST_EXCEPTION_HEADER, DOUBLE_OVERFLOW_WARNING);
    }

    /** Positive proof: the overflow-aware checked vector path fuses the real {@code Add.processDoubles} */
    public void testRealAddProcessDoublesCheckedVectorPathMatchesUnfused() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(
            Add.class,
            "processDoubles",
            "(DDZ)D",
            true,
            true,
            "java.lang.ArithmeticException",
            false,
            false,
            true,
            true
        );
        FusionNode tree = new FusionNode.Kernel(
            descriptor,
            List.of(new FusionNode.Input(0), new FusionNode.Input(1), FusionNode.Constant.baked(false, 'Z'))
        );

        Class<?> fused = stitcher.compileVectorLoopChecked(lookup, tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(
            DoubleBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            org.elasticsearch.compute.data.Vector.class,
            org.elasticsearch.compute.data.Vector.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        Method unfused = addKernel("processDoubles", double.class, double.class, boolean.class);

        double[] lhs = { Double.MAX_VALUE, 1.5, 3.0 };
        double[] rhs = { Double.MAX_VALUE, 2.5, 4.0 };
        int positionCount = lhs.length;

        org.elasticsearch.compute.data.DoubleVector va = null, vb = null;
        DoubleBlock result = null;
        try {
            va = blockFactory.newDoubleArrayVector(lhs, positionCount);
            vb = blockFactory.newDoubleArrayVector(rhs, positionCount);
            result = (DoubleBlock) handle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Double expected = refDouble(unfused, lhs[p], rhs[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat(
                        "p=" + p,
                        Double.doubleToLongBits(result.getDouble(result.getFirstValueIndex(p))),
                        equalTo(Double.doubleToLongBits(expected))
                    );
                }
            }
            assertThat("p0 Infinity -> null", result.isNull(0), is(true));
            assertThat("p1 value", result.getDouble(result.getFirstValueIndex(1)), equalTo(4.0));
            assertThat("p2 value", result.getDouble(result.getFirstValueIndex(2)), equalTo(7.0));
        } finally {
            Releasables.closeExpectNoException(va, vb, result);
        }
        assertCollectedWarnings(FIRST_EXCEPTION_HEADER, DOUBLE_OVERFLOW_WARNING);
    }

    @SuppressForbidden(reason = "test-only reflection: resolves the real package-private Add kernel as the differential oracle")
    private static Method addKernel(String name, Class<?>... parameterTypes) throws Exception {
        Method kernel = Add.class.getDeclaredMethod(name, parameterTypes);
        kernel.setAccessible(true);
        return kernel;
    }

    private static Long refLong(Method unfused, long a, long b) throws Exception {
        try {
            return (long) unfused.invoke(null, a, b);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof ArithmeticException) {
                return null;
            }
            throw ite;
        }
    }

    private static Double refDouble(Method unfused, double a, double b) throws Exception {
        try {
            return (double) unfused.invoke(null, a, b, false);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof ArithmeticException) {
                return null;
            }
            throw ite;
        }
    }

    private LongBlock singleValuedLong(long[] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(values.length)) {
            for (long v : values) {
                builder.appendLong(v);
            }
            return builder.build();
        }
    }

    private DoubleBlock singleValuedDouble(double[] values) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(values.length)) {
            for (double v : values) {
                builder.appendDouble(v);
            }
            return builder.build();
        }
    }
}
