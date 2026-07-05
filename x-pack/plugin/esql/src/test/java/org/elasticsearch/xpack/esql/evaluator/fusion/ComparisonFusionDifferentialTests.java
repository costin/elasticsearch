/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionSimd;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
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

    // ---------------------------------------------------------------------------------------------------------------
    // MV-warning PARITY across an ancestor short-circuit (the lookup-join corpus gap). Unlike the subset regressions
    // above, here the fused path MUST emit the SAME multi-value warning the unfused chain does: the unfused chain runs
    // every generated evaluator's loop FULLY, so a leaf's mv warning fires per its own consuming kernel's operand
    // order, INDEPENDENT of whether an ancestor kernel short-circuited the position. The fused block path must match
    // that (per-kernel operand-order gating), not drop the warning when the ancestor short-circuits.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The lookup-join csv-spec shape {@code other2 > id_int + 5000} where {@code other2} is NULL exactly at the
     * positions where {@code id_int} is MULTI-VALUE. The comparison's left operand ({@code other2}) is null, so the
     * whole-tree value short-circuit nulls the position before it reaches the {@code Add} subtree — yet the unfused
     * chain still runs the {@code Add} evaluator fully and emits the "single-value function encountered multi-value"
     * warning for {@code id_int + 5000} (id_int is the Add's operand 0, always inspected). The fused path MUST emit the
     * SAME warning, attributed to the Add's own source, matching the unfused chain — this is the corpus warning gap.
     */
    public void testComparisonNullLeftLeafDoesNotSuppressArithmeticChildMultiValueWarning() {
        FieldAttribute other2 = field("other2", DataType.LONG);
        FieldAttribute idInt = field("id_int", DataType.LONG);
        Layout layout = layout(other2, idInt); // other2 = channel 0 (comparison operand 0), id_int = channel 1
        // Distinct sources so the mv warning's attribution ([id_int + 5000], the Add) can be asserted exactly.
        Source addSource = new Source(3, 18, "id_int + 5000");
        Source gtSource = new Source(2, 3, "other2 > id_int + 5000");
        Expression expr = new GreaterThan(
            gtSource,
            other2,
            new Add(addSource, idInt, new Literal(Source.EMPTY, 5000L, DataType.LONG), EsqlTestUtils.TEST_CFG)
        );

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "other2 > id_int + 5000");
        assertDoesNotFuse(unfused, "other2 > id_int + 5000");

        int positionCount = 6;
        // other2 is NULL exactly at the positions where id_int is multi-valued (p=1, p=4), so the comparison's left
        // operand short-circuits the position to null before the Add subtree is reached on the value path.
        Block other2Block;
        try (var builder = blockFactory.newLongBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (p == 1 || p == 4) {
                    builder.appendNull();
                } else {
                    builder.appendLong(1000L + p);
                }
            }
            other2Block = builder.build();
        }
        Block idIntBlock;
        try (var builder = blockFactory.newLongBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (p == 1 || p == 4) {
                    builder.beginPositionEntry();
                    builder.appendLong(p);
                    builder.appendLong(p + 100);
                    builder.endPositionEntry();
                } else {
                    builder.appendLong(p);
                }
            }
            idIntBlock = builder.build();
        }
        Block[] inputs = { other2Block, idIntBlock };
        DifferentialWarnings warnings = runDifferentialWarnings(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            inputs,
            positionCount,
            "other2(null) > id_int(mv) + 5000"
        );
        // Sanity: the unfused chain emits the mv warning attributed to the Add ([id_int + 5000]).
        assertThat("unfused must emit the mv warning (sanity)", containsWarning(warnings.reference(), MULTI_VALUE_MSG), is(true));
        assertThat("unfused attributes it to the Add (sanity)", containsWarning(warnings.reference(), "id_int + 5000"), is(true));
        // The crux: the fused path must ALSO emit it (parity, not just subset), attributed to the Add's source — even
        // though the comparison's null left operand short-circuited the value path before the Add subtree.
        assertFusedEmitsWarning(warnings.fused(), MULTI_VALUE_MSG, "other2(null) > id_int(mv) + 5000");
        assertThat(
            "fused mv warning must be attributed to the Add source [id_int + 5000] despite the null-left short-circuit; fused="
                + warnings.fused(),
            containsWarning(warnings.fused(), "id_int + 5000"),
            is(true)
        );
    }

    /**
     * Companion proving the fix is per-kernel operand-order gating and NOT a naive "warn for every multi-valued leaf up
     * front" scan: {@code a * b} over longs at a single position where {@code a} (the {@code Mul}'s operand 0) is NULL
     * and {@code b} (operand 1) is MULTI-VALUE. The unfused {@code Mul} evaluator inspects operand 0 first, finds it
     * null and short-circuits WITHIN its own operand order — it never inspects {@code b}, so it emits NO mv warning.
     * The fused path must match: gating {@code b}'s mv check on {@code Mul}'s earlier operand ({@code a}) being present,
     * it must NOT emit the multi-value warning here.
     */
    public void testArithmeticNullOperandBeforeMultiValueOperandSuppressesWarningWithinKernel() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b); // a = channel 0 (Mul operand 0), b = channel 1 (Mul operand 1)
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, lit(0L), EsqlTestUtils.TEST_CFG), b);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+0) * b");
        assertDoesNotFuse(unfused, "(a+0) * b");

        int positionCount = 1;
        Block[] inputs = {
            nullLong(positionCount),                 // a: null (Mul operand 0 is null -> short-circuit before b)
            multiValueLong(new long[] { 1L, 2L }) };  // b: multi-value (never inspected by the unfused Mul)
        DifferentialWarnings warnings = runDifferentialWarnings(
            fused,
            unfused,
            ElementKind.LONG,
            inputs,
            positionCount,
            "(a=null) * (b=mv)"
        );
        // The unfused Mul short-circuits on the null operand 0 and never inspects b, so neither side warns.
        assertThat(
            "unfused must NOT emit the mv warning (operand-0-null short-circuit; sanity)",
            containsWarning(warnings.reference(), MULTI_VALUE_MSG),
            is(false)
        );
        assertThat(
            "fused must NOT warn for b: per-kernel operand-order gating suppresses it (a, Mul's operand 0, is null); fused="
                + warnings.fused(),
            containsWarning(warnings.fused(), MULTI_VALUE_MSG),
            is(false)
        );
    }

    /**
     * Pins the overflow/{@code live} dual on its SUBSET side. When an ancestor short-circuits the value path before a
     * sibling overflow-checked kernel, that sibling body still runs (it feeds a consuming kernel's present gate) but its
     * overflow is caught SILENTLY because {@code live} is already false. So the fused path must produce a correct null
     * with NO overflow warning (a valid subset), while the unfused chain — which runs every evaluator over the whole
     * page — DOES emit the sibling's overflow warning. Guards the {@code live}-gated silent catch in {@code
     * BlockDfsEmitter} against a future edit that drops the null or lets the overflow warning/exception escape.
     */
    public void testSiblingOverflowOffValuePathIsSilentButNullMatches() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        // (a + b) * (c + d): the Mul visits operand 0 (a+b) first; a null a short-circuits the value path (live=false)
        // before operand 1 (c+d) runs and overflows (MAX_VALUE + 1).
        Expression expr = new Mul(
            Source.EMPTY,
            new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG),
            new Add(Source.EMPTY, c, d, EsqlTestUtils.TEST_CFG)
        );
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a+b) * (c+d)");
        assertDoesNotFuse(unfused, "(a+b) * (c+d)");

        int positionCount = 1;
        Block[] inputs = {
            nullLong(positionCount),        // a: null -> (a+b) not present, live=false before (c+d) runs
            singleLong(1L),                 // b: never inspected (a short-circuits first)
            singleLong(Long.MAX_VALUE),     // c
            singleLong(1L) };               // d: c + d overflows
        // runDifferentialWarnings asserts value/null parity AND fused-warnings ⊆ unfused internally.
        DifferentialWarnings warnings = runDifferentialWarnings(
            fused,
            unfused,
            ElementKind.LONG,
            inputs,
            positionCount,
            "(a=null + b) * (MAX + 1 overflow)"
        );
        // Sanity: the unfused chain runs (c+d)'s evaluator fully, so it emits the overflow warning.
        assertThat("unfused must emit the overflow warning (sanity)", containsWarning(warnings.reference(), "long overflow"), is(true));
        // The crux: the fused path caught (c+d)'s overflow silently (live already false), so it emits NO overflow
        // warning — a valid subset — while still producing null at the position (checked by runDifferentialWarnings).
        assertThat(
            "fused must NOT emit the overflow warning off the value path (live-gated silent catch); fused=" + warnings.fused(),
            containsWarning(warnings.fused(), "long overflow"),
            is(false)
        );
    }

    /**
     * B2 end-to-end: with {@code esql.fusion.simd} on and the Vector API available, a bare {@code a OP b} column
     * comparison (a depth-1 tree the scalar planner would skip) fuses via the SIMD lane-compare path. Feeding dense
     * vector-backed columns drives the runtime onto that vector path, and the result must match the unfused evaluator
     * position-by-position across sizes below/at/above one SIMD species. Skipped when the Vector API is unavailable.
     */
    public void testSimdComparisonMatchesUnfused() {
        assumeTrue("Vector API (jdk.incubator.vector) not available", FusionSimd.available());
        FusionSettings.setSimdEnabledForTests(true);
        try {
            runSimdSweep(DataType.LONG);
            runSimdSweep(DataType.INTEGER);
            runSimdSweep(DataType.DOUBLE);
        } finally {
            FusionSettings.setSimdEnabledForTests(false);
        }
    }

    /** Drives all six comparison operators over dense vector-backed columns of {@code type} onto the SIMD path. */
    private void runSimdSweep(DataType type) {
        FieldAttribute a = field("a", type);
        FieldAttribute b = field("b", type);
        Layout layout = layout(a, b);
        Expression[] comparisons = {
            new GreaterThan(Source.EMPTY, a, b),
            new GreaterThanOrEqual(Source.EMPTY, a, b),
            new LessThan(Source.EMPTY, a, b),
            new LessThanOrEqual(Source.EMPTY, a, b),
            new Equals(Source.EMPTY, a, b),
            new NotEquals(Source.EMPTY, a, b) };
        for (Expression expr : comparisons) {
            String label = type + " " + expr.getClass().getSimpleName();
            ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
            assertFuses(fused, label + " (simd)");
            for (int pc : new int[] { 0, 1, 5, 16, 17, 64, 129, 512 + randomIntBetween(0, 512) }) {
                Block a0;
                Block b0;
                if (type == DataType.LONG) {
                    long[] av = new long[pc];
                    long[] bv = new long[pc];
                    for (int p = 0; p < pc; p++) {
                        av[p] = randomLongBetween(-50, 50);
                        bv[p] = randomLongBetween(-50, 50); // overlapping ranges -> both true and false lanes
                    }
                    a0 = blockFactory.newLongArrayVector(av, pc).asBlock();
                    b0 = blockFactory.newLongArrayVector(bv, pc).asBlock();
                } else if (type == DataType.INTEGER) {
                    int[] av = new int[pc];
                    int[] bv = new int[pc];
                    for (int p = 0; p < pc; p++) {
                        av[p] = randomIntBetween(-50, 50);
                        bv[p] = randomIntBetween(-50, 50);
                    }
                    a0 = blockFactory.newIntArrayVector(av, pc).asBlock();
                    b0 = blockFactory.newIntArrayVector(bv, pc).asBlock();
                } else {
                    // Double: mix IEEE specials (NaN/±Inf/-0.0) with normals so the SIMD lane compare's unordered
                    // semantics are exercised against the scalar tail end to end.
                    double[] pool = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, 1.0, -1.0, 2.5 };
                    double[] av = new double[pc];
                    double[] bv = new double[pc];
                    for (int p = 0; p < pc; p++) {
                        av[p] = pool[randomIntBetween(0, pool.length - 1)];
                        bv[p] = pool[randomIntBetween(0, pool.length - 1)];
                    }
                    a0 = blockFactory.newDoubleArrayVector(av, pc).asBlock();
                    b0 = blockFactory.newDoubleArrayVector(bv, pc).asBlock();
                }
                // Dense vector-backed columns so the runtime takes the vector (SIMD) path, not the block path.
                runDifferentialWarnings(fused, unfused, ElementKind.BOOLEAN, new Block[] { a0, b0 }, pc, label + " simd pc=" + pc);
            }
        }
    }

    private static Literal lit(long v) {
        return new Literal(Source.EMPTY, v, DataType.LONG);
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
