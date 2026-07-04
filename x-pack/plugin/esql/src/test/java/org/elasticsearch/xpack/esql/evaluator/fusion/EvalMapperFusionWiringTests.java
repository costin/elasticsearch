/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.settings.Settings;
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
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.util.Collections;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration coverage for iter 13's plan-time wiring: {@link EvalMapper#toEvaluator} routing a fully-fusable
 * arithmetic subtree through {@link FusionPlanner} into a {@link FusedExpressionEvaluatorFactory}, and doing so
 * strictly additively (the unfused chain is produced and used whenever fusion is impossible or a stitch fails).
 *
 * <p>Everything runs on a real breaker-backed {@link BlockFactory} against real emitted bytecode — no mocks — and the
 * {@code @After} breaker-balance assert proves every produced block/vector is released.
 *
 * <p>The four scenarios (per the iter brief):
 * <ol>
 *   <li><b>fused-eligible</b> {@code (a + b) * c}: the produced factory is a fused one and its output equals the
 *       unfused chain's, position-by-position, over many random shapes (including a non-commutative
 *       {@code (a - b) * c} variant to catch any operand-order regression in the wiring);</li>
 *   <li><b>non-fusable</b> ({@code (a + 5) * c}, a literal leaf): the unfused path is taken;</li>
 *   <li><b>forced fallback</b>: a test-injected stitch failure makes the query complete via the unfused path with the
 *       failure counter incremented;</li>
 *   <li><b>overflow-checked double</b> {@code (a + b) * c}: the fused factory now <b>does</b> take the checked-vector
 *       path (its {@link VectorStrategy} is {@link VectorStrategy#CHECKED_VECTOR}) — the old {@code double}+overflow
 *       hard gate is gone because the fused class is defined in this caller module and reaches the backing array via
 *       the public {@code VectorUnsafe} forwarder, so {@code NumericUtils} links on the vector path — and it computes
 *       correctly, matching the unfused chain.</li>
 * </ol>
 */
public class EvalMapperFusionWiringTests extends ESTestCase {

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private MockBigArrays bigArrays;
    private DriverContext driverContext;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService);
        blockFactory = BlockFactory.builder(bigArrays).build();
        driverContext = new DriverContext(bigArrays, blockFactory, null);
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
    }

    @After
    public void tearDownFusion() {
        FusionPlanner.compilerForTests = null;
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        FusionSettings.setEnabledForTests(true);
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

    // (a) A depth-2 all-fusable long tree fuses, and its output matches the unfused chain over random shapes.
    public void testFusedEligibleAddMulLongMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        // (a + b) * c
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat("(a + b) * c over long columns must fuse", factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        // Long arithmetic is overflow-checked and non-double => the safe checked-vector fast path is selected.
        assertThat(((FusedExpressionEvaluatorFactory) factory).vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));

        assertLongFusedMatchesUnfused(factory);
    }

    // (a, continued) Same wiring with a non-commutative (a - b) * c so an operand-order bug in the input mapping shows.
    public void testFusedNonCommutativeLongMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);

        Expression expr = new Mul(Source.EMPTY, new Sub(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));

        assertLongFusedMatchesUnfused(factory);
    }

    // (e) A non-overflow double tree Abs(Abs(x)) selects the plain vector fast path (returns a Vector) and matches.
    public void testNonOverflowDoubleUsesPlainVectorFastPath() {
        FieldAttribute x = field("x", DataType.DOUBLE);
        Layout layout = layout(x);

        Expression expr = new Abs(Source.EMPTY, new Abs(Source.EMPTY, x));
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(((FusedExpressionEvaluatorFactory) factory).vectorStrategy(), is(VectorStrategy.PLAIN_VECTOR));

        ExpressionEvaluator.Factory unfused = ((FusedExpressionEvaluatorFactory) factory).unfusedFactory();
        int positionCount = 257;
        double[] xs = new double[positionCount];
        for (int p = 0; p < positionCount; p++) {
            xs[p] = randomDoubleBetween(-1_000_000, 1_000_000, true);
        }
        ExpressionEvaluator fused = factory.get(driverContext);
        ExpressionEvaluator reference = unfused.get(driverContext);
        Page page = null;
        DoubleBlock fusedResult = null;
        DoubleBlock referenceResult = null;
        try {
            page = new Page(blockFactory.newDoubleArrayVector(xs, positionCount).asBlock());
            fusedResult = (DoubleBlock) fused.eval(page);
            referenceResult = (DoubleBlock) reference.eval(page);
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p, fusedResult.isNull(p), is(referenceResult.isNull(p)));
                if (referenceResult.isNull(p) == false) {
                    double expected = referenceResult.getDouble(referenceResult.getFirstValueIndex(p));
                    assertThat("p=" + p, fusedResult.getDouble(fusedResult.getFirstValueIndex(p)), is(expected));
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // (b) A literal leaf is now fused (iter 24): the constant is embedded as a bytecode constant, so a depth-2 tree
    // containing it stitches just like an all-column tree and still evaluates correctly.
    public void testLiteralLeafFuses() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, c);

        // (a + 5) * c : the Add's constant operand embeds into the fused body; the whole depth-2 tree fuses.
        Expression expr = new Mul(
            Source.EMPTY,
            new Add(Source.EMPTY, a, new Literal(Source.EMPTY, 5L, DataType.LONG), EsqlTestUtils.TEST_CFG),
            c
        );
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat("a depth-2 tree with a literal leaf must fuse", factory, instanceOf(FusedExpressionEvaluatorFactory.class));

        // And it still evaluates correctly.
        int positionCount = 64;
        long[] av = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
        Page page = null;
        LongBlock result = null;
        try {
            page = new Page(
                blockFactory.newLongArrayVector(av, positionCount).asBlock(),
                blockFactory.newLongArrayVector(cv, positionCount).asBlock()
            );
            result = (LongBlock) evaluator.eval(page);
            for (int p = 0; p < positionCount; p++) {
                long expected = (av[p] + 5L) * cv[p];
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), is(expected));
            }
        } finally {
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // (c) A forced stitch failure must fall back to the unfused chain AND bump the per-shape failure counter.
    public void testForcedStitchFailureFallsBackAndCountsFailure() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        // Inject a compiler whose very first stitch (the block path) fails to link, exactly as the Stitcher reports a
        // verification/linkage failure. This drives the criterion-#5 fallback without needing to craft bad bytecode.
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
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
        };

        long before = FusionTelemetry.totalFailures();
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(
            "a failed stitch must fall back to the unfused factory",
            factory,
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );
        assertThat("the failure must be counted", FusionTelemetry.totalFailures(), is(before + 1));

        // The query still completes correctly via the unfused chain.
        int positionCount = 128;
        long[] av = new long[positionCount];
        long[] bv = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            bv[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
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
                long expected = (av[p] + bv[p]) * cv[p];
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), is(expected));
            }
        } finally {
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // (f) A vector-fast-path stitch failure (e.g. the plain-vector inlining budget refusing a deep tree) must DEGRADE
    // to block-only fusion — the already-stitched block path is correct — instead of dropping all fusion back to the
    // unfused chain. Inject a compiler that stitches the block path for real but fails BOTH vector paths.
    public void testVectorPathFailureDegradesToBlockOnly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        Stitcher real = new Stitcher(new org.elasticsearch.compute.operator.fusion.TemplateRegistry());
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileBlockLoop(caller, tree);
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected vector-path failure (e.g. over inlining budget)", null);
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected vector-path failure (e.g. over inlining budget)", null);
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileLogicalBlockLoop(caller, tree);
            }
        };

        long degradedBefore = FusionTelemetry.vectorDegraded();
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(
            "a vector-path failure must still fuse (block-only), not fall back to unfused",
            factory,
            instanceOf(FusedExpressionEvaluatorFactory.class)
        );
        assertThat(
            "degraded fusion runs the block path only (no vector fast path)",
            ((FusedExpressionEvaluatorFactory) factory).vectorStrategy(),
            is(VectorStrategy.NONE)
        );
        assertThat("the degrade must be counted", FusionTelemetry.vectorDegraded(), is(degradedBefore + 1));

        // And it still computes correctly via the block path.
        int positionCount = 128;
        long[] av = new long[positionCount];
        long[] bv = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            bv[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
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
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // (d) The overflow-checked double tree now routes through the checked-vector path (the old hard gate is gone), and
    // still matches the unfused chain. NumericUtils.asFiniteNumber links because the fused class is defined in this
    // caller module and reads the backing array through the public VectorUnsafe forwarder.
    public void testOverflowCheckedDoubleUsesCheckedVectorPath() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);

        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        // The double+overflow tree now takes the checked-vector fast path (defined in this caller module).
        assertThat(
            "double + overflow now uses the checked-vector path",
            ((FusedExpressionEvaluatorFactory) factory).vectorStrategy(),
            is(VectorStrategy.CHECKED_VECTOR)
        );

        ExpressionEvaluator.Factory unfused = ((FusedExpressionEvaluatorFactory) factory).unfusedFactory();
        int positionCount = 300;
        double[] av = new double[positionCount];
        double[] bv = new double[positionCount];
        double[] cv = new double[positionCount];
        for (int p = 0; p < positionCount; p++) {
            // Bounded and finite so (a + b) * c stays finite and Add.processDoubles never throws.
            av[p] = randomDoubleBetween(-1_000, 1_000, true);
            bv[p] = randomDoubleBetween(-1_000, 1_000, true);
            cv[p] = randomDoubleBetween(-1_000, 1_000, true);
        }
        ExpressionEvaluator fused = factory.get(driverContext);
        ExpressionEvaluator reference = unfused.get(driverContext);
        Page page = null;
        DoubleBlock fusedResult = null;
        DoubleBlock referenceResult = null;
        try {
            page = new Page(
                blockFactory.newDoubleArrayVector(av, positionCount).asBlock(),
                blockFactory.newDoubleArrayVector(bv, positionCount).asBlock(),
                blockFactory.newDoubleArrayVector(cv, positionCount).asBlock()
            );
            fusedResult = (DoubleBlock) fused.eval(page);
            referenceResult = (DoubleBlock) reference.eval(page);
            for (int p = 0; p < positionCount; p++) {
                assertThat("p=" + p, fusedResult.isNull(p), is(referenceResult.isNull(p)));
                if (referenceResult.isNull(p) == false) {
                    double expected = referenceResult.getDouble(referenceResult.getFirstValueIndex(p));
                    assertThat("p=" + p, fusedResult.getDouble(fusedResult.getFirstValueIndex(p)), is(expected));
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // (CRITICAL #1) Fusion must be additive w.r.t. the FoldContext: a foldable subexpression must fold exactly once
    // whether fusion is on or off. Div takes the constant-RHS fast path, folding its right operand while building its
    // evaluator; making that operand a compound foldable (1 + 3) routes the fold through the FoldContext-backed breaker
    // (a scratch-block allocation, i.e. a trackAllocation call). The freed allocation nets to zero bytes, so we count
    // trackAllocation *calls*: if fusion probed by building the subtree and a fall-through then rebuilt it, that fold —
    // and its allocation — would happen twice.
    public void testFusionDoesNotDoubleFoldBudget() {
        int allocationsWithFusion = foldAllocationsWhileBuilding(true);
        int allocationsWithoutFusion = foldAllocationsWhileBuilding(false);
        assertThat(
            "a foldable subexpression must trigger the same number of fold allocations with fusion on vs off",
            allocationsWithFusion,
            is(allocationsWithoutFusion)
        );
        // Guards the equality above: prove the shape actually folds (allocates), so equal-but-zero can't pass vacuously.
        assertThat("the foldable subexpression must actually fold while building", allocationsWithoutFusion, greaterThan(0));
    }

    private int foldAllocationsWhileBuilding(boolean fusionEnabled) {
        FusionSettings.setEnabledForTests(fusionEnabled);
        try {
            FieldAttribute x = field("x", DataType.LONG);
            Layout layout = layout(x);
            // (1 + 3) is a foldable EvaluatorMapper; Div folds its constant RHS while building, allocating a scratch
            // block against the FoldContext (unlike a bare literal, which folds for free).
            Expression foldable = new Add(
                Source.EMPTY,
                new Literal(Source.EMPTY, 1L, DataType.LONG),
                new Literal(Source.EMPTY, 3L, DataType.LONG),
                EsqlTestUtils.TEST_CFG
            );
            Expression expr = new Div(Source.EMPTY, x, foldable);
            CountingFoldContext foldCtx = new CountingFoldContext(FoldContext.small().initialAllowedBytes());
            ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(foldCtx, expr, layout);
            // A literal-derived (non-Attribute) constant RHS makes the tree non-fusable regardless of the switch.
            assertThat(factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
            return foldCtx.allocationCalls;
        } finally {
            FusionSettings.setEnabledForTests(true);
        }
    }

    /** A {@link FoldContext} that counts positive-allocation {@code trackAllocation} calls (fold work), for CRITICAL #1. */
    private static final class CountingFoldContext extends FoldContext {
        private int allocationCalls;

        CountingFoldContext(long allowedBytes) {
            super(allowedBytes);
        }

        @Override
        public void trackAllocation(Source source, long bytes) {
            if (bytes > 0) {
                allocationCalls++;
            }
            super.trackAllocation(source, bytes);
        }
    }

    // (CRITICAL #2) The esql.fusion.enabled node kill switch: when off, a normally-fusable tree stays unfused and the
    // Stitcher is never invoked, yet still evaluates correctly.
    public void testFusionKillSwitchProducesUnfusedTree() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        // Fail the stitch if it is ever attempted, so an accidental fuse would be loud rather than silent.
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new AssertionError("Stitcher must not be invoked when esql.fusion.enabled=false");
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new AssertionError("Stitcher must not be invoked when esql.fusion.enabled=false");
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) {
                throw new AssertionError("Stitcher must not be invoked when esql.fusion.enabled=false");
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new AssertionError("Stitcher must not be invoked when esql.fusion.enabled=false");
            }
        };

        FusionSettings.setEnabledForTests(false);
        ExpressionEvaluator.Factory factory;
        try {
            factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        } finally {
            FusionSettings.setEnabledForTests(true);
        }
        assertThat("disabled fusion must return the unfused factory", factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        int positionCount = 96;
        long[] av = new long[positionCount];
        long[] bv = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            bv[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
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
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // The esql.fusion.enabled Setting itself: default true, parses an explicit false, and latches through initFromNodeSettings.
    public void testFusionEnabledSettingWiring() {
        assertThat(FusionSettings.FUSION_ENABLED_SETTING.get(Settings.EMPTY), is(true));
        assertThat(FusionSettings.FUSION_ENABLED_SETTING.get(Settings.builder().put("esql.fusion.enabled", false).build()), is(false));
        boolean original = FusionSettings.isEnabled();
        try {
            FusionSettings.initFromNodeSettings(Settings.builder().put("esql.fusion.enabled", false).build());
            assertThat(FusionSettings.isEnabled(), is(false));
            FusionSettings.initFromNodeSettings(Settings.EMPTY);
            assertThat(FusionSettings.isEnabled(), is(true));
        } finally {
            FusionSettings.setEnabledForTests(original);
        }
    }

    // (WARNING) A LinkageError escaping the fused handle at eval time must be caught, latch fusion off for the rest of
    // this evaluator's life (so later pages don't re-attempt the fused handle), record exactly one failure, and still
    // produce the correct result via the unfused chain on every page.
    public void testEvalTimeLinkageErrorLatchesAndFallsBack() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));

        long before = FusionTelemetry.totalFailures();
        FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = true;
        ExpressionEvaluator evaluator = factory.get(driverContext);
        try {
            // Two pages: the first hits the injected eval-time LinkageError and falls back; the second is served by the
            // latch straight from the unfused chain. Both must be correct.
            for (int iteration = 0; iteration < 2; iteration++) {
                int positionCount = 64 + iteration;
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
                        assertThat(
                            "iter=" + iteration + " p=" + p,
                            result.getLong(result.getFirstValueIndex(p)),
                            is((av[p] + bv[p]) * cv[p])
                        );
                    }
                } finally {
                    Releasables.closeExpectNoException(result);
                    if (page != null) {
                        page.releaseBlocks();
                    }
                }
            }
        } finally {
            FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests = false;
            Releasables.closeExpectNoException(evaluator);
        }
        // Latched: the failure is recorded once, not once per page.
        assertThat("eval-time linkage failure must be counted exactly once (latched)", FusionTelemetry.totalFailures(), is(before + 1));
    }

    // (WARNING) A vector-fast-path-eligible fused factory must route a non-*ArrayVector (but non-null) input — here a
    // ConstantLongVector — through the block path (no ClassCastException) and still match the unfused chain.
    public void testConstantVectorInputRoutesThroughBlockPath() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        // A vector strategy is compiled (checked-vector for long), so vector eligibility is actually consulted per page.
        assertThat(((FusedExpressionEvaluatorFactory) factory).vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));

        ExpressionEvaluator.Factory unfused = ((FusedExpressionEvaluatorFactory) factory).unfusedFactory();
        int positionCount = 128;
        long[] av = new long[positionCount];
        long[] cv = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            av[p] = randomLongBetween(-1000, 1000);
            cv[p] = randomLongBetween(-1000, 1000);
        }
        long constantB = randomLongBetween(-1000, 1000);

        ExpressionEvaluator fused = factory.get(driverContext);
        ExpressionEvaluator reference = unfused.get(driverContext);
        Page fusedPage = null;
        Page referencePage = null;
        LongBlock fusedResult = null;
        LongBlock referenceResult = null;
        try {
            // b is a dense ConstantLongVector: asVector() is non-null but not a LongArrayVector, so vectorEligible()
            // must reject the whole page and take the block path rather than throwing a ClassCastException.
            fusedPage = new Page(
                blockFactory.newLongArrayVector(av, positionCount).asBlock(),
                blockFactory.newConstantLongVector(constantB, positionCount).asBlock(),
                blockFactory.newLongArrayVector(cv, positionCount).asBlock()
            );
            referencePage = new Page(
                blockFactory.newLongArrayVector(av.clone(), positionCount).asBlock(),
                blockFactory.newConstantLongVector(constantB, positionCount).asBlock(),
                blockFactory.newLongArrayVector(cv.clone(), positionCount).asBlock()
            );
            fusedResult = (LongBlock) fused.eval(fusedPage);
            referenceResult = (LongBlock) reference.eval(referencePage);
            for (int p = 0; p < positionCount; p++) {
                long expected = referenceResult.getLong(referenceResult.getFirstValueIndex(p));
                assertThat("p=" + p, fusedResult.getLong(fusedResult.getFirstValueIndex(p)), is(expected));
                assertThat("p=" + p, expected, is((av[p] + constantB) * cv[p]));
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (fusedPage != null) {
                fusedPage.releaseBlocks();
            }
            if (referencePage != null) {
                referencePage.releaseBlocks();
            }
        }
    }

    /**
     * Evaluates a fused long factory and its unfused fallback over the same random pages (including empty, single,
     * small, and &ge; 1024-position shapes, and vector-ineligible pages carrying nulls) and asserts position-by-position
     * equality.
     */
    private void assertLongFusedMatchesUnfused(ExpressionEvaluator.Factory factory) {
        ExpressionEvaluator.Factory unfused = ((FusedExpressionEvaluatorFactory) factory).unfusedFactory();
        ExpressionEvaluator fused = factory.get(driverContext);
        ExpressionEvaluator reference = unfused.get(driverContext);
        try {
            for (int shape = 0; shape < 60; shape++) {
                int positionCount = positionCountFor(shape);
                boolean withNulls = shape % 3 == 0;
                Page page = null;
                LongBlock fusedResult = null;
                LongBlock referenceResult = null;
                try {
                    page = new Page(
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, withNulls),
                        randomLongBlock(positionCount, false)
                    );
                    fusedResult = (LongBlock) fused.eval(page);
                    referenceResult = (LongBlock) reference.eval(page);
                    assertThat("shape=" + shape, fusedResult.getPositionCount(), is(positionCount));
                    for (int p = 0; p < positionCount; p++) {
                        assertThat("shape=" + shape + " p=" + p, fusedResult.isNull(p), is(referenceResult.isNull(p)));
                        if (referenceResult.isNull(p) == false) {
                            long expected = referenceResult.getLong(referenceResult.getFirstValueIndex(p));
                            assertThat("shape=" + shape + " p=" + p, fusedResult.getLong(fusedResult.getFirstValueIndex(p)), is(expected));
                        }
                    }
                } finally {
                    Releasables.closeExpectNoException(fusedResult, referenceResult);
                    if (page != null) {
                        page.releaseBlocks();
                    }
                }
            }
        } finally {
            Releasables.closeExpectNoException(fused, reference);
        }
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

    private int positionCountFor(int shape) {
        return switch (shape) {
            case 0 -> 0;
            case 1 -> 1;
            default -> shape % 20 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 256);
        };
    }
}
