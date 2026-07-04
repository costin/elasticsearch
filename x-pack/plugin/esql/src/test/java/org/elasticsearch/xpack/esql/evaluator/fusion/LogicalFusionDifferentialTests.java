/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
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
