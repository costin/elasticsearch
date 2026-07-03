/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.planner.Layout;

import static org.hamcrest.Matchers.is;

/**
 * Deterministic, pinned-shape regressions for <b>comparison-rooted</b> fusion (numeric arithmetic children -> a boolean
 * result). The randomized comparison sweeps that used to live here (long/int/double, plus the double unary plain-vector
 * case) are now subsumed by the unified, spec-driven sweep in {@link FusionSpecDifferentialTests} (comparison root over
 * the same arithmetic palettes, and a dedicated plain-vector sweep), and all shared machinery lives in
 * {@link FusionDifferentialTestCase}. What remains here are the hand-built shapes:
 * <ul>
 *   <li>fusion-eligibility anchors: {@code a+b>c} fuses to a boolean via the checked-vector strategy, a nested
 *       {@code (a+b)>(c-d)} fuses, a bare {@code a>b} (kernel depth 1) does <b>not</b> fuse but still matches;</li>
 *   <li>per-operator enumeration across every element kind (not left to a random palette);</li>
 *   <li>the DFS-order block-loop short-circuit regressions where an intermediate overflow / null leaf preempts a
 *       sibling multi-value warning at the SAME position (a random single-profile page never mixes these mechanisms).</li>
 * </ul>
 */
public class ComparisonFusionDifferentialTests extends FusionDifferentialTestCase {

    /** {@code a + b > c} over longs fuses ON (checked-vector), NOT OFF, and produces a BooleanVector-backed block. */
    public void testAddGreaterThanColumnFusesToBoolean() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, "a + b > c");
        assertDoesNotFuse(unfused, "a + b > c");
        assertThat(
            "long comparison over overflow-checked children uses the checked-vector strategy",
            fusedFactory.vectorStrategy(),
            is(VectorStrategy.CHECKED_VECTOR)
        );

        long[] av = { 1, 2, 3, Long.MAX_VALUE };
        long[] bv = { 0, 5, 0, 1 }; // a+b overflows at position 3 => null in BOTH
        long[] cv = { 0, 6, 3, 10 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock(),
            blockFactory.newLongArrayVector(cv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "a+b>c");
    }

    /** {@code (a + b) > (c - d)} over ints fuses to a boolean, with values + nulls matching the unfused chain. */
    public void testNestedArithmeticComparisonFuses() {
        FieldAttribute a = field("a", DataType.INTEGER);
        FieldAttribute b = field("b", DataType.INTEGER);
        FieldAttribute c = field("c", DataType.INTEGER);
        FieldAttribute d = field("d", DataType.INTEGER);
        Layout layout = layout(a, b, c, d);
        Expression expr = new GreaterThan(
            Source.EMPTY,
            new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG),
            new Sub(Source.EMPTY, c, d, EsqlTestUtils.TEST_CFG)
        );

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+b) > (c-d)");
        assertDoesNotFuse(unfused, "(a+b) > (c-d)");

        int positions = 6;
        int[] av = new int[positions];
        int[] bv = new int[positions];
        int[] cv = new int[positions];
        int[] dv = new int[positions];
        for (int p = 0; p < positions; p++) {
            av[p] = randomIntBetween(-20, 20);
            bv[p] = randomIntBetween(-20, 20);
            cv[p] = randomIntBetween(-20, 20);
            dv[p] = randomIntBetween(-20, 20);
        }
        Block[] inputs = {
            blockFactory.newIntArrayVector(av, positions).asBlock(),
            blockFactory.newIntArrayVector(bv, positions).asBlock(),
            blockFactory.newIntArrayVector(cv, positions).asBlock(),
            blockFactory.newIntArrayVector(dv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "(a+b)>(c-d)");
    }

    /**
     * A bare {@code a > b} (kernel depth 1) is deliberately NOT fused, consistent with the depth&ge;2 fusion invariant:
     * a single kernel gains nothing from stitching and the generated comparison evaluator already handles it.
     */
    public void testBareComparisonDoesNotFuse() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(Source.EMPTY, a, b);
        assertDoesNotFuse(fusedFactory(expr, layout), "bare a > b (kernel depth 1)");
    }

    /**
     * Companion to {@link #testBareComparisonDoesNotFuse}: even though a bare {@code a > b} does not fuse, its
     * value/null output must still match — trivially, since with the kill switch ON and OFF both sides resolve to the
     * same generated comparison evaluator. Demonstrates parity for the depth-1 shape rather than only asserting
     * non-fusion.
     */
    public void testBareComparisonParityMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(Source.EMPTY, a, b);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "bare a > b must not fuse even with the kill switch ON");

        long[] av = { 5, 5, 1, 9, -3 };
        long[] bv = { 3, 5, 8, 9, -3 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "bare a>b parity");
    }

    /**
     * Deterministic per-operator coverage: every comparison operator over each supported element kind fuses and matches
     * the unfused chain. Enumerated explicitly rather than relying on a random palette. {@code a + b <cmp> c} keeps an
     * overflow-checked arithmetic child so long/int/double all route through the checked-vector path.
     */
    public void testAllComparisonOperatorsFusePerElementKind() {
        for (Cmp cmp : Cmp.values()) {
            assertComparisonOperatorFuses(cmp, ElementKind.LONG, DataType.LONG);
            assertComparisonOperatorFuses(cmp, ElementKind.INT, DataType.INTEGER);
            assertComparisonOperatorFuses(cmp, ElementKind.DOUBLE, DataType.DOUBLE);
        }
    }

    private void assertComparisonOperatorFuses(Cmp cmp, ElementKind kind, DataType type) {
        FieldAttribute a = field("a", type);
        FieldAttribute b = field("b", type);
        FieldAttribute c = field("c", type);
        Layout layout = layout(a, b, c);
        Expression expr = cmp.build(new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        String ctx = "cmp=" + cmp + " kind=" + kind;
        assertFuses(fused, ctx);
        assertDoesNotFuse(unfused, ctx);

        int positions = 8;
        Block[] inputs = { denseColumn(kind, positions, 0), denseColumn(kind, positions, 1), denseColumn(kind, positions, 2) };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, ctx);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // PRIORITY-1 regression: block-path warning subset when a null / intermediate overflow preempts a sibling
    // multi-value at the SAME position. The unfused kernel chain evaluates depth-first and short-circuits the position
    // to null on the first null/multi-value/overflow, never reaching (or warning about) a later-in-DFS-order operand.
    // A fused loop that pre-guards every leaf up front would emit a multi-value warning the unfused chain never raises,
    // breaking the ratified fused ⊆ unfused invariant. The randomized sweeps never expose this because each page
    // carries a single profile (nulls XOR multi-value XOR overflow), so these same-position combinations never arise.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code (a + b) > c} over longs at a single position where {@code a + b} OVERFLOWS (unfused nulls the position with
     * an OVERFLOW warning and never inspects {@code c}) while {@code c} is MULTI-VALUE. A pre-guard-all fused loop would
     * emit the multi-value warning instead; the DFS block loop lets the intermediate overflow short-circuit before c's
     * guard, so fused warnings ⊆ unfused. Values/nulls identical (both null).
     */
    public void testBlockPathIntermediateOverflowPreemptsSiblingMultiValueWarning() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+b) > c");

        int positions = 1;
        Block[] inputs = {
            blockFactory.newLongArrayVector(new long[] { Long.MAX_VALUE }, positions).asBlock(),
            blockFactory.newLongArrayVector(new long[] { 1L }, positions).asBlock(),
            multiValueLong(new long[] { 1L, 2L }) };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "overflow+mv same position");
    }

    /**
     * {@code (a + b) > c} over longs at a single position where {@code a} is NULL and {@code c} is MULTI-VALUE, with the
     * layout ordered so {@code c} has a LOWER channel than {@code a}. The unfused chain nulls the position via
     * {@code a}'s null (NO warning) and never inspects {@code c}; a fused loop guarding leaves in channel order would
     * hit {@code c} first and wrongly emit the multi-value warning. DFS order evaluates the {@code a + b} subtree first
     * and short-circuits on {@code a}'s null before c's guard, so neither side warns.
     */
    public void testBlockPathNullLeafPreemptsSiblingMultiValueWarning() {
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(c, a, b); // c = channel 0, a = channel 1, b = channel 2
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+b) > c");

        int positions = 1;
        Block[] inputs = {
            multiValueLong(new long[] { 1L, 2L }),                                  // channel 0: c, multi-value
            nullLong(positions),                                                    // channel 1: a, null
            blockFactory.newLongArrayVector(new long[] { 5L }, positions).asBlock() // channel 2: b
        };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "null+mv same position");
    }

    /**
     * The block-loop fix is general, so the same short-circuit invariant is asserted for an ARITHMETIC-rooted tree.
     * {@code (a + b) + c} over longs at a single position has {@code a + b} OVERFLOWING while {@code c} is MULTI-VALUE:
     * the unfused chain warns OVERFLOW and never inspects {@code c}, and the DFS block loop matches it (fused ⊆ unfused).
     */
    public void testBlockPathArithmeticRootOverflowPreemptsSiblingMultiValueWarning() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Add(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+b) + c");

        int positions = 1;
        Block[] inputs = {
            blockFactory.newLongArrayVector(new long[] { Long.MAX_VALUE }, positions).asBlock(),
            blockFactory.newLongArrayVector(new long[] { 1L }, positions).asBlock(),
            multiValueLong(new long[] { 1L, 2L }) };
        runDifferential(fused, unfused, ElementKind.LONG, inputs, positions, "arith-root overflow+mv same position");
    }

    /** The comparison operators, enumerated for the deterministic per-operator coverage test. */
    private enum Cmp {
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE;

        Expression build(Expression left, Expression right) {
            return switch (this) {
                case EQ -> new Equals(Source.EMPTY, left, right);
                case NE -> new NotEquals(Source.EMPTY, left, right);
                case LT -> new LessThan(Source.EMPTY, left, right);
                case LE -> new LessThanOrEqual(Source.EMPTY, left, right);
                case GT -> new GreaterThan(Source.EMPTY, left, right);
                case GE -> new GreaterThanOrEqual(Source.EMPTY, left, right);
            };
        }
    }
}
