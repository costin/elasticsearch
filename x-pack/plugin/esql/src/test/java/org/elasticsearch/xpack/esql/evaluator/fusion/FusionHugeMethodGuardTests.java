/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
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

import java.util.function.Function;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end huge-method guard: an over-limit fused class must not be defined. The factory falls back to the
 * unfused chain when the BLOCK path is over budget. Deep in-budget LOGICAL and FILTER-EVAL trees still fuse
 * and match unfused values (E4's missing differentials).
 */
public class FusionHugeMethodGuardTests extends FusionDifferentialTestCase {

    /**
     * Small enough that every real fused method exceeds it; used to force the factory fallback without a
     * thousands-deep tree. {@code resolveMaxFusedMethodBytecodes} rejects {@code <= 0}.
     */
    private static final int TINY_LIMIT = 50;

    /** Safety net: restore the default limit even if a test flips it outside its own try/finally. */
    @After
    public void restoreDefaultBudget() {
        Stitcher.maxFusedMethodBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES);
    }

    /**
     * FAIL-before: long arithmetic compiles BLOCK then CHECKED_VECTOR, both previously unenforced, so a tiny
     * huge-method limit still produced a fused factory. After H2 the BLOCK compile throws and the outer catch
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

    /**
     * Passing-before: logical-block was already enforced, so a tiny limit already returned the unfused factory.
     * Values still match.
     */
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
     * Passing-before: filter-eval already enforced the named {@code fused} method. After H2 it also measures
     * {@code pred}/{@code projAppend}; a tiny limit still refuses to the unfused pair.
     */
    public void testTinyLimitFilterEvalFallsBackUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression projection = new Mul(Source.EMPTY, a, b);

        FilterEvalEvaluator.Factory factory;
        int previous = Stitcher.maxFusedMethodBytecodes();
        try {
            Stitcher.maxFusedMethodBytecodesForTests(TINY_LIMIT);
            FusionPlanner.classCache().resetForTests();
            factory = fuseFilterEval(predicate, projection, layout);
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
            FusionPlanner.classCache().resetForTests();
        }
        assertThat(factory, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        assertThat(
            "over-limit filter-eval must fall back to the unfused Filter→Eval pair",
            ((FusedFilterEvalEvaluatorFactory) factory).fused(),
            is(false)
        );
    }

    /**
     * E4 missing differential: a deep LOGICAL tree (left-nested AND of 16 column comparisons) fuses via
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
     * E4 missing differential: a deep FILTER-EVAL projection (16-add chain) fuses. Size is asserted at the
     * stitcher seam ({@code StitcherHugeMethodGuardTests#testDeepFilterEvalLargestMethodBetween325And8000}).
     */
    public void testDeepInBudgetFilterEvalFuses() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, a, b);
        Expression projection = leftAddChain(a, b, 16);
        FilterEvalEvaluator.Factory factory = fuseFilterEval(predicate, projection, layout);
        assertThat("deep in-budget filter-eval must fuse", factory, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        assertThat(((FusedFilterEvalEvaluatorFactory) factory).fused(), is(true));
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

    private FilterEvalEvaluator.Factory fuseFilterEval(Expression predicate, Expression projection, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);
        FilterEvalEvaluator.Factory factory = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat("maybeFuseFilterEval must produce a factory for a fusable pair", factory, notNullValue());
        return factory;
    }
}
