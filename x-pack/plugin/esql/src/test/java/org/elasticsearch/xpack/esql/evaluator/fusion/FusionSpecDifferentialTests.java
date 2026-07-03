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
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.BinaryLogic;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.EsqlArithmeticOperation;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EsqlBinaryComparison;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The unified, <b>spec-driven</b> generative differential harness (iter 23, Stage 4). One declarative palette of
 * {@link OpTemplate}s — each an operator's {@code {category, arity, factory lambda, allowed element kinds}} — is expanded
 * by a constrained generator into <b>valid fusable trees only</b>, then swept (seeded, bounded, ~a few hundred trees) as
 * a fused-vs-unfused differential across arithmetic, comparison and logical roots <b>and the currently-fusable
 * cross-category combos</b> that fell between the three old single-category harnesses:
 * <ul>
 *   <li>{@code COMPARISON} over {@code ARITHMETIC} — a comparison root over {@code a+b} (comparison sweep);</li>
 *   <li>{@code LOGICAL} over column {@code COMPARISON} — a 3VL {@code AND}/{@code OR} over column-column comparisons and
 *       nested logicals (logical sweep).</li>
 * </ul>
 * The all-three-category shape (arithmetic under comparison under <em>logical</em>) does <b>not</b> fuse under the S3.1
 * logical scope ({@code FusionPlanner.buildComparisonOperand} requires column-column comparison operands); that boundary
 * is documented and asserted (additive fallback) by {@link #testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}.
 * All shared machinery — breaker-backed {@link org.elasticsearch.compute.data.BlockFactory}, the real kill-switch
 * toggle, the column/{@link Profile} model, {@link #assertResultsEqual}/{@link #assertWarningsSubset}, coverage
 * bookkeeping — comes from {@link FusionDifferentialTestCase}.
 *
 * <h2>The template model (why iters 24/25/26 are drop-in)</h2>
 * A fusable operator is described by exactly ONE data line in {@link #TEMPLATES}: its {@link Category} (which fixes the
 * result type and the operand result type — {@code NUMERIC} for arithmetic/comparison operands, {@code BOOLEAN} for
 * logical operands), its arity, a {@code List<Expression> -> Expression} factory, and the {@link ElementKind}s it
 * accepts. The generator picks templates by category + arity + <b>element applicability</b> (a template is only ever
 * generated for an {@link ElementKind} in its {@code allowedElements}), so:
 * <ul>
 *   <li>iter 24 (constant leaf) adds a leaf producer (a {@code NUMERIC} constant) — one entry;</li>
 *   <li>iter 25 (casts) adds unary {@code NUMERIC->NUMERIC} intermediate kernels — one entry each;</li>
 *   <li>iter 26 (math functions) adds unary/binary {@code double}-domain kernels — one entry each (their
 *       {@code allowedElements} = {@code {DOUBLE}} makes the generator only wire them under double subtrees).</li>
 * </ul>
 * None of them need a new harness; each becomes "add a template ⇒ it's swept".
 */
public class FusionSpecDifferentialTests extends FusionDifferentialTestCase {

    // ---------------------------------------------------------------------------------------------------------------
    // The declarative operator spec. Adding a fusable operator is ONE line here.
    // ---------------------------------------------------------------------------------------------------------------

    /** Which layer of the expression an operator belongs to; fixes its result type and its operands' result type. */
    private enum Category {
        /** {@code NUMERIC} operands -> {@code NUMERIC} result (arithmetic kernels). */
        ARITHMETIC,
        /** {@code NUMERIC} operands -> {@code BOOLEAN} result (comparison kernels). */
        COMPARISON,
        /** {@code BOOLEAN} operands -> {@code BOOLEAN} result (3VL logical connectives). */
        LOGICAL
    }

    /** All numeric primitive element kinds an arithmetic/comparison operand may take. */
    private static final EnumSet<ElementKind> NUMERIC = EnumSet.of(ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE);

    /**
     * {@code allowedElements} for logical connectives. Logical operands are {@code boolean} (comparison/logical
     * subtrees), not numeric leaves, so the generator does NOT element-filter logical templates — this marker keeps
     * {@code allowedElements} non-empty and documents that logical templates are wired by {@link #genLogicalRoot}/
     * {@link #genBoolean} directly rather than through the numeric {@link #randomNumericTemplate} element filter.
     */
    private static final EnumSet<ElementKind> BOOLEAN_OPERANDS = EnumSet.of(ElementKind.BOOLEAN);

    /**
     * A single fusable operator, declaratively. {@code overflowChecked} records whether the operator's kernel is
     * overflow-checked (informational — {@code Neg}/{@code Abs} are not, so a tree built only from them stays
     * overflow-free and reaches the plain-vector fast path). {@code mayDivideByZero} marks {@code Div}, which the
     * generator only wires in when a divide-by-zero profile is desired so the other profiles stay single-mechanism.
     */
    private record OpTemplate(
        String name,
        Category category,
        int arity,
        EnumSet<ElementKind> allowedElements,
        boolean overflowChecked,
        boolean mayDivideByZero,
        Function<List<Expression>, Expression> factory
    ) {
        Expression build(List<Expression> args) {
            assert args.size() == arity : name + " expects " + arity + " args, got " + args.size();
            return factory.apply(args);
        }
    }

    private static OpTemplate op(
        String name,
        Category category,
        int arity,
        EnumSet<ElementKind> allowed,
        boolean overflowChecked,
        boolean mayDivideByZero,
        Function<List<Expression>, Expression> factory
    ) {
        return new OpTemplate(name, category, arity, allowed, overflowChecked, mayDivideByZero, factory);
    }

    /**
     * The palette. Each line is one fusable operator. Future iters extend this list (and nothing else) to get swept:
     * a constant leaf (iter 24), casts as unary NUMERIC->NUMERIC kernels (iter 25), and double-domain math functions
     * (iter 26, {@code allowedElements = {DOUBLE}}).
     */
    private static final List<OpTemplate> TEMPLATES = List.of(
        // --- arithmetic (overflow-checked binary) ---
        op("Add", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Add(Source.EMPTY, a.get(0), a.get(1), EsqlTestUtils.TEST_CFG)),
        op("Sub", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Sub(Source.EMPTY, a.get(0), a.get(1), EsqlTestUtils.TEST_CFG)),
        op("Mul", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Mul(Source.EMPTY, a.get(0), a.get(1))),
        op("Div", Category.ARITHMETIC, 2, NUMERIC, true, true, a -> new Div(Source.EMPTY, a.get(0), a.get(1))),
        // --- arithmetic (NOT overflow-checked unary -> keeps the plain-vector fast path reachable) ---
        op("Neg", Category.ARITHMETIC, 1, NUMERIC, false, false, a -> new Neg(Source.EMPTY, a.get(0))),
        op("Abs", Category.ARITHMETIC, 1, NUMERIC, false, false, a -> new Abs(Source.EMPTY, a.get(0))),
        // --- comparisons (NUMERIC operands -> BOOLEAN) ---
        op("Equals", Category.COMPARISON, 2, NUMERIC, false, false, a -> new Equals(Source.EMPTY, a.get(0), a.get(1))),
        op("NotEquals", Category.COMPARISON, 2, NUMERIC, false, false, a -> new NotEquals(Source.EMPTY, a.get(0), a.get(1))),
        op("LessThan", Category.COMPARISON, 2, NUMERIC, false, false, a -> new LessThan(Source.EMPTY, a.get(0), a.get(1))),
        op("LessThanOrEqual", Category.COMPARISON, 2, NUMERIC, false, false, a -> new LessThanOrEqual(Source.EMPTY, a.get(0), a.get(1))),
        op("GreaterThan", Category.COMPARISON, 2, NUMERIC, false, false, a -> new GreaterThan(Source.EMPTY, a.get(0), a.get(1))),
        op(
            "GreaterThanOrEqual",
            Category.COMPARISON,
            2,
            NUMERIC,
            false,
            false,
            a -> new GreaterThanOrEqual(Source.EMPTY, a.get(0), a.get(1))
        ),
        // --- logical connectives (BOOLEAN operands -> BOOLEAN; allowedElements is the BOOLEAN marker, see above) ---
        op("And", Category.LOGICAL, 2, BOOLEAN_OPERANDS, false, false, a -> new And(Source.EMPTY, a.get(0), a.get(1))),
        op("Or", Category.LOGICAL, 2, BOOLEAN_OPERANDS, false, false, a -> new Or(Source.EMPTY, a.get(0), a.get(1)))
    );

    private static List<OpTemplate> byCategory(Category category) {
        return TEMPLATES.stream().filter(t -> t.category == category).toList();
    }

    private static final List<OpTemplate> COMPARISONS = byCategory(Category.COMPARISON);
    private static final List<OpTemplate> LOGICALS = byCategory(Category.LOGICAL);
    /** Overflow-checked binary arithmetic excluding {@code Div} — the general numeric palette (single-mechanism pages). */
    private static final List<OpTemplate> ARITH_NO_DIV = TEMPLATES.stream()
        .filter(t -> t.category == Category.ARITHMETIC && t.mayDivideByZero == false)
        .toList();
    /** The unary, non-overflow-checked arithmetic ({@code Neg}/{@code Abs}) — used to force overflow-free plain-vector trees. */
    private static final List<OpTemplate> ARITH_UNARY = TEMPLATES.stream()
        .filter(t -> t.category == Category.ARITHMETIC && t.arity == 1)
        .toList();
    /** The full arithmetic palette including {@code Div} — used only by the divide-by-zero sweep. */
    private static final List<OpTemplate> ARITH_WITH_DIV = byCategory(Category.ARITHMETIC);

    private static final List<ElementKind> ELEMENTS = List.of(ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE);

    // ---------------------------------------------------------------------------------------------------------------
    // The unified sweeps
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Arithmetic-rooted trees over each numeric element, cycling the CLEAN / NULLS / MULTIVALUE / OVERFLOW profiles.
     * Subsumes the old {@code FusionDifferentialTests} long/int/double arithmetic sweeps: overflow-checked kernels take
     * the checked-vector path, a multi-value input warns, an overflow warns.
     */
    public void testArithmeticSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        sweep(cov, Category.ARITHMETIC, ARITH_NO_DIV, cycle, false, 150);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("arithmetic trees over overflow-checked kernels => checked-vector", cov.checkedVector, greaterThan(0));
        // The iter-22 VectorUnsafe win, restored as a targeted guard: a DOUBLE overflow-checked tree links NumericUtils
        // on the vector path and so reaches CHECKED_VECTOR (the old testDoubleArithmeticFusedMatchesUnfused pinned this).
        assertThat(
            "a double overflow-checked tree must reach the checked-vector path",
            strategyHits(cov, ElementKind.DOUBLE, VectorStrategy.CHECKED_VECTOR),
            greaterThan(0)
        );
        assertThat("some overflow position must register a warning", cov.overflowWarnings, greaterThan(0));
        assertThat("some multi-value position must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    /**
     * Arithmetic-rooted trees that include {@code Div} over tiny values including {@code 0} divisors. Subsumes the old
     * {@code FusionDifferentialTests} long-division sweep: the {@code / by zero} null + warning is exercised.
     */
    public void testDivisionSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.DIVZERO };
        // Long division: integer divide-by-zero raises the "/ by zero" warning (double division yields Infinity, no
        // warning). The base value generator only makes divisors non-zero for LONG under the {@code division} flag, so
        // the sweep stays LONG-only (matching the original long-division coverage) to keep non-DIVZERO profiles clean.
        sweep(cov, Category.ARITHMETIC, ARITH_WITH_DIV, List.of(ElementKind.LONG), cycle, true, 96);
        // Deterministic guarantee: at least one Div-containing tree meets a zero divisor (don't rely on the random
        // sweep to both pick Div AND hit a 0 divisor). Records into the same coverage so cov.divZeroWarnings is proven.
        runGuaranteedDivByZero(cov);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.DIVZERO);
        assertThat("some divide-by-zero position must register a warning", cov.divZeroWarnings, greaterThan(0));
    }

    /**
     * Builds one explicit {@code Div(Add(a, b), z)} over longs (kernel depth 2, fuses via the checked-vector path) at a
     * page whose divisor column {@code z} contains a {@code 0}, so the integer {@code / by zero} warning is
     * <b>guaranteed</b> — no reliance on the random sweep picking {@code Div} and hitting a zero divisor. Runs the
     * fused-vs-unfused differential and records the div-zero warning into {@code cov}.
     */
    private void runGuaranteedDivByZero(Coverage cov) {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute z = field("z", DataType.LONG);
        Layout layout = layout(a, b, z);
        Expression expr = new Div(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), z);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "Div(Add(a,b), z) guaranteed div-by-zero");
        assertDoesNotFuse(unfused, "Div(Add(a,b), z) kill switch OFF");

        long[] av = { 10, 20, 30, 40 };
        long[] bv = { 1, 2, 3, 4 };
        long[] zv = { 2, 0, 5, 0 }; // zero divisor at positions 1 and 3 => "/ by zero" null + warning in BOTH paths
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock(),
            blockFactory.newLongArrayVector(zv, positions).asBlock() };
        List<String> referenceWarnings = runDifferential(fused, unfused, ElementKind.LONG, inputs, positions, "guaranteed div-by-zero");
        assertThat("the guaranteed Div tree must raise the / by zero warning", containsWarning(referenceWarnings, DIV_ZERO_MSG), is(true));
        recordWarnings(cov, Profile.DIVZERO, referenceWarnings);
    }

    /**
     * A double, {@code Neg}/{@code Abs}-only sweep (arithmetic root AND comparison root) — the only way to build an
     * overflow-free fusable tree, so it exercises the plain-vector fast path. Subsumes the old
     * {@code testDoubleArithmeticFusedMatchesUnfused} CLEAN_UNARY branch and
     * {@code testDoubleComparisonUnaryUsesPlainVector}.
     */
    public void testPlainVectorSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN_UNARY, Profile.NULLS, Profile.MULTIVALUE };
        sweep(cov, Category.ARITHMETIC, ARITH_UNARY, List.of(ElementKind.DOUBLE), cycle, false, 48);
        sweep(cov, Category.COMPARISON, ARITH_UNARY, List.of(ElementKind.DOUBLE), cycle, false, 48);

        assertProfilesCovered(cov, Profile.CLEAN_UNARY, Profile.NULLS, Profile.MULTIVALUE);
        assertThat("a Neg/Abs-only double tree must use the plain-vector fast path", cov.plainVector, greaterThan(0));
        assertThat(
            "a double Neg/Abs-only tree reaches PLAIN_VECTOR (not checked-vector)",
            strategyHits(cov, ElementKind.DOUBLE, VectorStrategy.PLAIN_VECTOR),
            greaterThan(0)
        );
    }

    /**
     * Comparison-rooted trees (numeric arithmetic children -> boolean) over each element, cycling CLEAN / NULLS /
     * MULTIVALUE / OVERFLOW. Subsumes the old {@code ComparisonFusionDifferentialTests} long/int/double sweeps: the
     * result is a {@code boolean} block; overflow-checked children take the checked-vector path; overflow/multi-value
     * warn.
     */
    public void testComparisonSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        sweep(cov, Category.COMPARISON, ARITH_NO_DIV, cycle, false, 120);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("comparison over overflow-checked arithmetic children => checked-vector", cov.checkedVector, greaterThan(0));
        assertThat("an overflow in an arithmetic child must null a position (warning)", cov.overflowWarnings, greaterThan(0));
        assertThat("a multi-value input must register a warning", cov.multiValueWarnings, greaterThan(0));
        assertThat("comparison-over-arithmetic is a cross-category combo (COMPARISON + ARITHMETIC)", cov.crossCategory, greaterThan(0));
    }

    /**
     * Logical-rooted 3VL {@code AND}/{@code OR} trees over column-column comparisons and nested logicals — the
     * <b>cross-category</b> combo (comparison under logical) that the arithmetic and comparison harnesses never
     * touched. Subsumes the {@code LogicalFusionDifferentialTests} flat / left-nested / right-nested sweeps. (A
     * comparison over arithmetic is NOT an accepted logical operand under the S3.1 scope; that boundary is asserted by
     * {@link #testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}.) Asserts the cross-category shape occurred.
     */
    public void testLogicalCrossCategorySpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.NULLS_AND_MULTIVALUE };
        sweep(cov, Category.LOGICAL, ARITH_NO_DIV, cycle, false, 120);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.NULLS_AND_MULTIVALUE);
        assertThat("a multi-value input must register a warning", cov.multiValueWarnings, greaterThan(0));
        assertThat("the sweep must produce cross-category trees (LOGICAL over COMPARISON)", cov.crossCategory, greaterThan(0));
        // A 3VL AND/OR tree is block-path only (no vector fast path); this is the only sweep that exercises NONE.
        assertThat("logical trees must compile the block-only VectorStrategy.NONE", cov.none, greaterThan(0));
    }

    /**
     * The S3.1 boundary, made explicit and honest: a comparison whose child is ARITHMETIC is <b>not</b> an accepted
     * logical operand ({@code FusionPlanner.buildComparisonOperand} requires column-column comparisons), so a tree that
     * puts arithmetic under a comparison under a logical — {@code (a + b > c) AND (d < e)} — does NOT fuse and correctly
     * falls back to the unfused chain (fusion is strictly additive). With the kill switch ON and OFF both sides resolve
     * to the same generated evaluator, so values, nulls and warnings are trivially identical. When a future iter lifts
     * this restriction, flipping the {@code assertDoesNotFuse} to {@code assertFuses} (and folding the shape into the
     * logical sweep's operand generator) is the whole change.
     */
    public void testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        FieldAttribute e = field("e", DataType.LONG);
        Layout layout = layout(a, b, c, d, e);
        // (a + b > c) AND (d < e): the left operand is a comparison over an arithmetic child -> not a fusable logical
        // operand under S3.1, so the whole AND falls back to unfused.
        Expression left = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression right = new LessThan(Source.EMPTY, d, e);
        Expression expr = new And(Source.EMPTY, left, right);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "arithmetic under comparison under logical (S3.1: not a fusable logical operand)");
        assertDoesNotFuse(unfused, "kill switch OFF");

        long[] av = { 1, Long.MAX_VALUE, 3, 4 };
        long[] bv = { 1, 1, 3, 4 }; // a+b overflows at position 1 => left comparison null there in both paths
        long[] cv = { 5, 2, 2, 100 };
        long[] dv = { 1, 2, 9, 4 };
        long[] ev = { 2, 1, 3, 4 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock(),
            blockFactory.newLongArrayVector(cv, positions).asBlock(),
            blockFactory.newLongArrayVector(dv, positions).asBlock(),
            blockFactory.newLongArrayVector(ev, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "arith-under-comp-under-logical additive fallback");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // iter 24: literal/constant leaves (embedded as bytecode constants) — (a+b)>K, a*K+b, a>K1 AND b<K2
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Which constant-leaf shape a sweep iteration builds. Each embeds one or two literal constants (see
     * {@link #constant}) into a depth &ge; 2 tree so it fuses, exercising the three emit paths a constant reaches:
     * checked-vector (arithmetic-rooted / comparison-rooted with an overflow-checked child) and the block-only logical
     * path (its {@code cmpN} comparison helpers embed the constant operand).
     */
    private enum ConstShape {
        /** {@code (a + b) > K} — comparison root over overflow-checked arithmetic; boolean output, checked-vector. */
        ADD_GT_CONST,
        /** {@code a * K + b} — arithmetic root with an embedded multiplier constant; numeric output, checked-vector. */
        MUL_CONST_ADD,
        /** {@code a > K1 AND b < K2} — the common WHERE shape: a 3VL logical over two constant-threshold comparisons. */
        AND_CONST_CMP
    }

    /**
     * The iter-24 constant-leaf sweep: for each numeric element and profile it builds one of the three
     * {@link ConstShape}s with <b>random</b> constant values and asserts the fused body (which embeds each literal as a
     * bytecode constant, never a per-position read) matches the unfused chain in values, null positions and the
     * ratified warning subset. Proves constants reach both the checked-vector and the block-only logical paths, and
     * that overflow / multi-value warnings still fire identically when a column operand is abnormal.
     */
    public void testConstantLeafSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        ConstShape[] shapes = ConstShape.values();
        int iterations = 180;
        int[] referenced = { 0, 1 };
        for (int i = 0; i < iterations; i++) {
            // Independent strides (element%3, profile%4, shape%3; period 36) so every (element × profile × shape) combo
            // is swept — otherwise a shared i%3 would lock each shape to a single element.
            ElementKind element = ELEMENTS.get(i % ELEMENTS.size());
            DataType type = dataType(element);
            int positionCount = positionCountFor(i);
            Profile profile = cycle[(i / ELEMENTS.size()) % cycle.length];
            ConstShape shape = shapes[(i / (ELEMENTS.size() * cycle.length)) % shapes.length];

            FieldAttribute a = field("c0", type);
            FieldAttribute b = field("c1", type);
            Layout layout = layout(a, b);

            // Small magnitudes so non-OVERFLOW profiles stay overflow-free (the OVERFLOW profile seeds MAX/MIN inputs,
            // so a * K or a + b overflows there regardless of K).
            long k1 = randomLongBetween(-5, 5);
            long k2 = randomLongBetween(-5, 5);
            ElementKind outputKind;
            Expression expr;
            switch (shape) {
                case ADD_GT_CONST -> {
                    expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(element, k1));
                    outputKind = ElementKind.BOOLEAN;
                }
                case MUL_CONST_ADD -> {
                    expr = new Add(Source.EMPTY, new Mul(Source.EMPTY, a, constant(element, k1)), b, EsqlTestUtils.TEST_CFG);
                    outputKind = element;
                }
                default -> {
                    expr = new And(
                        Source.EMPTY,
                        new GreaterThan(Source.EMPTY, a, constant(element, k1)),
                        new LessThan(Source.EMPTY, b, constant(element, k2))
                    );
                    outputKind = ElementKind.BOOLEAN;
                }
            }

            ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);

            String ctx = "constShape=" + shape + " element=" + element + " i=" + i + " profile=" + profile + " expr=" + expr;
            FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, ctx);
            assertDoesNotFuse(unfusedFactory, ctx);

            recordStrategy(cov, fused.vectorStrategy(), element);
            recordShape(cov, positionCount);
            recordProfile(cov, profile);

            Block[] inputs = buildPage(element, 2, positionCount, profile, false, referenced);
            List<String> referenceWarnings = runDifferential(fusedFactory, unfusedFactory, outputKind, inputs, positionCount, ctx);
            recordWarnings(cov, profile, referenceWarnings);
        }

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("constant arithmetic/comparison trees must reach the checked-vector fast path", cov.checkedVector, greaterThan(0));
        assertThat("logical-of-constant-comparisons must compile the block-only VectorStrategy.NONE", cov.none, greaterThan(0));
        assertThat("an overflow in a constant arithmetic tree must null a position (warning)", cov.overflowWarnings, greaterThan(0));
        assertThat("a multi-value column operand next to a constant must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    /**
     * The canonical WHERE shape, deterministic: {@code a > 5 AND b < 10} over longs fuses (a 3VL logical whose two
     * comparison operands each embed a constant threshold) and matches the unfused chain position-by-position.
     */
    public void testConstantLogicalDeterministic() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new And(
            Source.EMPTY,
            new GreaterThan(Source.EMPTY, a, constant(ElementKind.LONG, 5)),
            new LessThan(Source.EMPTY, b, constant(ElementKind.LONG, 10))
        );

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, "a>5 AND b<10");
        assertThat("a 3VL logical tree is block-path only", fusedFactory.vectorStrategy(), is(VectorStrategy.NONE));
        assertDoesNotFuse(unfused, "a>5 AND b<10 kill switch OFF");

        // pos0: 3>5 F -> false; pos1: 6>5 T && 12<10 F -> false; pos2: 10>5 T && 9<10 T -> true; pos3: 5>5 F -> false.
        long[] av = { 3, 6, 10, 5 };
        long[] bv = { 8, 12, 9, 10 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "a>5 AND b<10 deterministic");
    }

    /**
     * The depth gate is consistent for constant leaves: a bare {@code a > K} or {@code a + K} is kernel-depth 1 and so
     * does NOT fuse (a lone kernel gains nothing), while {@code (a + b) > K} is depth 2 and DOES. Constants do not count
     * towards depth (they are embedded, not read), so a constant can only ever be the child that keeps a tree at the
     * depth its column subtree already reached.
     */
    public void testConstantLeafDepthGate() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);

        Expression bareCmp = new GreaterThan(Source.EMPTY, a, constant(ElementKind.LONG, 5));
        assertDoesNotFuse(fusedFactory(bareCmp, layout(a)), "bare a>5 (depth 1) must not fuse");
        assertDoesNotFuse(unfusedFactory(bareCmp, layout(a)), "bare a>5 kill switch OFF");

        Expression bareAdd = new Add(Source.EMPTY, a, constant(ElementKind.LONG, 3), EsqlTestUtils.TEST_CFG);
        assertDoesNotFuse(fusedFactory(bareAdd, layout(a)), "bare a+3 (depth 1) must not fuse");

        Expression deep = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 3));
        assertFuses(fusedFactory(deep, layout(a, b)), "(a+b)>3 (depth 2) must fuse");
    }

    /**
     * The <b>non-vacuous</b> distinct-constant proof: two trees that differ only in an embedded constant value must
     * evaluate differently, and each must equal its own unfused reference. The constant is baked into the bytecode, so
     * {@link FusionPlanner#shapeOf} (the {@link FusedClassCache} key) folds the value into the key; if it did not, the
     * two would collide onto one process-cached class and the second query would silently evaluate the first's
     * threshold — which this test would catch, because it builds BOTH through the real {@link org.elasticsearch.xpack
     * .esql.evaluator.EvalMapper} (populating the shared cache with the first, then resolving the second) and asserts
     * the two fused results DIFFER at a crafted position where the two constants force different answers. It also
     * asserts each fused result equals its own unfused reference, so a wrong-valued cached class fails loudly rather
     * than passing a vacuous {@code toString()} comparison. Finally it pins that same-value trees over different columns
     * DO share a shape (column bindings are excluded from the key).
     */
    public void testDifferentConstantValuesEvaluateDistinctly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);

        // (a+b)>5 vs (a+b)>6 over the SAME input. a+b == 6 at pos0 => >5 is true, >6 is false (the crafted divergence);
        // the other positions agree, so only a wrong-constant cached class would make the two results equal at pos0.
        long[] av1 = { 3, 4, 5, 1 };
        long[] bv1 = { 3, 1, 5, 1 }; // a+b = {6, 5, 10, 2}
        assertConstantValueDrivesResult(
            new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 5)),
            new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 6)),
            layout,
            ElementKind.BOOLEAN,
            av1,
            bv1,
            0,
            "(a+b)>5 vs (a+b)>6"
        );

        // a*2+b vs a*3+b over the SAME input: at pos0 (a=1,b=0) the multiplier constant forces 2 vs 3.
        long[] av2 = { 1, 0, 2, -1 };
        long[] bv2 = { 0, 5, 1, 0 }; // a*2+b = {2,5,5,-2}; a*3+b = {3,5,7,-3}
        assertConstantValueDrivesResult(
            new Add(Source.EMPTY, new Mul(Source.EMPTY, a, constant(ElementKind.LONG, 2)), b, EsqlTestUtils.TEST_CFG),
            new Add(Source.EMPTY, new Mul(Source.EMPTY, a, constant(ElementKind.LONG, 3)), b, EsqlTestUtils.TEST_CFG),
            layout,
            ElementKind.LONG,
            av2,
            bv2,
            0,
            "a*2+b vs a*3+b"
        );

        // Same constant, different columns -> same shape (column bindings are excluded from the shape key).
        FusedExpressionEvaluatorFactory five = assertFuses(
            fusedFactory(
                new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 5)),
                layout
            ),
            "(a+b)>5"
        );
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        FusedExpressionEvaluatorFactory fiveCd = assertFuses(
            fusedFactory(
                new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, c, d, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 5)),
                layout(c, d)
            ),
            "(c+d)>5"
        );
        assertThat("same constant over different columns must share a shape", five.toString(), equalTo(fiveCd.toString()));
    }

    /**
     * Builds {@code exprA} and {@code exprB} (which differ only in an embedded constant) BOTH through the real
     * kill-switch-ON path so they resolve against the shared process {@link FusedClassCache}, evaluates each — plus its
     * unfused reference — over ONE page built from {@code av}/{@code bv}, and asserts (i) each fused result equals its
     * own unfused reference and (ii) the two fused results differ at {@code mustDifferAt}. A stale cache that returned
     * A's class for B (constant value dropped from the key) would make the two fused results equal at {@code
     * mustDifferAt} and fail assertion (ii).
     */
    private void assertConstantValueDrivesResult(
        Expression exprA,
        Expression exprB,
        Layout layout,
        ElementKind outputKind,
        long[] av,
        long[] bv,
        int mustDifferAt,
        String ctx
    ) {
        int positions = av.length;
        ExpressionEvaluator.Factory fusedA = fusedFactory(exprA, layout);
        ExpressionEvaluator.Factory fusedB = fusedFactory(exprB, layout);
        ExpressionEvaluator.Factory unfusedA = unfusedFactory(exprA, layout);
        ExpressionEvaluator.Factory unfusedB = unfusedFactory(exprB, layout);
        assertFuses(fusedA, ctx + " A");
        assertFuses(fusedB, ctx + " B");
        assertDoesNotFuse(unfusedA, ctx + " A off");
        assertDoesNotFuse(unfusedB, ctx + " B off");

        ExpressionEvaluator evalA = fusedA.get(driverContext);
        ExpressionEvaluator evalB = fusedB.get(driverContext);
        ExpressionEvaluator refA = unfusedA.get(driverContext);
        ExpressionEvaluator refB = unfusedB.get(driverContext);
        Page page = null;
        Block resultA = null;
        Block resultB = null;
        Block referenceA = null;
        Block referenceB = null;
        try {
            // Both evaluators borrow (never close) the page's blocks, so one shared page feeds all four evals.
            page = new Page(
                positions,
                blockFactory.newLongArrayVector(av, positions).asBlock(),
                blockFactory.newLongArrayVector(bv, positions).asBlock()
            );
            resultA = evalA.eval(page);
            resultB = evalB.eval(page);
            referenceA = refA.eval(page);
            referenceB = refB.eval(page);
            assertResultsEqual(outputKind, resultA, referenceA, positions, ctx + " A fused==unfused");
            assertResultsEqual(outputKind, resultB, referenceB, positions, ctx + " B fused==unfused");
            assertResultsDifferAt(outputKind, resultA, resultB, mustDifferAt, ctx + " A vs B (constant value must matter)");
        } finally {
            Releasables.closeExpectNoException(resultA, resultB, referenceA, referenceB, evalA, evalB, refA, refB);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * A constant leaf on the <b>plain-vector</b> fast path: {@code Abs(a) > K} over doubles has no overflow-checked
     * kernel, so it compiles the plain-vector loop (not checked-vector). Over dense no-null inputs the fused
     * plain-vector body — which pushes {@code K} as an embedded {@code LDC2_W} — must match the unfused chain.
     */
    public void testConstantPlainVectorMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        Layout layout = layout(a);
        Expression expr = new GreaterThan(Source.EMPTY, new Abs(Source.EMPTY, a), constant(ElementKind.DOUBLE, 3));

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, "Abs(a) > 3 (plain vector)");
        assertThat("Abs(a) > K has no overflow-checked kernel -> plain vector", fused.vectorStrategy(), is(VectorStrategy.PLAIN_VECTOR));
        assertDoesNotFuse(unfusedFactory, "Abs(a) > 3 kill switch OFF");

        int positions = 1024 + randomIntBetween(0, 64);
        double[] av = new double[positions];
        for (int p = 0; p < positions; p++) {
            av[p] = randomDoubleBetween(-10, 10, true);
        }
        Block[] inputs = { blockFactory.newDoubleArrayVector(av, positions).asBlock() };
        runDifferential(fusedFactory, unfusedFactory, ElementKind.BOOLEAN, inputs, positions, "Abs(a) > 3 plain vector");
    }

    /**
     * Deterministic constant <b>edge values</b> embedded as bytecode constants must match the unfused chain. For each
     * numeric element it builds {@code (a + b) > K} (comparison root, checked-vector) at the type's boundary constants —
     * {@code MIN_VALUE}, {@code MAX_VALUE}, a negative, {@code 0}, plus fractional / {@code -0.0} for double — proving
     * the exact bits are pushed ({@code LDC2_W}/{@code SIPUSH}/{@code ICONST}/&hellip;), not truncated or defaulted.
     */
    public void testConstantEdgeValuesMatchUnfused() {
        for (long k : new long[] { Long.MIN_VALUE, Long.MAX_VALUE, -7L, 0L, 1L }) {
            assertAddGreaterThanConstantMatches(ElementKind.LONG, constant(ElementKind.LONG, k), "long K=" + k);
        }
        for (int k : new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE, -7, 0, 1 }) {
            assertAddGreaterThanConstantMatches(ElementKind.INT, new Literal(Source.EMPTY, k, DataType.INTEGER), "int K=" + k);
        }
        // Double edges: extremes, a fractional value (LDC2_W of exact bits), and -0.0 (whose bits differ from +0.0).
        for (double k : new double[] { -Double.MAX_VALUE, Double.MAX_VALUE, -2.5, 0.0, -0.0, 3.5 }) {
            assertAddGreaterThanConstantMatches(ElementKind.DOUBLE, new Literal(Source.EMPTY, k, DataType.DOUBLE), "double K=" + k);
        }
    }

    /** Builds {@code (a + b) > K} of {@code element}, asserts it fuses, and differential-checks it over a dense page. */
    private void assertAddGreaterThanConstantMatches(ElementKind element, Literal k, String ctx) {
        FieldAttribute a = field("a", dataType(element));
        FieldAttribute b = field("b", dataType(element));
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), k);
        int positions = 128;
        Block[] inputs = { denseColumn(element, positions, 1), denseColumn(element, positions, 2) };
        runConstantDifferential(expr, layout, ElementKind.BOOLEAN, inputs, positions, "(a+b)>K " + ctx);
    }

    /**
     * A {@code long} tree with constant {@code 5} and a {@code double} tree with constant {@code 5.0} must NOT be
     * conflated: the shape key carries both the element and the constant's element char ({@code #C:J:5} vs
     * {@code #C:D:5.0}), so they resolve to distinct cached classes. Each is also differential-checked against its own
     * unfused reference on its own typed page.
     */
    public void testLongAndDoubleConstantsAreNotConflated() {
        FieldAttribute la = field("a", DataType.LONG);
        FieldAttribute lb = field("b", DataType.LONG);
        Layout longLayout = layout(la, lb);
        Expression longExpr = new GreaterThan(
            Source.EMPTY,
            new Add(Source.EMPTY, la, lb, EsqlTestUtils.TEST_CFG),
            constant(ElementKind.LONG, 5)
        );

        FieldAttribute da = field("a", DataType.DOUBLE);
        FieldAttribute db = field("b", DataType.DOUBLE);
        Layout doubleLayout = layout(da, db);
        Expression doubleExpr = new GreaterThan(
            Source.EMPTY,
            new Add(Source.EMPTY, da, db, EsqlTestUtils.TEST_CFG),
            constant(ElementKind.DOUBLE, 5)
        );

        FusedExpressionEvaluatorFactory longFused = assertFuses(fusedFactory(longExpr, longLayout), "(a+b)>5 long");
        FusedExpressionEvaluatorFactory doubleFused = assertFuses(fusedFactory(doubleExpr, doubleLayout), "(a+b)>5.0 double");
        assertThat(
            "a long tree with constant 5 and a double tree with constant 5.0 must have distinct shapes: "
                + longFused
                + " vs "
                + doubleFused,
            longFused.toString(),
            not(equalTo(doubleFused.toString()))
        );

        int positions = 128;
        runConstantDifferential(
            longExpr,
            longLayout,
            ElementKind.BOOLEAN,
            new Block[] { denseColumn(ElementKind.LONG, positions, 1), denseColumn(ElementKind.LONG, positions, 2) },
            positions,
            "(a+b)>5 long"
        );
        runConstantDifferential(
            doubleExpr,
            doubleLayout,
            ElementKind.BOOLEAN,
            new Block[] { denseColumn(ElementKind.DOUBLE, positions, 1), denseColumn(ElementKind.DOUBLE, positions, 2) },
            positions,
            "(a+b)>5.0 double"
        );
    }

    /**
     * A constant that <b>drives an overflow</b>: {@code (a + b) + K} with {@code K = Long.MAX_VALUE} overflows at every
     * position where {@code a + b > 0}, so those positions null out and register the {@code long overflow} warning in
     * BOTH paths. The embedded {@code LDC2_W} of {@code MAX_VALUE} feeds the checked-vector kernel exactly as the
     * unfused chain's constant operand does.
     */
    public void testConstantDrivenOverflowMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new Add(
            Source.EMPTY,
            new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG),
            constant(ElementKind.LONG, Long.MAX_VALUE),
            EsqlTestUtils.TEST_CFG
        );

        long[] av = { 1, 2, -5, 0 };
        long[] bv = { 1, 3, -5, 0 }; // a+b = {2, 5, -10, 0}; +MAX_VALUE overflows at pos0 and pos1
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        DifferentialWarnings warnings = runConstantDifferential(expr, layout, ElementKind.LONG, inputs, positions, "(a+b)+MAX overflow");
        // This shape has no short-circuit, so parity (not just subset) is required: BOTH paths must emit the overflow
        // warning. Asserting the fused side directly fails if the fused loop silently dropped it.
        assertThat("the unfused reference must register the overflow warning", containsWarning(warnings.reference(), "overflow"), is(true));
        assertFusedEmitsWarning(warnings.fused(), "overflow", "(a+b)+MAX overflow");
    }

    /**
     * A constant that <b>drives a divide-by-zero</b>: {@code (a + b) / K} with the literal divisor {@code K = 0}
     * nulls every position and registers the {@code / by zero} warning in BOTH paths. The embedded {@code LCONST_0}
     * divisor exercises the checked-vector div kernel's ArithmeticException guard identically to the unfused chain.
     */
    public void testConstantDrivenDivideByZeroMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new Div(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), constant(ElementKind.LONG, 0));

        long[] av = { 10, 20, 30, 40 };
        long[] bv = { 1, 2, 3, 4 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        DifferentialWarnings warnings = runConstantDifferential(expr, layout, ElementKind.LONG, inputs, positions, "(a+b)/0 div-zero");
        // Every position divides by the embedded 0 (no short-circuit), so parity is required: BOTH paths must emit the
        // / by zero warning. Asserting the fused side directly fails if the fused loop silently dropped it.
        assertThat(
            "the unfused reference must register the / by zero warning",
            containsWarning(warnings.reference(), DIV_ZERO_MSG),
            is(true)
        );
        assertFusedEmitsWarning(warnings.fused(), DIV_ZERO_MSG, "(a+b)/0 div-zero");
    }

    /**
     * A {@code null} literal in a constant position of an otherwise depth-&ge;2 fusable tree must <b>refuse to fuse</b>
     * (the planner cannot embed a {@code null} as a typed bytecode constant, so it bails and defers to the unfused
     * fallback, where the null literal folds to a null block). Covered in each constant position — arithmetic,
     * comparison and logical — and both kill-switch ON and OFF evaluate identically (all-null / 3VL) via that fallback.
     */
    public void testNullLiteralRefusesToFuse() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Literal nullLiteral = new Literal(Source.EMPTY, null, DataType.LONG);

        // arithmetic: (a + b) * null
        assertNullLiteralFallsBack(
            new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), nullLiteral),
            layout,
            ElementKind.LONG,
            "arithmetic (a+b)*null"
        );
        // comparison root: (a + b) > null
        assertNullLiteralFallsBack(
            new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), nullLiteral),
            layout,
            ElementKind.BOOLEAN,
            "comparison (a+b)>null"
        );
        // logical operand: (a > null) AND (b < 10)
        assertNullLiteralFallsBack(
            new And(
                Source.EMPTY,
                new GreaterThan(Source.EMPTY, a, nullLiteral),
                new LessThan(Source.EMPTY, b, constant(ElementKind.LONG, 10))
            ),
            layout,
            ElementKind.BOOLEAN,
            "logical (a>null) AND (b<10)"
        );
    }

    /** Asserts {@code expr} does NOT fuse (ON and OFF) and that both evaluate identically via the unfused fallback. */
    private void assertNullLiteralFallsBack(Expression expr, Layout layout, ElementKind outputKind, String ctx) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "null-literal " + ctx + " must refuse to fuse");
        assertDoesNotFuse(unfused, "null-literal " + ctx + " kill switch OFF");
        int positions = 64;
        Block[] inputs = { denseColumn(ElementKind.LONG, positions, 1), denseColumn(ElementKind.LONG, positions, 2) };
        runDifferential(fused, unfused, outputKind, inputs, positions, "null-literal " + ctx);
    }

    /**
     * Asserts {@code expr} fuses with the kill switch ON, does not with it OFF, and that the fused body (embedding each
     * literal as a bytecode constant) matches the unfused chain over the given page. Returns BOTH the fused and unfused
     * warning lists so a caller can assert an expected warning (overflow / div-zero) fired on the fused side too
     * (parity), not only on the unfused reference.
     */
    private DifferentialWarnings runConstantDifferential(
        Expression expr,
        Layout layout,
        ElementKind outputKind,
        Block[] inputs,
        int positions,
        String ctx
    ) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, ctx);
        assertDoesNotFuse(unfused, ctx + " kill switch OFF");
        return runDifferentialWarnings(fused, unfused, outputKind, inputs, positions, ctx);
    }

    /** Builds a numeric literal of the given {@code element} carrying {@code raw} (widened/narrowed to the element). */
    private static Literal constant(ElementKind element, long raw) {
        return switch (element) {
            case LONG -> new Literal(Source.EMPTY, raw, DataType.LONG);
            case INT -> new Literal(Source.EMPTY, (int) raw, DataType.INTEGER);
            case DOUBLE -> new Literal(Source.EMPTY, (double) raw, DataType.DOUBLE);
            case BOOLEAN -> throw new AssertionError("boolean constant leaves are not generated");
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core sweep: generate a valid fusable tree, build both evaluators through the real kill switch, differential-compare
    // ---------------------------------------------------------------------------------------------------------------

    private void sweep(Coverage cov, Category root, List<OpTemplate> numericPalette, Profile[] cycle, boolean division, int iterations) {
        sweep(cov, root, numericPalette, ELEMENTS, cycle, division, iterations);
    }

    private void sweep(
        Coverage cov,
        Category root,
        List<OpTemplate> numericPalette,
        List<ElementKind> elements,
        Profile[] cycle,
        boolean division,
        int iterations
    ) {
        for (int i = 0; i < iterations; i++) {
            ElementKind element = elements.get(i % elements.size());
            DataType type = dataType(element);
            int positionCount = positionCountFor(i);
            Profile profile = cycle[i % cycle.length];

            int columns = columnsFor(root);
            FieldAttribute[] cols = new FieldAttribute[columns];
            for (int c = 0; c < columns; c++) {
                cols[c] = field("c" + c, type);
            }
            Layout layout = layout(cols);

            Set<Integer> usedSet = new TreeSet<>();
            Expression expr = switch (root) {
                case ARITHMETIC -> genNumeric(numericPalette, 2, randomIntBetween(2, 4), element, cols, usedSet);
                case COMPARISON -> genComparisonRoot(numericPalette, element, cols, usedSet);
                case LOGICAL -> genLogicalRoot(randomIntBetween(2, 3), element, cols, usedSet);
            };
            int[] referenced = toArray(usedSet);

            ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);

            String ctx = "root=" + root + " element=" + element + " i=" + i + " profile=" + profile + " expr=" + expr;
            FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, ctx);
            assertDoesNotFuse(unfusedFactory, ctx);

            recordStrategy(cov, fused.vectorStrategy(), element);
            recordShape(cov, positionCount);
            recordProfile(cov, profile);
            if (isCrossCategory(expr)) {
                cov.crossCategory++;
            }

            ElementKind outputKind = root == Category.ARITHMETIC ? element : ElementKind.BOOLEAN;
            Block[] inputs = buildPage(element, columns, positionCount, profile, division, referenced);
            List<String> referenceWarnings = runDifferential(fusedFactory, unfusedFactory, outputKind, inputs, positionCount, ctx);
            recordWarnings(cov, profile, referenceWarnings);
        }
    }

    private static int columnsFor(Category root) {
        return switch (root) {
            case ARITHMETIC -> randomIntBetween(1, 4);
            case COMPARISON -> randomIntBetween(2, 4);
            case LOGICAL -> randomIntBetween(4, 6);
        };
    }

    private static DataType dataType(ElementKind element) {
        return switch (element) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> throw new AssertionError("boolean leaves are not generated");
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Constrained generators — produce VALID fusable trees only (kernel depth >= 2)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Picks a template from {@code palette} that supports {@code element} (i.e. {@code element} is in its
     * {@code allowedElements}). This is the seam that makes the template model safe for iters 24-26: a future
     * double-only template ({@code allowedElements = {DOUBLE}}) is only ever generated under a double subtree, never
     * wired with int/long args. Fails loudly if no template in the palette supports the element.
     */
    private OpTemplate randomNumericTemplate(List<OpTemplate> palette, ElementKind element) {
        List<OpTemplate> eligible = palette.stream().filter(t -> t.allowedElements.contains(element)).toList();
        assertThat("no template in palette " + palette + " supports element " + element, eligible.isEmpty(), is(false));
        return randomFrom(eligible);
    }

    /**
     * A numeric tree whose deepest path has kernel depth in {@code [minKernel, maxDepth]} (so with {@code minKernel>=1}
     * the root is a kernel, and with {@code minKernel>=2} the whole tree fuses on its own). Every operator is selected
     * with {@link #randomNumericTemplate} so it supports {@code element}; leaves are homogeneous {@link FieldAttribute}s
     * of {@code element}'s type; referenced column indices are collected into {@code used}.
     */
    private Expression genNumeric(
        List<OpTemplate> palette,
        int minKernel,
        int maxDepth,
        ElementKind element,
        FieldAttribute[] cols,
        Set<Integer> used
    ) {
        if (maxDepth <= 0 || (minKernel <= 0 && randomBoolean())) {
            int idx = randomInt(cols.length - 1);
            used.add(idx);
            return cols[idx];
        }
        OpTemplate opTemplate = randomNumericTemplate(palette, element);
        if (opTemplate.arity == 1) {
            return opTemplate.build(List.of(genNumeric(palette, minKernel - 1, maxDepth - 1, element, cols, used)));
        }
        // Force one child to carry the remaining depth; the other may be shallower. Randomize which side is deep so a
        // non-commutative op (Sub/Div) exercises both operand orders.
        Expression deep = genNumeric(palette, minKernel - 1, maxDepth - 1, element, cols, used);
        Expression shallow = genNumeric(palette, 0, maxDepth - 1, element, cols, used);
        return randomBoolean() ? opTemplate.build(List.of(deep, shallow)) : opTemplate.build(List.of(shallow, deep));
    }

    /**
     * A comparison <b>root</b> over two numeric subtrees (arithmetic under comparison). At least one child is guaranteed
     * to be a numeric kernel (depth &ge; 1), so the whole tree has kernel depth &ge; 2 and fuses. This shape is only
     * fusable at the ROOT: a comparison over arithmetic is <b>not</b> an accepted logical operand (see
     * {@link #genColumnComparison} and {@code FusionPlanner.buildComparisonOperand} — the S3.1 logical scope).
     */
    private Expression genComparisonRoot(List<OpTemplate> numericPalette, ElementKind element, FieldAttribute[] cols, Set<Integer> used) {
        OpTemplate cmp = randomNumericTemplate(COMPARISONS, element);
        Expression deep = genNumeric(numericPalette, 1, randomIntBetween(1, 3), element, cols, used);
        Expression shallow = genNumeric(numericPalette, 0, randomIntBetween(0, 2), element, cols, used);
        return randomBoolean() ? cmp.build(List.of(deep, shallow)) : cmp.build(List.of(shallow, deep));
    }

    /**
     * A bare column-column comparison ({@code a <cmp> b}). This is the ONLY comparison shape currently accepted as a
     * logical operand: {@code FusionPlanner.buildComparisonOperand} requires each comparison child to be a column
     * {@link org.elasticsearch.xpack.esql.core.expression.Attribute} (no arithmetic under a comparison under a logical
     * in the S3.1 scope). Depth-1 on its own (would not fuse alone), but fusable as a logical operand.
     */
    private Expression genColumnComparison(ElementKind element, FieldAttribute[] cols, Set<Integer> used) {
        OpTemplate cmp = randomNumericTemplate(COMPARISONS, element);
        int i1 = randomInt(cols.length - 1);
        int i2 = randomInt(cols.length - 1);
        used.add(i1);
        used.add(i2);
        return cmp.build(List.of(cols[i1], cols[i2]));
    }

    /**
     * A boolean subtree used as a logical operand: either a column-column comparison or a nested logical. Logical
     * connectives are picked directly from {@link #LOGICALS} (their operands are boolean, so they are not element-
     * filtered); the numeric {@code element} is threaded down to the leaf column comparisons.
     */
    private Expression genBoolean(int maxDepth, ElementKind element, FieldAttribute[] cols, Set<Integer> used) {
        if (maxDepth <= 1 || randomBoolean()) {
            return genColumnComparison(element, cols, used);
        }
        OpTemplate logical = randomFrom(LOGICALS);
        Expression left = genBoolean(maxDepth - 1, element, cols, used);
        Expression right = genBoolean(maxDepth - 1, element, cols, used);
        return logical.build(List.of(left, right));
    }

    /** A logical root: a connective over two boolean subtrees (column comparisons / nested logicals), so depth &ge; 2. */
    private Expression genLogicalRoot(int maxDepth, ElementKind element, FieldAttribute[] cols, Set<Integer> used) {
        OpTemplate logical = randomFrom(LOGICALS);
        Expression left = genBoolean(maxDepth - 1, element, cols, used);
        Expression right = genBoolean(maxDepth - 1, element, cols, used);
        return logical.build(List.of(left, right));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cross-category structural detection: a tree spanning >= 2 categories (the combos split across the old harnesses)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * True if the tree spans at least two operator categories — the currently-fusable cross-category combos that fell
     * between the three old single-category harnesses: {@code COMPARISON} over {@code ARITHMETIC} (comparison root over
     * {@code a+b}), and {@code LOGICAL} over {@code COMPARISON} (a 3VL connective over column comparisons). A tree
     * spanning all three ({@code ARITHMETIC} under {@code COMPARISON} under {@code LOGICAL}) is NOT fusable under the
     * S3.1 logical scope and is covered separately by {@link #testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}.
     */
    private static boolean isCrossCategory(Expression e) {
        EnumSet<Category> categories = EnumSet.noneOf(Category.class);
        collectCategories(e, categories);
        return categories.size() >= 2;
    }

    private static void collectCategories(Expression e, EnumSet<Category> categories) {
        if (e instanceof BinaryLogic) {
            categories.add(Category.LOGICAL);
        } else if (e instanceof EsqlBinaryComparison) {
            categories.add(Category.COMPARISON);
        } else if (e instanceof EsqlArithmeticOperation || e instanceof Neg || e instanceof Abs) {
            categories.add(Category.ARITHMETIC);
        }
        for (Expression child : e.children()) {
            collectCategories(child, categories);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A tiny sanity test that the template model itself is well-formed (so a future one-line addition can't silently
    // desync category/arity from the generator's expectations).
    // ---------------------------------------------------------------------------------------------------------------

    public void testTemplatePaletteIsWellFormed() {
        assertThat("arithmetic palette (no div) is non-empty", ARITH_NO_DIV.isEmpty(), is(false));
        assertThat("unary arithmetic palette is non-empty", ARITH_UNARY.isEmpty(), is(false));
        assertThat("comparison palette is non-empty", COMPARISONS.isEmpty(), is(false));
        assertThat("logical palette is non-empty", LOGICALS.isEmpty(), is(false));
        List<String> problems = new ArrayList<>();
        for (OpTemplate t : TEMPLATES) {
            if (t.arity < 1 || t.arity > 2) {
                problems.add(t.name + " has unsupported arity " + t.arity);
            }
            if (t.allowedElements.isEmpty()) {
                problems.add(t.name + " has no allowed element kinds");
            }
            // Every template's factory must accept exactly its declared arity of column args without throwing.
            FieldAttribute a = field("a", DataType.LONG);
            FieldAttribute b = field("b", DataType.LONG);
            List<Expression> args = t.arity == 1 ? List.of(a) : List.of(a, b);
            Expression built = t.build(args);
            assertThat(t.name + " factory must build a non-null Expression", built, instanceOf(Expression.class));
            // The built expression MUST match its declared category, so a miscategorized one-line addition (which the
            // generator would then wire into the wrong layer) fails loudly here rather than producing an invalid tree.
            boolean categoryOk = switch (t.category) {
                case ARITHMETIC -> built instanceof EsqlArithmeticOperation || built instanceof Neg || built instanceof Abs;
                case COMPARISON -> built instanceof EsqlBinaryComparison;
                case LOGICAL -> built instanceof BinaryLogic;
            };
            if (categoryOk == false) {
                problems.add(t.name + " built a " + built.getClass().getSimpleName() + " that does not match category " + t.category);
            }
        }
        assertThat("template palette problems: " + problems, problems.isEmpty(), is(true));
    }
}
