/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.IntBlock;
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
import org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cos;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Hypot;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Log;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Pow;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Sin;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Sqrt;
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
import java.util.stream.Stream;

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
        LOGICAL,
        /**
         * {@code DOUBLE} operands -> {@code DOUBLE} result (iter 26 scalar math functions, e.g. {@code sin}/{@code sqrt}/
         * {@code pow}). Like {@link #ARITHMETIC} these are numeric-in / numeric-out kernels, but they are a distinct
         * {@link org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction} family (not an
         * {@code EsqlArithmeticOperation}) whose {@code allowedElements} is {@code {DOUBLE}} only. They are NOT a sweep
         * root of their own — they are wired UNDER the {@link #ARITHMETIC}/{@link #COMPARISON} roots via the
         * {@link #ARITH_AND_MATH} palette, so a double tree can mix {@code a + sqrt(b)}, {@code pow(a,b) > c}, etc.
         */
        MATH
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
     * {@code allowedElements} for the iter-26 scalar math functions. Their kernels are {@code (D)D}/{@code (DD)D}
     * java.base double kernels, so the generator only ever wires them under a {@code double} subtree — an {@code int}/
     * {@code long} column feeding one is bridged by the same {@code Cast} the unfused chain inserts (proved by
     * {@link #testSinOverCastIntComposesCastAndFunction}), but the template itself is only generated for {@code DOUBLE}.
     */
    private static final EnumSet<ElementKind> DOUBLE_ONLY = EnumSet.of(ElementKind.DOUBLE);

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
        op("Or", Category.LOGICAL, 2, BOOLEAN_OPERANDS, false, false, a -> new Or(Source.EMPTY, a.get(0), a.get(1))),
        // --- iter 26 scalar math functions (DOUBLE-only; wired under the ARITHMETIC/COMPARISON roots) ---
        // Sin: a total (D)D java.base kernel (not overflow-checked) -> keeps the plain-vector fast path reachable.
        op("Sin", Category.MATH, 1, DOUBLE_ONLY, false, false, a -> new Sin(Source.EMPTY, a.get(0))),
        // Sqrt: (D)D, forwards ArithmeticException on a negative input -> overflow-checked (checked-vector), nulls+warns.
        op("Sqrt", Category.MATH, 1, DOUBLE_ONLY, true, false, a -> new Sqrt(Source.EMPTY, a.get(0))),
        // Log (natural, unary): (D)D, forwards ArithmeticException on a non-positive input -> overflow-checked.
        op("Log", Category.MATH, 1, DOUBLE_ONLY, true, false, a -> new Log(Source.EMPTY, a.get(0), null)),
        // Hypot: a total (DD)D java.base kernel (not overflow-checked).
        op("Hypot", Category.MATH, 2, DOUBLE_ONLY, false, false, a -> new Hypot(Source.EMPTY, a.get(0), a.get(1))),
        // Pow: (DD)D via NumericUtils.asFiniteNumber, which throws ArithmeticException on overflow -> overflow-checked.
        op("Pow", Category.MATH, 2, DOUBLE_ONLY, true, false, a -> new Pow(Source.EMPTY, a.get(0), a.get(1)))
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
    /** The iter-26 scalar math functions ({@code Sin}/{@code Sqrt}/{@code Log}/{@code Hypot}/{@code Pow}). */
    private static final List<OpTemplate> MATH_FUNCS = byCategory(Category.MATH);
    /**
     * The palette for the {@link #testMathFunctionSpecSweepMatchesUnfused} double sweep: overflow-checked binary
     * arithmetic ({@code Add}/{@code Sub}/{@code Mul}, no {@code Div}) plus the math functions, so the generator freely
     * mixes them into double trees like {@code a + sqrt(b)}, {@code sin(a) * c}, {@code pow(a, b) > c}.
     */
    private static final List<OpTemplate> ARITH_AND_MATH = Stream.concat(ARITH_NO_DIV.stream(), MATH_FUNCS.stream()).toList();

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
     * The Stage-6 adaptive-path corpus signal: the same generated arithmetic and comparison trees, but served through
     * the {@link AdaptiveFusionEvaluatorFactory} (fusion ON, {@code min_rows == 1}) so every non-empty page drives the
     * runtime unfused→fused switch. Proves the adaptive path is value/null-identical to the unfused chain (warnings a
     * subset) across the CLEAN / NULLS / MULTIVALUE / OVERFLOW profiles and the shape matrix — the tiered-compilation
     * analogue of {@link #testArithmeticSpecSweepMatchesUnfused}. Asserts switches actually fired so it is not vacuous.
     */
    public void testAdaptiveSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        long arithmeticSwitches = adaptiveSweep(cov, Category.ARITHMETIC, ARITH_NO_DIV, cycle, 90);
        long comparisonSwitches = adaptiveSweep(cov, Category.COMPARISON, ARITH_NO_DIV, cycle, 90);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("the adaptive arithmetic sweep must actually switch to fused at least once", arithmeticSwitches, greaterThan(0L));
        assertThat("the adaptive comparison sweep must actually switch to fused at least once", comparisonSwitches, greaterThan(0L));
        assertThat("an overflow position must still null + warn on the adaptive path", cov.overflowWarnings, greaterThan(0));
        assertThat("a multi-value input must still warn on the adaptive path", cov.multiValueWarnings, greaterThan(0));
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

    /**
     * END-TO-END over-budget fallback (criterion #6), through the REAL plan-time path
     * ({@link FusionPlanner#maybeFuse} → {@link FusedExpressionEvaluatorFactory#tryCreate} → the {@link
     * org.elasticsearch.compute.operator.fusion.Stitcher}): a large flat {@code AND} of column-column comparisons is a
     * fusable logical shape (S3.1), but deep enough that the ENFORCED logical block-loop body exceeds the
     * {@code MAX_FUSED_METHOD_BYTECODES} inlining budget, so the stitch is refused and the planner returns the
     * <b>unfused</b> factory unchanged (NOT a {@link FusedExpressionEvaluatorFactory}). A <em>small</em> version of the
     * SAME shape fuses, proving it is the SIZE (the budget guard) that forces the fallback here — not a planner-scope
     * rejection — and that the over-budget shape still evaluates correctly via the fallback chain.
     */
    public void testOverBudgetLogicalRefusesToFuseAndFallsBackEndToEnd() {
        int columns = 4;
        FieldAttribute[] cols = new FieldAttribute[columns];
        for (int c = 0; c < columns; c++) {
            cols[c] = field("c" + c, DataType.LONG);
        }
        Layout layout = layout(cols);

        // A small AND of the SAME shape (2 column comparisons) fuses — the shape is fusable in principle.
        Expression small = andOfColumnComparisons(2, cols);
        assertFuses(fusedFactory(small, layout), "small AND (2 comparisons) must fuse");

        // A large AND (many comparisons) makes the enforced logical block-loop body exceed the inlining budget, so the
        // real plan-time path refuses to fuse and returns the UNFUSED factory (the criterion #6 fallback).
        Expression large = andOfColumnComparisons(64, cols);
        ExpressionEvaluator.Factory fusedLarge = fusedFactory(large, layout);
        ExpressionEvaluator.Factory unfusedLarge = unfusedFactory(large, layout);
        assertDoesNotFuse(fusedLarge, "over-budget large AND must fall back to the unfused chain (not a FusedExpressionEvaluatorFactory)");
        assertDoesNotFuse(unfusedLarge, "over-budget large AND kill switch OFF");

        // And the over-budget shape still evaluates correctly through the fallback chain (produces a boolean block, no
        // crash), matching the explicitly-unfused reference position-by-position.
        int positions = 64;
        Block[] inputs = new Block[columns];
        for (int c = 0; c < columns; c++) {
            inputs[c] = denseColumn(ElementKind.LONG, positions, c + 1);
        }
        runDifferential(fusedLarge, unfusedLarge, ElementKind.BOOLEAN, inputs, positions, "over-budget AND fallback evaluates");
    }

    /** A left-nested {@code AND} of {@code comparisons} column-column comparisons over {@code cols} (a fusable S3.1 logical shape). */
    private static Expression andOfColumnComparisons(int comparisons, FieldAttribute[] cols) {
        Expression tree = columnComparison(cols, 0);
        for (int i = 1; i < comparisons; i++) {
            tree = new And(Source.EMPTY, tree, columnComparison(cols, i));
        }
        return tree;
    }

    /** The {@code i}-th column-column comparison, cycling operator and (distinct) operand columns for variety. */
    private static Expression columnComparison(FieldAttribute[] cols, int i) {
        FieldAttribute left = cols[i % cols.length];
        FieldAttribute right = cols[(i + 1) % cols.length]; // (i+1)%len != i%len for len >= 2, so operands differ
        return (i % 2 == 0) ? new GreaterThan(Source.EMPTY, left, right) : new LessThan(Source.EMPTY, left, right);
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
            case BYTES_REF -> throw new AssertionError("BytesRef constant leaves are not generated");
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // iter 25: mixed-type / cast fusion. ESQL bridges numeric operands of different types to a {@code commonType} with
    // an explicit widening {@link org.elasticsearch.xpack.esql.expression.function.scalar.math.Cast} (int->long,
    // int->double, long->double). The planner fuses those {@code @Fusable} cast kernels as INTERMEDIATE nodes (whose
    // return element differs from their argument element), so a mixed-type tree fuses end-to-end rather than falling
    // back — e.g. {@code doubleCol + intCol} is {@code Add(doubleCol, castIntToDouble(intCol))} and fuses whole.
    // ---------------------------------------------------------------------------------------------------------------

    /** The mixed-type shapes swept below; each mixes &ge; 2 numeric element kinds bridged by a synthetic widening cast. */
    private enum MixedShape {
        /** {@code doubleCol + intCol} -> {@code Add(double, castIntToDouble(int))}; output DOUBLE. */
        DOUBLE_ADD_INT,
        /** {@code longCol * intCol} -> {@code Mul(long, castIntToLong(int))}; output LONG. */
        LONG_MUL_INT,
        /** {@code doubleCol - longCol} -> {@code Sub(double, castLongToDouble(long))}; output DOUBLE. */
        DOUBLE_SUB_LONG,
        /** {@code (a_int * b_int) + c_long} -> {@code Add(castIntToLong(Mul(int,int)), long)} — a cast wrapping a KERNEL. */
        INTMUL_ADD_LONG,
        /**
         * {@code ((a_int * b_int) + c_long) + d_double} -> {@code Add(castLongToDouble(Add(castIntToLong(Mul(int,int)),
         * long)), double)} — a <b>nested</b> int→long→double widening: the inner {@code Mul}'s {@code int} is cast to
         * {@code long} for the inner {@code Add}, whose {@code long} is then cast to {@code double} for the outer
         * {@code Add}. Two cast kernels at different depths, exercising per-node typing bridging stacked widths.
         */
        NESTED_INT_LONG_DOUBLE,
        /** {@code intCol > longCol} -> {@code GreaterThan(castIntToLong(int), long)} — a cast-bridged COMPARISON root; output BOOLEAN. */
        INT_GT_LONG
    }

    /** The physical column element kinds a {@link MixedShape} reads, in channel order. */
    private static ElementKind[] mixedColumnKinds(MixedShape shape) {
        return switch (shape) {
            case DOUBLE_ADD_INT -> new ElementKind[] { ElementKind.DOUBLE, ElementKind.INT };
            case LONG_MUL_INT -> new ElementKind[] { ElementKind.LONG, ElementKind.INT };
            case DOUBLE_SUB_LONG -> new ElementKind[] { ElementKind.DOUBLE, ElementKind.LONG };
            case INTMUL_ADD_LONG -> new ElementKind[] { ElementKind.INT, ElementKind.INT, ElementKind.LONG };
            case NESTED_INT_LONG_DOUBLE -> new ElementKind[] { ElementKind.INT, ElementKind.INT, ElementKind.LONG, ElementKind.DOUBLE };
            case INT_GT_LONG -> new ElementKind[] { ElementKind.INT, ElementKind.LONG };
        };
    }

    /** The {@code commonType} element kind of a {@link MixedShape}'s root (its result element). */
    private static ElementKind mixedOutputKind(MixedShape shape) {
        return switch (shape) {
            case DOUBLE_ADD_INT, DOUBLE_SUB_LONG, NESTED_INT_LONG_DOUBLE -> ElementKind.DOUBLE;
            case LONG_MUL_INT, INTMUL_ADD_LONG -> ElementKind.LONG;
            case INT_GT_LONG -> ElementKind.BOOLEAN;
        };
    }

    /** Builds the {@link MixedShape}'s expression over {@code cols} (typed per {@link #mixedColumnKinds}). */
    private static Expression mixedExpr(MixedShape shape, FieldAttribute[] cols) {
        return switch (shape) {
            case DOUBLE_ADD_INT -> new Add(Source.EMPTY, cols[0], cols[1], EsqlTestUtils.TEST_CFG);
            case LONG_MUL_INT -> new Mul(Source.EMPTY, cols[0], cols[1]);
            case DOUBLE_SUB_LONG -> new Sub(Source.EMPTY, cols[0], cols[1], EsqlTestUtils.TEST_CFG);
            case INTMUL_ADD_LONG -> new Add(Source.EMPTY, new Mul(Source.EMPTY, cols[0], cols[1]), cols[2], EsqlTestUtils.TEST_CFG);
            // ((a_int * b_int) + c_long) + d_double: int→long (inner Add) then long→double (outer Add), two nested casts.
            case NESTED_INT_LONG_DOUBLE -> new Add(
                Source.EMPTY,
                new Add(Source.EMPTY, new Mul(Source.EMPTY, cols[0], cols[1]), cols[2], EsqlTestUtils.TEST_CFG),
                cols[3],
                EsqlTestUtils.TEST_CFG
            );
            case INT_GT_LONG -> new GreaterThan(Source.EMPTY, cols[0], cols[1]);
        };
    }

    /**
     * The iter-25 mixed-type sweep: for each {@link MixedShape} and profile it builds a tree whose operands span two or
     * three numeric element kinds (so ESQL's {@code commonType} inserts a widening cast the planner must fuse), asserts
     * the tree FUSES (the core iter-25 unlock — a cast-bridged tree must not silently fall back to the unfused chain),
     * and differential-checks values + null positions + the ratified warning subset against the unfused chain. Proves
     * the cast kernel is fused both wrapping a leaf ({@code intCol -> double}) and wrapping a whole kernel
     * ({@code Mul(int,int) -> long}), across the vector (CLEAN), block (NULLS/MULTIVALUE) and overflow paths.
     */
    public void testMixedTypeCastSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        MixedShape[] shapes = MixedShape.values();
        int iterations = 160;
        int fusedCount = 0;
        for (int i = 0; i < iterations; i++) {
            // Independent strides (shape%|shapes|, profile%4) so every (shape × profile) combo is swept.
            MixedShape shape = shapes[i % shapes.length];
            Profile profile = cycle[(i / shapes.length) % cycle.length];
            int positionCount = positionCountFor(i);

            ElementKind[] kinds = mixedColumnKinds(shape);
            FieldAttribute[] cols = new FieldAttribute[kinds.length];
            for (int c = 0; c < kinds.length; c++) {
                cols[c] = field("c" + c, dataType(kinds[c]));
            }
            Layout layout = layout(cols);
            Expression expr = mixedExpr(shape, cols);
            ElementKind outputKind = mixedOutputKind(shape);

            ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
            String ctx = "mixedShape=" + shape + " i=" + i + " profile=" + profile + " expr=" + expr;
            FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, ctx);
            assertDoesNotFuse(unfusedFactory, ctx);
            fusedCount++;

            recordStrategy(cov, fused.vectorStrategy(), outputKind);
            recordShape(cov, positionCount);
            recordProfile(cov, profile);

            int[] referenced = new int[kinds.length];
            for (int c = 0; c < kinds.length; c++) {
                referenced[c] = c;
            }
            Block[] inputs = buildMixedPage(kinds, positionCount, profile, referenced);
            List<String> referenceWarnings = runDifferential(fusedFactory, unfusedFactory, outputKind, inputs, positionCount, ctx);
            recordWarnings(cov, profile, referenceWarnings);
        }

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("every mixed-type (cast-bridged) tree must fuse, not silently fall back", fusedCount, equalTo(iterations));
        assertThat("a mixed-type overflow-checked tree must reach the checked-vector fast path", cov.checkedVector, greaterThan(0));
        assertThat("some overflow position in a mixed-type tree must register a warning", cov.overflowWarnings, greaterThan(0));
        assertThat("some multi-value position in a mixed-type tree must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    /**
     * The canonical mixed-type case, deterministic: {@code doubleCol + intCol} fuses (as {@code Add(doubleCol,
     * castIntToDouble(intCol))}, the {@code ToDouble} cast fused as an intermediate node) and matches the unfused chain
     * position-by-position over a dense page — including the {@code Integer.MAX_VALUE} row, whose exact {@code (double)}
     * widening the fused body must reproduce.
     */
    public void testMixedTypeDoubleIntFusesDeterministic() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.INTEGER);
        Layout layout = layout(a, b);
        Expression expr = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "doubleCol + intCol must fuse (cast-bridged double+int)");
        assertDoesNotFuse(unfused, "doubleCol + intCol kill switch OFF");

        double[] av = { 1.5, 2.0, -3.25, 0.0, 1e9 };
        int[] bv = { 2, -1, 4, 0, Integer.MAX_VALUE };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            blockFactory.newIntArrayVector(bv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.DOUBLE, inputs, positions, "doubleCol + intCol deterministic");
    }

    /**
     * A cast wrapping a whole KERNEL, deterministic: {@code (a_int * b_int) + c_long} is
     * {@code Add(castIntToLong(Mul(int,int)), longCol)} — the {@code Mul} sub-kernel's {@code int} result is widened to
     * {@code long} by a fused cast before the {@code Add}. Proves the per-node typing bridges an intermediate node
     * (not just a leaf) and matches the unfused chain.
     */
    public void testMixedTypeCastOfKernelFusesDeterministic() {
        FieldAttribute a = field("a", DataType.INTEGER);
        FieldAttribute b = field("b", DataType.INTEGER);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Add(Source.EMPTY, new Mul(Source.EMPTY, a, b), c, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "(a_int * b_int) + c_long must fuse (cast wrapping a kernel)");
        assertDoesNotFuse(unfused, "(a_int * b_int) + c_long kill switch OFF");

        int[] av = { 2, 3, -4, 0 };
        int[] bv = { 5, 7, 6, 1 };
        long[] cv = { 100L, -3L, 8L, 0L };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newIntArrayVector(av, positions).asBlock(),
            blockFactory.newIntArrayVector(bv, positions).asBlock(),
            blockFactory.newLongArrayVector(cv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.LONG, inputs, positions, "(a*b)+c mixed int/long deterministic");
    }

    /**
     * DETERMINISTIC casted-operand null/multi-value proof for {@code doubleCol + intCol}
     * ({@code Add(doubleCol, castIntToDouble(intCol))}): the INT operand — the one that is widened by the synthetic
     * {@code castIntToDouble} kernel — carries {@code null}s at known positions AND multi-values at other known
     * positions, while the {@code double} operand is dense. The tree must FUSE, and the fused result must be
     * {@code null} at <em>exactly</em> the casted operand's null/mv positions (so null/mv flows THROUGH the cast, not
     * around it), matching the unfused chain in values, null positions and the multi-value warning (parity). The
     * randomized {@link #testMixedTypeCastSpecSweepMatchesUnfused} does not pin WHICH operand is abnormal, so this
     * targets the casted operand specifically.
     */
    public void testMixedTypeDoubleAddCastedIntNullMvDeterministic() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.INTEGER);
        Layout layout = layout(a, b);
        Expression expr = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG); // Add(doubleCol, castIntToDouble(intCol))

        int positions = 8;
        double[] av = { 1.5, 2.0, -3.25, 0.0, 10.0, -1.0, 7.75, 100.0 };
        Set<Integer> nulls = Set.of(1, 5);
        Set<Integer> multivalues = Set.of(3, 6);
        Set<Integer> expectedNull = Set.of(1, 3, 5, 6); // null OR multi-value in the casted int operand => null result
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            intColumnWithNullsAndMultivalues(positions, nulls, multivalues, 2) };
        assertCastedIntOperandNullMvFuses(
            expr,
            layout,
            ElementKind.DOUBLE,
            inputs,
            positions,
            expectedNull,
            "doubleCol + intCol casted-int null/mv (Add(double, castIntToDouble(int)))"
        );
    }

    /**
     * DETERMINISTIC casted-operand null/multi-value proof for {@code longCol * intCol}
     * ({@code Mul(longCol, castIntToLong(intCol))}): the INT operand widened by {@code castIntToLong} carries nulls and
     * multi-values at known positions while the {@code long} operand is dense. Same guarantees as
     * {@link #testMixedTypeDoubleAddCastedIntNullMvDeterministic} for the {@code int→long} cast path.
     */
    public void testMixedTypeLongMulCastedIntNullMvDeterministic() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.INTEGER);
        Layout layout = layout(a, b);
        Expression expr = new Mul(Source.EMPTY, a, b); // Mul(longCol, castIntToLong(intCol))

        int positions = 8;
        long[] av = { 3, 7, -2, 9, 0, 11, -4, 100 };
        Set<Integer> nulls = Set.of(0, 4);
        Set<Integer> multivalues = Set.of(2, 7);
        Set<Integer> expectedNull = Set.of(0, 2, 4, 7);
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            intColumnWithNullsAndMultivalues(positions, nulls, multivalues, 1) };
        assertCastedIntOperandNullMvFuses(
            expr,
            layout,
            ElementKind.LONG,
            inputs,
            positions,
            expectedNull,
            "longCol * intCol casted-int null/mv (Mul(long, castIntToLong(int)))"
        );
    }

    /**
     * A DEEPER, nested-width mixed tree, deterministic: {@code ((a_int * b_int) + c_long) + d_double} is
     * {@code Add(castLongToDouble(Add(castIntToLong(Mul(int,int)), long)), double)} — the inner {@code Mul}'s
     * {@code int} widens to {@code long} for the inner {@code Add}, whose {@code long} widens to {@code double} for the
     * outer {@code Add}. Two synthetic cast kernels at different depths must both fuse (per-node typing bridging
     * stacked widths int→long→double), matching the unfused chain position-by-position — including the
     * {@code Integer.MAX_VALUE} row whose exact widenings the fused body must reproduce.
     */
    public void testMixedTypeNestedIntLongDoubleFusesDeterministic() {
        FieldAttribute a = field("a", DataType.INTEGER);
        FieldAttribute b = field("b", DataType.INTEGER);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.DOUBLE);
        Layout layout = layout(a, b, c, d);
        Expression expr = new Add(
            Source.EMPTY,
            new Add(Source.EMPTY, new Mul(Source.EMPTY, a, b), c, EsqlTestUtils.TEST_CFG),
            d,
            EsqlTestUtils.TEST_CFG
        );

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "((a_int*b_int)+c_long)+d_double nested int->long->double must fuse");
        assertDoesNotFuse(unfused, "((a*b)+c)+d kill switch OFF");

        int[] av = { 2, 3, -4, 0, Integer.MAX_VALUE };
        int[] bv = { 5, 7, 6, 1, 1 };
        long[] cv = { 100L, -3L, 8L, 0L, 2L };
        double[] dv = { 1.5, -2.25, 0.0, 7.5, 1e9 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newIntArrayVector(av, positions).asBlock(),
            blockFactory.newIntArrayVector(bv, positions).asBlock(),
            blockFactory.newLongArrayVector(cv, positions).asBlock(),
            blockFactory.newDoubleArrayVector(dv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.DOUBLE, inputs, positions, "((a*b)+c)+d nested int/long/double deterministic");
    }

    /**
     * Builds an {@code int} column carrying {@code null}s at {@code nullPositions} and multi-value entries at
     * {@code mvPositions} (all other positions single-valued), so a differential test can pin exactly which positions
     * the casted operand nulls. Values are {@code base + p} (deterministic, distinct across positions).
     */
    private Block intColumnWithNullsAndMultivalues(int positions, Set<Integer> nullPositions, Set<Integer> mvPositions, int base) {
        try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(positions)) {
            for (int p = 0; p < positions; p++) {
                if (nullPositions.contains(p)) {
                    builder.appendNull();
                } else if (mvPositions.contains(p)) {
                    builder.beginPositionEntry();
                    builder.appendInt(base + p);
                    builder.appendInt(base + p + 1);
                    builder.endPositionEntry();
                } else {
                    builder.appendInt(base + p);
                }
            }
            return builder.build();
        }
    }

    /**
     * Asserts a cast-bridged mixed-type tree whose CASTED int operand carries null/multi-values FUSES, that the fused
     * result is {@code null} at exactly {@code expectedNullPositions} (null/mv propagates through the cast), that it
     * matches the unfused chain in values + null positions, and that the multi-value warning fired on BOTH paths
     * (parity). Releases the page and both result blocks before returning.
     */
    private void assertCastedIntOperandNullMvFuses(
        Expression expr,
        Layout layout,
        ElementKind outputKind,
        Block[] inputs,
        int positions,
        Set<Integer> expectedNullPositions,
        String ctx
    ) {
        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, ctx); // core iter-25 unlock: the cast-bridged tree must FUSE, not fall back
        assertDoesNotFuse(unfusedFactory, ctx + " kill switch OFF");

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            drainWarnings(); // clean slate
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // The null/multi-value carried by the CASTED int operand must null the fused result at exactly those
            // positions — proving null/mv propagates THROUGH the synthetic widening cast, not around it.
            for (int p = 0; p < positions; p++) {
                assertThat(ctx + " fused null@" + p, fusedResult.isNull(p), is(expectedNullPositions.contains(p)));
            }
            assertResultsEqual(outputKind, fusedResult, referenceResult, positions, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
            // A multi-value in the casted operand raises the single-value multi-value warning on BOTH paths — parity,
            // not just subset (a silently-dropped fused warning would still satisfy the subset rule).
            assertThat(
                ctx + " the unfused chain must raise the multi-value warning",
                containsWarning(referenceWarnings, MULTI_VALUE_MSG),
                is(true)
            );
            assertFusedEmitsWarning(fusedWarnings, MULTI_VALUE_MSG, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /** Builds a page whose columns may have DIFFERENT element kinds (mixed-type trees), forcing an abnormal referenced column. */
    private Block[] buildMixedPage(ElementKind[] kinds, int positionCount, Profile profile, int[] referenced) {
        boolean abnormal = profile == Profile.NULLS || profile == Profile.MULTIVALUE || profile == Profile.NULLS_AND_MULTIVALUE;
        int forced = abnormal && referenced.length > 0 ? referenced[randomInt(referenced.length - 1)] : -1;
        Block[] blocks = new Block[kinds.length];
        for (int c = 0; c < kinds.length; c++) {
            Structure structure = structureFor(profile, positionCount, c == forced);
            blocks[c] = buildColumn(kinds[c], positionCount, structure, profile, false);
        }
        return blocks;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // iter 26: scalar math-function fusion. The @Fusable (D)D / (DD)D java.base double kernels (Sin/Sqrt/Log/Hypot/Pow
    // + the rest of Tier A/A'/B) are wired UNDER the arithmetic/comparison roots via the ARITH_AND_MATH palette, so the
    // sweep generates a + sqrt(b), sin(a) * c, pow(a, b) > c, etc. Domain errors (Sqrt/Log negative-domain, Pow
    // overflow) null + warn identically to the unfused chain; the pure-java.base functions (Sin/Cos/Hypot/...) also
    // VECTORIZE (plain-vector), the throwing ones take the checked-vector path (iter-22 VectorUnsafe).
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The iter-26 math-function sweep: double trees mixing overflow-checked binary arithmetic with the scalar math
     * functions, under both an ARITHMETIC root ({@code a + sqrt(b)}, {@code sin(a) * c}) and a COMPARISON root
     * ({@code pow(a, b) > c}). Because {@link #nextDouble} spans negatives, {@code Sqrt}/{@code Log} naturally meet
     * out-of-domain inputs and {@code Pow} can overflow, so the sweep exercises the domain-error null+warn path in
     * addition to the clean/null/multi-value paths — all asserted fused==unfused (values + null positions) with the
     * ratified warning subset. Overflow-checked math kernels ({@code Sqrt}/{@code Log}/{@code Pow}) reach the
     * checked-vector fast path (the iter-22 VectorUnsafe win, now for double math functions).
     */
    public void testMathFunctionSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        // Arithmetic root: sin(a)+b, sqrt(a)*c, a+pow(b,c), ...
        sweep(cov, Category.ARITHMETIC, ARITH_AND_MATH, List.of(ElementKind.DOUBLE), cycle, false, 150);
        // Comparison root: pow(a,b)>c, sqrt(a)+b < d, ...
        sweep(cov, Category.COMPARISON, ARITH_AND_MATH, List.of(ElementKind.DOUBLE), cycle, false, 150);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat(
            "a double math tree with an overflow-checked kernel must reach the checked-vector fast path",
            cov.checkedVector,
            greaterThan(0)
        );
        assertThat(
            "a double math tree (Sqrt/Log/Pow) must reach the checked-vector path (iter-22 VectorUnsafe for math fns)",
            strategyHits(cov, ElementKind.DOUBLE, VectorStrategy.CHECKED_VECTOR),
            greaterThan(0)
        );
        assertThat("a multi-value input feeding a math function must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    /**
     * {@code sin(intCol)} — a math function over a NON-double column — fuses as {@code Sin(castIntToDouble(intCol))},
     * composing the iter-25 widening cast kernel with the iter-26 function kernel (kernel depth 2), and matches the
     * unfused chain (which splices the very same {@code Cast.cast(int -> double)} before {@code SinEvaluator}) exactly.
     * Proves the per-node typing bridges an {@code int} leaf into a {@code double}-domain function.
     */
    public void testSinOverCastIntComposesCastAndFunction() {
        FieldAttribute a = field("a", DataType.INTEGER);
        Layout layout = layout(a);
        Expression expr = new Sin(Source.EMPTY, a); // Sin(castIntToDouble(intCol))

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "sin(intCol) must fuse as Sin(castIntToDouble(intCol)) — cast + function compose");
        assertDoesNotFuse(unfused, "sin(intCol) kill switch OFF");

        int positions = 1024 + randomIntBetween(0, 64);
        int[] av = new int[positions];
        for (int p = 0; p < positions; p++) {
            av[p] = randomIntBetween(-360, 360);
        }
        Block[] inputs = { blockFactory.newIntArrayVector(av, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.DOUBLE, inputs, positions, "sin(intCol) cast+function compose");
    }

    /**
     * A pure-java.base math tree ({@code hypot(sin(a), cos(b))}, none overflow-checked) over dense double columns
     * compiles the PLAIN-vector fast path — proving the Tier-A math kernels (Sin/Cos/Hypot) VECTORIZE, not just fuse —
     * and matches the unfused chain position-by-position.
     */
    public void testMathPlainVectorMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        Layout layout = layout(a, b);
        Expression expr = new Hypot(Source.EMPTY, new Sin(Source.EMPTY, a), new Cos(Source.EMPTY, b));

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, "hypot(sin(a), cos(b)) plain vector");
        assertThat(
            "hypot(sin,cos) has no overflow-checked kernel -> plain vector",
            fused.vectorStrategy(),
            is(VectorStrategy.PLAIN_VECTOR)
        );
        assertDoesNotFuse(unfusedFactory, "hypot(sin(a), cos(b)) kill switch OFF");

        int positions = 1024 + randomIntBetween(0, 64);
        double[] av = new double[positions];
        double[] bv = new double[positions];
        for (int p = 0; p < positions; p++) {
            av[p] = randomDoubleBetween(-10, 10, true);
            bv[p] = randomDoubleBetween(-10, 10, true);
        }
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            blockFactory.newDoubleArrayVector(bv, positions).asBlock() };
        runDifferential(fusedFactory, unfusedFactory, ElementKind.DOUBLE, inputs, positions, "hypot(sin(a), cos(b)) plain vector");
    }

    /**
     * DETERMINISTIC {@code Sqrt} negative-domain error: {@code sqrt(a) + b} over doubles nulls + warns at every
     * position where {@code a < 0} ({@code Sqrt.process} throws {@code ArithmeticException}), in BOTH the fused
     * checked-vector path and the unfused chain. Asserts the fused result is null at exactly the negative positions,
     * matches the unfused values/nulls, and that the fused path ITSELF emits the "Square root of negative" warning
     * (parity, not just subset).
     */
    public void testSqrtNegativeDomainErrorMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        Layout layout = layout(a, b);
        Expression expr = new Add(Source.EMPTY, new Sqrt(Source.EMPTY, a), b, EsqlTestUtils.TEST_CFG);

        double[] av = { 4.0, -1.0, 9.0, -4.0, 0.0 }; // negative at 1 and 3 => sqrt throws => null + warning
        double[] bv = { 1.0, 2.0, 3.0, 4.0, 5.0 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            blockFactory.newDoubleArrayVector(bv, positions).asBlock() };
        DifferentialWarnings warnings = assertMathDomainError(expr, layout, inputs, positions, Set.of(1, 3), "sqrt(a)+b negative-domain");
        assertThat(
            "the unfused chain must warn on the negative sqrt",
            containsWarning(warnings.reference(), "Square root of negative"),
            is(true)
        );
        assertFusedEmitsWarning(warnings.fused(), "Square root of negative", "sqrt(a)+b negative-domain");
    }

    /**
     * DETERMINISTIC {@code Log} non-positive-domain error: {@code log(a) * b} over doubles nulls + warns at every
     * position where {@code a <= 0} ({@code Log.process} throws {@code ArithmeticException}), in BOTH paths. Same
     * guarantees as {@link #testSqrtNegativeDomainErrorMatchesUnfused} for the "Log of non-positive number" warning.
     */
    public void testLogNonPositiveDomainErrorMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        Layout layout = layout(a, b);
        Expression expr = new Mul(Source.EMPTY, new Log(Source.EMPTY, a, null), b);

        double[] av = { Math.E, -2.0, 1.0, 0.0, 10.0 }; // <= 0 at 1 and 3 => log throws => null + warning
        double[] bv = { 1.0, 2.0, 3.0, 4.0, 5.0 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            blockFactory.newDoubleArrayVector(bv, positions).asBlock() };
        DifferentialWarnings warnings = assertMathDomainError(
            expr,
            layout,
            inputs,
            positions,
            Set.of(1, 3),
            "log(a)*b non-positive-domain"
        );
        assertThat(
            "the unfused chain must warn on the non-positive log",
            containsWarning(warnings.reference(), "Log of non-positive number"),
            is(true)
        );
        assertFusedEmitsWarning(warnings.fused(), "Log of non-positive number", "log(a)*b non-positive-domain");
    }

    /**
     * DETERMINISTIC {@code Pow} overflow: {@code pow(a, b) + c} over doubles nulls + warns at the position where the
     * result overflows to {@code Infinity} ({@code NumericUtils.asFiniteNumber} throws {@code ArithmeticException}), in
     * BOTH paths. Proves the {@code Pow}/{@code NumericUtils} kernel — a non-java.base callee — takes the checked-vector
     * path (iter-22) and its overflow nulls+warns identically to the unfused chain.
     */
    public void testPowOverflowMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);
        Expression expr = new Add(Source.EMPTY, new Pow(Source.EMPTY, a, b), c, EsqlTestUtils.TEST_CFG);

        double[] av = { 2.0, 10.0, 3.0, 5.0 };
        double[] bv = { 3.0, 400.0, 2.0, 2.0 }; // 10^400 overflows to +Inf at position 1 => asFiniteNumber throws
        double[] cv = { 1.0, 1.0, 1.0, 1.0 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(av, positions).asBlock(),
            blockFactory.newDoubleArrayVector(bv, positions).asBlock(),
            blockFactory.newDoubleArrayVector(cv, positions).asBlock() };
        DifferentialWarnings warnings = assertMathDomainError(expr, layout, inputs, positions, Set.of(1), "pow(a,b)+c overflow");
        assertThat(
            "the unfused chain must warn on the pow overflow",
            containsWarning(warnings.reference(), "not a finite double number"),
            is(true)
        );
        assertFusedEmitsWarning(warnings.fused(), "not a finite double number", "pow(a,b)+c overflow");
    }

    /**
     * Asserts a DOUBLE math tree {@code expr} fuses, that its fused result is null at exactly {@code expectedNull}
     * (where the math kernel threw), matches the unfused chain in values + null positions, and returns BOTH warning
     * lists so the caller can assert the fused path itself emitted the domain-error warning (parity).
     */
    private DifferentialWarnings assertMathDomainError(
        Expression expr,
        Layout layout,
        Block[] inputs,
        int positions,
        Set<Integer> expectedNull,
        String ctx
    ) {
        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, ctx);
        assertDoesNotFuse(unfusedFactory, ctx + " kill switch OFF");

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            for (int p = 0; p < positions; p++) {
                assertThat(ctx + " fused null@" + p, fusedResult.isNull(p), is(expectedNull.contains(p)));
            }
            assertResultsEqual(ElementKind.DOUBLE, fusedResult, referenceResult, positions, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
            return new DifferentialWarnings(fusedWarnings, referenceWarnings);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
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
                // MATH is a template FAMILY, not a sweep root: math functions are numeric-in/numeric-out kernels wired
                // UNDER the ARITHMETIC/COMPARISON roots (via the ARITH_AND_MATH palette), never a root category here.
                case MATH -> throw new AssertionError("MATH is not a sweep root; math templates are swept under ARITHMETIC/COMPARISON");
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

    /**
     * The Stage-6 <b>adaptive</b> counterpart of {@link #sweep}: builds the same generated trees but through
     * {@link #adaptiveFactory} (fusion ON, {@code min_rows == 1}) so each tree is served by an
     * {@link AdaptiveFusionEvaluatorFactory} that starts unfused and switches to the stitched fused evaluator on the
     * first non-empty page. It asserts the adaptive path's values/nulls are identical to the unfused chain and its
     * warnings a subset — the same contract the eager path meets — and returns the number of runtime switches observed
     * so the caller can prove the switch actually fired (not a vacuous unfused-vs-unfused comparison).
     */
    private long adaptiveSweep(Coverage cov, Category root, List<OpTemplate> numericPalette, Profile[] cycle, int iterations) {
        long switchesBefore = FusionTelemetry.adaptiveSwitches();
        for (int i = 0; i < iterations; i++) {
            ElementKind element = ELEMENTS.get(i % ELEMENTS.size());
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
                case MATH -> throw new AssertionError("MATH is not a sweep root");
            };
            int[] referenced = toArray(usedSet);

            ExpressionEvaluator.Factory adaptive = adaptiveFactory(expr, layout);
            ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);

            String ctx = "adaptive root=" + root + " element=" + element + " i=" + i + " profile=" + profile + " expr=" + expr;
            assertAdaptive(adaptive, ctx);
            assertDoesNotFuse(unfusedFactory, ctx);

            recordShape(cov, positionCount);
            recordProfile(cov, profile);

            ElementKind outputKind = root == Category.ARITHMETIC ? element : ElementKind.BOOLEAN;
            Block[] inputs = buildPage(element, columns, positionCount, profile, false, referenced);
            List<String> referenceWarnings = runDifferential(adaptive, unfusedFactory, outputKind, inputs, positionCount, ctx);
            recordWarnings(cov, profile, referenceWarnings);
        }
        return FusionTelemetry.adaptiveSwitches() - switchesBefore;
    }

    private static int columnsFor(Category root) {
        return switch (root) {
            case ARITHMETIC -> randomIntBetween(1, 4);
            case COMPARISON -> randomIntBetween(2, 4);
            case LOGICAL -> randomIntBetween(4, 6);
            // MATH is never a sweep root (math templates are wired under ARITHMETIC/COMPARISON), so column-count is n/a.
            case MATH -> throw new AssertionError("MATH is not a sweep root; math templates are swept under ARITHMETIC/COMPARISON");
        };
    }

    private static DataType dataType(ElementKind element) {
        return switch (element) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> throw new AssertionError("boolean leaves are not generated");
            case BYTES_REF -> throw new AssertionError("BytesRef leaves are not generated");
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
        assertThat("math-function palette is non-empty", MATH_FUNCS.isEmpty(), is(false));
        assertThat("arith+math palette includes the math functions", ARITH_AND_MATH.containsAll(MATH_FUNCS), is(true));
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
                // A math function is a scalar EsqlScalarFunction (not an arithmetic operator) that returns a double.
                case MATH -> built instanceof EsqlScalarFunction && built.dataType() == DataType.DOUBLE;
            };
            if (categoryOk == false) {
                problems.add(t.name + " built a " + built.getClass().getSimpleName() + " that does not match category " + t.category);
            }
        }
        assertThat("template palette problems: " + problems, problems.isEmpty(), is(true));
    }
}
