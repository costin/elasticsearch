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
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end huge-method guard: an over-limit fused class must not be defined. The factory falls back to the
 * unfused chain when the BLOCK path is over budget. Deep in-budget LOGICAL trees and WHERE predicates still fuse
 * and match unfused values. The per-row helper limit ({@code FreqInlineSize}) refuses only classes with helpers: the
 * logical block loops, for EVAL and for WHERE.
 */
public class FusionHugeMethodGuardTests extends FusionDifferentialTestCase {

    /**
     * Small enough that every real fused method exceeds it; used to force the factory fallback without a
     * thousands-deep tree. {@code resolveMaxFusedMethodBytecodes} rejects {@code <= 0}.
     */
    private static final int TINY_LIMIT = 50;

    /** Safety net: restore the default limits even if a test flips them outside its own try/finally. */
    @After
    public void restoreDefaultBudget() {
        Stitcher.maxFusedMethodBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES);
        Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
    }

    /**
     * FAIL-before: long arithmetic compiles BLOCK then CHECKED_VECTOR, both previously unenforced, so a tiny
     * huge-method limit still produced a fused factory. After the BLOCK compile throws and the outer catch
     * returns the unfused factory. Values still match.
     */
    public void testTinyLimitArithmeticFallsBackUnfusedAndMatches() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        ExpressionEvaluator.Factory candidate;
        int previous = Stitcher.maxFusedMethodBytecodes();
        try {
            Stitcher.maxFusedMethodBytecodesForTests(TINY_LIMIT);
            FusionPlanner.classCache().resetForTests();
            candidate = fusedFactory(expr, layout);
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
            FusionPlanner.classCache().resetForTests();
        }
        assertDoesNotFuse(candidate, "over-limit arithmetic must fall back to the unfused chain, not a fused block-only factory");
        assertDoesNotFuse(unfused, "kill switch OFF");
        int positions = 32;
        Block[] inputs = {
            denseColumn(ElementKind.LONG, positions, 1),
            denseColumn(ElementKind.LONG, positions, 2),
            denseColumn(ElementKind.LONG, positions, 3) };
        runDifferential(candidate, unfused, ElementKind.LONG, inputs, positions, "tiny-limit arithmetic unfused fallback");
    }

    /** Passing-before: logical-block was already enforced, so a tiny limit already returned the unfused factory. */
    public void testTinyLimitLogicalFallsBackUnfusedAndMatches() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));

        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        ExpressionEvaluator.Factory candidate;
        int previous = Stitcher.maxFusedMethodBytecodes();
        try {
            Stitcher.maxFusedMethodBytecodesForTests(TINY_LIMIT);
            FusionPlanner.classCache().resetForTests();
            candidate = fusedFactory(expr, layout);
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
            FusionPlanner.classCache().resetForTests();
        }
        assertDoesNotFuse(candidate, "over-limit logical must fall back to the unfused chain");
        int positions = 32;
        Block[] inputs = {
            denseColumn(ElementKind.LONG, positions, 1),
            denseColumn(ElementKind.LONG, positions, 2),
            denseColumn(ElementKind.LONG, positions, 3),
            denseColumn(ElementKind.LONG, positions, 4) };
        runDifferential(candidate, unfused, ElementKind.BOOLEAN, inputs, positions, "tiny-limit logical unfused fallback");
    }

    /**
     * A logical tree whose {@code cmpN} helpers are over the helper limit falls back to the unfused chain (the logical
     * block loop is its primary path) and still matches it.
     */
    public void testTinyHelperLimitLogicalFallsBackUnfusedAndMatches() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));

        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        ExpressionEvaluator.Factory candidate;
        try {
            Stitcher.maxFusedHelperBytecodesForTests(1);
            FusionPlanner.classCache().resetForTests();
            candidate = fusedFactory(expr, layout);
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
            FusionPlanner.classCache().resetForTests();
        }
        assertDoesNotFuse(candidate, "a logical tree with over-limit helpers must fall back to the unfused chain");
        int positions = 32;
        Block[] inputs = {
            denseColumn(ElementKind.LONG, positions, 1),
            denseColumn(ElementKind.LONG, positions, 2),
            denseColumn(ElementKind.LONG, positions, 3),
            denseColumn(ElementKind.LONG, positions, 4) };
        runDifferential(candidate, unfused, ElementKind.BOOLEAN, inputs, positions, "tiny-helper-limit logical unfused fallback");
    }

    /** The helper limit leaves root-only classes alone: arithmetic still fuses with its vector path. */
    public void testTinyHelperLimitKeepsArithmeticFused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory candidate;
        try {
            Stitcher.maxFusedHelperBytecodesForTests(1);
            FusionPlanner.classCache().resetForTests();
            candidate = fusedFactory(expr, layout);
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
            FusionPlanner.classCache().resetForTests();
        }
        FusedExpressionEvaluatorFactory fused = assertFuses(candidate, "root-only arithmetic under a tiny helper limit");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        int positions = 32;
        Block[] inputs = {
            denseColumn(ElementKind.LONG, positions, 1),
            denseColumn(ElementKind.LONG, positions, 2),
            denseColumn(ElementKind.LONG, positions, 3) };
        runDifferential(fused, unfusedFactory(expr, layout), ElementKind.LONG, inputs, positions, "tiny-helper-limit arithmetic");
    }

    /**
     * A WHERE predicate whose block POSITIONS class is over budget falls back to the unfused predicate: the filter keeps
     * the same rows, read from the unfused BooleanBlock.
     */
    public void testTinyLimitWherePredicateFallsBackUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory candidate;
        int previous = Stitcher.maxFusedMethodBytecodes();
        try {
            Stitcher.maxFusedMethodBytecodesForTests(TINY_LIMIT);
            FusionPlanner.classCache().resetForTests();
            candidate = filterFactory(predicate, layout);
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
            FusionPlanner.classCache().resetForTests();
        }
        assertThat(
            "over-limit WHERE predicate must fall back to the unfused predicate",
            candidate,
            not(instanceOf(FusedFilterEvaluatorFactory.class))
        );
        assertFilterSameRows(candidate, unfusedFactory(predicate, layout), 3, "tiny-limit WHERE unfused fallback");
    }

    /**
     * A logical WHERE predicate whose block POSITIONS class has over-limit {@code cmpN} helpers falls back to the unfused
     * predicate and keeps the same rows.
     */
    public void testTinyHelperLimitLogicalWherePredicateFallsBackUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, a, c));

        ExpressionEvaluator.Factory candidate;
        try {
            Stitcher.maxFusedHelperBytecodesForTests(1);
            FusionPlanner.classCache().resetForTests();
            candidate = filterFactory(predicate, layout);
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
            FusionPlanner.classCache().resetForTests();
        }
        assertThat(
            "a logical WHERE predicate with over-limit helpers must fall back to the unfused predicate",
            candidate,
            not(instanceOf(FusedFilterEvaluatorFactory.class))
        );
        assertFilterSameRows(candidate, unfusedFactory(predicate, layout), 3, "tiny-helper-limit logical WHERE unfused fallback");
    }

    /**
     * missing differential: a deep LOGICAL tree (left-nested AND of 16 column comparisons) fuses via
     * {@link VectorStrategy#LOGICAL_VECTOR} and matches the unfused chain. Size is asserted at the stitcher
     * seam ({@code StitcherHugeMethodGuardTests#testDeepLogicalVectorLargestMethodBetween325And8000}).
     */
    public void testDeepInBudgetLogicalFusesAndMatchesUnfused() {
        FieldAttribute[] cols = {
            field("c0", DataType.LONG),
            field("c1", DataType.LONG),
            field("c2", DataType.LONG),
            field("c3", DataType.LONG) };
        Layout layout = layout(cols);
        Expression expr = andOfColumnComparisons(16, cols);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "deep in-budget logical");
        assertThat(
            "in-budget logical tree must compile LOGICAL_VECTOR (not degrade to block-only)",
            fused.vectorStrategy(),
            is(VectorStrategy.LOGICAL_VECTOR)
        );
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        int positions = 64;
        Block[] inputs = new Block[cols.length];
        for (int c = 0; c < cols.length; c++) {
            inputs[c] = denseColumn(ElementKind.LONG, positions, c + 1);
        }
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "deep in-budget logical 16-AND");
    }

    /**
     * A deep WHERE predicate (a comparison over a 16-add chain) fuses within budget and keeps the unfused rows. Size is
     * asserted at the stitcher seam ({@code StitcherHugeMethodGuardTests#testDeepPositionsLargestMethodBetween325And8000}).
     */
    public void testDeepInBudgetWherePredicateFusesAndMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression predicate = new GreaterThan(Source.EMPTY, leftAddChain(a, b, 16), b);
        FusedFilterEvaluatorFactory fused = assertFilterFuses(filterFactory(predicate, layout), "deep in-budget WHERE");
        assertThat("in-budget predicate keeps its vector path", fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        assertFilterSameRows(fused, unfusedFactory(predicate, layout), 2, "deep in-budget WHERE 16-add");
    }

    /** Filters a dense page of {@code columns} long columns with both factories and asserts they keep the same rows. */
    private void assertFilterSameRows(ExpressionEvaluator.Factory candidate, ExpressionEvaluator.Factory unfused, int columns, String ctx) {
        int positions = 64;
        Block[] inputs = new Block[columns];
        for (int c = 0; c < columns; c++) {
            inputs[c] = denseColumn(ElementKind.LONG, positions, c + 1);
        }
        Page page = new Page(positions, inputs);
        CapturedFilter actual = null;
        CapturedFilter expected = null;
        try {
            actual = filterCapturing(candidate, page);
            expected = filterCapturing(unfused, page);
            assertPagesEqual(actual.page(), expected.page(), ctx);
        } finally {
            page.releaseBlocks();
            for (CapturedFilter captured : new CapturedFilter[] { actual, expected }) {
                if (captured != null && captured.page() != null) {
                    captured.page().releaseBlocks();
                }
            }
        }
    }

    private static Expression andOfColumnComparisons(int comparisons, FieldAttribute[] cols) {
        Expression tree = columnComparison(cols, 0);
        for (int i = 1; i < comparisons; i++) {
            tree = new And(Source.EMPTY, tree, columnComparison(cols, i));
        }
        return tree;
    }

    private static Expression columnComparison(FieldAttribute[] cols, int i) {
        FieldAttribute left = cols[i % cols.length];
        FieldAttribute right = cols[(i + 1) % cols.length];
        return (i % 2 == 0) ? new GreaterThan(Source.EMPTY, left, right) : new LessThan(Source.EMPTY, left, right);
    }

    private static Expression leftAddChain(FieldAttribute a, FieldAttribute b, int adds) {
        Expression node = a;
        for (int i = 0; i < adds; i++) {
            node = new Add(Source.EMPTY, node, b, EsqlTestUtils.TEST_CFG);
        }
        return node;
    }
}
