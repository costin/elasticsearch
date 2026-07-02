/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
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
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Cross-module overflow acceptance for iter 12 (binding rule #3), the overflow counterpart to
 * {@link StitcherBlockCrossModuleTests}. Fuses the <b>real</b> overflow-checked {@code Add} kernels (whose
 * {@code FUSION_DESCRIPTOR} the annotation processor now stamps with
 * {@code overflowExceptionType = "java.lang.ArithmeticException"}) from an esql-proper caller and proves that an
 * overflowing position becomes {@code null} with a registered warning, byte-for-byte matching the unfused
 * evaluator ({@code AddLongsEvaluator.eval}/{@code AddDoublesEvaluator.eval}).
 *
 * <p>It also pins the iter-11 carry-forward the iter-13 caller must respect: the overflow-aware checked vector path
 * ({@link Stitcher#compileVectorLoopChecked}) cannot be used from esql-proper for these kernels — it must define its
 * hidden class into the effective {@code compute.data} package (for the package-private {@code rawValues()}), which
 * {@code compute} neither opens to esql-proper nor can link {@code NumericUtils.asFiniteNumber} against. Either way
 * the attempt fails cleanly as a {@link Stitcher.StitchingException} (no crash), and the caller must route such trees
 * through {@link Stitcher#compileBlockLoop} (proven here to work) or the unfused chain.
 */
public class StitcherOverflowCrossModuleTests extends ESTestCase {

    private static final String FIRST_EXCEPTION_HEADER =
        "Line 1:1: evaluation of [overflow] failed, treating result as null. Only first 20 failures recorded.";
    private static final String LONG_OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: long overflow";
    private static final String DOUBLE_OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: not a finite double number: Infinity";

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
     * Block path over the real {@code Add.processLongs} ({@code Math.addExact}): overflowing positions are null +
     * warning, clean positions equal the reflectively-invoked unfused kernel. Proves the descriptor's
     * {@code overflowExceptionType} drives a correct cross-module overflow try/catch.
     */
    public void testRealAddProcessLongsBlockPathOverflowMatchesUnfused() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(Add.class, "processLongs", "(JJ)J", true, true);
        assertThat(descriptor.overflowExceptionType(), equalTo("java.lang.ArithmeticException"));
        FusionNode tree = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        Class<?> fused = stitcher.compileBlockLoop(lookup, tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            LongBlock.class,
            LongBlock.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        Method unfused = Add.class.getDeclaredMethod("processLongs", long.class, long.class);
        unfused.setAccessible(true);

        // p0: MAX + 1 overflows. p1: clean. p2: MAX + 10 overflows. p3: clean negative.
        long[] lhs = { Long.MAX_VALUE, 100, Long.MAX_VALUE, -50 };
        long[] rhs = { 1, 23, 10, -70 };
        int positionCount = lhs.length;

        LongBlock lb = null, rb = null, result = null;
        try {
            lb = singleValuedLong(lhs);
            rb = singleValuedLong(rhs);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warnings, positionCount, lb, rb);
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
        assertWarnings(FIRST_EXCEPTION_HEADER, LONG_OVERFLOW_WARNING);
    }

    /**
     * Block path over the real {@code Add.processDoubles} ({@code NumericUtils.asFiniteNumber(lhs + rhs)}): a
     * non-finite (Infinity) sum overflows to null + warning, matching the unfused kernel. This is the route the
     * iter-13 caller must use for overflow-checked double trees, since the checked vector path cannot link
     * {@code NumericUtils} in {@code compute.data} (see below).
     */
    public void testRealAddProcessDoublesBlockPathOverflowMatchesUnfused() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(Add.class, "processDoubles", "(DD)D", true, true);
        FusionNode tree = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        Class<?> fused = stitcher.compileBlockLoop(lookup, tree);
        MethodType type = MethodType.methodType(
            DoubleBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            DoubleBlock.class,
            DoubleBlock.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        Method unfused = Add.class.getDeclaredMethod("processDoubles", double.class, double.class);
        unfused.setAccessible(true);

        // p0: MAX + MAX = Infinity -> asFiniteNumber throws. p1: clean. p2: Infinity again. p3: clean.
        double[] lhs = { Double.MAX_VALUE, 1.5, Double.MAX_VALUE, -2.0 };
        double[] rhs = { Double.MAX_VALUE, 2.5, Double.MAX_VALUE, 6.0 };
        int positionCount = lhs.length;

        DoubleBlock lb = null, rb = null, result = null;
        try {
            lb = singleValuedDouble(lhs);
            rb = singleValuedDouble(rhs);
            result = (DoubleBlock) handle.invokeWithArguments(blockFactory, warnings, positionCount, lb, rb);
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
        assertWarnings(FIRST_EXCEPTION_HEADER, DOUBLE_OVERFLOW_WARNING);
    }

    /**
     * The overflow-aware checked vector path splices the real {@code Add.processDoubles} body, which invokes
     * {@code NumericUtils.asFiniteNumber} in {@code org.elasticsearch.xpack.esql.core} — a target outside
     * {@code java.base}. Because that path defines its hidden class into the effective {@code compute.data} package
     * (for the package-private {@code rawValues()}), in a real <b>modular</b> runtime the JVM cannot link the
     * {@code NumericUtils} callee (and {@code privateLookupIn(compute.data, esqlLookup)} would itself be denied,
     * since {@code compute.data} is exported but not opened). The contract is that either failure is caught and
     * rethrown as a clean {@link Stitcher.StitchingException} — never an escaping {@link Error} — so the iter-13
     * caller routes such trees through {@link Stitcher#compileBlockLoop} (proven above) or the unfused chain.
     *
     * <p>This unit test runs on the <b>classpath</b> (unnamed module), where those module boundaries do not apply,
     * so here {@code compileVectorLoopChecked} actually links and runs. The assertion is therefore the environment
     * -independent invariant that matters for the caller: the call <b>does not crash</b>. Whichever way it goes —
     * a clean {@code StitchingException} fallback (modular runtime) or a working fused class (classpath) — the
     * overflow behaviour is exercised and no {@code Error} escapes.
     */
    public void testCheckedVectorPathDoubleDoesNotCrash() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FusionDescriptor descriptor = new FusionDescriptor(Add.class, "processDoubles", "(DD)D", true, true);
        FusionNode tree = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        Class<?> fused;
        try {
            fused = stitcher.compileVectorLoopChecked(lookup, tree);
        } catch (Stitcher.StitchingException e) {
            // Clean fallback (as in a modular runtime that cannot link NumericUtils into compute.data): cause is
            // preserved for diagnostics and the caller falls back to the block path / unfused chain. No warning or
            // block was produced, so the breaker stays balanced and there is nothing to assert on warnings.
            assertThat(e.getCause(), is(org.hamcrest.Matchers.notNullValue()));
            return;
        }

        // Linked in this classpath environment: prove it still behaves — an Infinity sum overflows to null + warning.
        MethodType type = MethodType.methodType(
            DoubleBlock.class,
            BlockFactory.class,
            Warnings.class,
            int.class,
            org.elasticsearch.compute.data.Vector.class,
            org.elasticsearch.compute.data.Vector.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        double[] lhs = { Double.MAX_VALUE, 1.5, 3.0 };
        double[] rhs = { Double.MAX_VALUE, 2.5, 4.0 };
        int positionCount = lhs.length;

        org.elasticsearch.compute.data.DoubleVector va = null, vb = null;
        DoubleBlock result = null;
        try {
            va = blockFactory.newDoubleArrayVector(lhs, positionCount);
            vb = blockFactory.newDoubleArrayVector(rhs, positionCount);
            result = (DoubleBlock) handle.invoke(blockFactory, warnings, positionCount, va, vb);
            assertThat("p0 Infinity -> null", result.isNull(0), is(true));
            assertThat("p1 value", result.getDouble(result.getFirstValueIndex(1)), equalTo(4.0));
            assertThat("p2 value", result.getDouble(result.getFirstValueIndex(2)), equalTo(7.0));
        } finally {
            Releasables.closeExpectNoException(va, vb, result);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, DOUBLE_OVERFLOW_WARNING);
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
            return (double) unfused.invoke(null, a, b);
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
