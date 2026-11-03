/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Shape;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Acos;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Acosh;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Asin;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Atanh;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cos;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cosh;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Hypot;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Log;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Log10;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Pow;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Sin;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Sinh;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Sqrt;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.BinaryLogic;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.EsqlArithmeticOperation;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mod;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The unified, <b>spec-driven</b> generative differential harness. One declarative palette of
 * {@link OpTemplate}s — each an operator's {@code {category, arity, factory lambda, allowed element kinds}} — is expanded
 * by a constrained generator into <b>valid fusable trees only</b>, then swept (seeded, bounded, ~a few hundred trees) as
 * a fused-vs-unfused differential across arithmetic, comparison and logical roots <b>and the currently-fusable
 * cross-category combos</b> that fell between the three old single-category harnesses:
 * <ul>
 * <li>{@code COMPARISON} over {@code ARITHMETIC} — a comparison root over {@code a+b} (comparison sweep);</li>
 * <li>{@code LOGICAL} over column {@code COMPARISON} — a 3VL {@code AND}/{@code OR} over column-column comparisons and
 * nested logicals (logical sweep).</li>
 * </ul>
 * The all-three-category shape (arithmetic under comparison under <em>logical</em>) does <b>not</b> fuse under the
 * current logical scope ({@code FusionPlanner.buildComparisonOperand} requires column-column comparison operands); that boundary
 * is documented and asserted (additive fallback) by {@link #testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}.
 * All shared machinery — breaker-backed {@link org.elasticsearch.compute.data.BlockFactory}, the real kill-switch
 * toggle, the column/{@link Profile} model, {@link #assertResultsEqual}/{@link #assertWarningsSubset}, coverage
 * bookkeeping — comes from {@link FusionDifferentialTestCase}.
 *
 * <h2>The template model (why later operators are drop-in)</h2>
 * A fusable operator is described by exactly ONE data line in {@link #TEMPLATES}: its {@link Category} (which fixes the
 * result type and the operand result type — {@code NUMERIC} for arithmetic/comparison operands, {@code BOOLEAN} for
 * logical operands), its arity, a {@code List<Expression> -> Expression} factory, and the {@link ElementKind}s it
 * accepts. The generator picks templates by category + arity + <b>element applicability</b> (a template is only ever
 * generated for an {@link ElementKind} in its {@code allowedElements}), so:
 * <ul>
 * <li>(constant leaf) adds a leaf producer (a {@code NUMERIC} constant) — one entry;</li>
 * <li>(casts) adds unary {@code NUMERIC->NUMERIC} intermediate kernels — one entry each;</li>
 * <li>(math functions) adds unary/binary {@code double}-domain kernels — one entry each (their
 * {@code allowedElements} = {@code {DOUBLE}} makes the generator only wire them under double subtrees).</li>
 * </ul>
 * None of them need a new harness; each becomes "add a template ⇒ it's swept".
 */
public class FusionSpecDifferentialTests extends FusionDifferentialTestCase {

    /** Which layer of the expression an operator belongs to; fixes its result type and its operands' result type. */
    private enum Category {
        /** {@code NUMERIC} operands -> {@code NUMERIC} result (arithmetic kernels). */
        ARITHMETIC,
        /** {@code NUMERIC} operands -> {@code BOOLEAN} result (comparison kernels). */
        COMPARISON,
        /** {@code BOOLEAN} operands -> {@code BOOLEAN} result (3VL logical connectives). */
        LOGICAL,
        /**
         * {@code DOUBLE} operands -> {@code DOUBLE} result (scalar math functions, e.g. {@code sin}/{@code sqrt}/
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

    /** {@code allowedElements} for the scalar math functions. Their kernels are {@code (D)D}/{@code (DD)D} */
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
     * The palette. Each line is one fusable operator. Later operators extend this list (and nothing else) to get swept:
     * a constant leaf, casts as unary NUMERIC->NUMERIC kernels, and double-domain math functions
     * ({@code allowedElements = {DOUBLE}}).
     */
    private static final List<OpTemplate> TEMPLATES = List.of(
        op("Add", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Add(Source.EMPTY, a.get(0), a.get(1), EsqlTestUtils.TEST_CFG)),
        op("Sub", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Sub(Source.EMPTY, a.get(0), a.get(1), EsqlTestUtils.TEST_CFG)),
        op("Mul", Category.ARITHMETIC, 2, NUMERIC, true, false, a -> new Mul(Source.EMPTY, a.get(0), a.get(1))),
        op("Div", Category.ARITHMETIC, 2, NUMERIC, true, true, a -> new Div(Source.EMPTY, a.get(0), a.get(1))),
        op("Mod", Category.ARITHMETIC, 2, NUMERIC, true, true, a -> new Mod(Source.EMPTY, a.get(0), a.get(1))),
        op("Neg", Category.ARITHMETIC, 1, NUMERIC, false, false, a -> new Neg(Source.EMPTY, a.get(0))),
        op("Abs", Category.ARITHMETIC, 1, NUMERIC, false, false, a -> new Abs(Source.EMPTY, a.get(0))),
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
        op("And", Category.LOGICAL, 2, BOOLEAN_OPERANDS, false, false, a -> new And(Source.EMPTY, a.get(0), a.get(1))),
        op("Or", Category.LOGICAL, 2, BOOLEAN_OPERANDS, false, false, a -> new Or(Source.EMPTY, a.get(0), a.get(1))),
        op("Sin", Category.MATH, 1, DOUBLE_ONLY, false, false, a -> new Sin(Source.EMPTY, a.get(0))),
        op("Sqrt", Category.MATH, 1, DOUBLE_ONLY, true, false, a -> new Sqrt(Source.EMPTY, a.get(0))),
        op("Log", Category.MATH, 1, DOUBLE_ONLY, true, false, a -> new Log(Source.EMPTY, a.get(0), null)),
        op("Hypot", Category.MATH, 2, DOUBLE_ONLY, false, false, a -> new Hypot(Source.EMPTY, a.get(0), a.get(1))),
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
    /** The scalar math functions ({@code Sin}/{@code Sqrt}/{@code Log}/{@code Hypot}/{@code Pow}). */
    private static final List<OpTemplate> MATH_FUNCS = byCategory(Category.MATH);
    /**
     * The palette for the {@link #testMathFunctionSpecSweepMatchesUnfused} double sweep: overflow-checked binary
     * arithmetic ({@code Add}/{@code Sub}/{@code Mul}, no {@code Div}) plus the math functions, so the generator freely
     * mixes them into double trees like {@code a + sqrt(b)}, {@code sin(a) * c}, {@code pow(a, b) > c}.
     */
    private static final List<OpTemplate> ARITH_AND_MATH = Stream.concat(ARITH_NO_DIV.stream(), MATH_FUNCS.stream()).toList();

    private static final List<ElementKind> ELEMENTS = List.of(ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE);

    /** Arithmetic-rooted trees over each numeric element, cycling the CLEAN / NULLS / MULTIVALUE / OVERFLOW profiles. */
    public void testArithmeticSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        sweep(cov, Category.ARITHMETIC, ARITH_NO_DIV, cycle, false, 150);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat("arithmetic trees over overflow-checked kernels => checked-vector", cov.checkedVector, greaterThan(0));
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
        sweep(cov, Category.ARITHMETIC, ARITH_WITH_DIV, List.of(ElementKind.LONG), cycle, true, 96);
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
     * comparison over arithmetic is NOT an accepted logical operand under the current logical scope; that boundary is asserted by
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
        assertThat("logical trees must compile VectorStrategy.LOGICAL_VECTOR", cov.logicalVector, greaterThan(0));
    }

    /**
     * The current logical-operand boundary, made explicit and honest: a comparison whose child is ARITHMETIC is <b>not</b> an accepted
     * logical operand ({@code FusionPlanner.buildComparisonOperand} requires column-column comparisons), so a tree that
     * puts arithmetic under a comparison under a logical — {@code (a + b > c) AND (d < e)} — does NOT fuse and correctly
     * falls back to the unfused chain (fusion is strictly additive). With the kill switch ON and OFF both sides resolve
     * to the same generated evaluator, so values, nulls and warnings are trivially identical. When a later change lifts
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
        Expression left = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression right = new LessThan(Source.EMPTY, d, e);
        Expression expr = new And(Source.EMPTY, left, right);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "arithmetic under comparison under logical (: not a fusable logical operand)");
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
     * END-TO-END over-limit fallback, through the REAL plan-time path
     * ({@link FusionPlanner#maybeFuse} → {@link FusedExpressionEvaluatorFactory#tryCreate} → the {@link
     * org.elasticsearch.compute.operator.fusion.Stitcher}): a large flat {@code AND} of negated column-column
     * comparisons is a fusable logical shape, but deep enough that the ENFORCED logical block-loop body is
     * refused by the {@code maxFusedMethodBytecodes} huge-method guard — either because the measured body exceeds the
     * limit, or (on current ASM, reached first for a branch-heavy 3VL nest) because ASM's {@code COMPUTE_FRAMES}
     * operation budget is exhausted computing its stack-map frames; both are surfaced as the same huge-method {@link
     * org.elasticsearch.compute.operator.fusion.Stitcher.StitchingException}. The stitch is refused and the planner
     * returns the <b>unfused</b> factory unchanged (NOT a {@link FusedExpressionEvaluatorFactory}). A <em>small</em>
     * version of the SAME shape fuses, proving it is the SIZE (the huge-method guard) that forces the fallback here
     * — not a planner-scope rejection — and that the over-limit shape still evaluates correctly via the fallback
     * chain.
     *
     * <p>Why not a 250-comparison AND: the planner assigns a fresh fused-method parameter per leaf occurrence
     * (no channel reuse), and {@code MethodType} rejects more than 255 parameters. A 250-comparison AND is 503
     * params ({@code BlockFactory} + {@code Warnings[]} + {@code int} + 500 {@code *Block}s) and refuses with
     * {@code bad parameter count 503} — never reaching the huge-method guard, KERNEL_MAP, or the frame-computation
     * budget. 120 comparisons stay at 243 params; wrapping each in {@code NOT} inflates the top-level 3VL combining
     * enough that its stack-map frame computation exhausts ASM's {@code COMPUTE_FRAMES} operation budget (which
     * scales with join points, not byte length), tripping the huge-method refusal before the body is serialized and
     * byte-measured. KERNEL_MAP is ≈88 chars/entry (FQCN + index/{@code =}/{@code ;}), cap 65535 — 120 ≈ 11 KB, well
     * under; scaling to thousands would overflow KERNEL_MAP (and MethodType) before the huge-method guard is reached.
     */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:WARN",
        reason = "assert the large AND is refused by the huge-method guard, not a KERNEL_MAP UTF-8 overflow"
    )
    public void testOverLimitLogicalRefusesToFuseAndFallsBackEndToEnd() {
        int columns = 4;
        FieldAttribute[] cols = new FieldAttribute[columns];
        for (int c = 0; c < columns; c++) {
            cols[c] = field("c" + c, DataType.LONG);
        }
        Layout layout = layout(cols);

        Expression small = andOfNegatedColumnComparisons(2, cols);
        assertFuses(fusedFactory(small, layout), "small AND (2 negated comparisons) must fuse");

        Expression large = andOfNegatedColumnComparisons(120, cols);
        String[] refused = { null };
        ExpressionEvaluator.Factory fusedLarge;
        try (var mockLog = MockLog.capture(FusedExpressionEvaluatorFactory.class)) {
            mockLog.addExpectation(
                new MockLog.SeenEventExpectation(
                    "huge-method guard refused the large AND (not a KERNEL_MAP UTF-8 overflow)",
                    FusedExpressionEvaluatorFactory.class.getCanonicalName(),
                    Level.WARN,
                    "*unfused*"
                ) {
                    @Override
                    public boolean innerMatch(LogEvent event) {
                        Throwable thrown = event.getThrown();
                        while (thrown != null) {
                            if (thrown instanceof Stitcher.StitchingException
                                && thrown.getMessage() != null
                                && thrown.getMessage().contains("exceeds the huge-method limit")) {
                                refused[0] = thrown.getMessage();
                                return true;
                            }
                            thrown = thrown.getCause();
                        }
                        return false;
                    }
                }
            );
            fusedLarge = fusedFactory(large, layout);
            mockLog.assertAllExpectationsMatched();
        }
        logger.info("120-comparison negated AND refused: {}", refused[0]);
        assertThat(refused[0], containsString("exceeds the huge-method limit"));
        if (refused[0].contains("ASM COMPUTE_FRAMES operation budget") == false) {
            int reportedSize = Integer.parseInt(refused[0].replaceAll(".*\\[(\\d+) bytecodes].*", "$1"));
            assertThat(
                "the size the huge-method guard measured must actually exceed the limit: " + refused[0],
                reportedSize,
                greaterThan(Stitcher.maxFusedMethodBytecodes())
            );
        }
        ExpressionEvaluator.Factory unfusedLarge = unfusedFactory(large, layout);
        assertDoesNotFuse(fusedLarge, "over-limit large AND must fall back to the unfused chain (not a FusedExpressionEvaluatorFactory)");
        assertDoesNotFuse(unfusedLarge, "over-limit large AND kill switch OFF");

        int positions = 64;
        Block[] inputs = new Block[columns];
        for (int c = 0; c < columns; c++) {
            inputs[c] = denseColumn(ElementKind.LONG, positions, c + 1);
        }
        runDifferential(fusedLarge, unfusedLarge, ElementKind.BOOLEAN, inputs, positions, "over-limit AND fallback evaluates");
    }

    /** A left-nested {@code AND} of {@code comparisons} negated column-column comparisons over {@code cols} */
    private static Expression andOfNegatedColumnComparisons(int comparisons, FieldAttribute[] cols) {
        Expression tree = new Not(Source.EMPTY, columnComparison(cols, 0));
        for (int i = 1; i < comparisons; i++) {
            tree = new And(Source.EMPTY, tree, new Not(Source.EMPTY, columnComparison(cols, i)));
        }
        return tree;
    }

    /** The {@code i}-th column-column comparison, cycling operator and (distinct) operand columns for variety. */
    private static Expression columnComparison(FieldAttribute[] cols, int i) {
        FieldAttribute left = cols[i % cols.length];
        FieldAttribute right = cols[(i + 1) % cols.length]; // (i+1)%len != i%len for len >= 2, so operands differ
        return (i % 2 == 0) ? new GreaterThan(Source.EMPTY, left, right) : new LessThan(Source.EMPTY, left, right);
    }

    /**
     * Which constant-leaf shape a sweep iteration builds. Each embeds one or two literal constants (see
     * {@link #constant}) into a depth &ge; 2 tree so it fuses, exercising the emit paths a constant reaches:
     * checked-vector (arithmetic-rooted / comparison-rooted with an overflow-checked child) and LOGICAL_VECTOR
     * (a 3VL {@code AND}/{@code OR} tree; null/MV pages still evaluate on the 3VL block path).
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
     * The constant-leaf sweep: for each numeric element and profile it builds one of the three
     * {@link ConstShape}s with <b>random</b> constant values and asserts the fused body (which embeds each literal as a
     * bytecode constant, never a per-position read) matches the unfused chain in values, null positions and the
     * ratified warning subset. Proves constants reach both the checked-vector and the LOGICAL_VECTOR paths
     * (null/MV pages still evaluate on the 3VL block path), and that overflow / multi-value warnings still fire
     * identically when a column operand is abnormal.
     */
    public void testConstantLeafSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        ConstShape[] shapes = ConstShape.values();
        int iterations = 180;
        int[] referenced = { 0, 1 };
        for (int i = 0; i < iterations; i++) {
            ElementKind element = ELEMENTS.get(i % ELEMENTS.size());
            DataType type = dataType(element);
            int positionCount = positionCountFor(i);
            Profile profile = cycle[(i / ELEMENTS.size()) % cycle.length];
            ConstShape shape = shapes[(i / (ELEMENTS.size() * cycle.length)) % shapes.length];

            FieldAttribute a = field("c0", type);
            FieldAttribute b = field("c1", type);
            Layout layout = layout(a, b);

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
        assertThat("logical-of-constant-comparisons must compile VectorStrategy.LOGICAL_VECTOR", cov.logicalVector, greaterThan(0));
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
        assertThat(
            "a 3VL logical tree compiles the dense LOGICAL_VECTOR path (null/MV pages still eval on the 3VL block path)",
            fusedFactory.vectorStrategy(),
            is(VectorStrategy.LOGICAL_VECTOR)
        );
        assertDoesNotFuse(unfused, "a>5 AND b<10 kill switch OFF");

        long[] av = { 3, 6, 10, 5 };
        long[] bv = { 8, 12, 9, 10 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, positions, "a>5 AND b<10 deterministic");
    }

    /** F2b: {@code NOT(a > 5)} and {@code NOT(a > 5 AND b < 10)} built by hand (the optimizer does not rewrite Not) */
    public void testNotLogicalDeterministic() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout two = layout(a, b);
        Layout one = layout(a);
        Expression notGt = new Not(Source.EMPTY, new GreaterThan(Source.EMPTY, a, constant(ElementKind.LONG, 5)));
        Expression notAnd = new Not(
            Source.EMPTY,
            new And(
                Source.EMPTY,
                new GreaterThan(Source.EMPTY, a, constant(ElementKind.LONG, 5)),
                new LessThan(Source.EMPTY, b, constant(ElementKind.LONG, 10))
            )
        );
        assertNotLogical(notGt, one, 1, "NOT(a>5)");
        assertNotLogical(notAnd, two, 2, "NOT(a>5 AND b<10)");
    }

    private void assertNotLogical(Expression expr, Layout layout, int columns, String ctx) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, ctx);
        assertThat(fusedFactory.vectorStrategy(), is(VectorStrategy.LOGICAL_VECTOR));
        assertDoesNotFuse(unfused, ctx + " kill switch OFF");
        int n = 64;
        Block[] dense = new Block[columns];
        for (int c = 0; c < columns; c++) {
            dense[c] = denseColumn(ElementKind.LONG, n, c);
        }
        runDifferential(fused, unfused, ElementKind.BOOLEAN, dense, n, ctx + " dense");
        int[] referenced = new int[columns];
        for (int c = 0; c < columns; c++) {
            referenced[c] = c;
        }
        runDifferential(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            buildPage(ElementKind.LONG, columns, n, Profile.NULLS, false, referenced),
            n,
            ctx + " nulls"
        );
        runDifferential(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            buildPage(ElementKind.LONG, columns, n, Profile.MULTIVALUE, false, referenced),
            n,
            ctx + " mv"
        );
    }

    /**
     *: GTE/LTE under a constant-logical tree. {@code a >= 5 AND b <= 10} (and the OR variant) fuse for
     * int/long/double, compile {@link VectorStrategy#LOGICAL_VECTOR}, and match unfused on a crafted dense page
     * (including the equal-threshold position that distinguishes {@code >=}/{@code <=} from {@code >}/{@code <}) plus
     * 3VL null/multi-value pages.
     */
    public void testConstantLogicalGteLteDeterministic() {
        for (ElementKind element : new ElementKind[] { ElementKind.LONG, ElementKind.INT, ElementKind.DOUBLE }) {
            FieldAttribute a = field("a", dataType(element));
            FieldAttribute b = field("b", dataType(element));
            Layout layout = layout(a, b);
            Expression and = new And(
                Source.EMPTY,
                new GreaterThanOrEqual(Source.EMPTY, a, constant(element, 5)),
                new LessThanOrEqual(Source.EMPTY, b, constant(element, 10))
            );
            Expression or = new Or(
                Source.EMPTY,
                new GreaterThanOrEqual(Source.EMPTY, a, constant(element, 5)),
                new LessThanOrEqual(Source.EMPTY, b, constant(element, 10))
            );
            assertConstantLogicalGteLte(and, layout, element, "a>=5 AND b<=10");
            assertConstantLogicalGteLte(or, layout, element, "a>=5 OR b<=10");
        }
    }

    private void assertConstantLogicalGteLte(Expression expr, Layout layout, ElementKind element, String ctx) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, ctx + " " + element);
        assertThat(
            "a 3VL logical of GTE/LTE compiles the dense LOGICAL_VECTOR path",
            fusedFactory.vectorStrategy(),
            is(VectorStrategy.LOGICAL_VECTOR)
        );
        assertDoesNotFuse(unfused, ctx + " " + element + " kill switch OFF");

        long[] av = { 3, 6, 10, 5 };
        long[] bv = { 8, 12, 9, 10 };
        int positions = av.length;
        runDifferential(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            new Block[] { numericVector(element, av), numericVector(element, bv) },
            positions,
            ctx + " " + element + " dense"
        );
        int n = 64;
        runDifferential(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            buildPage(element, 2, n, Profile.NULLS, false, new int[] { 0, 1 }),
            n,
            ctx + " " + element + " nulls"
        );
        runDifferential(
            fused,
            unfused,
            ElementKind.BOOLEAN,
            buildPage(element, 2, n, Profile.MULTIVALUE, false, new int[] { 0, 1 }),
            n,
            ctx + " " + element + " mv"
        );
    }

    /** A dense numeric vector of {@code element} filled from {@code values} (narrowed for int, widened for double). */
    private Block numericVector(ElementKind element, long[] values) {
        int n = values.length;
        return switch (element) {
            case LONG -> blockFactory.newLongArrayVector(values, n).asBlock();
            case INT -> {
                int[] v = new int[n];
                for (int i = 0; i < n; i++) {
                    v[i] = (int) values[i];
                }
                yield blockFactory.newIntArrayVector(v, n).asBlock();
            }
            case DOUBLE -> {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) {
                    v[i] = values[i];
                }
                yield blockFactory.newDoubleArrayVector(v, n).asBlock();
            }
            case BOOLEAN, BYTES_REF -> throw new AssertionError("numeric GTE/LTE coverage: " + element);
        };
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
     *.esql.evaluator.EvalMapper} (populating the shared cache with the first, then resolving the second) and asserts
     * the two fused results DIFFER at a crafted position where the two constants force different answers. It also
     * asserts each fused result equals its own unfused reference, so a wrong-valued cached class fails loudly rather
     * than passing a vacuous {@code toString()} comparison. Finally it pins that same-value trees over different columns
     * DO share a shape (column bindings are excluded from the key).
     */
    public void testDifferentConstantValuesEvaluateDistinctly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);

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
        assertThat("the unfused reference must register the overflow warning", containsWarning(warnings.reference(), "overflow"), is(true));
        assertFusedEmitsWarning(warnings.fused(), "overflow", "(a+b)+MAX overflow");
    }

    /** A constant that drives a divide-by-zero: {@code (a + b) / K} with the literal divisor {@code K = 0} */
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
        assertThat(
            "the unfused reference must register the / by zero warning",
            containsWarning(warnings.reference(), DIV_ZERO_MSG),
            is(true)
        );
        assertFusedEmitsWarning(warnings.fused(), DIV_ZERO_MSG, "(a+b)/0 div-zero");
    }

    /**
     * /: a compile-time constant INT/LONG divisor of {@code Div} is baked as an immediate (DOUBLE stays a
     * trailing param). {@code (a * b) / K} fuses (depth ≥ 2) and matches the unfused chain for int/long/double,
     * including a constant {@code 0} (warn+null) and {@code MIN/-1} integer overflow (fused values+warnings ==
     * unfused). The spliced kernel is still {@code process*}, not {@code *ByConstant}.
     */
    public void testConstantDivisorDivMatchesUnfused() {
        assertConstantDivisorMatches(true, ElementKind.LONG, 1000L, false);
        assertConstantDivisorMatches(true, ElementKind.INT, 1000L, false);
        assertConstantDivisorMatches(true, ElementKind.DOUBLE, 1000L, false);
        assertConstantDivisorMatches(true, ElementKind.LONG, 0L, true);
        assertConstantDivisorMatches(true, ElementKind.INT, 0L, true);
        assertConstantDivisorMatches(true, ElementKind.DOUBLE, 0L, true);
        assertConstantDivisorMinNegOne(true, ElementKind.LONG);
        assertConstantDivisorMinNegOne(true, ElementKind.INT);
    }

    /** {@code Mod.process*} is {@code @Fusable(overflowChecked=true)}, so a constant-divisor {@code (a * b) % K} */
    public void testConstantDivisorModMatchesUnfused() {
        assertConstantDivisorMatches(false, ElementKind.LONG, 7L, false);
        assertConstantDivisorMatches(false, ElementKind.INT, 7L, false);
        assertConstantDivisorMatches(false, ElementKind.DOUBLE, 7L, false);
        assertConstantDivisorMatches(false, ElementKind.LONG, 0L, true);
        assertConstantDivisorMatches(false, ElementKind.INT, 0L, true);
        assertConstantDivisorMatches(false, ElementKind.DOUBLE, 0L, true);
        assertConstantDivisorMinNegOne(false, ElementKind.LONG);
        assertConstantDivisorMinNegOne(false, ElementKind.INT);
    }

    /** {@code (a * b) / K} or {@code (a * b) % K} over a dense page of {@code element}, fused vs unfused. */
    private void assertConstantDivisorMatches(boolean div, ElementKind element, long k, boolean expectDivZero) {
        FieldAttribute a = field("a", dataType(element));
        FieldAttribute b = field("b", dataType(element));
        Layout layout = layout(a, b);
        Literal divisor = constant(element, k);
        Expression mul = new Mul(Source.EMPTY, a, b);
        Expression expr = div ? new Div(Source.EMPTY, mul, divisor) : new Mod(Source.EMPTY, mul, divisor);
        String op = div ? "/" : "%";
        String ctx = "(a*b)" + op + k + " " + element;
        int positions = 64;
        Block[] inputs = { denseColumn(element, positions, 1), denseColumn(element, positions, 2) };
        DifferentialWarnings warnings = runConstantDifferential(expr, layout, element, inputs, positions, ctx);
        if (expectDivZero) {
            assertThat("unfused " + ctx + " must warn " + DIV_ZERO_MSG, containsWarning(warnings.reference(), DIV_ZERO_MSG), is(true));
            assertFusedEmitsWarning(warnings.fused(), DIV_ZERO_MSG, ctx);
        }
    }

    /**
     * {@code (MIN * 1) / -1} or {@code %} — the two's-complement overflow edge. Java {@code /} and {@code %} wrap
     * rather than throw for MIN/-1, so both paths must agree on the wrapped value (and not invent a warning).
     */
    private void assertConstantDivisorMinNegOne(boolean div, ElementKind element) {
        FieldAttribute a = field("a", dataType(element));
        FieldAttribute b = field("b", dataType(element));
        Layout layout = layout(a, b);
        Literal minusOne = constant(element, -1L);
        Expression mul = new Mul(Source.EMPTY, a, b);
        Expression expr = div ? new Div(Source.EMPTY, mul, minusOne) : new Mod(Source.EMPTY, mul, minusOne);
        String op = div ? "/" : "%";
        String ctx = "(MIN*1)" + op + "-1 " + element;
        int positions = 4;
        Block[] inputs = switch (element) {
            case LONG -> new Block[] {
                blockFactory.newLongArrayVector(new long[] { Long.MIN_VALUE, Long.MIN_VALUE, -1L, 0L }, positions).asBlock(),
                blockFactory.newLongArrayVector(new long[] { 1L, 1L, 1L, 1L }, positions).asBlock() };
            case INT -> new Block[] {
                blockFactory.newIntArrayVector(new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE, -1, 0 }, positions).asBlock(),
                blockFactory.newIntArrayVector(new int[] { 1, 1, 1, 1 }, positions).asBlock() };
            case DOUBLE, BOOLEAN, BYTES_REF -> throw new AssertionError("MIN/-1 is an int/long edge: " + element);
        };
        runConstantDifferential(expr, layout, element, inputs, positions, ctx);
    }

    /**
     * mixed-tree key case: a BAKED Div divisor and a RUNTIME-PARAM comparison constant in the same tree
     * ({@code (a * b) / 1000 > 5}). The runtime {@code 5} must still occupy the correct trailing-param slot (the baked
     * {@code 1000} consumes none and must not shift it). Asserts the tree fuses and values+warnings match unfused.
     */
    public void testBakedDivisorWithRuntimeComparisonConstantMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(
            Source.EMPTY,
            new Div(Source.EMPTY, new Mul(Source.EMPTY, a, b), constant(ElementKind.LONG, 1000L)),
            constant(ElementKind.LONG, 5L)
        );
        int positions = 64;
        Block[] inputs = { denseColumn(ElementKind.LONG, positions, 1), denseColumn(ElementKind.LONG, positions, 2) };
        runConstantDifferential(expr, layout, ElementKind.BOOLEAN, inputs, positions, "(a*b)/1000 > 5 baked+runtime");
    }

    /**
     *: 20 distinct LONG divisors over one un-baked shape ({@code (a*b)/K}) must create at most
     * {@link FusionPlanner#BAKED_DIVISOR_CAP}+1 classes per emit path (8 baked immediates + 1 shared runtime-param
     * class). Pre- every divisor bakes, so this is 20 classes per path.
     */
    public void testBakedDivisorCapBoundsClassesPerShape() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        FusedClassCache cache = FusionPlanner.classCache();
        int positions = 16;
        int distinct = 20;
        for (int k = 1; k <= distinct; k++) {
            Expression expr = new Div(Source.EMPTY, new Mul(Source.EMPTY, a, b), constant(ElementKind.LONG, k));
            Block[] inputs = { denseColumn(ElementKind.LONG, positions, 1), denseColumn(ElementKind.LONG, positions, 2) };
            runConstantDifferential(expr, layout, ElementKind.LONG, inputs, positions, "(a*b)/" + k + " cap");
        }
        int capPlusOne = FusionPlanner.BAKED_DIVISOR_CAP + 1;
        int block = 0;
        int checked = 0;
        for (Shape shape : cache.entriesForTests()) {
            if (shape.path() == Path.BLOCK) {
                block++;
            } else if (shape.path() == Path.CHECKED_VECTOR) {
                checked++;
            }
        }
        assertThat(
            "BLOCK classes for 20 LONG divisors of one un-baked shape must be <= cap+1, got " + block,
            block,
            lessThanOrEqualTo(capPlusOne)
        );
        assertThat(
            "CHECKED_VECTOR classes for 20 LONG divisors of one un-baked shape must be <= cap+1, got " + checked,
            checked,
            lessThanOrEqualTo(capPlusOne)
        );
        assertThat("cap test must actually stitch (not refuse)", block, greaterThan(1));
        assertThat("cap test must actually stitch the checked-vector path", checked, greaterThan(1));
    }

    /**
     *: 400 distinct {@code (a,b)} pairs of {@code (x / a) % b} must create at most
     * {@link FusionPlanner#BAKED_DIVISOR_CAP}+1 classes per emit path (≤8 fully-baked tuples + 1 shared fully-unbaked
     * class). Pre- admission is per value, so two baked positions explode well past cap+1.
     */
    public void testBakedDivisorTupleCapBoundsClassesForTwoDivisorTrees() {
        FieldAttribute x = field("x", DataType.LONG);
        Layout layout = layout(x);
        FusedClassCache cache = FusionPlanner.classCache();
        int positions = 16;
        int n = 20;
        long[] as = new long[n];
        long[] bs = new long[n];
        for (int i = 0; i < n; i++) {
            as[i] = randomLongBetween(2, 100000);
            bs[i] = randomLongBetween(2, 100000);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Expression expr = new Mod(
                    Source.EMPTY,
                    new Div(Source.EMPTY, x, constant(ElementKind.LONG, as[i])),
                    constant(ElementKind.LONG, bs[j])
                );
                Block[] inputs = { denseColumn(ElementKind.LONG, positions, 1) };
                runConstantDifferential(expr, layout, ElementKind.LONG, inputs, positions, "(x/" + as[i] + ")%" + bs[j]);
            }
        }
        int capPlusOne = FusionPlanner.BAKED_DIVISOR_CAP + 1;
        int block = 0;
        int checked = 0;
        for (Shape shape : cache.entriesForTests()) {
            if (shape.path() == Path.BLOCK) {
                block++;
            } else if (shape.path() == Path.CHECKED_VECTOR) {
                checked++;
            }
        }
        assertThat(
            "BLOCK classes for 400 (a,b) tuples of one un-baked shape must be <= cap+1, got " + block,
            block,
            lessThanOrEqualTo(capPlusOne)
        );
        assertThat(
            "CHECKED_VECTOR classes for 400 (a,b) tuples of one un-baked shape must be <= cap+1, got " + checked,
            checked,
            lessThanOrEqualTo(capPlusOne)
        );
        assertThat("tuple-cap test must actually stitch (not refuse)", block, greaterThan(1));
        assertThat("tuple-cap test must actually stitch the checked-vector path", checked, greaterThan(1));
    }

    /**
     *: a depth-1 {@code x / 1000} is refused by the depth gate before admission, so it consumes no baked-tuple
     * slot. A depth-2 {@code (x * y) / 7} that does fuse admits exactly one tuple.
     */
    public void testLoneDivisorConsumesNoBakedSlot() {
        FusionPlanner.resetBakedDivisorsForTests();
        FusionSettings.setEnabledForTests(true);

        FieldAttribute x = field("x", DataType.LONG);
        Layout layout = layout(x);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);
        Expression lone = new Div(Source.EMPTY, x, constant(ElementKind.LONG, 1000L));
        assertThat("depth-1 x/1000 must refuse fusion", FusionPlanner.maybeFuse(lone, layout, rawFactory), nullValue());
        assertThat("a refused depth-1 divisor must consume no baked-tuple slot", FusionPlanner.totalAdmittedTuplesForTests(), is(0));

        FieldAttribute y = field("y", DataType.LONG);
        Layout layout2 = layout(x, y);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory2 = e -> unfusedFactory(e, layout2);
        Expression depth2 = new Div(Source.EMPTY, new Mul(Source.EMPTY, x, y), constant(ElementKind.LONG, 7L));
        assertThat("depth-2 (x*y)/7 must fuse", FusionPlanner.maybeFuse(depth2, layout2, rawFactory2), notNullValue());
        assertThat("a fused depth-2 tree must admit exactly one tuple", FusionPlanner.totalAdmittedTuplesForTests(), is(1));
    }

    /** a DOUBLE Div divisor is a trailing runtime parameter, not an {@code LDC}/{@code LDC2_W} immediate. */
    public void testDoubleDivisorIsTrailingParameterNotImmediate() {
        assumeTrue("class-byte capture is verify-mode only; skip when verify is off", FusionSettings.isVerifyEnabled());
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        Layout layout = layout(a, b);
        Expression expr = new Div(Source.EMPTY, new Mul(Source.EMPTY, a, b), constant(ElementKind.DOUBLE, 1000L));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "(a*b)/1000.0 double divisor");
        String shape = fused.shape();
        byte[][] classBytes = FusionVerify.classBytesForTests(shape);
        assertThat("verify-mode must capture fused class bytes for " + shape, classBytes, notNullValue());
        assertThat("verify-mode must capture at least one fused class", classBytes.length, greaterThan(0));
        for (byte[] bytes : classBytes) {
            assertThat("DOUBLE 1000.0 must not appear as LDC/LDC2_W", hasDoubleImmediate(bytes, 1000.0), is(false));
            String desc = fusedMethodDescriptor(bytes);
            assertThat("DOUBLE divisor must occupy a trailing D param: " + desc, trailingPrimitiveParamCount(desc, 'D'), greaterThan(0));
        }
        assertThat("DOUBLE divisor must not bake its value into the shape: " + shape, shape, not(containsString("#C:D:")));
        assertThat("DOUBLE divisor must still occupy a typed constant slot: " + shape, shape, containsString("#C:D"));
        int positions = 16;
        Block[] inputs = { denseColumn(ElementKind.DOUBLE, positions, 1), denseColumn(ElementKind.DOUBLE, positions, 2) };
        runConstantDifferential(expr, layout, ElementKind.DOUBLE, inputs, positions, "(a*b)/1000.0 double not-baked");
    }

    /**
     *: a DOUBLE non-finite result with a NON-ZERO divisor (large/small → Inf via {@code asFiniteNumber}) must
     * match unfused. The existing constant-divisor suite only covers {@code /0}.
     */
    public void testDoubleNonFiniteWithNonZeroDivisorMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        Layout layout = layout(a, b);
        Literal tiny = new Literal(Source.EMPTY, 1e-300, DataType.DOUBLE);
        Expression expr = new Div(Source.EMPTY, new Mul(Source.EMPTY, a, b), tiny);
        int positions = 4;
        Block[] inputs = {
            blockFactory.newDoubleArrayVector(new double[] { 1e300, -1e300, 1.0, 0.0 }, positions).asBlock(),
            blockFactory.newDoubleArrayVector(new double[] { 1.0, 1.0, 1.0, 1.0 }, positions).asBlock() };
        runConstantDifferential(expr, layout, ElementKind.DOUBLE, inputs, positions, "(a*b)/1e-300 double Inf");
    }

    /**
     * The per-expression {@code allowNonFinite} flag (NonFiniteSupport — the PromQL-only lenient double path) is
     * baked as a trailing {@code boolean} kernel operand, so a fused double {@code Add}/{@code Sub}/{@code Mul}/{@code
     * Div}/{@code Mod} must match its unfused chain in BOTH modes over non-finite inputs. With {@code
     * allowNonFinite=false} (the ES|QL default) a non-finite result — {@code NaN}, {@code ±Inf}, an overflow-to-{@code
     * Inf}, or a zero divisor — is rejected to {@code null} with an {@link ArithmeticException} warning; with {@code
     * allowNonFinite=true} the same result is returned as-is with no warning. A {@code NaN} input at position 0
     * guarantees a non-finite flows through every operator, so the strict mode warns and the lenient mode does not.
     * The differential (same flag on both sides) proves the baked flag drives identical values and null positions
     * whichever way it is set; the per-mode warning assertions prove it also drives the warn-vs-keep behaviour.
     */
    public void testDoubleArithmeticAllowNonFiniteMatchesUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);
        int positions = 8;
        for (boolean allowNonFinite : new boolean[] { false, true }) {
            for (NonFiniteArithmeticOp op : NonFiniteArithmeticOp.values()) {
                Expression expr = op.build(op.build(a, b, allowNonFinite), c, allowNonFinite);
                Block[] inputs = {
                    blockFactory.newDoubleArrayVector(new double[] { Double.NaN, 1e308, -1e308, 1e308, 2.0, 1.0, 5.0, -3.5 }, positions)
                        .asBlock(),
                    blockFactory.newDoubleArrayVector(new double[] { 1.0, 1e308, 1e308, 10.0, 0.0, 3.0, 0.0, 2.0 }, positions).asBlock(),
                    blockFactory.newDoubleArrayVector(new double[] { 2.0, 1.0, 1.0, 1.0, 1.0, 7.0, 4.0, 0.0 }, positions).asBlock() };
                String ctx = op + " allowNonFinite=" + allowNonFinite;
                DifferentialWarnings warnings = runConstantDifferential(expr, layout, ElementKind.DOUBLE, inputs, positions, ctx);
                if (allowNonFinite) {
                    assertThat(
                        ctx + ": the lenient path must return non-finite results WITHOUT any warning; fused=" + warnings.fused(),
                        warnings.fused().isEmpty(),
                        is(true)
                    );
                } else {
                    assertFusedEmitsWarning(warnings.fused(), "ArithmeticException", ctx + ": strict path must reject the NaN");
                }
            }
        }
    }

    /**
     * The {@code allowNonFinite} flag threads through every double MATH kernel exactly as it does through the
     * arithmetic ones: with the flag {@code false} an out-of-domain input (negative sqrt, non-positive log,
     * out-of-range arccos, overflowing cosh, …) is rejected to {@code null} with an {@link ArithmeticException}
     * warning; with the flag {@code true} the raw non-finite ({@code NaN}/{@code ±Inf}) is returned with no warning.
     * Each function is nested one level under a {@link Mul} that reuses its first column, so the whole tree is a
     * depth-2 fusable shape; the differential (same flag on both sides) proves the fused kernel's baked flag drives
     * identical values, null positions and warnings as the unfused chain, and the per-mode assertions prove the
     * warn-vs-keep behaviour. Covers the ten {@link NonFiniteMathFn} functions across both flag settings.
     */
    public void testDoubleMathFunctionsAllowNonFiniteMatchUnfused() {
        for (NonFiniteMathFn fn : NonFiniteMathFn.values()) {
            double[][] cols = fn.columns();
            int positions = cols[0].length;
            FieldAttribute[] attrs = new FieldAttribute[cols.length];
            for (int i = 0; i < cols.length; i++) {
                attrs[i] = field("c" + i, DataType.DOUBLE);
            }
            Layout layout = layout(attrs);
            for (boolean allowNonFinite : new boolean[] { false, true }) {
                Expression expr = new Mul(Source.EMPTY, fn.build(attrs, allowNonFinite), attrs[0], allowNonFinite);
                Block[] inputs = new Block[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    inputs[i] = blockFactory.newDoubleArrayVector(cols[i], positions).asBlock();
                }
                String ctx = fn + " allowNonFinite=" + allowNonFinite;
                DifferentialWarnings warnings = runConstantDifferential(expr, layout, ElementKind.DOUBLE, inputs, positions, ctx);
                if (allowNonFinite) {
                    assertThat(
                        ctx + ": the lenient path must return non-finite results WITHOUT any warning; fused=" + warnings.fused(),
                        warnings.fused().isEmpty(),
                        is(true)
                    );
                } else {
                    assertFusedEmitsWarning(warnings.fused(), fn.strictWarning(), ctx + ": strict path must reject the non-finite result");
                }
            }
        }
    }

    /**
     * The baked {@code allowNonFinite} flag participates in the {@link FusedClassCache} key, so the strict
     * ({@code false}) and non-finite ({@code true}) variants of the same double expression resolve to DISTINCT fused
     * classes and never collide onto one — a shared class would silently give one variant the other's divide-by-zero /
     * overflow behaviour. Both are built through the real plan-time path (populating the shared cache with the first,
     * then resolving the second); the shapes must differ and each must carry its own {@code #C:Z:<flag>} marker.
     */
    public void testAllowNonFiniteVariantsResolveToDistinctClasses() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);
        Expression strict = new Add(
            Source.EMPTY,
            new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG, false),
            c,
            EsqlTestUtils.TEST_CFG,
            false
        );
        Expression lenient = new Add(
            Source.EMPTY,
            new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG, true),
            c,
            EsqlTestUtils.TEST_CFG,
            true
        );
        String strictShape = assertFuses(fusedFactory(strict, layout), "strict (a+b)+c must fuse").shape();
        String lenientShape = assertFuses(fusedFactory(lenient, layout), "lenient (a+b)+c must fuse").shape();
        assertThat(
            "strict variant must bake allowNonFinite=false into the shape key: " + strictShape,
            strictShape,
            containsString("#C:Z:false")
        );
        assertThat(
            "lenient variant must bake allowNonFinite=true into the shape key: " + lenientShape,
            lenientShape,
            containsString("#C:Z:true")
        );
        assertThat("strict and non-finite variants must key DISTINCT fused classes", strictShape, not(equalTo(lenientShape)));
    }

    /** The five double arithmetic operators carrying a per-expression {@code allowNonFinite} flag (NonFiniteSupport). */
    private enum NonFiniteArithmeticOp {
        ADD,
        SUB,
        MUL,
        DIV,
        MOD;

        Expression build(Expression left, Expression right, boolean allowNonFinite) {
            return switch (this) {
                case ADD -> new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG, allowNonFinite);
                case SUB -> new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG, allowNonFinite);
                case MUL -> new Mul(Source.EMPTY, left, right, allowNonFinite);
                case DIV -> new Div(Source.EMPTY, left, right, null, allowNonFinite);
                case MOD -> new Mod(Source.EMPTY, left, right, allowNonFinite);
            };
        }
    }

    /**
     * The double MATH functions carrying a per-expression {@code allowNonFinite} flag (NonFiniteSupport). Each constant
     * supplies its own out-of-domain input columns (so the strict path throws at some positions and the lenient path
     * returns a non-finite there), builds the function over those columns, and names the strict-mode warning substring.
     */
    private enum NonFiniteMathFn {
        SQRT,
        LOG1,
        LOG2,
        LOG10,
        POW,
        ACOS,
        ASIN,
        ACOSH,
        ATANH,
        COSH,
        SINH;

        /** Input column(s) read by this function's leaves; each row includes at least one out-of-domain position. */
        double[][] columns() {
            return switch (this) {
                case SQRT -> new double[][] { { 4.0, -1.0, 9.0, -4.0, 0.25 } };
                case LOG1 -> new double[][] { { Math.E, -2.0, 1.0, 0.0, 10.0 } };
                case LOG2 -> new double[][] { { 2.0, 2.0, 10.0, 2.0, 3.0 }, { 8.0, -4.0, 1.0, 0.0, 9.0 } };
                case LOG10 -> new double[][] { { 100.0, -5.0, 1.0, 0.0, 1000.0 } };
                case POW -> new double[][] { { 2.0, 10.0, 3.0, 5.0, 2.0 }, { 3.0, 400.0, 2.0, 2.0, 10.0 } };
                case ACOS -> new double[][] { { 0.5, 2.0, -1.0, 3.0, 0.0 } };
                case ASIN -> new double[][] { { 0.5, 2.0, -1.0, -3.0, 0.0 } };
                case ACOSH -> new double[][] { { 2.0, 0.5, 1.0, -1.0, 3.0 } };
                case ATANH -> new double[][] { { 0.5, 2.0, 0.0, -3.0, 0.9 } };
                case COSH -> new double[][] { { 1.0, 1000.0, 2.0, 710.0, 0.0 } };
                case SINH -> new double[][] { { 1.0, 1000.0, 2.0, 710.0, 0.0 } };
            };
        }

        Expression build(FieldAttribute[] cols, boolean allowNonFinite) {
            return switch (this) {
                case SQRT -> new Sqrt(Source.EMPTY, cols[0], allowNonFinite);
                case LOG1 -> new Log(Source.EMPTY, cols[0], null, allowNonFinite);
                case LOG2 -> new Log(Source.EMPTY, cols[0], cols[1], allowNonFinite);
                case LOG10 -> new Log10(Source.EMPTY, cols[0], allowNonFinite);
                case POW -> new Pow(Source.EMPTY, cols[0], cols[1], allowNonFinite);
                case ACOS -> new Acos(Source.EMPTY, cols[0], allowNonFinite);
                case ASIN -> new Asin(Source.EMPTY, cols[0], allowNonFinite);
                case ACOSH -> new Acosh(Source.EMPTY, cols[0], allowNonFinite);
                case ATANH -> new Atanh(Source.EMPTY, cols[0], allowNonFinite);
                case COSH -> new Cosh(Source.EMPTY, cols[0], allowNonFinite);
                case SINH -> new Sinh(Source.EMPTY, cols[0], allowNonFinite);
            };
        }

        String strictWarning() {
            return switch (this) {
                case SQRT -> "Square root of negative";
                case LOG1, LOG2, LOG10 -> "Log of non-positive number";
                case POW -> "not a finite double number";
                case ACOS -> "Acos input out of range";
                case ASIN -> "Asin input out of range";
                case ACOSH -> "Acosh input out of range";
                case ATANH -> "Atanh input out of range";
                case COSH -> "cosh overflow";
                case SINH -> "sinh overflow";
            };
        }
    }

    private static String fusedMethodDescriptor(byte[] bytecode) {
        String[] desc = { null };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (Stitcher.FUSED_METHOD_NAME.equals(name) && desc[0] == null) {
                    desc[0] = descriptor;
                }
                return null;
            }
        }, 0);
        assertThat("emitted class must define fused()", desc[0] != null, is(true));
        return desc[0];
    }

    private static int trailingPrimitiveParamCount(String descriptor, char element) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return 0;
        }
        String params = descriptor.substring(1, close);
        int lastRef = params.lastIndexOf(';');
        int start = lastRef < 0 ? 0 : lastRef + 1;
        int count = 0;
        for (int i = start; i < params.length(); i++) {
            if (params.charAt(i) == element) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasDoubleImmediate(byte[] bytecode, double value) {
        boolean[] found = { false };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof Double d && Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(value)) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (value == 0.0 && opcode == Opcodes.DCONST_0) {
                            found[0] = true;
                        }
                        if (value == 1.0 && opcode == Opcodes.DCONST_1) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
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

        assertNullLiteralFallsBack(
            new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), nullLiteral),
            layout,
            ElementKind.LONG,
            "arithmetic (a+b)*null"
        );
        assertNullLiteralFallsBack(
            new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), nullLiteral),
            layout,
            ElementKind.BOOLEAN,
            "comparison (a+b)>null"
        );
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
     * Asserts {@code expr} fuses with the kill switch ON, does not with it OFF, and that the fused body matches the
     * unfused chain over the given page. Only a Div/Mod divisor Literal is baked as a bytecode immediate;
     * every other literal stays a trailing method parameter (so {@code a > 5} and {@code a > 6} still share a class).
     * Returns BOTH the fused and unfused warning lists so a caller can assert an expected warning (overflow / div-zero)
     * fired on the fused side too (parity), not only on the unfused reference.
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
        /** Nested int/long/double mixed-type add. */
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
     * The mixed-type sweep: for each {@link MixedShape} and profile it builds a tree whose operands span two or
     * three numeric element kinds (so ESQL's {@code commonType} inserts a widening cast the planner must fuse), asserts
     * the tree FUSES (the core unlock — a cast-bridged tree must not silently fall back to the unfused chain),
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

    /** Canonical mixed-type case: {@code doubleCol + intCol} fuses. */
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

    /** DETERMINISTIC casted-operand null/multi-value proof for {@code doubleCol + intCol} */
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

    /** DETERMINISTIC casted-operand null/multi-value proof for {@code longCol * intCol} */
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
        assertFuses(fusedFactory, ctx); // core unlock: the cast-bridged tree must FUSE, not fall back
        assertDoesNotFuse(unfusedFactory, ctx + " kill switch OFF");

        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            CapturedEval fusedCap = evalCapturing(fusedFactory, page);
            fusedResult = fusedCap.block();
            CapturedEval referenceCap = evalCapturing(unfusedFactory, page);
            referenceResult = referenceCap.block();
            List<String> fusedWarnings = fusedCap.warnings();
            List<String> referenceWarnings = referenceCap.warnings();

            for (int p = 0; p < positions; p++) {
                assertThat(ctx + " fused null@" + p, fusedResult.isNull(p), is(expectedNullPositions.contains(p)));
            }
            assertResultsEqual(outputKind, fusedResult, referenceResult, positions, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
            assertThat(
                ctx + " the unfused chain must raise the multi-value warning",
                containsWarning(referenceWarnings, MULTI_VALUE_MSG),
                is(true)
            );
            assertFusedEmitsWarning(fusedWarnings, MULTI_VALUE_MSG, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /** Builds a page whose columns may have DIFFERENT element kinds (mixed-type trees), forcing an abnormal referenced column. */
    private Block[] buildMixedPage(ElementKind[] kinds, int positionCount, Profile profile, int[] referenced) {
        boolean abnormal = profile == Profile.NULLS || profile == Profile.MULTIVALUE || profile == Profile.NULLS_AND_MULTIVALUE;
        int forced = abnormal && referenced.length > 0 ? referenced[randomInt(referenced.length - 1)] : -1;
        Structure pageStructure = structureFor(profile, positionCount, false);
        Block[] blocks = new Block[kinds.length];
        for (int c = 0; c < kinds.length; c++) {
            Structure structure = c == forced ? structureFor(profile, positionCount, true) : pageStructure;
            blocks[c] = buildColumn(kinds[c], positionCount, structure, profile, false);
        }
        return blocks;
    }

    /**
     * The math-function sweep: double trees mixing overflow-checked binary arithmetic with the scalar math
     * functions, under both an ARITHMETIC root ({@code a + sqrt(b)}, {@code sin(a) * c}) and a COMPARISON root
     * ({@code pow(a, b) > c}). Because {@link #nextDouble} spans negatives, {@code Sqrt}/{@code Log} naturally meet
     * out-of-domain inputs and {@code Pow} can overflow, so the sweep exercises the domain-error null+warn path in
     * addition to the clean/null/multi-value paths — all asserted fused==unfused (values + null positions) with the
     * ratified warning subset. Overflow-checked math kernels ({@code Sqrt}/{@code Log}/{@code Pow}) reach the
     * checked-vector fast path (the VectorUnsafe win, now for double math functions).
     */
    public void testMathFunctionSpecSweepMatchesUnfused() {
        Coverage cov = new Coverage();
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        sweep(cov, Category.ARITHMETIC, ARITH_AND_MATH, List.of(ElementKind.DOUBLE), cycle, false, 150);
        sweep(cov, Category.COMPARISON, ARITH_AND_MATH, List.of(ElementKind.DOUBLE), cycle, false, 150);

        assertShapeMatrixCovered(cov);
        assertProfilesCovered(cov, Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW);
        assertThat(
            "a double math tree with an overflow-checked kernel must reach the checked-vector fast path",
            cov.checkedVector,
            greaterThan(0)
        );
        assertThat(
            "a double math tree (Sqrt/Log/Pow) must reach the checked-vector path (VectorUnsafe for math fns)",
            strategyHits(cov, ElementKind.DOUBLE, VectorStrategy.CHECKED_VECTOR),
            greaterThan(0)
        );
        assertThat("a multi-value input feeding a math function must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    /**
     * {@code sin(intCol)} — a math function over a NON-double column — fuses as {@code Sin(castIntToDouble(intCol))},
     * composing the widening cast kernel with the function kernel (kernel depth 2), and matches the
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
     * path and its overflow nulls+warns identically to the unfused chain.
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

    /** Asserts a DOUBLE math tree {@code expr} fuses, that its fused result is null at exactly {@code expectedNull} */
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

        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            CapturedEval fusedCap = evalCapturing(fusedFactory, page);
            fusedResult = fusedCap.block();
            CapturedEval referenceCap = evalCapturing(unfusedFactory, page);
            referenceResult = referenceCap.block();
            List<String> fusedWarnings = fusedCap.warnings();
            List<String> referenceWarnings = referenceCap.warnings();

            for (int p = 0; p < positions; p++) {
                assertThat(ctx + " fused null@" + p, fusedResult.isNull(p), is(expectedNull.contains(p)));
            }
            assertResultsEqual(ElementKind.DOUBLE, fusedResult, referenceResult, positions, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
            return new DifferentialWarnings(fusedWarnings, referenceWarnings);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

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

    private static int columnsFor(Category root) {
        return switch (root) {
            case ARITHMETIC -> randomIntBetween(1, 4);
            case COMPARISON -> randomIntBetween(2, 4);
            case LOGICAL -> randomIntBetween(4, 6);
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

    /**
     * Picks a template from {@code palette} that supports {@code element} (i.e. {@code element} is in its
     * {@code allowedElements}). This is the seam that makes the template model safe for later operators: a future
     * double-only template ({@code allowedElements = {DOUBLE}}) is only ever generated under a double subtree, never
     * wired with int/long args. Fails loudly if no template in the palette supports the element.
     */
    private OpTemplate randomNumericTemplate(List<OpTemplate> palette, ElementKind element) {
        List<OpTemplate> eligible = palette.stream().filter(t -> t.allowedElements.contains(element)).toList();
        assertThat("no template in palette " + palette + " supports element " + element, eligible.isEmpty(), is(false));
        return randomFrom(eligible);
    }

    /** A numeric tree whose deepest path has kernel depth in {@code [minKernel, maxDepth]} (so with {@code minKernel>=1} */
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
        Expression deep = genNumeric(palette, minKernel - 1, maxDepth - 1, element, cols, used);
        Expression shallow = genNumeric(palette, 0, maxDepth - 1, element, cols, used);
        return randomBoolean() ? opTemplate.build(List.of(deep, shallow)) : opTemplate.build(List.of(shallow, deep));
    }

    /**
     * A comparison <b>root</b> over two numeric subtrees (arithmetic under comparison). At least one child is guaranteed
     * to be a numeric kernel (depth &ge; 1), so the whole tree has kernel depth &ge; 2 and fuses. This shape is only
     * fusable at the ROOT: a comparison over arithmetic is <b>not</b> an accepted logical operand (see
     * {@link #genColumnComparison} and {@code FusionPlanner.buildComparisonOperand} — the current logical scope).
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
     * in the current logical scope). Depth-1 on its own (would not fuse alone), but fusable as a logical operand.
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

    /**
     * True if the tree spans at least two operator categories — the currently-fusable cross-category combos that fell
     * between the three old single-category harnesses: {@code COMPARISON} over {@code ARITHMETIC} (comparison root over
     * {@code a+b}), and {@code LOGICAL} over {@code COMPARISON} (a 3VL connective over column comparisons). A tree
     * spanning all three ({@code ARITHMETIC} under {@code COMPARISON} under {@code LOGICAL}) is NOT fusable under the
     * logical scope and is covered separately by {@link #testArithmeticUnderComparisonUnderLogicalDoesNotFuseYet}.
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
            FieldAttribute a = field("a", DataType.LONG);
            FieldAttribute b = field("b", DataType.LONG);
            List<Expression> args = t.arity == 1 ? List.of(a) : List.of(a, b);
            Expression built = t.build(args);
            assertThat(t.name + " factory must build a non-null Expression", built, instanceOf(Expression.class));
            boolean categoryOk = switch (t.category) {
                case ARITHMETIC -> built instanceof EsqlArithmeticOperation || built instanceof Neg || built instanceof Abs;
                case COMPARISON -> built instanceof EsqlBinaryComparison;
                case LOGICAL -> built instanceof BinaryLogic;
                case MATH -> built instanceof EsqlScalarFunction && built.dataType() == DataType.DOUBLE;
            };
            if (categoryOk == false) {
                problems.add(t.name + " built a " + built.getClass().getSimpleName() + " that does not match category " + t.category);
            }
        }
        assertThat("template palette problems: " + problems, problems.isEmpty(), is(true));
    }
}
