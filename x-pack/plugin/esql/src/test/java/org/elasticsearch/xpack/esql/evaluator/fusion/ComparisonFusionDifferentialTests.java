/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Differential, fused-vs-unfused sweep for <b>comparison-rooted</b> fusion (iter 19, S3.0): expression trees whose root
 * is a boolean-producing comparison ({@code ==}, {@code !=}, {@code <}, {@code <=}, {@code >}, {@code >=}) over
 * homogeneous-primitive ({@code long}/{@code int}/{@code double}) arithmetic children with column ({@link FieldAttribute})
 * leaves only — e.g. {@code a + b > c}, {@code (a + b) > (c - d)}, {@code abs(a) >= abs(b)}.
 *
 * <p>Each tree is built <b>twice through the real plan-time path</b> {@link EvalMapper#toEvaluator}: once with the
 * production kill switch {@link FusionSettings} <b>enabled</b> (routing the fusable subtree through {@link FusionPlanner}
 * + the {@link org.elasticsearch.compute.operator.fusion.Stitcher} into a {@link FusedExpressionEvaluatorFactory} that
 * now emits a <b>boolean</b> output element from <b>numeric</b> inputs) and once <b>disabled</b> (the ordinary generated
 * comparison-evaluator chain). Both evaluators run over the same random {@link Page} and are asserted to satisfy the
 * ratified fusion contract: the result is a {@link BooleanBlock}, its VALUES and NULL positions are <b>identical</b> to
 * the unfused chain's, and the fused WARNING set is a <b>subset</b> of the unfused set (short-circuit can only drop
 * warnings, never invent one). See {@link FusionDifferentialTests} for the full rationale of the subset invariant.
 *
 * <h2>Input/output element split (what this iter exercises)</h2>
 * The fused method reads its inputs with the numeric INPUT element ({@code rawValues()[J}/{@code getLong}/...) and writes
 * its output with the boolean OUTPUT element ({@code new boolean[]}/{@code newBooleanArrayVector}/{@code appendBoolean}).
 * A comparison kernel body leaves an {@code int} 0/1 on the stack, stored/appended as a boolean. The three fused paths
 * are all covered:
 * <ul>
 *   <li><b>plain-vector</b> — a comparison over non-overflow {@code double} {@code Neg}/{@code Abs} children (e.g.
 *       {@code abs(a) > abs(b)}) is overflow-free, so the dense {@code BooleanVector}-output fast path is used;</li>
 *   <li><b>checked-vector</b> — a comparison over overflow-checked arithmetic children of <b>any</b> numeric element
 *       ({@code long}/{@code int}/{@code double}, e.g. {@code a + b > c}) reads each input's backing array via the
 *       public {@code VectorUnsafe} forwarder but appends through a {@code BooleanBlock.Builder} so an overflow in a
 *       child nulls the position; {@code double}+overflow is no longer hard-gated (the fused class links
 *       {@code NumericUtils} from the caller module);</li>
 *   <li><b>block</b> — any tree with null/multi-value inputs runs the null/multi-value/overflow-tolerant block path.</li>
 * </ul>
 * Everything runs on a real breaker-backed {@link BlockFactory}; a {@code @After} asserts the breaker is balanced.
 */
public class ComparisonFusionDifferentialTests extends ESTestCase {

    private static final String MULTI_VALUE_MSG = "single-value function encountered multi-value";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private MockBigArrays bigArrays;
    private DriverContext driverContext;

    @Before
    public void setUpFusion() throws Exception {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService);
        blockFactory = BlockFactory.builder(bigArrays).build();
        driverContext = new DriverContext(bigArrays, blockFactory, null);
        FusionTelemetry.resetForTests();
        FusionPlanner.compilerForTests = null;
        FusionSettings.setEnabledForTests(true);
    }

    @After
    public void tearDownFusion() {
        FusionPlanner.compilerForTests = null;
        FusionSettings.setEnabledForTests(true);
        assertThat(breaker.getUsed(), is(0L));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Randomized sweeps
    // ---------------------------------------------------------------------------------------------------------------

    public void testLongComparisonFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.NEG, Op.ABS);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        Coverage cov = runSweep(ElementKind.LONG, DataType.LONG, palette, cycle, 96);

        assertMatrixCovered(cov);
        assertThat("long comparison trees over overflow-checked children => checked-vector", cov.checkedVector, greaterThan(0));
        assertThat("overflow in an arithmetic child must null a position (warning)", cov.overflowWarnings, greaterThan(0));
        assertThat("a multi-value input must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testIntComparisonFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.NEG, Op.ABS);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        Coverage cov = runSweep(ElementKind.INT, DataType.INTEGER, palette, cycle, 96);

        assertMatrixCovered(cov);
        assertThat("int comparison trees over overflow-checked children => checked-vector", cov.checkedVector, greaterThan(0));
        assertThat("overflow in an arithmetic child must null a position (warning)", cov.overflowWarnings, greaterThan(0));
        assertThat("a multi-value input must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testDoubleComparisonBinaryFusedMatchesUnfused() {
        // Add/Sub/Mul double children are overflow-checked (NumericUtils.asFiniteNumber). The old hard gate is gone:
        // the fused class is defined in this caller module and reads backing arrays via VectorUnsafe, so NumericUtils
        // links on the checked-vector path. A dense no-null page therefore takes the checked-vector fast path.
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE };
        Coverage cov = runSweep(ElementKind.DOUBLE, DataType.DOUBLE, palette, cycle, 96);

        assertMatrixCovered(cov);
        assertThat("double+overflow comparison now uses the checked-vector path", cov.checkedVector, greaterThan(0));
        assertThat("a multi-value input must register a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testDoubleComparisonUnaryUsesPlainVector() {
        // Neg/Abs double children are NOT overflow-checked, so a comparison over them is overflow-free and eligible for
        // the plain-vector fast path with a dense BooleanVector output.
        List<Op> palette = List.of(Op.NEG, Op.ABS);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE };
        Coverage cov = runSweep(ElementKind.DOUBLE, DataType.DOUBLE, palette, cycle, 96);

        assertMatrixCovered(cov);
        assertThat("a Neg/Abs-only double comparison must use the plain-vector fast path", cov.plainVector, greaterThan(0));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Targeted, deterministic shapes
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code a + b > c} over longs fuses ON (checked-vector), NOT OFF, and produces a BooleanVector-backed block. */
    public void testAddGreaterThanColumnFusesToBoolean() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertThat("a + b > c must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat("a + b > c must NOT fuse OFF", unfused, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
        assertThat(
            "long comparison over overflow-checked children uses the checked-vector strategy",
            ((FusedExpressionEvaluatorFactory) fused).vectorStrategy(),
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
        runAndAssertEqual(fused, unfused, ElementKind.LONG, inputs, positions, "a+b>c");
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
        assertThat("(a+b) > (c-d) must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat("(a+b) > (c-d) must NOT fuse OFF", unfused, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

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
        runAndAssertEqual(fused, unfused, ElementKind.INT, inputs, positions, "(a+b)>(c-d)");
    }

    /**
     * A bare {@code a > b} (kernel depth 1) is deliberately NOT fused, consistent with the established depth&ge;2 fusion
     * invariant: a single kernel gains nothing from stitching (there is no intermediate to keep in a register / avoid a
     * block round-trip), and the generated comparison evaluator already handles it. Depth-1 fusion is intentionally out
     * of scope.
     */
    public void testBareComparisonDoesNotFuse() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(Source.EMPTY, a, b);
        assertThat(
            "a bare comparison of two columns has kernel depth 1 and must not fuse",
            fusedFactory(expr, layout),
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );
    }

    /**
     * Companion to {@link #testBareComparisonDoesNotFuse}: even though a bare {@code a > b} does not fuse, its
     * value/null output must still match — trivially, since with the kill switch ON and OFF both sides resolve to the
     * same generated comparison evaluator. This demonstrates parity for the depth-1 shape rather than only asserting
     * non-fusion.
     */
    public void testBareComparisonParityMatchesUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(a, b);
        Expression expr = new GreaterThan(Source.EMPTY, a, b);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertThat("bare a > b must not fuse even with the kill switch ON", fused, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        long[] av = { 5, 5, 1, 9, -3 };
        long[] bv = { 3, 5, 8, 9, -3 };
        int positions = av.length;
        Block[] inputs = {
            blockFactory.newLongArrayVector(av, positions).asBlock(),
            blockFactory.newLongArrayVector(bv, positions).asBlock() };
        runAndAssertEqual(fused, unfused, ElementKind.LONG, inputs, positions, "bare a>b parity");
    }

    /**
     * Deterministic per-operator coverage: every comparison operator ({@code ==}, {@code !=}, {@code <}, {@code <=},
     * {@code >}, {@code >=}) over each supported element kind fuses and matches the unfused chain. Enumerated
     * explicitly rather than relying on a random palette to hit each within N iterations. {@code a + b <cmp> c} keeps
     * an overflow-checked arithmetic child so long/int/double all route through the checked-vector path (the old
     * double hard gate is gone); the dense input has a spread of values so each operator produces a mix of true/false.
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
        assertThat(ctx + " must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(ctx + " must NOT fuse OFF", unfused, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        int positions = 8;
        Block[] inputs = { denseColumn(kind, positions, 0), denseColumn(kind, positions, 1), denseColumn(kind, positions, 2) };
        runAndAssertEqual(fused, unfused, kind, inputs, positions, ctx);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // PRIORITY-1 regression: block-path warning subset when a null / intermediate overflow preempts a sibling
    // multi-value at the SAME position. The unfused kernel chain evaluates depth-first and short-circuits the position
    // to null on the first null/multi-value/overflow, never reaching (or warning about) a later-in-DFS-order operand.
    // A fused loop that pre-guards every leaf up front would emit a multi-value warning the unfused chain never
    // raises, breaking the ratified fused ⊆ unfused invariant. These would FAIL before the DFS-order block-loop fix.
    // The randomized sweeps never expose this because each page carries a single profile (nulls XOR multi-value XOR
    // overflow), so the divergent same-position combinations never arise there.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code (a + b) > c} over longs at a single position where {@code a + b} OVERFLOWS (unfused nulls the position
     * with an OVERFLOW warning and never inspects {@code c}) while {@code c} is MULTI-VALUE. A pre-guard-all fused loop
     * would emit the multi-value warning instead ({mv} ⊄ {overflow}); the DFS block loop lets the intermediate
     * overflow short-circuit before c's guard, so fused warnings ⊆ unfused. Values/nulls identical (both null).
     */
    public void testBlockPathIntermediateOverflowPreemptsSiblingMultiValueWarning() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertThat("(a+b) > c must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));

        int positions = 1;
        Block[] inputs = {
            blockFactory.newLongArrayVector(new long[] { Long.MAX_VALUE }, positions).asBlock(),
            blockFactory.newLongArrayVector(new long[] { 1L }, positions).asBlock(),
            multiValueLong(new long[] { 1L, 2L }) };
        runAndAssertEqual(fused, unfused, ElementKind.LONG, inputs, positions, "overflow+mv same position");
    }

    /**
     * {@code (a + b) > c} over longs at a single position where {@code a} is NULL and {@code c} is MULTI-VALUE, with the
     * layout ordered so {@code c} has a LOWER channel than {@code a}. The unfused chain nulls the position via
     * {@code a}'s null (NO warning) and never inspects {@code c}; a fused loop guarding leaves in channel order would
     * hit {@code c} first and wrongly emit the multi-value warning (fused-only {mv}). DFS order evaluates the
     * {@code a + b} subtree first and short-circuits on {@code a}'s null before c's guard, so neither side warns.
     */
    public void testBlockPathNullLeafPreemptsSiblingMultiValueWarning() {
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(c, a, b); // c = channel 0, a = channel 1, b = channel 2
        Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertThat("(a+b) > c must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));

        int positions = 1;
        Block[] inputs = {
            multiValueLong(new long[] { 1L, 2L }),                                  // channel 0: c, multi-value
            nullLong(positions),                                                    // channel 1: a, null
            blockFactory.newLongArrayVector(new long[] { 5L }, positions).asBlock() // channel 2: b
        };
        runAndAssertEqual(fused, unfused, ElementKind.LONG, inputs, positions, "null+mv same position");
    }

    /**
     * The block-loop fix is general, so the same short-circuit invariant is asserted for an ARITHMETIC-rooted tree.
     * {@code (a + b) + c} over longs at a single position has {@code a + b} OVERFLOWING while {@code c} is MULTI-VALUE:
     * the unfused chain warns OVERFLOW and never inspects {@code c}, and the DFS block loop matches it (fused ⊆
     * unfused). A pre-guard-all fused loop would have emitted the multi-value warning instead.
     */
    public void testBlockPathArithmeticRootOverflowPreemptsSiblingMultiValueWarning() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Add(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertThat("(a+b) + c must fuse ON", fused, instanceOf(FusedExpressionEvaluatorFactory.class));

        int positions = 1;
        Block[] inputs = {
            blockFactory.newLongArrayVector(new long[] { Long.MAX_VALUE }, positions).asBlock(),
            blockFactory.newLongArrayVector(new long[] { 1L }, positions).asBlock(),
            multiValueLong(new long[] { 1L, 2L }) };
        runAndAssertLongEqual(fused, unfused, inputs, positions, "arith-root overflow+mv same position");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core sweep
    // ---------------------------------------------------------------------------------------------------------------

    private Coverage runSweep(ElementKind kind, DataType type, List<Op> childPalette, Profile[] cycle, int iterations) {
        Coverage cov = new Coverage();
        for (int i = 0; i < iterations; i++) {
            int positionCount = positionCountFor(i);
            Profile profile = cycle[i % cycle.length];

            int columns = randomIntBetween(2, 4);
            FieldAttribute[] cols = new FieldAttribute[columns];
            for (int c = 0; c < columns; c++) {
                cols[c] = field("c" + c, type);
            }
            Layout layout = layout(cols);

            Set<Integer> usedSet = new TreeSet<>();
            Expression expr = genComparison(childPalette, cols, usedSet);
            int[] referenced = toArray(usedSet);

            FusionSettings.setEnabledForTests(true);
            ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            FusionSettings.setEnabledForTests(false);
            ExpressionEvaluator.Factory unfusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            FusionSettings.setEnabledForTests(true);

            assertThat(
                "comparison root must fuse ON i=" + i + " expr=" + expr,
                fusedFactory,
                instanceOf(FusedExpressionEvaluatorFactory.class)
            );
            assertThat(
                "comparison root must NOT fuse OFF i=" + i + " expr=" + expr,
                unfusedFactory,
                not(instanceOf(FusedExpressionEvaluatorFactory.class))
            );

            recordStrategy(cov, ((FusedExpressionEvaluatorFactory) fusedFactory).vectorStrategy());
            recordShape(cov, positionCount);

            Block[] inputs = buildPage(kind, columns, positionCount, profile, referenced);
            ExpressionEvaluator fused = fusedFactory.get(driverContext);
            ExpressionEvaluator reference = unfusedFactory.get(driverContext);
            Page page = null;
            Block fusedResult = null;
            Block referenceResult = null;
            try {
                page = new Page(positionCount, inputs);

                drainWarnings();
                fusedResult = fused.eval(page);
                List<String> fusedWarnings = drainWarnings();
                referenceResult = reference.eval(page);
                List<String> referenceWarnings = drainWarnings();

                String ctx = "i=" + i + " profile=" + profile + " positions=" + positionCount + " expr=" + expr;
                assertThat(ctx + " fused output is a BooleanBlock", fusedResult, instanceOf(BooleanBlock.class));
                assertThat(ctx + " unfused output is a BooleanBlock", referenceResult, instanceOf(BooleanBlock.class));
                assertBooleanResultsEqual(fusedResult, referenceResult, positionCount, ctx);
                assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
                recordWarnings(cov, profile, referenceWarnings);
            } finally {
                Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
                if (page != null) {
                    page.releaseBlocks();
                }
            }
        }
        return cov;
    }

    private void runAndAssertEqual(
        ExpressionEvaluator.Factory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        ElementKind kind,
        Block[] inputs,
        int positionCount,
        String ctx
    ) {
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            assertThat(ctx + " fused output is a BooleanBlock", fusedResult, instanceOf(BooleanBlock.class));
            assertBooleanResultsEqual(fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    private void runAndAssertLongEqual(
        ExpressionEvaluator.Factory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        Block[] inputs,
        int positionCount,
        String ctx
    ) {
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            assertThat(ctx + " fused output is a LongBlock", fusedResult, instanceOf(LongBlock.class));
            assertLongResultsEqual(fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Differential assertions
    // ---------------------------------------------------------------------------------------------------------------

    private static void assertBooleanResultsEqual(Block fused, Block reference, int positionCount, String ctx) {
        assertThat(ctx + " fused positionCount", fused.getPositionCount(), is(positionCount));
        assertThat(ctx + " reference positionCount", reference.getPositionCount(), is(positionCount));
        BooleanBlock f = (BooleanBlock) fused;
        BooleanBlock r = (BooleanBlock) reference;
        for (int p = 0; p < positionCount; p++) {
            boolean fusedNull = f.isNull(p);
            assertThat(ctx + " null@" + p, fusedNull, is(r.isNull(p)));
            if (fusedNull) {
                continue;
            }
            boolean fv = f.getBoolean(f.getFirstValueIndex(p));
            boolean rv = r.getBoolean(r.getFirstValueIndex(p));
            assertThat(ctx + " value@" + p, fv, equalTo(rv));
        }
    }

    private static void assertLongResultsEqual(Block fused, Block reference, int positionCount, String ctx) {
        assertThat(ctx + " fused positionCount", fused.getPositionCount(), is(positionCount));
        assertThat(ctx + " reference positionCount", reference.getPositionCount(), is(positionCount));
        LongBlock f = (LongBlock) fused;
        LongBlock r = (LongBlock) reference;
        for (int p = 0; p < positionCount; p++) {
            boolean fusedNull = f.isNull(p);
            assertThat(ctx + " null@" + p, fusedNull, is(r.isNull(p)));
            if (fusedNull) {
                continue;
            }
            assertThat(ctx + " value@" + p, f.getLong(f.getFirstValueIndex(p)), equalTo(r.getLong(r.getFirstValueIndex(p))));
        }
    }

    private static void assertWarningsSubset(List<String> fused, List<String> reference, String ctx) {
        Set<String> fusedOnly = new HashSet<>(fused);
        fusedOnly.removeAll(new HashSet<>(reference));
        assertThat(ctx + " fused warnings must be a SUBSET of unfused warnings; fused-only=" + fusedOnly, fusedOnly, is(Set.of()));
    }

    private List<String> drainWarnings() {
        List<String> warnings = new ArrayList<>(threadContext.getResponseHeaders().getOrDefault("Warning", List.of()));
        threadContext.stashContext();
        return warnings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Random comparison-rooted expression trees
    // ---------------------------------------------------------------------------------------------------------------

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

    private enum Op {
        ADD(false),
        SUB(false),
        MUL(false),
        NEG(true),
        ABS(true);

        private final boolean unary;

        Op(boolean unary) {
            this.unary = unary;
        }

        Expression build(Expression left, Expression right) {
            return switch (this) {
                case ADD -> new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
                case SUB -> new Sub(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
                case MUL -> new Mul(Source.EMPTY, left, right);
                case NEG, ABS -> throw new IllegalStateException("unary op used as binary: " + this);
            };
        }

        Expression build(Expression child) {
            return switch (this) {
                case NEG -> new Neg(Source.EMPTY, child);
                case ABS -> new Abs(Source.EMPTY, child);
                default -> throw new IllegalStateException("binary op used as unary: " + this);
            };
        }
    }

    /**
     * Builds a comparison root over two arithmetic subtrees. At least one child is guaranteed to be an arithmetic kernel
     * (depth &ge; 1), so the whole tree has kernel depth &ge; 2 and therefore fuses.
     */
    private Expression genComparison(List<Op> childPalette, FieldAttribute[] cols, Set<Integer> used) {
        Cmp cmp = randomFrom(Cmp.values());
        Expression deep = genArith(childPalette, 1, randomIntBetween(1, 3), cols, used);
        Expression shallow = genArith(childPalette, 0, randomIntBetween(0, 2), cols, used);
        return randomBoolean() ? cmp.build(deep, shallow) : cmp.build(shallow, deep);
    }

    private Expression genArith(List<Op> palette, int minDepth, int maxDepth, FieldAttribute[] cols, Set<Integer> used) {
        if (maxDepth <= 0 || (minDepth <= 0 && randomBoolean())) {
            int idx = randomInt(cols.length - 1);
            used.add(idx);
            return cols[idx];
        }
        Op op = palette.get(randomInt(palette.size() - 1));
        if (op.unary) {
            return op.build(genArith(palette, minDepth - 1, maxDepth - 1, cols, used));
        }
        Expression deep = genArith(palette, minDepth - 1, maxDepth - 1, cols, used);
        Expression shallow = genArith(palette, 0, maxDepth - 1, cols, used);
        return randomBoolean() ? op.build(deep, shallow) : op.build(shallow, deep);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Input page construction
    // ---------------------------------------------------------------------------------------------------------------

    private enum ElementKind {
        LONG,
        INT,
        DOUBLE
    }

    private enum Profile {
        CLEAN,
        NULLS,
        MULTIVALUE,
        OVERFLOW
    }

    private enum Structure {
        DENSE_VECTOR,
        CONSTANT,
        NULLABLE_BLOCK,
        MULTIVALUE_BLOCK
    }

    /** A deterministic dense (no null / multi-value) column of {@code kind}, seeded so distinct columns differ. */
    private Block denseColumn(ElementKind kind, int positions, int seed) {
        return switch (kind) {
            case LONG -> {
                long[] v = new long[positions];
                for (int p = 0; p < positions; p++) {
                    v[p] = ((p * 7 + seed * 3) % 11) - 5;
                }
                yield blockFactory.newLongArrayVector(v, positions).asBlock();
            }
            case INT -> {
                int[] v = new int[positions];
                for (int p = 0; p < positions; p++) {
                    v[p] = ((p * 7 + seed * 3) % 11) - 5;
                }
                yield blockFactory.newIntArrayVector(v, positions).asBlock();
            }
            case DOUBLE -> {
                double[] v = new double[positions];
                for (int p = 0; p < positions; p++) {
                    v[p] = ((p * 7 + seed * 3) % 11) - 5;
                }
                yield blockFactory.newDoubleArrayVector(v, positions).asBlock();
            }
        };
    }

    /** A single-position {@code long} block whose only position holds {@code values} as a multi-value entry. */
    private Block multiValueLong(long[] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(1)) {
            builder.beginPositionEntry();
            for (long v : values) {
                builder.appendLong(v);
            }
            builder.endPositionEntry();
            return builder.build();
        }
    }

    /** A {@code long} block whose every position is null. */
    private Block nullLong(int positions) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positions)) {
            for (int p = 0; p < positions; p++) {
                builder.appendNull();
            }
            return builder.build();
        }
    }

    private Block[] buildPage(ElementKind kind, int columns, int positionCount, Profile profile, int[] referenced) {
        int forced = (profile == Profile.NULLS || profile == Profile.MULTIVALUE) ? referenced[randomInt(referenced.length - 1)] : -1;
        Block[] blocks = new Block[columns];
        for (int c = 0; c < columns; c++) {
            Structure structure = structureFor(profile, positionCount, c == forced);
            blocks[c] = buildColumn(kind, positionCount, structure, profile);
        }
        return blocks;
    }

    private Structure structureFor(Profile profile, int positionCount, boolean forcedAbnormal) {
        if (positionCount == 0) {
            return Structure.DENSE_VECTOR;
        }
        return switch (profile) {
            case CLEAN, OVERFLOW -> randomInt(3) == 0 ? Structure.CONSTANT : Structure.DENSE_VECTOR;
            case NULLS -> forcedAbnormal ? Structure.NULLABLE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK);
            case MULTIVALUE -> forcedAbnormal ? Structure.MULTIVALUE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.MULTIVALUE_BLOCK);
        };
    }

    private Block buildColumn(ElementKind kind, int positionCount, Structure structure, Profile profile) {
        return switch (kind) {
            case LONG -> buildLongColumn(positionCount, structure, () -> nextLong(profile));
            case INT -> buildIntColumn(positionCount, structure, () -> nextInt(profile));
            case DOUBLE -> buildDoubleColumn(positionCount, structure, this::nextDouble);
        };
    }

    private long nextLong(Profile profile) {
        return profile == Profile.OVERFLOW
            ? (randomBoolean() ? Long.MAX_VALUE - randomLongBetween(0, 3) : Long.MIN_VALUE + randomLongBetween(0, 3))
            : randomLongBetween(-20, 20);
    }

    private int nextInt(Profile profile) {
        return profile == Profile.OVERFLOW
            ? (randomBoolean() ? Integer.MAX_VALUE - randomIntBetween(0, 3) : Integer.MIN_VALUE + randomIntBetween(0, 3))
            : randomIntBetween(-20, 20);
    }

    private double nextDouble() {
        return randomDoubleBetween(-1000.0, 1000.0, true);
    }

    private Block buildLongColumn(int positionCount, Structure structure, LongSupplier values) {
        switch (structure) {
            case DENSE_VECTOR -> {
                long[] a = new long[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    a[p] = values.getAsLong();
                }
                return blockFactory.newLongArrayVector(a, positionCount).asBlock();
            }
            case CONSTANT -> {
                return blockFactory.newConstantLongVector(values.getAsLong(), positionCount).asBlock();
            }
            case NULLABLE_BLOCK -> {
                try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.appendNull();
                        } else {
                            builder.appendLong(values.getAsLong());
                        }
                    }
                    return builder.build();
                }
            }
            case MULTIVALUE_BLOCK -> {
                try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.beginPositionEntry();
                            int count = randomIntBetween(2, 3);
                            for (int v = 0; v < count; v++) {
                                builder.appendLong(values.getAsLong());
                            }
                            builder.endPositionEntry();
                        } else {
                            builder.appendLong(values.getAsLong());
                        }
                    }
                    return builder.build();
                }
            }
            default -> throw new AssertionError(structure);
        }
    }

    private Block buildIntColumn(int positionCount, Structure structure, IntSupplier values) {
        switch (structure) {
            case DENSE_VECTOR -> {
                int[] a = new int[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    a[p] = values.getAsInt();
                }
                return blockFactory.newIntArrayVector(a, positionCount).asBlock();
            }
            case CONSTANT -> {
                return blockFactory.newConstantIntVector(values.getAsInt(), positionCount).asBlock();
            }
            case NULLABLE_BLOCK -> {
                try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.appendNull();
                        } else {
                            builder.appendInt(values.getAsInt());
                        }
                    }
                    return builder.build();
                }
            }
            case MULTIVALUE_BLOCK -> {
                try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.beginPositionEntry();
                            int count = randomIntBetween(2, 3);
                            for (int v = 0; v < count; v++) {
                                builder.appendInt(values.getAsInt());
                            }
                            builder.endPositionEntry();
                        } else {
                            builder.appendInt(values.getAsInt());
                        }
                    }
                    return builder.build();
                }
            }
            default -> throw new AssertionError(structure);
        }
    }

    private Block buildDoubleColumn(int positionCount, Structure structure, DoubleSupplier values) {
        switch (structure) {
            case DENSE_VECTOR -> {
                double[] a = new double[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    a[p] = values.getAsDouble();
                }
                return blockFactory.newDoubleArrayVector(a, positionCount).asBlock();
            }
            case CONSTANT -> {
                return blockFactory.newConstantDoubleVector(values.getAsDouble(), positionCount).asBlock();
            }
            case NULLABLE_BLOCK -> {
                try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.appendNull();
                        } else {
                            builder.appendDouble(values.getAsDouble());
                        }
                    }
                    return builder.build();
                }
            }
            case MULTIVALUE_BLOCK -> {
                try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (randomInt(3) == 0) {
                            builder.beginPositionEntry();
                            int count = randomIntBetween(2, 3);
                            for (int v = 0; v < count; v++) {
                                builder.appendDouble(values.getAsDouble());
                            }
                            builder.endPositionEntry();
                        } else {
                            builder.appendDouble(values.getAsDouble());
                        }
                    }
                    return builder.build();
                }
            }
            default -> throw new AssertionError(structure);
        }
    }

    private int positionCountFor(int i) {
        return switch (i) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 1024 + randomIntBetween(0, 256);
            default -> i % 17 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 300);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Coverage bookkeeping
    // ---------------------------------------------------------------------------------------------------------------

    private static final class Coverage {
        private int empty;
        private int single;
        private int large;
        private int plainVector;
        private int checkedVector;
        private int none;
        private int overflowWarnings;
        private int multiValueWarnings;
    }

    private static void assertMatrixCovered(Coverage cov) {
        assertThat("empty (positionCount==0) shape must be exercised", cov.empty, greaterThan(0));
        assertThat("single-row shape must be exercised", cov.single, greaterThan(0));
        assertThat("large (>=1024) shape must be exercised", cov.large, greaterThan(0));
    }

    private static void recordShape(Coverage cov, int positionCount) {
        if (positionCount == 0) {
            cov.empty++;
        } else if (positionCount == 1) {
            cov.single++;
        } else if (positionCount >= 1024) {
            cov.large++;
        }
    }

    private static void recordStrategy(Coverage cov, VectorStrategy strategy) {
        switch (strategy) {
            case PLAIN_VECTOR -> cov.plainVector++;
            case CHECKED_VECTOR -> cov.checkedVector++;
            case NONE -> cov.none++;
        }
    }

    private static void recordWarnings(Coverage cov, Profile profile, List<String> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        switch (profile) {
            case OVERFLOW -> cov.overflowWarnings++;
            case MULTIVALUE -> {
                if (warnings.stream().anyMatch(w -> w.contains(MULTI_VALUE_MSG))) {
                    cov.multiValueWarnings++;
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------------------------

    private ExpressionEvaluator.Factory fusedFactory(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        return EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
    }

    private ExpressionEvaluator.Factory unfusedFactory(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(false);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(true);
        return factory;
    }

    private static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(
            Source.EMPTY,
            name,
            new EsField(name, type, Collections.emptyMap(), false, EsField.TimeSeriesFieldType.NONE)
        );
    }

    private static Layout layout(FieldAttribute... fields) {
        Layout.Builder builder = new Layout.Builder();
        for (FieldAttribute field : fields) {
            builder.append(field);
        }
        return builder.build();
    }

    private static int[] toArray(Set<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (int v : values) {
            out[i++] = v;
        }
        return out;
    }
}
