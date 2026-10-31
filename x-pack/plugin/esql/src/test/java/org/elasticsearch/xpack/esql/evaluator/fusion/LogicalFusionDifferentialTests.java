/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.LongBigArrayVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Deterministic regressions for <b>logical</b> fusion (3VL {@code AND}/{@code OR}). The randomized logical sweeps that
 * used to live here (flat / left-nested / right-nested AND/OR over long/int/double) are now subsumed by the unified,
 * spec-driven sweep in {@link FusionSpecDifferentialTests}, which sweeps a logical root over column-column comparisons
 * and nested logicals ({@code LOGICAL} over {@code COMPARISON}) — the currently-fusable cross-category shape. Arithmetic
 * beneath a comparison beneath a logical is <b>not</b> a fusable logical operand under the S3.1 scope
 * ({@code FusionPlanner.buildComparisonOperand} requires column-column comparisons); that boundary is covered by
 * {@link FusionSpecDifferentialTests#testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}. Shared machinery lives in
 * {@link FusionDifferentialTestCase}. What remains here are the three hand-built regressions a random sweep cannot
 * reliably pin:
 * <ul>
 *   <li>{@link #testThreeValuedLogicDeterministic()} — every {@code (left, right)} combination of {true, false, null}
 *       checked against a hand-coded 3VL oracle AND the unfused chain;</li>
 *   <li>{@link #testBothOperandsEmitMultiValueWarning()} — both operands are evaluated for warnings even when the
 *       left decides the value, so the fused path emits the right operand's multi-value warning too (exact parity
 *       with the unfused chain; values identical);</li>
 *   <li>{@link #testDistinctSourceWarningsAttributedPerComparison()} — per-comparison warning-source attribution.</li>
 *   <li>Dense logical vector path (R2a) — {@link VectorStrategy#LOGICAL_VECTOR} over {@code *ArrayVector},
     *       {@code ConstantLongVector}, and {@code LongBigArrayVector} inputs, AND and OR, long/int/double, matching the
     *       unfused chain value-by-value. Null/multi-value pages stay on the 3VL block path above.
     *       {@link #testDenseLogicalVectorPicksInterfaceVsBlock()} pins INTERFACE vs BLOCK (INTERFACE-only after B2).</li>
 *   <li>GTE/LTE under AND/OR (F2a) — {@link #testGteLteUnderAndOrMatchesUnfused} on the dense 2VL vector path and the
 *       3VL block path (nulls / multi-values), plus {@link #testGteLteEqualOperandsDistinguishFromGtLt} so a silent
 *       GT↔GTE or LT↔LTE swap in fused emission cannot hide.</li>
 *   <li>F2b {@code Not} — Kleene 3VL negation of a fusable boolean subtree. Built by hand
 *       ({@code BooleanSimplification} does not rewrite {@code NOT(a>5)} to {@code a<=5}). {@code NOT(bool_col)}
 *       refuses.</li>
 * </ul>
 */
public class LogicalFusionDifferentialTests extends FusionDifferentialTestCase {

    // ---------------------------------------------------------------------------------------------------------------
    // Deterministic 3VL truth-table coverage (fused == unfused == oracle at every combination)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Constructs positions exercising every {@code (left, right)} combination of {true, false, null} for both AND and
     * OR, and asserts the fused result equals both the unfused {@code BooleanLogicExpressionEvaluator} chain and a
     * hand-coded 3VL oracle — covering {@code false AND null = false}, {@code true OR null = true},
     * {@code null AND true = null}, {@code null OR false = null}, {@code true AND true = true}, etc.
     */
    public void testThreeValuedLogicDeterministic() {
        assertThreeValuedLogic(Connective.AND);
        assertThreeValuedLogic(Connective.OR);
    }

    private void assertThreeValuedLogic(Connective connective) {
        // Expression: (a > b) <op> (c > d). left = a>b, right = c>d. Force each operand to true/false/null per position.
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        GreaterThan left = new GreaterThan(Source.EMPTY, a, b);
        GreaterThan right = new GreaterThan(Source.EMPTY, c, d);
        Expression expr = connective == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);

        Boolean[] states = { Boolean.TRUE, Boolean.FALSE, null };
        int positions = states.length * states.length;
        Long[] av = new Long[positions];
        Long[] bv = new Long[positions];
        Long[] cv = new Long[positions];
        Long[] dv = new Long[positions];
        Boolean[] expected = new Boolean[positions];
        int p = 0;
        for (Boolean l : states) {
            for (Boolean r : states) {
                fillComparison(av, bv, p, l); // a>b == l
                fillComparison(cv, dv, p, r); // c>d == r
                expected[p] = connective == Connective.AND ? and3(l, r) : or3(l, r);
                p++;
            }
        }

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, connective + " tree");

        Block[] inputs = { longColumn(av), longColumn(bv), longColumn(cv), longColumn(dv) };
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            fusedResult = fused.eval(page);
            referenceResult = reference.eval(page);
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            for (int q = 0; q < positions; q++) {
                String ctx = connective + " @" + q + " expected=" + expected[q];
                if (expected[q] == null) {
                    assertThat(ctx + " fused null", fb.isNull(q), is(true));
                } else {
                    assertThat(ctx + " fused non-null", fb.isNull(q), is(false));
                    assertThat(ctx + " fused value", fb.getBoolean(fb.getFirstValueIndex(q)), equalTo(expected[q]));
                }
                // Differential parity with the unfused 3VL chain.
                assertThat(ctx + " null parity", fb.isNull(q), is(rb.isNull(q)));
                if (rb.isNull(q) == false) {
                    assertThat(
                        ctx + " value parity",
                        fb.getBoolean(fb.getFirstValueIndex(q)),
                        equalTo(rb.getBoolean(rb.getFirstValueIndex(q)))
                    );
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Short-circuit evidence: a decisive left operand suppresses the right operand's multi-value warning
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The right operand ({@code c < d}) has a multi-value input at the only position, so evaluating it registers the
     * single-value multi-value warning. The left operand decides the whole expression (AND: left false; OR: left true).
     * Post-B3a the fused logical path evaluates BOTH operands for their warnings (only the 3VL VALUE short-circuits),
     * so the fused path emits the right operand's warning too — exact parity with the unfused (eager) chain, matching
     * the arithmetic block path. Values remain identical.
     */
    public void testBothOperandsEmitMultiValueWarning() {
        assertBothOperandsWarn(Connective.AND);
        assertBothOperandsWarn(Connective.OR);
    }

    private void assertBothOperandsWarn(Connective connective) {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        GreaterThan left = new GreaterThan(Source.EMPTY, a, b);
        LessThan right = new LessThan(Source.EMPTY, c, d);
        Expression expr = connective == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, connective + " tree");

        // AND: make left false (a=0 > b=1 is false) so AND short-circuits to false.
        // OR: make left true (a=1 > b=0 is true) so OR short-circuits to true.
        long aVal = connective == Connective.AND ? 0L : 1L;
        long bVal = connective == Connective.AND ? 1L : 0L;
        boolean expected = connective != Connective.AND; // AND -> false, OR -> true
        Block[] inputs = {
            longColumn(new Long[] { aVal }),
            longColumn(new Long[] { bVal }),
            multiValueLong(new long[] { 1L, 2L }), // c: multi-value -> c < d would warn if evaluated
            longColumn(new Long[] { 5L }) };

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(1, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // Values identical, and both non-null (the decisive short-circuit result is never null here).
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            assertThat(connective + " fused non-null", fb.isNull(0), is(false));
            assertThat(connective + " fused value", fb.getBoolean(fb.getFirstValueIndex(0)), is(expected));
            assertThat(connective + " value parity", fb.isNull(0), is(rb.isNull(0)));
            assertThat(connective + " value parity", fb.getBoolean(fb.getFirstValueIndex(0)), is(rb.getBoolean(rb.getFirstValueIndex(0))));

            // B3a parity: both operands are evaluated for their warnings, so the fused path ALSO emits the right
            // operand's multi-value warning — exact parity with the unfused (eager) chain (not merely a subset),
            // matching the arithmetic block path. The 3VL VALUE above is still decided by the (unchanged) truth table.
            assertThat(
                connective + " unfused (eager) must emit the right operand's multi-value warning",
                referenceWarnings,
                hasItem(containsString(MULTI_VALUE_MSG))
            );
            assertThat(
                connective + " fused must ALSO emit the right operand's multi-value warning (both operands evaluated)",
                containsWarning(fusedWarnings, MULTI_VALUE_MSG),
                is(true)
            );
            assertWarningsSubset(fusedWarnings, referenceWarnings, connective + " parity");
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Warning-source fidelity (iter-20 P1): each sub-comparison warning is attributed to ITS OWN source, not the root
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * With DISTINCT NON-EMPTY {@link Source}s per comparison and root, both operands are reached (a multi-value input
     * makes the left comparison {@code null}, which does not short-circuit either AND or OR), so each sub-comparison
     * registers a multi-value warning attributed to <b>its own</b> source. The fused warning HEADERS must therefore be
     * a subset of the unfused chain's. Pre-fix, the fused path attributed every warning to the ROOT ({@code And}/{@code
     * Or}) source, emitting a {@code [ROOT_LOGICAL]} header the unfused set never contains — so this test FAILS pre-fix
     * and PASSES once per-source {@code Warnings[]} threading lands.
     */
    public void testDistinctSourceWarningsAttributedPerComparison() {
        assertDistinctSourceWarnings(Connective.AND);
        assertDistinctSourceWarnings(Connective.OR);
    }

    private void assertDistinctSourceWarnings(Connective connective) {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        // Distinct non-empty sources per comparison and root -> every warning header is unambiguous.
        GreaterThan left = new GreaterThan(new Source(1, 3, "aaa>bbb"), a, b);
        LessThan right = new LessThan(new Source(2, 7, "ccc<ddd"), c, d);
        Source rootSource = new Source(7, 14, "ROOT_LOGICAL");
        Expression expr = connective == Connective.AND ? new And(rootSource, left, right) : new Or(rootSource, left, right);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, connective + " tree");

        // Single position: a and c are multi-value, so both comparisons warn and return null. left null does NOT
        // short-circuit AND (short-circuits only on left=false) or OR (only on left=true), so BOTH operands are
        // reached in fused and unfused alike -> the two multi-value warnings are reached in both paths.
        Block[] inputs = {
            multiValueLong(new long[] { 1L, 2L }), // a -> left (a>b) multi-value warning, null
            longColumn(new Long[] { 0L }),          // b
            multiValueLong(new long[] { 3L, 4L }),  // c -> right (c<d) multi-value warning, null
            longColumn(new Long[] { 9L }) };        // d

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(1, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // Both operands null -> result null in both paths (values/nulls unchanged by the source-fidelity fix).
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            assertThat(connective + " fused null", fb.isNull(0), is(true));
            assertThat(connective + " null parity", fb.isNull(0), is(rb.isNull(0)));

            // The critical invariant: fused headers subset of unfused. Pre-fix the fused path attributed both warnings
            // to the ROOT source ("[ROOT_LOGICAL]"), a header the unfused set never contains -> subset fails.
            assertWarningsSubset(fusedWarnings, referenceWarnings, connective + " distinct-source");
            // Attribution is correct (not merely empty): each comparison's own source text appears fused.
            assertThat(connective + " left source attribution", fusedWarnings, hasItem(containsString("[aaa>bbb]")));
            assertThat(connective + " right source attribution", fusedWarnings, hasItem(containsString("[ccc<ddd]")));
            // And the pre-fix root-source misattribution is gone.
            assertThat(connective + " no root-source misattribution", containsWarning(fusedWarnings, "[ROOT_LOGICAL]"), is(false));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F2a: GTE/LTE under AND/OR (already fusable; deterministic coverage the GT/LT-only tests do not pin)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code (a >= b) AND/OR (c <= d)} fuses for int/long/double. Values, nulls and warnings match the unfused chain
     * on a dense 2VL vector page and on 3VL block pages (nulls and multi-values). The compiled strategy is
     * {@link VectorStrategy#LOGICAL_VECTOR}.
     */
    public void testGteLteUnderAndOrMatchesUnfused() {
        for (ElementKind kind : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            DataType type = dataTypeOf(kind);
            for (Connective connective : Connective.values()) {
                FieldAttribute a = field("a", type);
                FieldAttribute b = field("b", type);
                FieldAttribute c = field("c", type);
                FieldAttribute d = field("d", type);
                Layout layout = layout(a, b, c, d);
                Expression left = new GreaterThanOrEqual(Source.EMPTY, a, b);
                Expression right = new LessThanOrEqual(Source.EMPTY, c, d);
                Expression expr = connective == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);
                String ctx = connective + " " + kind + " a>=b " + connective + " c<=d";
                ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
                ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
                FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, ctx);
                assertThat(
                    "GTE/LTE under a logical must compile VectorStrategy.LOGICAL_VECTOR",
                    fusedFactory.vectorStrategy(),
                    is(VectorStrategy.LOGICAL_VECTOR)
                );
                assertDoesNotFuse(unfused, ctx + " kill switch OFF");

                for (int n : new int[] { 0, 1, 8, 64 }) {
                    Block[] dense = { denseColumn(kind, n, 0), denseColumn(kind, n, 1), denseColumn(kind, n, 2), denseColumn(kind, n, 3) };
                    runDifferential(fused, unfused, ElementKind.BOOLEAN, dense, n, ctx + " dense n=" + n);
                }
                for (int n : new int[] { 8, 64 }) {
                    Block[] nulls = buildPage(kind, 4, n, Profile.NULLS, false, new int[] { 0, 1, 2, 3 });
                    runDifferential(fused, unfused, ElementKind.BOOLEAN, nulls, n, ctx + " nulls n=" + n);
                    Block[] mvs = buildPage(kind, 4, n, Profile.MULTIVALUE, false, new int[] { 0, 1, 2, 3 });
                    runDifferential(fused, unfused, ElementKind.BOOLEAN, mvs, n, ctx + " mv n=" + n);
                }
            }
        }

        // Pin INTERFACE vs BLOCK on one LONG AND tree so an always-block regression is caught even when values match.
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Expression expr = new And(Source.EMPTY, new GreaterThanOrEqual(Source.EMPTY, a, b), new LessThanOrEqual(Source.EMPTY, c, d));
        ExpressionEvaluator eval = assertFuses(fusedFactory(expr, layout(a, b, c, d)), "GTE/LTE AND path pin").get(driverContext);
        try {
            int n = 8;
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] {
                    longArrayVectorColumn(n, 0),
                    longArrayVectorColumn(n, 1),
                    longArrayVectorColumn(n, 2),
                    longArrayVectorColumn(n, 3) },
                "dense GTE/LTE AND must take the 2VL INTERFACE path"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.BLOCK,
                new Block[] {
                    longColumn(new Long[] { 1L, null, 3L, 4L }),
                    longColumn(new Long[] { 0L, 1L, 2L, 3L }),
                    longColumn(new Long[] { 0L, 1L, 2L, 3L }),
                    longColumn(new Long[] { 1L, 2L, 3L, 4L }) },
                "null-carrying GTE/LTE AND must take the 3VL BLOCK path"
            );
        } finally {
            Releasables.closeExpectNoException(eval);
        }
    }

    /**
     * Equal operands distinguish {@code >=} from {@code >} and {@code <=} from {@code <}. {@code (a >= a) AND (b <= b)}
     * is true at every dense position; {@code (a > a) AND (b < b)} is false. A silent GT↔GTE or LT↔LTE swap in fused
     * emission would make the inclusive tree agree with the exclusive one (or disagree with unfused).
     */
    public void testGteLteEqualOperandsDistinguishFromGtLt() {
        for (ElementKind kind : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            DataType type = dataTypeOf(kind);
            FieldAttribute a = field("a", type);
            FieldAttribute b = field("b", type);
            Layout layout = layout(a, b);
            Expression inclusive = new And(
                Source.EMPTY,
                new GreaterThanOrEqual(Source.EMPTY, a, a),
                new LessThanOrEqual(Source.EMPTY, b, b)
            );
            Expression exclusive = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, a), new LessThan(Source.EMPTY, b, b));
            assertEqualOperandTree(inclusive, exclusive, layout, kind, "a>=a AND b<=b vs a>a AND b<b " + kind);
        }
    }

    private void assertEqualOperandTree(Expression inclusive, Expression exclusive, Layout layout, ElementKind kind, String ctx) {
        ExpressionEvaluator.Factory fusedInc = fusedFactory(inclusive, layout);
        ExpressionEvaluator.Factory unfusedInc = unfusedFactory(inclusive, layout);
        ExpressionEvaluator.Factory fusedExc = fusedFactory(exclusive, layout);
        ExpressionEvaluator.Factory unfusedExc = unfusedFactory(exclusive, layout);
        assertFuses(fusedInc, ctx + " inclusive");
        assertFuses(fusedExc, ctx + " exclusive");
        assertDoesNotFuse(unfusedInc, ctx + " inclusive kill switch OFF");
        int n = 8;
        runDifferential(
            fusedInc,
            unfusedInc,
            ElementKind.BOOLEAN,
            new Block[] { denseColumn(kind, n, 1), denseColumn(kind, n, 2) },
            n,
            ctx + " inclusive dense"
        );
        runDifferential(
            fusedExc,
            unfusedExc,
            ElementKind.BOOLEAN,
            new Block[] { denseColumn(kind, n, 1), denseColumn(kind, n, 2) },
            n,
            ctx + " exclusive dense"
        );
        assertFusedBooleans(fusedInc, new Block[] { denseColumn(kind, n, 1), denseColumn(kind, n, 2) }, n, true, ctx + " inclusive");
        assertFusedBooleans(fusedExc, new Block[] { denseColumn(kind, n, 1), denseColumn(kind, n, 2) }, n, false, ctx + " exclusive");
        // 3VL: a null on either column viral-nulls that comparison; fused still matches unfused.
        Block[] nulls = buildPage(kind, 2, n, Profile.NULLS, false, new int[] { 0, 1 });
        runDifferential(fusedInc, unfusedInc, ElementKind.BOOLEAN, nulls, n, ctx + " inclusive nulls");
        Block[] mvs = buildPage(kind, 2, n, Profile.MULTIVALUE, false, new int[] { 0, 1 });
        runDifferential(fusedInc, unfusedInc, ElementKind.BOOLEAN, mvs, n, ctx + " inclusive mv");
    }

    private void assertFusedBooleans(ExpressionEvaluator.Factory fused, Block[] inputs, int n, boolean expected, String ctx) {
        ExpressionEvaluator eval = fused.get(driverContext);
        Page page = new Page(n, inputs);
        Block out = null;
        try {
            out = eval.eval(page);
            BooleanBlock bb = (BooleanBlock) out;
            for (int p = 0; p < n; p++) {
                assertThat(ctx + " null@" + p, bb.isNull(p), is(false));
                assertThat(ctx + " value@" + p, bb.getBoolean(bb.getFirstValueIndex(p)), is(expected));
            }
        } finally {
            Releasables.closeExpectNoException(out, eval);
            page.releaseBlocks();
        }
    }

    private static DataType dataTypeOf(ElementKind kind) {
        return switch (kind) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN, BYTES_REF -> throw new AssertionError("GTE/LTE logical coverage is numeric: " + kind);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F2b: Kleene 3VL NOT of a fusable boolean subtree (comparison / AND / OR / nested Not)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code NOT(a > 5)} and {@code NOT(a >= 5)} fuse for int/long/double and match the unfused {@code NotEvaluator}
     * chain on a dense 2VL page and on 3VL null/multi-value pages.
     */
    public void testNotOfComparisonMatchesUnfused() {
        for (ElementKind kind : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            DataType type = dataTypeOf(kind);
            FieldAttribute a = field("a", type);
            Layout layout = layout(a);
            Literal five = literalOf(kind, 5);
            assertNotTree(new Not(Source.EMPTY, new GreaterThan(Source.EMPTY, a, five)), layout, kind, 1, "NOT(a>5) " + kind);
            assertNotTree(new Not(Source.EMPTY, new GreaterThanOrEqual(Source.EMPTY, a, five)), layout, kind, 1, "NOT(a>=5) " + kind);
        }
    }

    /**
     * {@code NOT(a > b AND c < d)} and {@code NOT(a > b OR c < d)} fuse and match unfused on dense + 3VL pages.
     */
    public void testNotOfAndOrMatchesUnfused() {
        for (ElementKind kind : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            DataType type = dataTypeOf(kind);
            FieldAttribute a = field("a", type);
            FieldAttribute b = field("b", type);
            FieldAttribute c = field("c", type);
            FieldAttribute d = field("d", type);
            Layout layout = layout(a, b, c, d);
            Expression and = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
            Expression or = new Or(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
            assertNotTree(new Not(Source.EMPTY, and), layout, kind, 4, "NOT(a>b AND c<d) " + kind);
            assertNotTree(new Not(Source.EMPTY, or), layout, kind, 4, "NOT(a>b OR c<d) " + kind);
        }
    }

    /**
     * {@code a > 5 AND NOT(b < 10)} — a Not as a logical operand — fuses and matches unfused.
     */
    public void testAndWithNotOperandMatchesUnfused() {
        for (ElementKind kind : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            DataType type = dataTypeOf(kind);
            FieldAttribute a = field("a", type);
            FieldAttribute b = field("b", type);
            Layout layout = layout(a, b);
            Literal five = literalOf(kind, 5);
            Literal ten = literalOf(kind, 10);
            Expression expr = new And(
                Source.EMPTY,
                new GreaterThan(Source.EMPTY, a, five),
                new Not(Source.EMPTY, new LessThan(Source.EMPTY, b, ten))
            );
            assertNotTree(expr, layout, kind, 2, "a>5 AND NOT(b<10) " + kind);
        }
    }

    /** Nested {@code NOT(NOT(a > 5))} fuses (double flip) and matches unfused. */
    public void testDoubleNotMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        Layout layout = layout(a);
        Expression inner = new GreaterThan(Source.EMPTY, a, literalOf(ElementKind.LONG, 5));
        assertNotTree(new Not(Source.EMPTY, new Not(Source.EMPTY, inner)), layout, ElementKind.LONG, 1, "NOT(NOT(a>5))");
    }

    /**
     * Kleene 3VL: {@code NOT(null) = null}. Crafted {@code a > b} tri-states {true, false, null} under {@code NOT}
     * match both a hand-coded oracle and the unfused chain.
     */
    public void testNotOfNullStaysNull() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new Not(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b));
        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, "NOT(a>b) 3VL");

        // pos0: 1>0 true -> NOT false; pos1: 0>1 false -> NOT true; pos2: null>0 null -> NOT null.
        Long[] av = { 1L, 0L, null };
        Long[] bv = { 0L, 1L, 0L };
        Boolean[] expected = { Boolean.FALSE, Boolean.TRUE, null };
        int positions = av.length;
        Block[] inputs = { longColumn(av), longColumn(bv) };
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            fusedResult = fused.eval(page);
            referenceResult = reference.eval(page);
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            for (int q = 0; q < positions; q++) {
                String ctx = "NOT(a>b) @" + q + " expected=" + expected[q];
                if (expected[q] == null) {
                    assertThat(ctx + " fused null", fb.isNull(q), is(true));
                } else {
                    assertThat(ctx + " fused non-null", fb.isNull(q), is(false));
                    assertThat(ctx + " fused value", fb.getBoolean(fb.getFirstValueIndex(q)), equalTo(expected[q]));
                }
                assertThat(ctx + " null parity", fb.isNull(q), is(rb.isNull(q)));
                if (rb.isNull(q) == false) {
                    assertThat(
                        ctx + " value parity",
                        fb.getBoolean(fb.getFirstValueIndex(q)),
                        equalTo(rb.getBoolean(rb.getFirstValueIndex(q)))
                    );
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * A multi-value input under {@code NOT(a > b)} warns on the comparison, not on the {@code Not}. Distinct non-empty
     * sources make a Not-vs-child misattribution fail: fused headers must contain the child's source text and must not
     * contain the Not's.
     */
    public void testNotMultiValueWarningAttributedToChildComparison() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        GreaterThan cmp = new GreaterThan(new Source(1, 3, "a>b"), a, b);
        Expression expr = new Not(new Source(7, 14, "ROOT_NOT"), cmp);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "NOT(a>b) mv");
        Block[] inputs = { multiValueLong(new long[] { 1L, 2L }), longColumn(new Long[] { 0L }) };
        DifferentialWarnings warnings = runDifferentialWarnings(fused, unfused, ElementKind.BOOLEAN, inputs, 1, "NOT(a>b) mv");
        assertThat("unfused comparison under NOT must warn", warnings.reference(), hasItem(containsString(MULTI_VALUE_MSG)));
        assertThat(
            "fused must emit the child's multi-value warning (Not is not a warning source)",
            containsWarning(warnings.fused(), MULTI_VALUE_MSG),
            is(true)
        );
        assertThat("fused warning must carry the comparison's source", warnings.fused(), hasItem(containsString("[a>b]")));
        assertThat("fused warning must not be attributed to the Not node", containsWarning(warnings.fused(), "[ROOT_NOT]"), is(false));
    }

    /** A bare boolean column is not a fusable Not child (S3.1); {@code NOT(bool_col)} stays unfused. */
    public void testNotOfBareBooleanColumnDoesNotFuse() {
        FieldAttribute flag = field("flag", DataType.BOOLEAN);
        Expression expr = new Not(Source.EMPTY, flag);
        assertDoesNotFuse(fusedFactory(expr, layout(flag)), "NOT(bool_col) must refuse");
        assertDoesNotFuse(unfusedFactory(expr, layout(flag)), "NOT(bool_col) kill switch OFF");
    }

    private void assertNotTree(Expression expr, Layout layout, ElementKind kind, int columns, String ctx) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, ctx);
        assertThat(ctx + " must compile LOGICAL_VECTOR", fusedFactory.vectorStrategy(), is(VectorStrategy.LOGICAL_VECTOR));
        assertDoesNotFuse(unfused, ctx + " kill switch OFF");
        for (int n : new int[] { 0, 1, 8, 64 }) {
            Block[] dense = new Block[columns];
            for (int c = 0; c < columns; c++) {
                dense[c] = denseColumn(kind, n, c);
            }
            runDifferential(fused, unfused, ElementKind.BOOLEAN, dense, n, ctx + " dense n=" + n);
        }
        for (int n : new int[] { 8, 64 }) {
            int[] referenced = new int[columns];
            for (int c = 0; c < columns; c++) {
                referenced[c] = c;
            }
            runDifferential(
                fused,
                unfused,
                ElementKind.BOOLEAN,
                buildPage(kind, columns, n, Profile.NULLS, false, referenced),
                n,
                ctx + " nulls n=" + n
            );
            runDifferential(
                fused,
                unfused,
                ElementKind.BOOLEAN,
                buildPage(kind, columns, n, Profile.MULTIVALUE, false, referenced),
                n,
                ctx + " mv n=" + n
            );
        }
    }

    private static Literal literalOf(ElementKind kind, long raw) {
        return switch (kind) {
            case LONG -> new Literal(Source.EMPTY, raw, DataType.LONG);
            case INT -> new Literal(Source.EMPTY, (int) raw, DataType.INTEGER);
            case DOUBLE -> new Literal(Source.EMPTY, (double) raw, DataType.DOUBLE);
            case BOOLEAN, BYTES_REF -> throw new AssertionError("numeric literal: " + kind);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Dense logical vector path (R2a): 2VL over any vector (no nulls / multi-values)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A dense-eligible {@code (a > b) AND (c < d)} tree must compile {@link VectorStrategy#LOGICAL_VECTOR} so the
     * factory can take the dense 2VL vector path when every input {@code asVector()} is non-null. Pre-R2a the logical
     * branch bound {@link VectorStrategy#NONE} (block-only).
     */
    public void testDenseLogicalTreeCompilesLogicalVectorStrategy() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "dense AND tree");
        assertThat(
            "a dense-eligible logical tree must compile VectorStrategy.LOGICAL_VECTOR",
            fused.vectorStrategy(),
            is(VectorStrategy.LOGICAL_VECTOR)
        );
    }

    /**
     * Random dense {@code LongArrayVector} inputs: fused {@code AND}/{@code OR} of two long comparisons matches the
     * unfused chain at every position, including empty / single / &ge;1024 shapes.
     */
    public void testDenseArrayVectorLongAndOrMatchesUnfused() {
        assertDenseLongAndOr(this::longArrayVectorColumn, "LongArrayVector");
    }

    /**
     * Dense {@code ConstantLongVector} inputs (asVector() non-null but not an {@code *ArrayVector}): the interface
     * read variant must still match the unfused chain.
     */
    public void testDenseConstantLongVectorAndOrMatchesUnfused() {
        assertDenseLongAndOr(this::constantLongVectorColumn, "ConstantLongVector");
    }

    /**
     * Dense {@code LongBigArrayVector} inputs (asVector() non-null but not an {@code *ArrayVector}): the interface
     * read variant must still match the unfused chain.
     */
    public void testDenseLongBigArrayVectorAndOrMatchesUnfused() {
        assertDenseLongAndOr(this::longBigArrayVectorColumn, "LongBigArrayVector");
    }

    /**
     * Mixed page: some inputs are {@code LongArrayVector} and some are {@code ConstantLongVector}. LOGICAL_VECTOR is
     * INTERFACE-only after B2, so mixed pages still match the unfused chain.
     */
    public void testDenseMixedArrayAndConstantLongVectorMatchesUnfused() {
        for (Connective connective : Connective.values()) {
            FieldAttribute a = field("a", DataType.LONG);
            FieldAttribute b = field("b", DataType.LONG);
            FieldAttribute c = field("c", DataType.LONG);
            FieldAttribute d = field("d", DataType.LONG);
            Layout layout = layout(a, b, c, d);
            Expression expr = connective == Connective.AND
                ? new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d))
                : new Or(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
            FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), connective + " mixed");
            assertThat(
                "a dense-eligible logical tree must compile VectorStrategy.LOGICAL_VECTOR",
                fused.vectorStrategy(),
                is(VectorStrategy.LOGICAL_VECTOR)
            );
            ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
            for (int i = 0; i < 40; i++) {
                int n = positionCountFor(i);
                Block[] inputs = {
                    longArrayVectorColumn(n, 0),
                    constantLongVectorColumn(n, 1),
                    longArrayVectorColumn(n, 2),
                    constantLongVectorColumn(n, 3) };
                runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, n, connective + " mixed@" + i);
            }
        }
    }

    /**
     * Dense {@code IntArrayVector} and {@code DoubleArrayVector} comparisons under both {@code AND} and {@code OR}
     * match the unfused chain. The compiled strategy is {@link VectorStrategy#LOGICAL_VECTOR} for both elements.
     */
    public void testDenseIntAndDoubleArrayVectorAndOrMatchesUnfused() {
        assertDenseNumericAndOr(DataType.INTEGER, this::intArrayVectorColumn, "IntArrayVector");
        assertDenseNumericAndOr(DataType.DOUBLE, this::doubleArrayVectorColumn, "DoubleArrayVector");
    }

    /**
     * Pins the per-page INTERFACE vs BLOCK choice so an always-block regression is caught even when values still
     * match the unfused chain. B2: LOGICAL_VECTOR is INTERFACE-only — exact {@code *ArrayVector} pages take
     * INTERFACE too, not RAW. Test-only {@link LastEvalPath} seam.
     */
    public void testDenseLogicalVectorPicksInterfaceVsBlock() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "dense AND tree");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.LOGICAL_VECTOR));

        ExpressionEvaluator eval = fused.get(driverContext);
        try {
            int n = 8;
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] {
                    longArrayVectorColumn(n, 0),
                    longArrayVectorColumn(n, 1),
                    longArrayVectorColumn(n, 2),
                    longArrayVectorColumn(n, 3) },
                "exact *ArrayVector page must take INTERFACE (logical vector is INTERFACE-only after B2)"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] {
                    constantLongVectorColumn(n, 0),
                    constantLongVectorColumn(n, 1),
                    constantLongVectorColumn(n, 2),
                    constantLongVectorColumn(n, 3) },
                "ConstantLongVector page must take ALL-INTERFACE"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] {
                    longBigArrayVectorColumn(n, 0),
                    longBigArrayVectorColumn(n, 1),
                    longBigArrayVectorColumn(n, 2),
                    longBigArrayVectorColumn(n, 3) },
                "LongBigArrayVector page must take ALL-INTERFACE"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.BLOCK,
                new Block[] {
                    longColumn(new Long[] { 1L, null, 3L, 4L }),
                    longColumn(new Long[] { 0L, 1L, 2L, 3L }),
                    longColumn(new Long[] { 0L, 1L, 2L, 3L }),
                    longColumn(new Long[] { 1L, 2L, 3L, 4L }) },
                "null-carrying page must take the 3VL BLOCK path"
            );
        } finally {
            Releasables.closeExpectNoException(eval);
        }
    }

    private static void assertLastEvalPath(ExpressionEvaluator eval, LastEvalPath expected, Block[] inputs, String ctx) {
        int n = inputs[0].getPositionCount();
        Page page = new Page(n, inputs);
        Block result = null;
        try {
            result = eval.eval(page);
            assertThat(ctx, FusedExpressionEvaluatorFactory.lastEvalPath(eval), is(expected));
        } finally {
            Releasables.closeExpectNoException(result);
            page.releaseBlocks();
        }
    }

    private void assertDenseLongAndOr(DenseColumnFactory columns, String kind) {
        assertDenseNumericAndOr(DataType.LONG, columns, kind);
    }

    private void assertDenseNumericAndOr(DataType type, DenseColumnFactory columns, String kind) {
        for (Connective connective : Connective.values()) {
            FieldAttribute a = field("a", type);
            FieldAttribute b = field("b", type);
            FieldAttribute c = field("c", type);
            FieldAttribute d = field("d", type);
            Layout layout = layout(a, b, c, d);
            Expression expr = connective == Connective.AND
                ? new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d))
                : new Or(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
            FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), connective + " " + kind);
            assertThat(
                "a dense-eligible logical tree must compile VectorStrategy.LOGICAL_VECTOR",
                fused.vectorStrategy(),
                is(VectorStrategy.LOGICAL_VECTOR)
            );
            ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
            for (int i = 0; i < 40; i++) {
                int n = positionCountFor(i);
                Block[] inputs = { columns.column(n, 0), columns.column(n, 1), columns.column(n, 2), columns.column(n, 3) };
                runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, n, connective + " " + kind + "@" + i);
            }
        }
    }

    @FunctionalInterface
    private interface DenseColumnFactory {
        Block column(int positionCount, int seed);
    }

    private Block longArrayVectorColumn(int n, int seed) {
        long[] v = new long[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7L + seed * 3L) % 11) - 5;
        }
        return blockFactory.newLongArrayVector(v, n).asBlock();
    }

    private Block constantLongVectorColumn(int n, int seed) {
        // A constant vector is dense (no nulls / multi-values) but not an *ArrayVector, so the factory must pick
        // the all-interface read variant rather than VectorUnsafe.rawValues.
        return blockFactory.newConstantLongVector(((seed * 3L) % 11) - 5, n).asBlock();
    }

    private Block longBigArrayVectorColumn(int n, int seed) {
        LongArray array = bigArrays.newLongArray(n);
        for (int p = 0; p < n; p++) {
            array.set(p, ((p * 7L + seed * 3L) % 11) - 5);
        }
        return new LongBigArrayVector(array, n, blockFactory).asBlock();
    }

    private Block intArrayVectorColumn(int n, int seed) {
        int[] v = new int[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7 + seed * 3) % 11) - 5;
        }
        return blockFactory.newIntArrayVector(v, n).asBlock();
    }

    private Block doubleArrayVectorColumn(int n, int seed) {
        double[] v = new double[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7 + seed * 3) % 11) - 5;
        }
        return blockFactory.newDoubleArrayVector(v, n).asBlock();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3VL oracle + small helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static Boolean and3(Boolean l, Boolean r) {
        if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
            return Boolean.FALSE;
        }
        if (l == null || r == null) {
            return null;
        }
        return l && r;
    }

    private static Boolean or3(Boolean l, Boolean r) {
        if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) {
            return Boolean.TRUE;
        }
        if (l == null || r == null) {
            return null;
        }
        return l || r;
    }

    /** Sets {@code x[p]}, {@code y[p]} so that {@code x > y} yields {@code state} (null when {@code state == null}). */
    private static void fillComparison(Long[] x, Long[] y, int p, Boolean state) {
        if (state == null) {
            x[p] = null; // null input -> comparison is null (viral)
            y[p] = 0L;
        } else if (state) {
            x[p] = 1L;
            y[p] = 0L;
        } else {
            x[p] = 0L;
            y[p] = 1L;
        }
    }

    private enum Connective {
        AND,
        OR
    }
}
