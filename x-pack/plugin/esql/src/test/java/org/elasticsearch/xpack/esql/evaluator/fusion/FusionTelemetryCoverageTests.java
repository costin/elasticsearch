/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.plugin.EsqlPlugin;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.util.function.Function;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * F4: opt-in fusion coverage counters. With {@link FusionTelemetry#coverageEnabled} on, a fusing tree, a refused
 * lone kernel, a runtime nested-mapping MV fallback, and a stitch failure are each counted once and show up in
 * {@link FusionTelemetry#coverageReport()}. With coverage off the record* coverage buckets stay at zero (zero cost).
 */
public class FusionTelemetryCoverageTests extends FusionDifferentialTestCase {

    @Before
    public void enableCoverage() {
        FusionTelemetry.coverageEnabled = true;
        FusionTelemetry.resetForTests();
    }

    @After
    public void disableCoverage() {
        FusionTelemetry.coverageEnabled = false;
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setAdaptiveMinRowsForTests(0);
    }

    public void testCoverageDisabledRecordMethodsAreNoOps() {
        FusionTelemetry.coverageEnabled = false;
        FusionTelemetry.resetForTests();

        FusionTelemetry.recordAttempt();
        FusionTelemetry.recordFused();
        FusionTelemetry.recordRefusedAtPlan();
        FusionTelemetry.recordRuntimeFallback();
        assertCoverageZero();

        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression fusedExpr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression refusedExpr = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), fusedExpr, layout);
        assertThat(fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        ExpressionEvaluator.Factory refusedFactory = EvalMapper.toEvaluator(FoldContext.small(), refusedExpr, layout);
        assertThat(refusedFactory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        assertCoverageZero();
        assertThat(
            "recordFailure stays unconditional even when coverage is off; this path did not stitch-fail",
            FusionTelemetry.totalFailures(),
            is(0L)
        );

        FusionPlanner.compilerForTests = failingCompiler();
        ExpressionEvaluator.Factory failed = EvalMapper.toEvaluator(FoldContext.small(), fusedExpr, layout);
        assertThat(failed, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
        assertThat("criterion #5 still counts with coverage off", FusionTelemetry.totalFailures(), is(1L));
        assertThat("the path map is coverage-gated", FusionTelemetry.failuresForPath(Path.BLOCK), is(0L));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(0L));
        assertCoverageZero();
    }

    public void testFuseAndRefuseThroughEvalMapperAndReportPercentages() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        Expression fusedExpr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), fusedExpr, layout);
        assertThat("(a+b)*c must fuse", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.refusedAtPlan(), is(0L));
        assertThat(FusionTelemetry.totalFailures(), is(0L));

        Expression refusedExpr = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);
        ExpressionEvaluator.Factory refusedFactory = EvalMapper.toEvaluator(FoldContext.small(), refusedExpr, layout);
        assertThat("a lone kernel must refuse at plan", refusedFactory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
        assertThat(FusionTelemetry.attempts(), is(2L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.refusedAtPlan(), is(1L));
        assertThat(FusionTelemetry.totalFailures(), is(0L));
        assertThat(FusionTelemetry.runtimeFallbacks(), is(0L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("attempts=2"));
        assertThat(report, containsString("fused=1"));
        assertThat(report, containsString("refused-at-plan=1"));
        assertThat(report, containsString("stitch-failures=0"));
        assertThat(report, containsString("runtime-fallbacks=0"));
        assertThat(report, containsString("fused/attempt=50.0%"));
        assertAccountingIdentity();
    }

    public void testRuntimeMvFallbackCountedOncePerEvaluator() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, EsqlTestUtils.TEST_CFG));
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout(s));
        assertThat("LENGTH(TO_LOWER(k)) must fuse", factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.runtimeFallbacks(), is(0L));

        int positionCount = 4;
        Block input;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("Hello"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("WORLD"));
            b.appendBytesRef(new BytesRef("MiXeD"));
            b.endPositionEntry();
            b.appendNull();
            b.appendBytesRef(new BytesRef("café"));
            input = b.build();
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
        Page page = new Page(input);
        Block first = null;
        Block second = null;
        try {
            drainWarnings();
            first = evaluator.eval(page);
            assertThat(FusedExpressionEvaluatorFactory.lastEvalPath(evaluator), is(LastEvalPath.UNFUSED_FALLBACK));
            assertThat("first MV page records one runtime fallback", FusionTelemetry.runtimeFallbacks(), is(1L));
            drainWarnings();
            second = evaluator.eval(page);
            assertThat("a per-page MV fallback must not be counted per page", FusionTelemetry.runtimeFallbacks(), is(1L));
            drainWarnings();
        } finally {
            Releasables.closeExpectNoException(first, second, evaluator);
            page.releaseBlocks();
        }

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("attempts=1"));
        assertThat(report, containsString("fused=1"));
        assertThat(report, containsString("runtime-fallbacks=1"));
        assertThat(report, containsString("fused/attempt=100.0%"));
        assertAccountingIdentity();
    }

    public void testStitchFailureIsTheAttemptOutcomeAndClassifiedByPath() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        FusionPlanner.compilerForTests = failingCompiler();
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(0L));
        assertThat(FusionTelemetry.refusedAtPlan(), is(0L));
        assertThat(FusionTelemetry.totalFailures(), is(1L));
        assertThat(FusionTelemetry.failuresForPath(Path.BLOCK), is(1L));
        assertThat(FusionTelemetry.failuresForPath(Path.FILTER_EVAL_BLOCK), is(0L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("attempts=1"));
        assertThat(report, containsString("fused=0"));
        assertThat(report, containsString("stitch-failures=1"));
        assertThat(report, containsString("BLOCK=1"));
        assertThat(report, containsString("fused/attempt=0.0%"));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(1L));
        assertAccountingIdentity();
    }

    public void testFilterEvalFusedAndStitchFailureSurfacesFilterEvalBlock() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression projection = new Mul(Source.EMPTY, a, b);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);

        FilterEvalEvaluator.Factory fused = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat(fused, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        assertThat(((FusedFilterEvalEvaluatorFactory) fused).fused(), is(true));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.totalFailures(), is(0L));

        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = failingCompiler();
        FilterEvalEvaluator.Factory fallback = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat(fallback, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        assertThat(((FusedFilterEvalEvaluatorFactory) fallback).fused(), is(false));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(0L));
        assertThat(FusionTelemetry.refusedAtPlan(), is(0L));
        assertThat(FusionTelemetry.totalFailures(), is(1L));
        assertThat(FusionTelemetry.failuresForPath(Path.FILTER_EVAL_BLOCK), is(1L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("stitch-failures=1"));
        assertThat(report, containsString("FILTER_EVAL_BLOCK=1"));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(1L));
        assertAccountingIdentity();
    }

    public void testCoverageReportSettingDefaultsFalseParsesTrueAndLatchesTelemetry() {
        assertThat(FusionSettings.FUSION_COVERAGE_REPORT_SETTING.get(Settings.EMPTY), is(false));
        assertThat(
            FusionSettings.FUSION_COVERAGE_REPORT_SETTING.get(Settings.builder().put("esql.fusion.coverage_report", true).build()),
            is(true)
        );
        boolean registered = false;
        for (Setting<?> setting : new EsqlPlugin().getSettings()) {
            if (setting.getKey().equals("esql.fusion.coverage_report")) {
                registered = true;
                break;
            }
        }
        assertThat("EsqlPlugin must register esql.fusion.coverage_report", registered, is(true));

        boolean originalCoverage = FusionSettings.isCoverageReportEnabled();
        try {
            FusionSettings.setCoverageReportEnabledForTests(true);
            assertThat(FusionSettings.isCoverageReportEnabled(), is(true));
            assertThat(FusionTelemetry.coverageEnabled, is(true));
            FusionSettings.setCoverageReportEnabledForTests(false);
            assertThat(FusionSettings.isCoverageReportEnabled(), is(false));
            assertThat(FusionTelemetry.coverageEnabled, is(false));
        } finally {
            FusionSettings.setCoverageReportEnabledForTests(originalCoverage);
        }
    }

    public void testAdaptiveTwoDriversRecordFusedOnce() {
        FusionSettings.setAdaptiveMinRowsForTests(1);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(AdaptiveFusionEvaluatorFactory.class));
        assertThat("attempt is counted at plan time", FusionTelemetry.attempts(), is(1L));
        assertThat("deferred stitch has not fused yet", FusionTelemetry.fused(), is(0L));

        DriverContext other = new DriverContext(bigArrays, blockFactory, null);
        ExpressionEvaluator first = factory.get(driverContext);
        ExpressionEvaluator second = factory.get(other);
        try {
            evalAddMulPage(first, 8);
            evalAddMulPage(second, 8);
            assertThat("two drivers crossing minRows switch twice", FusionTelemetry.adaptiveSwitches(), is(2L));
            assertThat("recordFused fires once per factory, not per driver", FusionTelemetry.fused(), is(1L));
            assertAccountingIdentity();
        } finally {
            Releasables.closeExpectNoException(first, second);
        }
    }

    public void testEvalTimeLinkageErrorIsRuntimeFallbackNotStitchFailure() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(0L));

        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = true;
        ExpressionEvaluator evaluator = factory.get(driverContext);
        try {
            evalAddMulPage(evaluator, 16);
            assertThat("eval-time LinkageError is still criterion #5", FusionTelemetry.totalFailures(), is(1L));
            assertThat("it is not a plan-time stitch failure", FusionTelemetry.planTimeStitchFailures(), is(0L));
            assertThat(FusionTelemetry.runtimeFallbacks(), is(1L));
            String report = FusionTelemetry.coverageReport();
            assertThat(report, containsString("stitch-failures=0"));
            assertThat(report, containsString("runtime-fallbacks=1"));
            assertThat(report, containsString("fused=1"));
            assertAccountingIdentity();
        } finally {
            FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
            Releasables.closeExpectNoException(evaluator);
        }
    }

    private static void assertCoverageZero() {
        assertThat(FusionTelemetry.attempts(), is(0L));
        assertThat(FusionTelemetry.fused(), is(0L));
        assertThat(FusionTelemetry.refusedAtPlan(), is(0L));
        assertThat(FusionTelemetry.runtimeFallbacks(), is(0L));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(0L));
    }

    private static void assertAccountingIdentity() {
        assertThat(
            "fused + refused-at-plan + plan-time stitch == attempts",
            FusionTelemetry.fused() + FusionTelemetry.refusedAtPlan() + FusionTelemetry.planTimeStitchFailures(),
            is(FusionTelemetry.attempts())
        );
    }

    private void evalAddMulPage(ExpressionEvaluator evaluator, int positionCount) {
        long[] av = new long[positionCount];
        long[] bv = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            bv[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        Page page = null;
        LongBlock result = null;
        try {
            page = new Page(
                blockFactory.newLongArrayVector(av, positionCount).asBlock(),
                blockFactory.newLongArrayVector(bv, positionCount).asBlock(),
                blockFactory.newLongArrayVector(cv, positionCount).asBlock()
            );
            result = (LongBlock) evaluator.eval(page);
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), is((av[p] + bv[p]) * cv[p]));
            }
        } finally {
            Releasables.closeExpectNoException(result);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    private static FusionPlanner.FusedClassCompiler failingCompiler() {
        return new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
                throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected filter-eval stitch failure", new VerifyError("injected"));
            }
        };
    }
}
