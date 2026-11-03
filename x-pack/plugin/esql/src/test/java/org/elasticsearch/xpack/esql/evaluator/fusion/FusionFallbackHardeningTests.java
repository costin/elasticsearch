/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Shape;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** Iter-18 catch-and-fallback hardening (GOAL): proves that every stitching/linkage failure mode */
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
        FusionPlanner.classCache().resetForTests();
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setEnabledForTests(true);
    }

    @After
    public void tearDownFusion() {
        FusionPlanner.compilerForTests = null;
        FusionPlanner.compilation().compileBlockLoopForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setEnabledForTests(true);
        FusionPlanner.classCache().resetForTests();
        assertThat(breaker.getUsed(), is(0L));
    }

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

    public void testPlanTimeBareLinkageErrorFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(expr, layout, () -> { throw new LinkageError("injected linkage failure"); }, false);
    }

    public void testPlanTimeRuntimeExceptionFallsBackToUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        runPlanTimeFallbackCase(expr, layout, () -> { throw new IllegalArgumentException("injected emit failure"); }, false);
    }

    /** A1(a): an {@link IllegalArgumentException} from the stitcher is wrapped onto the negative-cache path, so */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache:DEBUG,"
            + "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:DEBUG",
        reason = "assert an IAE stitch failure WARNs once at the factory then DEBUG on negative-cache hits"
    )
    public void testIllegalArgumentExceptionStitchesOnceAndWarnsOnce() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        AtomicInteger invocations = new AtomicInteger();
        FusionPlanner.compilerForTests = cacheBackedCompiler(cache, (caller, tree, path) -> {
            invocations.incrementAndGet();
            throw new IllegalArgumentException("injected emit failure");
        });

        try (var mockLog = MockLog.capture(FusedExpressionEvaluatorFactory.class)) {
            mockLog.addExpectation(exactlyOneUnfusedWarn(FusedExpressionEvaluatorFactory.class));
            mockLog.addExpectation(seenFactoryDebugRepeat(FusedExpressionEvaluatorFactory.class));
            for (int i = 0; i < 3; i++) {
                ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
                assertThat(
                    "IAE from the stitcher must fall back to the unfused factory",
                    candidate,
                    not(instanceOf(FusedExpressionEvaluatorFactory.class))
                );
            }
            mockLog.assertAllExpectationsMatched();
        }
        assertThat("planning the shape 3× must stitch once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /** A2: a VECTOR-path-only stitch failure (block path still succeeds) must NOT WARN — fusion is degraded, not */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache:DEBUG,"
            + "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:DEBUG",
        reason = "assert a vector-path-only stitch failure emits no WARN (degrade-to-block, still fused)"
    )
    public void testVectorPathStitchFailureLogsNoWarnAndStillFuses() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        FusionPlanner.compilerForTests = cacheBackedCompilerFailingExcept(
            cache,
            Set.of(Path.BLOCK, Path.LOGICAL_BLOCK),
            "injected vector-path stitch failure"
        );

        long degradedBefore = FusionTelemetry.vectorDegraded();
        try (var mockLog = MockLog.capture(FusedExpressionEvaluatorFactory.class)) {
            mockLog.addExpectation(
                new MockLog.UnseenEventExpectation(
                    "vector-path degrade must not WARN at factory",
                    FusedExpressionEvaluatorFactory.class.getCanonicalName(),
                    Level.WARN,
                    "*fusion stitch*"
                )
            );
            mockLog.addExpectation(
                new MockLog.SeenEventExpectation(
                    "vector-path degrade DEBUG names the failed path",
                    FusedExpressionEvaluatorFactory.class.getCanonicalName(),
                    Level.DEBUG,
                    "*on path [CHECKED_VECTOR]*degrading to block-only fusion*"
                )
            );
            ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            assertThat(
                "a vector-path stitch failure must still fuse (block-only), not fall back to unfused",
                candidate,
                instanceOf(FusedExpressionEvaluatorFactory.class)
            );
            assertThat(
                "degraded fusion runs the block path only (no vector fast path)",
                ((FusedExpressionEvaluatorFactory) candidate).vectorStrategy(),
                is(VectorStrategy.NONE)
            );
            ExpressionEvaluator.Factory again = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            assertThat(again, instanceOf(FusedExpressionEvaluatorFactory.class));
            assertThat(((FusedExpressionEvaluatorFactory) again).vectorStrategy(), is(VectorStrategy.NONE));
            mockLog.assertAllExpectationsMatched();
        }
        assertThat("the vector-path degrade must be counted", FusionTelemetry.vectorDegraded(), is(degradedBefore + 2));
        assertThat("the failing vector shape is attempted once", cache.failures(), is(1L));
        assertThat("the second plan is a negative-cache hit", cache.negativeHits(), is(1L));
    }

    /** A2: a full-fallback stitch failure (block path fails, fusion is lost) must WARN exactly once at the factory, */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache:DEBUG,"
            + "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:DEBUG",
        reason = "assert a full-unfused stitch failure WARNs once at the factory then DEBUG on repeats"
    )
    public void testFullFallbackStitchFailureWarnsOnceThenDebug() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        AtomicInteger invocations = new AtomicInteger();
        FusionPlanner.compilerForTests = cacheBackedCompiler(cache, (caller, tree, path) -> {
            invocations.incrementAndGet();
            throw new Stitcher.StitchingException("injected full-fallback stitch failure", null);
        });

        try (var mockLog = MockLog.capture(FusedExpressionEvaluatorFactory.class)) {
            mockLog.addExpectation(exactlyOneUnfusedWarn(FusedExpressionEvaluatorFactory.class));
            mockLog.addExpectation(seenFactoryDebugRepeat(FusedExpressionEvaluatorFactory.class));
            for (int i = 0; i < 3; i++) {
                ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
                assertThat(
                    "a failed plan-time stitch must fall back to the unfused factory",
                    candidate,
                    not(instanceOf(FusedExpressionEvaluatorFactory.class))
                );
            }
            mockLog.assertAllExpectationsMatched();
        }
        assertThat("planning the shape 3× must stitch once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /** the production {@link FusionCompilationService} stitches a real class that is missing the method */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache:DEBUG,"
            + "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:DEBUG",
        reason = "assert a factory findStatic miss WARNs once then DEBUG on negative-cache hits"
    )
    public void testFactoryFindStaticMissWarnsOnceThenDebug() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        assertThat(
            " must use the production compiler (compilerForTests bypasses FusionCompilationService.cached)",
            FusionPlanner.compilerForTests,
            nullValue()
        );
        FusionPlanner.compilation().compileBlockLoopForTests = (real, caller, tree) -> real.compileVectorLoop(caller, tree);
        try {
            try (var mockLog = MockLog.capture(FusedExpressionEvaluatorFactory.class)) {
                mockLog.addExpectation(exactlyOneUnfusedWarn(FusedExpressionEvaluatorFactory.class));
                mockLog.addExpectation(seenFactoryDebugRepeat(FusedExpressionEvaluatorFactory.class));
                for (int i = 0; i < 3; i++) {
                    ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
                    assertThat(
                        "a findStatic miss must fall back to the unfused factory",
                        candidate,
                        not(instanceOf(FusedExpressionEvaluatorFactory.class))
                    );
                }
                mockLog.assertAllExpectationsMatched();
            }
            assertThat("planning the shape 3× must stitch once", cache.failures(), is(1L));
            assertThat(cache.negativeHits(), is(2L));
        } finally {
            FusionPlanner.compilation().compileBlockLoopForTests = null;
        }
    }

    public void testEvalTimeLinkageErrorLatchesAndFallsBackAcrossPages() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = addMul(a, b, c);

        ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat("(a + b) * c over long columns must fuse", candidate, instanceOf(FusedExpressionEvaluatorFactory.class));
        ExpressionEvaluator.Factory reference = ((FusedExpressionEvaluatorFactory) candidate).unfusedFactory();

        long before = FusionTelemetry.totalFailures();
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = true;

        ExpressionEvaluator fused = candidate.get(driverContext);
        ExpressionEvaluator ref = reference.get(driverContext);
        try {
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

        assertMetricRecordedExactlyOnce(before);
    }

    /** A logical {@code AND}/{@code OR} tree whose dense-vector stitch fails must degrade to block-only fusion */
    public void testLogicalVectorPathFailureDegradesToBlockOnly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));

        ExpressionEvaluator.Factory reference = unfusedReference(expr, layout);

        Stitcher real = new Stitcher(new TemplateRegistry());
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileBlockLoop(caller, tree);
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected logical-vector failure", null);
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected logical-vector failure", null);
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileLogicalBlockLoop(caller, tree);
            }

            @Override
            public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected logical-vector failure", null);
            }
        };

        long degradedBefore = FusionTelemetry.vectorDegraded();
        ExpressionEvaluator.Factory candidate = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(
            "a logical-vector stitch failure must still fuse (block-only), not fall back to unfused",
            candidate,
            instanceOf(FusedExpressionEvaluatorFactory.class)
        );
        assertThat(
            "degraded logical fusion keeps the 3VL block path only",
            ((FusedExpressionEvaluatorFactory) candidate).vectorStrategy(),
            is(VectorStrategy.NONE)
        );
        assertThat("the logical-vector degrade must be counted", FusionTelemetry.vectorDegraded(), is(degradedBefore + 1));

        int positionCount = 128;
        long[] av = new long[positionCount];
        long[] bv = new long[positionCount];
        long[] cv = new long[positionCount];
        long[] dv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            bv[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
            dv[p] = randomLongBetween(-1000, 1000);
        }
        ExpressionEvaluator fused = candidate.get(driverContext);
        ExpressionEvaluator ref = reference.get(driverContext);
        Page page = null;
        BooleanBlock fusedResult = null;
        BooleanBlock refResult = null;
        try {
            page = new Page(
                blockFactory.newLongArrayVector(av, positionCount).asBlock(),
                blockFactory.newLongArrayVector(bv, positionCount).asBlock(),
                blockFactory.newLongArrayVector(cv, positionCount).asBlock(),
                blockFactory.newLongArrayVector(dv, positionCount).asBlock()
            );
            fusedResult = (BooleanBlock) fused.eval(page);
            refResult = (BooleanBlock) ref.eval(page);
            assertThat(fusedResult.getPositionCount(), is(positionCount));
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p + " null", fusedResult.isNull(p), is(refResult.isNull(p)));
                if (refResult.isNull(p) == false) {
                    assertThat(
                        "p=" + p,
                        fusedResult.getBoolean(fusedResult.getFirstValueIndex(p)),
                        is(refResult.getBoolean(refResult.getFirstValueIndex(p)))
                    );
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, refResult, fused, ref);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /** Drives one plan-time fallback case: builds an independent unfused reference, injects {@code failure} into the */
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

    /** Evaluates a candidate long factory and its unfused reference over the same random pages (empty, single, small, */
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

    private static MockLog.LoggingExpectation exactlyOneUnfusedWarn(Class<?> loggerClass) {
        return new ExactlyOnceWarnExpectation(
            "exactly one unfused WARN at " + loggerClass.getSimpleName(),
            loggerClass.getCanonicalName(),
            "*unfused*"
        );
    }

    private static MockLog.LoggingExpectation seenFactoryDebugRepeat(Class<?> loggerClass) {
        return new MockLog.SeenEventExpectation(
            "repeat DEBUG at factory",
            loggerClass.getCanonicalName(),
            Level.DEBUG,
            "*previously failed for shape*"
        );
    }

    /** Counts WARN events matching {@code message} on {@code logger} and asserts the count is exactly 1. */
    private static final class ExactlyOnceWarnExpectation implements MockLog.LoggingExpectation {
        private final String name;
        private final String logger;
        private final String message;
        private final AtomicInteger count = new AtomicInteger();

        ExactlyOnceWarnExpectation(String name, String logger, String message) {
            this.name = name;
            this.logger = logger;
            this.message = message;
        }

        @Override
        public void match(LogEvent event) {
            if (event.getLevel() == Level.WARN && event.getLoggerName().equals(logger)) {
                String formatted = event.getMessage().getFormattedMessage();
                boolean matched = Regex.isSimpleMatchPattern(message) ? Regex.simpleMatch(message, formatted) : formatted.contains(message);
                if (matched) {
                    count.incrementAndGet();
                }
            }
        }

        @Override
        public void assertMatched() {
            assertThat(name + ": expected exactly 1 WARN", count.get(), is(1));
        }
    }

    /** Like {@link #cacheBackedCompiler} but succeeds on {@code succeeding} paths via the real {@link Stitcher} and */
    private static FusionPlanner.FusedClassCompiler cacheBackedCompilerFailingExcept(
        FusedClassCache cache,
        Set<Path> succeeding,
        String failureMessage
    ) {
        Stitcher real = new Stitcher(new TemplateRegistry());
        return cacheBackedCompiler(cache, (caller, tree, path) -> {
            if (succeeding.contains(path) == false) {
                throw new Stitcher.StitchingException(failureMessage, null);
            }
            return switch (path) {
                case BLOCK -> real.compileBlockLoop(caller, tree);
                case PLAIN_VECTOR -> real.compileVectorLoop(caller, tree);
                case CHECKED_VECTOR -> real.compileVectorLoopChecked(caller, tree);
                case LOGICAL_BLOCK -> real.compileLogicalBlockLoop(caller, tree);
                case LOGICAL_VECTOR -> real.compileLogicalVectorLoop(caller, tree);
            };
        });
    }

    /** A compiler that still goes through {@code cache} (unlike {@link FusionPlanner#compilerForTests}'s usual bypass) */
    private static FusionPlanner.FusedClassCompiler cacheBackedCompiler(FusedClassCache cache, PathStitch stitch) {
        return new FusionPlanner.FusedClassCompiler() {
            private Class<?> cached(MethodHandles.Lookup caller, FusionNode tree, Path path) throws Stitcher.StitchingException {
                ElementKind element = FusionNode.isBooleanLogic(tree) ? ElementKind.BOOLEAN : ElementKind.LONG;
                Shape shape = new Shape(FusionPlanner.shapeOf(tree), element, path);
                return cache.get(shape, () -> FusedClassCache.stitchAndResolve(() -> stitch.stitch(caller, tree, path), caller, null));
            }

            /** WHY resolve inside {@code cache.getResolved}: the factory's MethodType overloads pass the expected */
            private FusedClassCache.CachedFused cachedResolved(
                MethodHandles.Lookup caller,
                FusionNode tree,
                Path path,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                ElementKind element = FusionNode.isBooleanLogic(tree) ? ElementKind.BOOLEAN : ElementKind.LONG;
                Shape shape = new Shape(FusionPlanner.shapeOf(tree), element, path);
                return cache.getResolved(
                    shape,
                    () -> FusedClassCache.stitchAndResolve(() -> stitch.stitch(caller, tree, path), caller, type, methods)
                );
            }

            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return cached(caller, tree, Path.BLOCK);
            }

            @Override
            public FusedClassCache.CachedFused compileBlockLoop(
                MethodHandles.Lookup caller,
                FusionNode tree,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                return cachedResolved(caller, tree, Path.BLOCK, type, methods);
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return cached(caller, tree, Path.PLAIN_VECTOR);
            }

            @Override
            public FusedClassCache.CachedFused compileVectorLoop(
                MethodHandles.Lookup caller,
                FusionNode tree,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                return cachedResolved(caller, tree, Path.PLAIN_VECTOR, type, methods);
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return cached(caller, tree, Path.CHECKED_VECTOR);
            }

            @Override
            public FusedClassCache.CachedFused compileVectorLoopChecked(
                MethodHandles.Lookup caller,
                FusionNode tree,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                return cachedResolved(caller, tree, Path.CHECKED_VECTOR, type, methods);
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return cached(caller, tree, Path.LOGICAL_BLOCK);
            }

            @Override
            public FusedClassCache.CachedFused compileLogicalBlockLoop(
                MethodHandles.Lookup caller,
                FusionNode tree,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                return cachedResolved(caller, tree, Path.LOGICAL_BLOCK, type, methods);
            }

            @Override
            public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return cached(caller, tree, Path.LOGICAL_VECTOR);
            }

            @Override
            public FusedClassCache.CachedFused compileLogicalVectorLoop(
                MethodHandles.Lookup caller,
                FusionNode tree,
                MethodType type,
                String... methods
            ) throws Stitcher.StitchingException {
                return cachedResolved(caller, tree, Path.LOGICAL_VECTOR, type, methods);
            }
        };
    }

    @FunctionalInterface
    private interface PathStitch {
        Class<?> stitch(MethodHandles.Lookup caller, FusionNode tree, Path path) throws Stitcher.StitchingException;
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

            @Override
            public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
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
