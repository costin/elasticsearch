/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Deterministic, pinned-shape regressions for <b>arithmetic-rooted</b> expression fusion. The randomized arithmetic
 * sweeps that used to live here (long/int/double arithmetic + long division) are now subsumed by the unified,
 * spec-driven sweep in {@link FusionSpecDifferentialTests} (arithmetic root over the same overflow-checked / unary /
 * division palettes), and all the shared machinery lives in {@link FusionDifferentialTestCase}. What remains here are
 * the two hand-built shapes that a random sweep cannot reliably hit, kept as explicit regressions:
 * <ul>
 *   <li>{@link #testMixedMultiValueAndDivByZeroWarningIsSubset()} — the exact mixed shape that first surfaced the
 *       ratified warning-<b>subset</b> invariant (a single position that is simultaneously multi-valued in one sibling
 *       and zero-divisor in another);</li>
 *   <li>{@link #testArithmeticOverflowWarningsAttributedPerKernel()} — per-kernel warning-source attribution
 *       ({@code (a+b)+(c+d)} with distinct non-empty {@link Source}s), which fails if fused warnings are misattributed
 *       to the root source.</li>
 * </ul>
 */
public class FusionDifferentialTests extends FusionDifferentialTestCase {

    /**
     * Deterministic regression for the ratified warning-<b>subset</b> invariant, pinned so the exact mixed shape that
     * first surfaced the property can never silently regress.
     *
     * <p>The wired tree is {@code Add(Abs(mv), Div(a, z))} over three {@code long} columns, evaluated at a single
     * position that is <b>both</b> multi-valued (the {@code Abs} branch) <b>and</b> has a zero divisor (the {@code Div}
     * branch): the unfused columnar tree evaluates both siblings and registers <b>both</b> the multi-value and the
     * {@code / by zero} warning before {@code Add} nulls the row, while the fused per-position loop short-circuits on
     * the multi-value it sees first and never reaches the division — a strict subset.
     */
    public void testMixedMultiValueAndDivByZeroWarningIsSubset() {
        FieldAttribute mv = field("mv", DataType.LONG);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute z = field("z", DataType.LONG);
        Layout layout = layout(mv, a, z);
        // Add(Abs(mv), Div(a, z)): kernel depth 2 => fully fusable through the real plan-time path.
        Expression expr = new Add(Source.EMPTY, new Abs(Source.EMPTY, mv), new Div(Source.EMPTY, a, z), EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, "mixed-shape tree");
        assertDoesNotFuse(unfusedFactory, "mixed-shape tree");

        int positions = 5;
        int mixed = 2; // the one position that is simultaneously multi-valued (mv) and zero-divisor (z)

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            Block mvBlock;
            try (LongBlock.Builder b = blockFactory.newLongBlockBuilder(positions)) {
                for (int p = 0; p < positions; p++) {
                    if (p == mixed) {
                        b.beginPositionEntry();
                        b.appendLong(7L);
                        b.appendLong(9L);
                        b.endPositionEntry();
                    } else {
                        b.appendLong(3L);
                    }
                }
                mvBlock = b.build();
            }
            long[] aVals = new long[positions];
            long[] zVals = new long[positions];
            for (int p = 0; p < positions; p++) {
                aVals[p] = 10L;
                zVals[p] = (p == mixed) ? 0L : 2L; // zero divisor only at the mixed position
            }
            Block aBlock = blockFactory.newLongArrayVector(aVals, positions).asBlock();
            Block zBlock = blockFactory.newLongArrayVector(zVals, positions).asBlock();

            page = new Page(positions, mvBlock, aBlock, zBlock);

            drainWarnings(); // clean slate
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> unfusedWarnings = drainWarnings();

            // (a) VALUES + NULL positions identical everywhere. At the clean positions the result is
            // Add(Abs(3), Div(10, 2)) = 3 + 5 = 8; the mixed position is null in BOTH.
            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "mixed-shape");
            assertThat("fused mixed position must be null", fusedResult.isNull(mixed), is(true));
            assertThat("unfused mixed position must be null", referenceResult.isNull(mixed), is(true));

            // (b) The ratified subset invariant on the real wired path.
            assertWarningsSubset(fusedWarnings, unfusedWarnings, "mixed-shape");

            // (c) The unfused tree raised BOTH mechanisms; the fused loop raised at least the multi-value one and, by
            // short-circuiting, did NOT raise the div-by-zero it never reached (proving a strict subset, not a superset).
            assertThat("unfused must contain the multi-value warning", containsWarning(unfusedWarnings, MULTI_VALUE_MSG), is(true));
            assertThat("unfused must contain the / by zero warning", containsWarning(unfusedWarnings, DIV_ZERO_MSG), is(true));
            assertThat("fused must contain the multi-value warning", containsWarning(fusedWarnings, MULTI_VALUE_MSG), is(true));
            assertThat(
                "fused must NOT contain the / by zero warning (short-circuited past the division)",
                containsWarning(fusedWarnings, DIV_ZERO_MSG),
                is(false)
            );
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * PRIORITY-1 warning-source fidelity for ARITHMETIC overflow (iter-20): {@code (a + b) + (c + d)} over long
     * columns with DISTINCT NON-EMPTY {@link Source}s on the two inner {@code Add}s and the root {@code Add}. Position 0
     * overflows the LEFT inner add; position 1 overflows the RIGHT inner add; the root add only ever sees a null operand
     * at those positions and so never overflows. The unfused chain attributes one overflow warning to EACH inner add's
     * own source and NONE to the root, so the fused warnings must be a subset — a pre-fix single-root-{@code Warnings}
     * path attributed every fused overflow to the root source, a header the unfused set never contains, breaking subset.
     */
    public void testArithmeticOverflowWarningsAttributedPerKernel() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        // (a+b) + (c+d): distinct non-empty sources on both inner adds and the root add.
        Expression leftInner = new Add(new Source(1, 1, "a+b"), a, b, EsqlTestUtils.TEST_CFG);
        Expression rightInner = new Add(new Source(2, 1, "c+d"), c, d, EsqlTestUtils.TEST_CFG);
        Expression expr = new Add(new Source(3, 1, "OUTER_ADD"), leftInner, rightInner, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat("overflow tree must fuse ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat("overflow tree must NOT fuse OFF", unfusedFactory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        int positions = 4;
        // p0: a+b overflows (left inner). p1: c+d overflows (right inner). p2,p3 clean. The root add never overflows —
        // at p0/p1 it sees a null operand and propagates null — so no warning is ever attributed to OUTER_ADD unfused.
        long[] av = { Long.MAX_VALUE, 1, 2, 3 };
        long[] bv = { 1, 1, 3, 4 };
        long[] cv = { 1, Long.MAX_VALUE, 5, 6 };
        long[] dv = { 1, 1, 7, 8 };

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            Block aBlock = blockFactory.newLongArrayVector(av, positions).asBlock();
            Block bBlock = blockFactory.newLongArrayVector(bv, positions).asBlock();
            Block cBlock = blockFactory.newLongArrayVector(cv, positions).asBlock();
            Block dBlock = blockFactory.newLongArrayVector(dv, positions).asBlock();
            page = new Page(positions, aBlock, bBlock, cBlock, dBlock);

            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // Values/nulls unchanged: p0,p1 null in both paths; p2,p3 equal.
            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "overflow-attribution");
            assertThat("p0 null (left inner overflow)", fusedResult.isNull(0), is(true));
            assertThat("p1 null (right inner overflow)", fusedResult.isNull(1), is(true));

            // The subset invariant. Pre-fix, both overflows were attributed to the root "[OUTER_ADD]" source, a header
            // the unfused set never contains -> subset fails.
            assertWarningsSubset(fusedWarnings, referenceWarnings, "overflow-attribution");
            // Attribution is correct (not merely empty): each inner add's own source text appears fused.
            assertThat("left inner source attribution", containsWarning(fusedWarnings, "[a+b]"), is(true));
            assertThat("right inner source attribution", containsWarning(fusedWarnings, "[c+d]"), is(true));
            // And the pre-fix root-source misattribution is gone.
            assertThat("no root-source misattribution", containsWarning(fusedWarnings, "[OUTER_ADD]"), is(false));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }
}
