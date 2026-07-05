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

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage for the Stage-6 adaptive (tiered) fusion path: {@code esql.fusion.adaptive.min_rows > 0} makes
 * {@link EvalMapper#toEvaluator} return an {@link AdaptiveFusionEvaluatorFactory} that starts on the unfused chain and
 * switches to a stitched fused evaluator only after it has seen the threshold number of rows — plus the A/B
 * {@code esql.fusion.sample} gate. All scenarios run on a real breaker-backed {@link BlockFactory} against real emitted
 * bytecode; the {@code @After} breaker-balance assert proves every produced block is released.
 */
public class AdaptiveFusionEvaluatorTests extends ESTestCase {

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private MockBigArrays bigArrays;
    private DriverContext driverContext;

    @Before
    public void setUpAdaptive() throws Exception {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService);
        blockFactory = BlockFactory.builder(bigArrays).build();
        driverContext = new DriverContext(bigArrays, blockFactory, null);
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
    }

    @After
    public void tearDownAdaptive() {
        FusionPlanner.compilerForTests = null;
        FusionSettings.setEnabledForTests(true);
        FusionSettings.setAdaptiveMinRowsForTests(0);
        FusionSettings.setSampleFractionForTests(1.0);
        assertThat(breaker.getUsed(), is(0L));
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

    /** (a + b) * c over long columns — the canonical fusable depth-2 tree used across the fusion suite. */
    private Expression addMulLong(FieldAttribute a, FieldAttribute b, FieldAttribute c) {
        return new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
    }

    private Page longPage(int positionCount, long[] a, long[] b, long[] c) {
        return new Page(
            blockFactory.newLongArrayVector(a, positionCount).asBlock(),
            blockFactory.newLongArrayVector(b, positionCount).asBlock(),
            blockFactory.newLongArrayVector(c, positionCount).asBlock()
        );
    }

    // Fill an array with bounded random longs so (a + b) * c never overflows.
    private long[] boundedLongs(int positionCount) {
        long[] values = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            values[p] = randomLongBetween(-1000, 1000);
        }
        return values;
    }

    private void assertPageMatches(ExpressionEvaluator evaluator, int positionCount) {
        long[] a = boundedLongs(positionCount);
        long[] b = boundedLongs(positionCount);
        long[] c = boundedLongs(positionCount);
        Page page = null;
        LongBlock result = null;
        try {
            page = longPage(positionCount, a, b, c);
            result = (LongBlock) evaluator.eval(page);
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), is((a[p] + b[p]) * c[p]));
            }
        } finally {
            Releasables.closeExpectNoException(result);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // With min_rows > 0 the planner returns an adaptive factory (not an eager fused one), and the evaluator stays
    // unfused until the cumulative row count crosses the threshold, then switches — computing correctly throughout.
    public void testSwitchesToFusedAfterThreshold() {
        FusionSettings.setAdaptiveMinRowsForTests(200);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), addMulLong(a, b, c), layout);
        assertThat("min_rows>0 must produce an adaptive factory", factory, instanceOf(AdaptiveFusionEvaluatorFactory.class));
        assertThat("no eager stitch at plan time", factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        ExpressionEvaluator evaluator = factory.get(driverContext);
        try {
            // Page 1: 100 rows < 200 threshold -> still unfused, no switch yet.
            assertPageMatches(evaluator, 100);
            assertThat("below threshold: not yet switched", FusionTelemetry.adaptiveSwitches(), is(0L));

            // Page 2: cumulative 100 + 150 = 250 >= 200 -> stitch + switch on this page.
            assertPageMatches(evaluator, 150);
            assertThat("threshold crossed: switched exactly once", FusionTelemetry.adaptiveSwitches(), is(1L));

            // Page 3: already switched -> still correct, still counted once (no re-switch).
            assertPageMatches(evaluator, 64);
            assertThat("no re-switch after switching", FusionTelemetry.adaptiveSwitches(), is(1L));
        } finally {
            Releasables.closeExpectNoException(evaluator);
        }
    }

    // A deferred stitch that fails must latch fusion off (adaptiveAbandoned++), leaving the evaluator on the unfused
    // chain for the rest of its life while still computing correctly.
    public void testFailedDeferredStitchStaysUnfused() {
        FusionSettings.setAdaptiveMinRowsForTests(50);
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected deferred stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected deferred stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected deferred stitch failure", new VerifyError("injected"));
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected deferred stitch failure", new VerifyError("injected"));
            }
        };
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), addMulLong(a, b, c), layout);
        assertThat(factory, instanceOf(AdaptiveFusionEvaluatorFactory.class));

        long abandonedBefore = FusionTelemetry.adaptiveAbandoned();
        ExpressionEvaluator evaluator = factory.get(driverContext);
        try {
            // Cross the threshold: the deferred stitch fires and fails, so fusion is abandoned but the query is correct.
            assertPageMatches(evaluator, 64);
            assertThat("deferred stitch failure is recorded once", FusionTelemetry.adaptiveAbandoned(), is(abandonedBefore + 1));
            assertThat("never actually switched", FusionTelemetry.adaptiveSwitches(), is(0L));
            // A later page stays on the unfused chain (latched) and is still correct.
            assertPageMatches(evaluator, 32);
            assertThat("no retry after abandoning", FusionTelemetry.adaptiveAbandoned(), is(abandonedBefore + 1));
        } finally {
            Releasables.closeExpectNoException(evaluator);
        }
    }

    // A/B sampling at fraction 0.0 must never attempt fusion: the planner returns neither a fused nor an adaptive
    // factory, and the query runs entirely unfused.
    public void testSampleFractionZeroNeverFuses() {
        FusionSettings.setSampleFractionForTests(0.0);
        FusionSettings.setAdaptiveMinRowsForTests(1); // even with adaptivity armed, sample=0 short-circuits first
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), addMulLong(a, b, c), layout);
        assertThat("sample=0 must not fuse", factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
        assertThat("sample=0 must not go adaptive", factory, not(instanceOf(AdaptiveFusionEvaluatorFactory.class)));

        ExpressionEvaluator evaluator = factory.get(driverContext);
        try {
            assertPageMatches(evaluator, 128);
            assertThat(FusionTelemetry.adaptiveSwitches(), is(0L));
        } finally {
            Releasables.closeExpectNoException(evaluator);
        }
    }

    // The adaptive + sample Settings themselves: defaults and bounds parse as declared and latch through the node hook.
    public void testAdaptiveAndSampleSettingWiring() {
        assertThat(FusionSettings.FUSION_ADAPTIVE_MIN_ROWS_SETTING.get(org.elasticsearch.common.settings.Settings.EMPTY), is(0));
        assertThat(FusionSettings.FUSION_SAMPLE_SETTING.get(org.elasticsearch.common.settings.Settings.EMPTY), is(1.0));
        assertThat(
            FusionSettings.FUSION_ADAPTIVE_MIN_ROWS_SETTING.get(
                org.elasticsearch.common.settings.Settings.builder().put("esql.fusion.adaptive.min_rows", 4096).build()
            ),
            is(4096)
        );
        assertThat(
            FusionSettings.FUSION_SAMPLE_SETTING.get(
                org.elasticsearch.common.settings.Settings.builder().put("esql.fusion.sample", 0.25).build()
            ),
            is(0.25)
        );
    }
}
