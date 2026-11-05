/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.lucene.EmptyIndexedByShardId;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MapExpression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Round;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.plugin.EsqlPlugin;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Opt-in fusion coverage counters. With {@link FusionTelemetry#coverageEnabled} on, each <em>top-level</em>
 * expression is classified once as FUSED/ISLAND/FRAGMENT/NONE (categories) and show up in
 * {@link FusionTelemetry#coverageReport()}. With coverage off the record* coverage buckets stay at zero (zero cost).
 */
public class FusionTelemetryCoverageTests extends FusionDifferentialTestCase {

    @Before
    public void enableCoverage() {
        FusionSettings.setCoverageReportEnabledForTests(true);
        FusionTelemetry.resetForTests();
    }

    @After
    public void disableCoverage() {
        FusionSettings.setCoverageReportEnabledForTests(false);
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
    }

    public void testCoverageDisabledRecordMethodsAreNoOps() {
        FusionSettings.setCoverageReportEnabledForTests(false);
        FusionTelemetry.resetForTests();

        FusionTelemetry.recordAttempt();
        FusionTelemetry.recordFused();
        FusionTelemetry.recordRuntimeFallback();
        FusionTelemetry.recordNestedMappingMvFallback();
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
        assertThat("failures still count with coverage off", FusionTelemetry.totalFailures(), is(1L));
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

        assertThat(FusionTelemetry.none(), is(1L));
        assertThat(FusionTelemetry.refusalsFor("depth<2"), is(1L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("expressions=2"));
        assertThat(report, containsString("FUSED=1 (50.0%)"));
        assertThat(report, containsString("NONE=1 (50.0%)"));
        assertThat(report, containsString("stitch-failures=0"));
        assertThat(report, containsString("runtime-fallbacks=0"));
        assertThat(report, containsString("nested-mapping-mv-fallbacks=0"));
        assertThat(report, containsString("depth<2=1"));
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
        assertThat(FusionTelemetry.nestedMappingMvFallbacks(), is(0L));

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
            first = evaluator.eval(page);
            assertThat(FusedExpressionEvaluatorFactory.lastEvalPath(evaluator), is(LastEvalPath.UNFUSED_FALLBACK));
            assertThat("first MV page records one nested-mapping MV fallback", FusionTelemetry.nestedMappingMvFallbacks(), is(1L));
            assertThat("nested-mapping MV is not a LinkageError runtime fallback", FusionTelemetry.runtimeFallbacks(), is(0L));
            second = evaluator.eval(page);
            assertThat("a per-page MV fallback must not be counted per page", FusionTelemetry.nestedMappingMvFallbacks(), is(1L));
            assertThat(FusionTelemetry.runtimeFallbacks(), is(0L));
        } finally {
            Releasables.closeExpectNoException(first, second, evaluator);
            page.releaseBlocks();
        }

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("expressions=1"));
        assertThat(report, containsString("FUSED=1 (100.0%)"));
        assertThat(report, containsString("runtime-fallbacks=0"));
        assertThat(report, containsString("nested-mapping-mv-fallbacks=1"));
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
        assertThat(FusionTelemetry.fragment(), is(1L));
        assertThat(FusionTelemetry.totalFailures(), is(1L));
        assertThat(FusionTelemetry.failuresForPath(Path.BLOCK), is(1L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("expressions=1"));
        assertThat(report, containsString("FUSED=0 (0.0%)"));
        assertThat(report, containsString("FRAGMENT=1 (100.0%)"));
        assertThat(report, containsString("stitch-failures=1"));
        assertThat(report, containsString("BLOCK=1"));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(1L));
        assertAccountingIdentity();
    }

    /**
     * guard: four top-level expressions, one per category, produce FUSED=1 / ISLAND=1 / FRAGMENT=1 / NONE=1
     * and record a first-refusal reason for the non-fused rows.
     */
    public void testFourCategoriesAndOneRefusalReason() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout numeric = layout(a, b, c);
        Layout keyword = layout(s);

        Expression fusedExpr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        assertThat(EvalMapper.toEvaluator(FoldContext.small(), fusedExpr, numeric), instanceOf(FusedExpressionEvaluatorFactory.class));

        Expression islandExpr = new Round(Source.EMPTY, fusedExpr, null);
        assertThat(
            EvalMapper.toEvaluator(FoldContext.small(), islandExpr, numeric),
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );

        Expression fragmentExpr = new ToLower(Source.EMPTY, new ToUpper(Source.EMPTY, s, EsqlTestUtils.TEST_CFG), EsqlTestUtils.TEST_CFG);
        assertThat(
            EvalMapper.toEvaluator(FoldContext.small(), fragmentExpr, keyword),
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );

        Expression noneExpr = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);
        assertThat(EvalMapper.toEvaluator(FoldContext.small(), noneExpr, numeric), not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        assertThat(FusionTelemetry.attempts(), is(4L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.island(), is(1L));
        assertThat(FusionTelemetry.fragment(), is(1L));
        assertThat(FusionTelemetry.none(), is(1L));
        assertThat("lone kernel first-refusal is depth<2", FusionTelemetry.refusalsFor("depth<2"), is(1L));
        assertThat("island first-refusal is the unfusable parent", FusionTelemetry.refusalsFor("Round"), is(1L));
        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("FUSED=1 (25.0%)"));
        assertThat(report, containsString("ISLAND=1 (25.0%)"));
        assertThat(report, containsString("FRAGMENT=1 (25.0%)"));
        assertThat(report, containsString("NONE=1 (25.0%)"));
        assertThat(report, containsString("depth<2=1"));
        assertAccountingIdentity();
    }

    /** Named-option maps (DECAY/CHUNK/ST_BUFFER, …) are expression children but not {@code EvaluatorMapper}s. */
    public void testMapExpressionChildDoesNotInvokeRawFactory() {
        MapExpression opts = new MapExpression(
            Source.EMPTY,
            List.of(Literal.keyword(Source.EMPTY, "offset"), Literal.integer(Source.EMPTY, 5))
        );
        Function<Expression, ExpressionEvaluator.Factory> raw = e -> {
            throw new AssertionError("rawFactory must not be applied to [" + e + "]");
        };
        assertThat(FusionCoverage.isFusableOperator(opts, raw), is(false));
        assertThat(FusionCoverage.firstRefusalReason(opts, raw), is("MapExpression"));
        assertThat(FusionCoverage.kernelSkeletonDepth(opts, raw), is(0));
    }

    /** A declined WHERE predicate (a lone comparison) is one top-level expression classified NONE. */
    public void testDeclinedWhereCountsAsOneTopLevel() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression predicate = new GreaterThan(Source.EMPTY, a, b);
        EvalMapper.toFilterEvaluator(FoldContext.small(), predicate, layout, EmptyIndexedByShardId.instance(), null);
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.none(), is(1L));
        assertAccountingIdentity();
    }

    /**
     * A fused WHERE predicate counts as FUSED; when its block POSITIONS stitch fails it falls back unfused, counts as a
     * FRAGMENT, and the plan-time failure is classified under {@link Path#BLOCK} in the coverage report.
     */
    public void testWhereFusedAndStitchFailureSurfacesBlockPath() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = EvalMapper.toFilterEvaluator(
            FoldContext.small(),
            predicate,
            layout,
            EmptyIndexedByShardId.instance(),
            null
        );
        assertThat(fused, instanceOf(FusedFilterEvaluatorFactory.class));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(1L));
        assertThat(FusionTelemetry.totalFailures(), is(0L));

        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = failingCompiler();
        ExpressionEvaluator.Factory fallback = EvalMapper.toFilterEvaluator(
            FoldContext.small(),
            predicate,
            layout,
            EmptyIndexedByShardId.instance(),
            null
        );
        assertThat(fallback, not(instanceOf(FusedFilterEvaluatorFactory.class)));
        assertThat(FusionTelemetry.attempts(), is(1L));
        assertThat(FusionTelemetry.fused(), is(0L));
        assertThat(FusionTelemetry.fragment(), is(1L));
        assertThat(FusionTelemetry.totalFailures(), is(1L));
        assertThat(FusionTelemetry.failuresForPath(Path.BLOCK), is(1L));

        String report = FusionTelemetry.coverageReport();
        assertThat(report, containsString("stitch-failures=1"));
        assertThat(report, containsString("BLOCK=1"));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(1L));
        assertAccountingIdentity();
    }

    public void testCoverageReportIsNotANodeSetting() {
        for (Setting<?> setting : new EsqlPlugin().getSettings()) {
            assertThat(
                "EsqlPlugin must not register esql.fusion.coverage_report (a non-Setting diagnostic, like verify)",
                setting.getKey().equals("esql.fusion.coverage_report"),
                is(false)
            );
        }

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
            assertThat("eval-time LinkageError is still counted", FusionTelemetry.totalFailures(), is(1L));
            assertThat("it is not a plan-time stitch failure", FusionTelemetry.planTimeStitchFailures(), is(0L));
            assertThat(FusionTelemetry.runtimeFallbacks(), is(1L));
            String report = FusionTelemetry.coverageReport();
            assertThat(report, containsString("stitch-failures=0"));
            assertThat(report, containsString("runtime-fallbacks=1"));
            assertThat(report, containsString("FUSED=1"));
            assertAccountingIdentity();
        } finally {
            FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
            Releasables.closeExpectNoException(evaluator);
        }
    }

    private static void assertCoverageZero() {
        assertThat(FusionTelemetry.attempts(), is(0L));
        assertThat(FusionTelemetry.fused(), is(0L));
        assertThat(FusionTelemetry.island(), is(0L));
        assertThat(FusionTelemetry.fragment(), is(0L));
        assertThat(FusionTelemetry.none(), is(0L));
        assertThat(FusionTelemetry.runtimeFallbacks(), is(0L));
        assertThat(FusionTelemetry.nestedMappingMvFallbacks(), is(0L));
        assertThat(FusionTelemetry.planTimeStitchFailures(), is(0L));
    }

    private static void assertAccountingIdentity() {
        assertThat(
            "FUSED + ISLAND + FRAGMENT + NONE == expressions",
            FusionTelemetry.fused() + FusionTelemetry.island() + FusionTelemetry.fragment() + FusionTelemetry.none(),
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
            public Class<?> compileBlockPositionsLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected positions stitch failure", new VerifyError("injected"));
            }
        };
    }
}
