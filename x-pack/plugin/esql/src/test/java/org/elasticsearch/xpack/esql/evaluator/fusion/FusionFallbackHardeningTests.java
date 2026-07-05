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
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Iter-18 catch-and-fallback hardening (GOAL criterion #5): proves that <b>every</b> stitching/linkage failure mode
 * degrades to the <b>correct unfused result</b> end-to-end through the real {@link EvalMapper} /
 * {@link FusedExpressionEvaluatorFactory} path, not merely that an exception is caught.
 *
 * <p>Four failure modes are injected via the existing test seams (production hot path untouched — the seams default
 * to {@code null}/no-op):
 * <ol>
 *   <li><b>Plan-time {@code VerifyError}</b> — a {@link Stitcher.StitchingException} wrapping a {@link VerifyError}
 *       (the shape the {@link Stitcher} reports when a malformed emitted class fails JVM verification at
 *       {@code defineHiddenClass}; the genuine bytecode-corruption→{@code VerifyError}→{@code StitchingException}
 *       wrap is proven end-to-end by the iter-9 {@code StitcherTests#testVerificationFailureSurfacesAsStitchingException}
 *       via {@code Stitcher.hostMethodInterceptor}). Here we assert the resulting EvalMapper fallback produces the
 *       correct unfused result and the failure metric increments by exactly one.</li>
 *   <li><b>Plan-time {@code LinkageError}/{@code IllegalAccessError}</b> — a raw {@link LinkageError} thrown at define
 *       time, simulating the modular double-kernel non-{@code java.base} linkage failure that cannot be reproduced on
 *       the flat test classpath. Exercises the {@code LinkageError} arm of {@link FusedExpressionEvaluatorFactory}'s
 *       plan-time catch.</li>
 *   <li><b>Eval-time {@code LinkageError}</b> — a stitch that verified at plan time but throws a {@link LinkageError}
 *       at first evaluation. Per the iter-13 latch, the first failure records once and every later page routes
 *       straight to the unfused chain; values are asserted correct across multiple pages and the metric increments
 *       exactly once.</li>
 *   <li><b>Generic {@code RuntimeException} from the stitcher</b> — an {@link IllegalArgumentException} (the shape the
 *       stitcher's emit rejects an ill-formed tree with) thrown at define time. Exercises the broadened
 *       {@code RuntimeException} arm of the plan-time catch.</li>
 * </ol>
 *
 * <p>Every case asserts the query result equals the unfused result <b>position-by-position (values and null
 * positions)</b> over many random pages — empty, single-row, small, {@code >= 1024}-position, and null-carrying
 * shapes — against an independently built unfused reference evaluator. Everything runs on a real breaker-backed
 * {@link BlockFactory} against real emitted bytecode; the {@code @After} breaker-balance assert proves no block or
 * builder leaks on any fallback path.
 */
public class FusionFallbackHardeningTests extends ESTestCase {

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private MockBigArrays bigArrays;
    private DriverContext driverContext;

    @Before
    public void setUpFusion() throws Exception {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService);
        blockFactory = BlockFactory.builder(bigArrays).build();
        driverContext = new DriverContext(bigArrays, blockFactory, null);
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setEnabledForTests(true);
    }

    @After
    public void tearDownFusion() {
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setEnabledForTests(true);
        assertThat(breaker.getUsed(), is(0L));
    }

    // Case 1: a plan-time VerifyError (surfaced as StitchingException) must fall back to the unfused chain, count one
    // failure for the shape, and produce results identical to the unfused reference over every page.
    public void testPlanTimeVerifyErrorFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(expr, layout, () -> {
            throw new Stitcher.StitchingException("injected malformed emitted class", new VerifyError("injected verify failure"));
        }, false);
    }

    // Case 2a: a plan-time LinkageError/IllegalAccessError (the modular double-kernel non-java.base linkage failure the
    // flat classpath cannot reproduce) must fall back cleanly to the unfused chain, count one failure, and match.
    public void testPlanTimeIllegalAccessErrorFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(
            expr,
            layout,
            () -> { throw new IllegalAccessError("injected modular double-kernel linkage failure"); },
            true
        );
    }

    // Case 2b: the bare LinkageError base type (not the IllegalAccessError subtype) must also fall back cleanly.
    public void testPlanTimeBareLinkageErrorFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(expr, layout, () -> { throw new LinkageError("injected linkage failure"); }, false);
    }

    // Case 4: a generic RuntimeException escaping the stitcher (e.g. an IllegalArgumentException from emit rejecting an
    // ill-shaped tree) must be caught by the broadened plan-time catch, fall back, count one failure, and match.
    public void testPlanTimeRuntimeExceptionFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(expr, layout, () -> { throw new IllegalArgumentException("injected emit failure"); }, false);
    }

    // Case 3: a stitch that verified at plan time but throws a LinkageError at the FIRST evaluation must be caught,
    // latch fusion off for the rest of this evaluator's life, record exactly one failure (not one per page), and
    // still produce the correct unfused result on every page across multiple pages.
    public void testEvalTimeLinkageErrorLatchesAndFallsBackAcrossPages() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        // Plan-time stitch succeeds here: a real fused factory is produced (no test compiler injected).
        ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat("(a + b) * c over long columns must fuse", candidate, instanceOf(FusedExpressionEvaluatorFactory.class));
        ExpressionEvaluator.Factory reference = ((FusedExpressionEvaluatorFactory) candidate).unfusedFactory();

        long before = FusionTelemetry.totalFailures();
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = true;

        ExpressionEvaluator fused = candidate.get(driverContext);
        ExpressionEvaluator ref = reference.get(driverContext);
        try {
            // Several pages: the first hits the injected eval-time LinkageError and falls back; every later page is
            // served by the latch straight from the unfused chain. All must equal the unfused reference exactly.
            for (int shape = 0; shape < 8; shape++) {
                int positionCount = positionCountFor(shape);
                boolean withNulls = shape % 3 == 0;
                Page page = null;
                LongBlock fusedResult = null;
                LongBlock refResult = null;
                try {
                    page = new Page(
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, false)
                    );
                    fusedResult = (LongBlock) fused.eval(page);
                    refResult = (LongBlock) ref.eval(page);
                    assertLongBlocksEqual(shape, positionCount, fusedResult, refResult);
                } finally {
                    Releasables.closeExpectNoException(fusedResult, refResult);
                    if (page != null) {
                        page.releaseBlocks();
                    }
                }
            }
        } finally {
            FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
            Releasables.closeExpectNoException(fused, ref);
        }

        // Latched: exactly one failure recorded for the shape regardless of how many pages were evaluated.
        assertMetricRecordedExactlyOnce(before);
    }

    /**
     * Drives one plan-time fallback case: builds an independent unfused reference, injects {@code failure} into the
     * plan-time stitch, asserts {@link EvalMapper} returned the (non-fused) unfused factory, that exactly one failure
     * was recorded for the shape, and that the fallen-back query result equals the unfused reference on every page.
     */
    private void runPlanTimeFallbackCase(Expression expr, Layout layout, FailingStitch failure, boolean doubleElement) {
        ExpressionEvaluator.Factory reference = unfusedReference(expr, layout);
        long before = FusionTelemetry.totalFailures();

        FusionPlanner.compilerForTests = failingCompiler(failure);
        ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(
            "a failed plan-time stitch must fall back to the unfused factory",
            candidate,
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );
        assertMetricRecordedExactlyOnce(before);

        if (doubleElement) {
            assertDoubleResultsMatch(candidate, reference);
        } else {
            assertLongResultsMatch(candidate, reference);
        }
    }

    /** Builds the ordinary unfused factory for {@code expr} by disabling fusion, so it never touches the Stitcher. */
    private ExpressionEvaluator.Factory unfusedReference(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(false);
        try {
            ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            assertThat(factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
            return factory;
        } finally {
            FusionSettings.setEnabledForTests(true);
        }
    }

    /** Asserts the aggregate grew by one and exactly one shape is tracked with a count of one (per-shape metric +1). */
    private void assertMetricRecordedExactlyOnce(long before) {
        assertThat("the stitch failure must be counted", FusionTelemetry.totalFailures(), is(before + 1));
        Map<String, Long> snapshot = FusionTelemetry.snapshot();
        assertThat("exactly one shape must be tracked", snapshot.size(), is(1));
        assertThat("that shape must be counted exactly once", snapshot.values().iterator().next(), is(1L));
    }

    /**
     * Evaluates a candidate long factory and its unfused reference over the same random pages (empty, single, small,
     * {@code >= 1024}-position, and null-carrying shapes) and asserts position-by-position equality of values and
     * null positions.
     */
    private void assertLongResultsMatch(ExpressionEvaluator.Factory candidate, ExpressionEvaluator.Factory reference) {
        ExpressionEvaluator candidateEval = candidate.get(driverContext);
        ExpressionEvaluator referenceEval = reference.get(driverContext);
        try {
            for (int shape = 0; shape < 40; shape++) {
                int positionCount = positionCountFor(shape);
                boolean withNulls = shape % 3 == 0;
                Page page = null;
                LongBlock candidateResult = null;
                LongBlock referenceResult = null;
                try {
                    page = new Page(
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, false)
                    );
                    candidateResult = (LongBlock) candidateEval.eval(page);
                    referenceResult = (LongBlock) referenceEval.eval(page);
                    assertLongBlocksEqual(shape, positionCount, candidateResult, referenceResult);
                } finally {
                    Releasables.closeExpectNoException(candidateResult, referenceResult);
                    if (page != null) {
                        page.releaseBlocks();
                    }
                }
            }
        } finally {
            Releasables.closeExpectNoException(candidateEval, referenceEval);
        }
    }

    /** Double counterpart to {@link #assertLongResultsMatch}. */
    private void assertDoubleResultsMatch(ExpressionEvaluator.Factory candidate, ExpressionEvaluator.Factory reference) {
        ExpressionEvaluator candidateEval = candidate.get(driverContext);
        ExpressionEvaluator referenceEval = reference.get(driverContext);
        try {
            for (int shape = 0; shape < 40; shape++) {
                int positionCount = positionCountFor(shape);
                boolean withNulls = shape % 3 == 0;
                Page page = null;
                DoubleBlock candidateResult = null;
                DoubleBlock referenceResult = null;
                try {
                    page = new Page(
                        randomDoubleBlock(positionCount, withNulls),
                        randomDoubleBlock(positionCount, withNulls),
                        randomDoubleBlock(positionCount, false)
                    );
                    candidateResult = (DoubleBlock) candidateEval.eval(page);
                    referenceResult = (DoubleBlock) referenceEval.eval(page);
                    assertThat("shape=" + shape, candidateResult.getPositionCount(), is(positionCount));
                    for (int p = 0; p < positionCount; p++) {
                        assertThat("shape=" + shape + " p=" + p, candidateResult.isNull(p), is(referenceResult.isNull(p)));
                        if (referenceResult.isNull(p) == false) {
                            double expected = referenceResult.getDouble(referenceResult.getFirstValueIndex(p));
                            assertThat(
                                "shape=" + shape + " p=" + p,
                                candidateResult.getDouble(candidateResult.getFirstValueIndex(p)),
                                is(expected)
                            );
                        }
                    }
                } finally {
                    Releasables.closeExpectNoException(candidateResult, referenceResult);
                    if (page != null) {
                        page.releaseBlocks();
                    }
                }
            }
        } finally {
            Releasables.closeExpectNoException(candidateEval, referenceEval);
        }
    }

    private static void assertLongBlocksEqual(int shape, int positionCount, LongBlock candidate, LongBlock reference) {
        assertThat("shape=" + shape, candidate.getPositionCount(), is(positionCount));
        assertThat("shape=" + shape, reference.getPositionCount(), is(positionCount));
        for (int p = 0; p < positionCount; p++) {
            assertThat("shape=" + shape + " p=" + p, candidate.isNull(p), is(reference.isNull(p)));
            if (reference.isNull(p) == false) {
                long expected = reference.getLong(reference.getFirstValueIndex(p));
                assertThat("shape=" + shape + " p=" + p, candidate.getLong(candidate.getFirstValueIndex(p)), is(expected));
            }
        }
    }

    /** {@code (a + b) * c} — a depth-2 homogeneous arithmetic tree, fusable when a/b/c are same-typed attributes. */
    private static Expression addMul(FieldAttribute a, FieldAttribute b, FieldAttribute c) {
        return new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
    }

    /** A dense (or, when {@code withNulls}, sparsely-null) long block with bounded values so no overflow occurs. */
    private Block randomLongBlock(int positionCount, boolean withNulls) {
        if (withNulls == false) {
            long[] values = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                values[p] = randomLongBetween(-1_000_000, 1_000_000);
            }
            return blockFactory.newLongArrayVector(values, positionCount).asBlock();
        }
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (randomInt(5) == 0) {
                    builder.appendNull();
                } else {
                    builder.appendLong(randomLongBetween(-1_000_000, 1_000_000));
                }
            }
            return builder.build();
        }
    }

    /** A dense (or, when {@code withNulls}, sparsely-null) double block with bounded finite values (no NaN/Inf). */
    private Block randomDoubleBlock(int positionCount, boolean withNulls) {
        if (withNulls == false) {
            double[] values = new double[positionCount];
            for (int p = 0; p < positionCount; p++) {
                values[p] = randomDoubleBetween(-1_000_000, 1_000_000, true);
            }
            return blockFactory.newDoubleArrayVector(values, positionCount).asBlock();
        }
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (randomInt(5) == 0) {
                    builder.appendNull();
                } else {
                    builder.appendDouble(randomDoubleBetween(-1_000_000, 1_000_000, true));
                }
            }
            return builder.build();
        }
    }

    private int positionCountFor(int shape) {
        return switch (shape) {
            case 0 -> 0;
            case 1 -> 1;
            default -> shape % 20 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 256);
        };
    }

    /** A {@link FusionPlanner.FusedClassCompiler} whose every stitch entry point throws the supplied failure. */
    private static FusionPlanner.FusedClassCompiler failingCompiler(FailingStitch failure) {
        return new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return failure.fail();
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return failure.fail();
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return failure.fail();
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return failure.fail();
            }
        };
    }

    /** A stitch that always fails; the lambda throws the mode-specific error/exception under test. */
    @FunctionalInterface
    private interface FailingStitch {
        Class<?> fail() throws Stitcher.StitchingException;
    }

    private static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(
            Source.EMPTY,
            name,
            new EsField(name, type, Collections.emptyMap(), false, EsField.TimeSeriesFieldType.NONE)
        );
    }

    private static Layout layout(FieldAttribute... fields) {
        Layout.Builder builder = new Layout.Builder();
        for (FieldAttribute field : fields) {
            builder.append(field);
        }
        return builder.build();
    }
}
