/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.FilterEvalOperator;
import org.elasticsearch.compute.operator.FilterOperator;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.test.CannedSourceOperator;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Direct fused-vs-unfused differential harness for the S3.2a WHERE-predicate + EVAL-projection mega-fusion. It drives
 * the {@link FilterEvalOperator} (fed by a stitched {@link FusedFilterEvalEvaluatorFactory} from
 * {@link FusionPlanner#maybeFuseFilterEval}) against the reference two-operator {@code FilterOperator → EvalOperator}
 * chain over the SAME page, and asserts the hard S3.2a contract:
 * <ul>
 *   <li>the surviving row SET, all projected VALUES and all NULL positions are byte-for-byte identical to the unfused
 *       chain (both the surviving original columns AND the appended projection column);</li>
 *   <li>the fused path's warnings are a SUBSET of the unfused chain's (short-circuit can only drop a warning);</li>
 *   <li>a row the predicate DROPS emits NO projection warning (the fused loop never evaluates the projection for it —
 *       matching the unfused {@code Eval}, which only ever sees survivors);</li>
 *   <li>the sweep genuinely FUSED (not the fallback), and the injected-stitch-failure FALLBACK still computes the exact
 *       same page.</li>
 * </ul>
 *
 * <p>Because the operators consume (and release) their input page, each differential run builds one source page and
 * {@link CannedSourceOperator#deepCopyOf deep-copies} it once per chain, so the fused and unfused chains read
 * independent-but-identical inputs. All the {@code (ElementKind, Structure, Profile)} column model, value generators,
 * warning draining and the ratified subset assertion are inherited from {@link FusionDifferentialTestCase}.
 */
public class FilterEvalFusionDifferentialTests extends FusionDifferentialTestCase {

    private static final int COLUMNS = 4; // a, b, c, d

    // ---------------------------------------------------------------------------------------------------------------
    // Predicate (WHERE) and projection (EVAL) shape palettes
    // ---------------------------------------------------------------------------------------------------------------

    /** WHERE-predicate shapes: bare/arithmetic comparisons and 3VL logical trees, all boolean-producing and fusable. */
    private enum Pred {
        BARE_GT,      // a > b (comparison kernel, depth 1)
        ARITH_GT,     // (a + b) > c (comparison over arithmetic)
        NESTED_CMP,   // (a + b) > (c - d)
        CONST_GT,     // (a + b) > 7 (predicate constant)
        AND,          // (a > b) AND (c > d) (logical)
        OR;           // (a > b) OR (c > d) (logical)

        Expression build(FieldAttribute a, FieldAttribute b, FieldAttribute c, FieldAttribute d, DataType type) {
            return switch (this) {
                case BARE_GT -> new GreaterThan(Source.EMPTY, a, b);
                case ARITH_GT -> new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
                case NESTED_CMP -> new GreaterThan(
                    Source.EMPTY,
                    new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG),
                    new Sub(Source.EMPTY, c, d, EsqlTestUtils.TEST_CFG)
                );
                case CONST_GT -> new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), literal(7, type));
                case AND -> new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new GreaterThan(Source.EMPTY, c, d));
                case OR -> new Or(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new GreaterThan(Source.EMPTY, c, d));
            };
        }
    }

    /** EVAL-projection shapes: arithmetic kernels (incl. a constant and a divide), all numeric-producing and fusable. */
    private enum Proj {
        ADD(false),        // a + b
        MULADD(false),     // (a * b) + c
        ADDMUL(false),     // (a + b) * c
        SUBCONST(false),   // (a - b) + 3 (projection constant)
        DIV(true);         // a / b (needs division-safe divisors unless the DIVZERO profile is in play)

        final boolean divides;

        Proj(boolean divides) {
            this.divides = divides;
        }

        Expression build(FieldAttribute a, FieldAttribute b, FieldAttribute c, FieldAttribute d, DataType type) {
            return switch (this) {
                case ADD -> new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);
                case MULADD -> new Add(Source.EMPTY, new Mul(Source.EMPTY, a, b), c, EsqlTestUtils.TEST_CFG);
                case ADDMUL -> new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
                case SUBCONST -> new Add(
                    Source.EMPTY,
                    new Sub(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG),
                    literal(3, type),
                    EsqlTestUtils.TEST_CFG
                );
                case DIV -> new Div(Source.EMPTY, a, b);
            };
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The randomized sweep across element kinds, predicate/projection shapes, profiles and page shapes
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The main sweep: for a spread of element kinds, predicate/projection combos, value profiles and page shapes
     * (empty / single-row / large), drive both chains over the same page and assert identical survivors + values +
     * nulls and a warning subset. Coverage bookkeeping proves the empty / single / large shapes and every profile were
     * genuinely exercised, and that at least one page dropped SOME (but not all) rows so the compaction is non-trivial.
     */
    public void testFilterEvalMatchesUnfusedChain() {
        Coverage cov = new Coverage();
        int fusedRuns = 0;
        int partialDropRuns = 0;
        int iterations = 400;
        for (int i = 0; i < iterations; i++) {
            ElementKind kind = randomFrom(ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE);
            DataType type = dataType(kind);
            Pred pred = randomFrom(Pred.values());
            Proj proj = randomFrom(Proj.values());
            Profile profile = randomFrom(Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW, Profile.DIVZERO);
            int positionCount = positionCountFor(i);

            String ctx = "kind=" + kind + " pred=" + pred + " proj=" + proj + " profile=" + profile + " n=" + positionCount;

            FieldAttribute a = field("a", type);
            FieldAttribute b = field("b", type);
            FieldAttribute c = field("c", type);
            FieldAttribute d = field("d", type);
            Layout layout = layout(a, b, c, d);
            Expression predicate = pred.build(a, b, c, d, type);
            Expression projection = proj.build(a, b, c, d, type);

            FilterEvalEvaluator.Factory fusedFactory = fuse(predicate, projection, layout);
            assertFilterEvalFuses(fusedFactory, ctx);
            fusedRuns++;

            Block[] columns = buildPage(kind, COLUMNS, positionCount, profile, proj.divides, new int[] { 0, 1, 2, 3 });
            RunResult result = runBothChains(fusedFactory, predicate, projection, layout, kind, columns, positionCount, ctx);

            recordShape(cov, positionCount);
            recordProfile(cov, profile);
            recordWarnings(cov, profile, result.unfusedWarnings);
            if (result.survivors > 0 && result.survivors < positionCount) {
                partialDropRuns++;
            }
        }

        assertThat("sweep must have actually fused every shape", fusedRuns, is(iterations));
        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW, Profile.DIVZERO);
        assertThat("at least one page must drop SOME but not ALL rows (non-trivial compaction)", partialDropRuns, greaterThan(0));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Deterministic: a DROPPED row emits NO projection warning (matches unfused Filter -> Eval)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every row's predicate is FALSE, and every row's projection WOULD overflow if evaluated. The unfused chain drops
     * all rows in the {@code FilterOperator} and the {@code EvalOperator} never runs, so no overflow warning is raised;
     * the fused loop likewise skips the projection for every (dropped) row. The fused path must therefore emit NO
     * overflow warning — proving dropped rows never trigger projection warnings. A sanity control confirms the same
     * projection over the same values DOES overflow when the rows survive.
     */
    public void testDroppedRowsEmitNoProjectionWarning() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);

        // Predicate a > b is FALSE at every row (a < b everywhere). Projection a * b overflows at every row.
        Expression predicate = new GreaterThan(Source.EMPTY, a, b);
        Expression projection = new Mul(Source.EMPTY, a, b);

        int positionCount = 4;
        long[] av = { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
        long[] bv = { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE }; // a == b => a > b false everywhere
        long[] cv = { 0, 0, 0, 0 };
        long[] dv = { 0, 0, 0, 0 };

        FilterEvalEvaluator.Factory fusedFactory = fuse(predicate, projection, layout);
        assertFilterEvalFuses(fusedFactory, "dropped-rows no-projection-warning");

        Block[] columns = {
            blockFactory.newLongArrayVector(av, positionCount).asBlock(),
            blockFactory.newLongArrayVector(bv, positionCount).asBlock(),
            blockFactory.newLongArrayVector(cv, positionCount).asBlock(),
            blockFactory.newLongArrayVector(dv, positionCount).asBlock() };
        RunResult result = runBothChains(
            fusedFactory,
            predicate,
            projection,
            layout,
            ElementKind.LONG,
            columns,
            positionCount,
            "all-dropped overflow projection"
        );

        assertThat("every row dropped => no page", result.survivors, is(0));
        assertThat(
            "the fused path must NOT emit an overflow warning for dropped rows; fused=" + result.fusedWarnings,
            containsWarning(result.fusedWarnings, "overflow"),
            is(false)
        );
        assertThat(
            "the unfused chain must NOT emit an overflow warning either (sanity); unfused=" + result.unfusedWarnings,
            containsWarning(result.unfusedWarnings, "overflow"),
            is(false)
        );

        // Sanity control: the SAME projection over the SAME values DOES overflow when the rows are NOT dropped.
        assertProjectionOverflowsWhenSurviving(a, b, layout, av, bv, positionCount);
    }

    /** Control for {@link #testDroppedRowsEmitNoProjectionWarning}: {@code a * b} over the same values overflows/warns. */
    private void assertProjectionOverflowsWhenSurviving(FieldAttribute a, FieldAttribute b, Layout layout, long[] av, long[] bv, int n) {
        Expression projection = new Mul(Source.EMPTY, a, b);
        ExpressionEvaluator.Factory projFactory = unfusedFactory(projection, layout);
        Block[] cols = { blockFactory.newLongArrayVector(av, n).asBlock(), blockFactory.newLongArrayVector(bv, n).asBlock() };
        Page page = new Page(n, cols);
        drainWarnings();
        try (ExpressionEvaluator eval = projFactory.get(driverContext); Block out = eval.eval(page)) {
            List<String> warnings = drainWarnings();
            assertThat("control: surviving a*b must overflow-warn; warnings=" + warnings, containsWarning(warnings, "overflow"), is(true));
        } finally {
            page.releaseBlocks();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fallback: an injected stitch failure must still compute the exact same page via the unfused pair
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * With a compiler that always throws {@link Stitcher.StitchingException} from {@code compileFilterEvalBlockLoop},
     * {@link FusionPlanner#maybeFuseFilterEval} still returns a usable (fallback) factory whose evaluator reproduces
     * {@code Filter → Eval} from the two unfused evaluators. The differential contract must still hold: identical
     * survivors + values + nulls to the real two-operator chain.
     */
    public void testStitchFailureFallsBackButStaysCorrect() {
        FusionPlanner.compilerForTests = new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected", null);
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected", null);
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected", null);
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected", null);
            }

            @Override
            public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
                throws Stitcher.StitchingException {
                throw new Stitcher.StitchingException("injected filter-eval stitch failure", null);
            }
        };
        try {
            FieldAttribute a = field("a", DataType.LONG);
            FieldAttribute b = field("b", DataType.LONG);
            FieldAttribute c = field("c", DataType.LONG);
            FieldAttribute d = field("d", DataType.LONG);
            Layout layout = layout(a, b, c, d);
            Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
            Expression projection = new Mul(Source.EMPTY, a, b);

            FilterEvalEvaluator.Factory factory = fuse(predicate, projection, layout);
            assertThat("injected stitch failure must NOT fuse", asFused(factory).fused(), is(false));

            for (int i = 0; i < 40; i++) {
                Profile profile = randomFrom(Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
                int positionCount = positionCountFor(i);
                Block[] columns = buildPage(ElementKind.LONG, COLUMNS, positionCount, profile, false, new int[] { 0, 1, 2 });
                runBothChains(
                    factory,
                    predicate,
                    projection,
                    layout,
                    ElementKind.LONG,
                    columns,
                    positionCount,
                    "fallback profile=" + profile + " n=" + positionCount
                );
            }
        } finally {
            FusionPlanner.compilerForTests = null;
        }
    }

    /**
     * Eval-time defensive fallback: a {@link LinkageError} escaping the fused handle on the first invocation flips the
     * evaluator to the unfused pair for the rest of its life, still computing the correct page.
     */
    public void testEvalTimeLinkageErrorFallsBackButStaysCorrect() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression projection = new Add(Source.EMPTY, new Mul(Source.EMPTY, a, b), d, EsqlTestUtils.TEST_CFG);

        FilterEvalEvaluator.Factory factory = fuse(predicate, projection, layout);
        assertThat("must have fused for the eval-time linkage test to be meaningful", asFused(factory).fused(), is(true));

        FusedFilterEvalEvaluatorFactory.injectEvalLinkageErrorForTests = true;
        try {
            for (int i = 0; i < 20; i++) {
                Profile profile = randomFrom(Profile.CLEAN, Profile.NULLS, Profile.OVERFLOW);
                int positionCount = positionCountFor(i);
                Block[] columns = buildPage(ElementKind.LONG, COLUMNS, positionCount, profile, false, new int[] { 0, 1, 2, 3 });
                runBothChains(
                    factory,
                    predicate,
                    projection,
                    layout,
                    ElementKind.LONG,
                    columns,
                    positionCount,
                    "eval-linkage-fallback profile=" + profile + " n=" + positionCount
                );
            }
        } finally {
            FusedFilterEvalEvaluatorFactory.injectEvalLinkageErrorForTests = false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Driving the two chains + comparison
    // ---------------------------------------------------------------------------------------------------------------

    /** The per-run bookkeeping the sweep needs after a differential comparison. */
    private record RunResult(int survivors, List<String> fusedWarnings, List<String> unfusedWarnings) {}

    /**
     * Deep-copies {@code columns} into two independent pages, drives the fused {@link FilterEvalOperator} over one and
     * the reference {@code FilterOperator → EvalOperator} chain over the other, asserts the two output pages are
     * identical (survivors + values + nulls, across every column incl. the appended projection) and that the fused
     * warnings are a subset of the unfused warnings, and returns the run's survivor count and warning lists.
     */
    private RunResult runBothChains(
        FilterEvalEvaluator.Factory fusedFactory,
        Expression predicate,
        Expression projection,
        Layout layout,
        ElementKind kind,
        Block[] columns,
        int positionCount,
        String ctx
    ) {
        Page source = new Page(positionCount, columns);
        if (positionCount == 0) {
            // An empty page never reaches process()/the evaluator: AbstractPageMappingOperator.getOutput short-circuits
            // and passes it straight through, in BOTH chains. There is nothing to fuse or compare, so exercise the
            // trivial pass-through without driving the operators (whose getOutput leaves prev set for empty pages,
            // making close() release the very page it just handed back). The shape is still recorded by the caller.
            source.releaseBlocks();
            return new RunResult(0, List.of(), List.of());
        }
        Page fusedIn = CannedSourceOperator.deepCopyOf(blockFactory, List.of(source)).get(0);
        Page unfusedIn = CannedSourceOperator.deepCopyOf(blockFactory, List.of(source)).get(0);
        source.releaseBlocks();

        ExpressionEvaluator.Factory predFactory = unfusedFactory(predicate, layout);
        ExpressionEvaluator.Factory projFactory = unfusedFactory(projection, layout);

        Page fusedOut = null;
        Page unfusedOut = null;
        try {
            drainWarnings();
            fusedOut = runFilterEval(fusedFactory, fusedIn);
            List<String> fusedWarnings = drainWarnings();
            fusedIn = null; // consumed + released by the operator

            unfusedOut = runFilterThenEval(predFactory, projFactory, unfusedIn);
            List<String> unfusedWarnings = drainWarnings();
            unfusedIn = null; // consumed + released by the operator(s)

            int survivors = fusedOut == null ? 0 : (positionCount == 0 ? 0 : fusedOut.getPositionCount());
            assertPagesEqual(kind, fusedOut, unfusedOut, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, unfusedWarnings, ctx);
            return new RunResult(survivors, fusedWarnings, unfusedWarnings);
        } finally {
            if (fusedOut != null) {
                fusedOut.releaseBlocks();
            }
            if (unfusedOut != null) {
                unfusedOut.releaseBlocks();
            }
            if (fusedIn != null) {
                fusedIn.releaseBlocks();
            }
            if (unfusedIn != null) {
                unfusedIn.releaseBlocks();
            }
        }
    }

    /** Runs {@code page} through a {@link FilterEvalOperator} built from the fused factory and returns its output page. */
    private Page runFilterEval(FilterEvalEvaluator.Factory factory, Page page) {
        try (FilterEvalOperator op = new FilterEvalOperator(driverContext, factory.get(driverContext))) {
            op.addInput(page);
            op.finish();
            return op.getOutput();
        }
    }

    /** Runs {@code page} through the reference {@code FilterOperator → EvalOperator} chain and returns its output page. */
    private Page runFilterThenEval(ExpressionEvaluator.Factory predFactory, ExpressionEvaluator.Factory projFactory, Page page) {
        Page filtered;
        try (FilterOperator filter = new FilterOperator(predFactory.get(driverContext))) {
            filter.addInput(page);
            filter.finish();
            filtered = filter.getOutput();
        }
        if (filtered == null) {
            return null;
        }
        try (EvalOperator eval = new EvalOperator(driverContext, projFactory.get(driverContext))) {
            eval.addInput(filtered);
            eval.finish();
            return eval.getOutput();
        }
    }

    /**
     * Asserts the fused and unfused output pages are identical: both null (drop-all) or both present with the same
     * position count, the same block count, and every block byte-for-byte equal (values + nulls) position-by-position.
     */
    private static void assertPagesEqual(ElementKind kind, Page fused, Page unfused, int inputPositions, String ctx) {
        if (fused == null || unfused == null) {
            assertThat(ctx + " both chains must drop all rows (null page)", fused == null && unfused == null, is(true));
            return;
        }
        int positions = fused.getPositionCount();
        assertThat(ctx + " positionCount parity", positions, is(unfused.getPositionCount()));
        assertThat(ctx + " blockCount parity", fused.getBlockCount(), is(unfused.getBlockCount()));
        for (int b = 0; b < fused.getBlockCount(); b++) {
            // Every column (the surviving original columns AND the appended projection column) shares the numeric kind.
            assertResultsEqual(kind, fused.getBlock(b), unfused.getBlock(b), positions, ctx + " block" + b);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** Builds the fused filter-eval factory through the real plan-time path (with a raw unfused fallback factory). */
    private FilterEvalEvaluator.Factory fuse(Expression predicate, Expression projection, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);
        FilterEvalEvaluator.Factory factory = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat("maybeFuseFilterEval must produce a factory for a fusable pair", factory, is(org.hamcrest.Matchers.notNullValue()));
        return factory;
    }

    private static FusedFilterEvalEvaluatorFactory asFused(FilterEvalEvaluator.Factory factory) {
        assertThat(
            "factory must be a FusedFilterEvalEvaluatorFactory",
            factory,
            org.hamcrest.Matchers.instanceOf(FusedFilterEvalEvaluatorFactory.class)
        );
        return (FusedFilterEvalEvaluatorFactory) factory;
    }

    /** Asserts the factory actually stitched a fused handle (so the differential comparison is not vacuously a fallback). */
    private static void assertFilterEvalFuses(FilterEvalEvaluator.Factory factory, String ctx) {
        assertThat(ctx + " must actually fuse (not fall back)", asFused(factory).fused(), is(true));
    }

    private static DataType dataType(ElementKind kind) {
        return switch (kind) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> throw new AssertionError("boolean columns are not inputs here");
            case BYTES_REF -> throw new AssertionError("BytesRef columns are not inputs here");
        };
    }

    private static Literal literal(int value, DataType type) {
        return switch (type) {
            case LONG -> new Literal(Source.EMPTY, (long) value, DataType.LONG);
            case INTEGER -> new Literal(Source.EMPTY, value, DataType.INTEGER);
            case DOUBLE -> new Literal(Source.EMPTY, (double) value, DataType.DOUBLE);
            default -> throw new AssertionError("unsupported literal type " + type);
        };
    }
}
