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
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
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
 * Property-based, fused-vs-unfused <b>differential</b> sweep — the iter-16 regression harness for expression fusion.
 *
 * <p>For each of several hundred randomly generated arithmetic expression trees (kernel depth 2..4 over
 * {@code long}/{@code int}/{@code double} columns, built from the real {@code Add}/{@code Sub}/{@code Mul}/
 * {@code Div}/{@code Neg}/{@code Abs} expressions whose {@code process*} kernels carry {@code @Fusable}), the harness
 * builds the evaluator <b>twice through the real plan-time path</b> {@link EvalMapper#toEvaluator}: once with the
 * production kill switch {@link FusionSettings} <b>enabled</b> (which routes the fully-fusable subtree through
 * {@link FusionPlanner} + the {@link org.elasticsearch.compute.operator.fusion.Stitcher} into a
 * {@link FusedExpressionEvaluatorFactory}) and once <b>disabled</b> (the ordinary generated evaluator chain). The two
 * evaluators are then run over the same random input {@link Page} and asserted to satisfy the ratified fusion contract:
 * <b>identical</b> VALUES and <b>identical</b> NULL positions (unconditionally, on every tree and every position), and a
 * fused WARNING set that is a <b>subset</b> of the unfused warning set (see below).
 *
 * <p><b>It toggles the real kill switch</b> (rather than reaching for {@link FusedExpressionEvaluatorFactory}'s
 * unfused-fallback handle) so the comparison exercises the production wiring end to end, and each generated tree is
 * asserted to actually fuse when the switch is on (and to <em>not</em> fuse when off), so no comparison is vacuous.
 *
 * <h2>Coverage matrix</h2>
 * Each iteration picks one shape (empty {@code positionCount==0}, single row, small, and &ge; 1024 for SIMD-window
 * relevance) and one <b>value/density profile</b>, and randomizes per column whether it is a dense {@code ArrayVector},
 * a {@code ConstantVector} (a non-{@code ArrayVector} dense input that forces the block path), or a {@code *Block}
 * carrying nulls / multi-values:
 * <ul>
 *   <li><b>CLEAN</b> — dense, bounded, no nulls/multi-values: the vector fast path (plain or checked), no warnings;</li>
 *   <li><b>NULLS</b> — {@code *Block}s with random nulls (no multi-values): the block path, null propagation;</li>
 *   <li><b>MULTIVALUE</b> — {@code *Block}s with random multi-values (no nulls): the block path + the
 *       single-value-function multi-value warning;</li>
 *   <li><b>OVERFLOW</b> ({@code long}/{@code int}) — dense values seeded at the {@code MAX_VALUE}/{@code MIN_VALUE}
 *       boundary so the overflow-checked kernels throw: null + warning, via the checked-vector and block paths;</li>
 *   <li><b>DIVZERO</b> ({@code long}, division sweep) — dense tiny values including {@code 0} divisors: {@code Div}'s
 *       {@code / by zero} null + warning.</li>
 * </ul>
 * {@code Div} and {@code Abs} are deliberately left in the {@code NULLS} / {@code MULTIVALUE} / {@code OVERFLOW}
 * profiles (not scoped out), so pages routinely mix warning mechanisms at a single position — that mix is exactly what
 * makes the subset assertion below meaningful.
 *
 * <h2>The ratified warning-subset invariant</h2>
 * Per the coordinator note in {@code IMPLEMENTATION_PLAN.md} ("Coordinator Note [2026-07-02] — Warning-parity
 * ratification (iter 16)"), the fusion contract is: <b>values and null positions MUST be identical</b>, and the fused
 * path's registered warnings <b>MUST be a subset</b> of the unfused path's warnings for the same page — never a
 * superset, never a spurious warning the unfused chain would not raise. The strict-subset case is inherent to the
 * design: fusion evaluates one position at a time and <b>short-circuits</b> the whole position to null on the
 * <em>first</em> null / multi-value / overflow / div-by-zero it meets, whereas the unfused chain evaluates each operator
 * <b>columnar</b> and independently, so two <em>independent sibling</em> subtrees can each register a warning for the
 * same (identically-null) position (e.g. a {@code / by zero} in one branch and a {@code single-value function
 * encountered multi-value} in another) before the parent nulls the row. The fused loop registers only the first, a
 * strict subset. Forcing exact parity would require the fused body to keep evaluating siblings after a position is
 * known-null, abandoning per-position short-circuit and defeating the single-hot-body JIT goal. This harness therefore
 * asserts the subset relation ({@link #assertWarningsSubset}) on every page and, additionally, pins the exact mixed
 * shape that first surfaced the property in {@link #testMixedMultiValueAndDivByZeroWarningIsSubset()}. Values and null
 * positions are compared exactly and unconditionally on every profile; any superset (a fused-only warning) fails the
 * assertion as a genuine divergence.
 *
 * <p>Everything runs on a real breaker-backed {@link BlockFactory} against real emitted bytecode — no mocks — with a
 * seeded RNG (ESTestCase randomness, so a failure reproduces from the printed seed) and a {@code @After} breaker-balance
 * assert that proves every produced/consumed block was released.
 */
public class FusionDifferentialTests extends ESTestCase {

    /** Substring of the warning raised when a single-value function meets a multi-valued position. */
    private static final String MULTI_VALUE_MSG = "single-value function encountered multi-value";
    /** Substring of the warning raised by an integer/long divide-by-zero. */
    private static final String DIV_ZERO_MSG = "/ by zero";

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
    // Sweeps
    // ---------------------------------------------------------------------------------------------------------------

    public void testLongArithmeticFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.NEG, Op.ABS);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        Coverage cov = runSweep(ElementKind.LONG, DataType.LONG, palette, null, cycle, false, 132);

        assertMatrixCovered(cov);
        assertThat("long trees are all overflow-checked => checked-vector strategy", cov.checkedVector, greaterThan(0));
        assertThat("some overflow position must have registered a warning", cov.overflowWarnings, greaterThan(0));
        assertThat("some multi-value position must have registered a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testIntArithmeticFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.NEG, Op.ABS);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.OVERFLOW };
        Coverage cov = runSweep(ElementKind.INT, DataType.INTEGER, palette, null, cycle, false, 132);

        assertMatrixCovered(cov);
        assertThat("int trees are all overflow-checked => checked-vector strategy", cov.checkedVector, greaterThan(0));
        assertThat("some overflow position must have registered a warning", cov.overflowWarnings, greaterThan(0));
        assertThat("some multi-value position must have registered a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testDoubleArithmeticFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.NEG, Op.ABS);
        List<Op> unaryPalette = List.of(Op.NEG, Op.ABS);
        // CLEAN_UNARY builds a non-overflow (Neg/Abs only) double tree so the plain-vector fast path is exercised;
        // the binary profiles use overflow-checked double kernels (NumericUtils.asFiniteNumber), which are hard-gated
        // off the vector path (VectorStrategy.NONE) and therefore always run the block path.
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.CLEAN_UNARY };
        Coverage cov = runSweep(ElementKind.DOUBLE, DataType.DOUBLE, palette, unaryPalette, cycle, false, 132);

        assertMatrixCovered(cov);
        assertThat("a Neg/Abs-only double tree must use the plain-vector fast path", cov.plainVector, greaterThan(0));
        assertThat("a double tree with overflow-checked kernels must be hard-gated off the vector path", cov.none, greaterThan(0));
        assertThat("some multi-value position must have registered a warning", cov.multiValueWarnings, greaterThan(0));
    }

    public void testLongDivisionFusedMatchesUnfused() {
        List<Op> palette = List.of(Op.ADD, Op.SUB, Op.MUL, Op.DIV);
        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.DIVZERO };
        Coverage cov = runSweep(ElementKind.LONG, DataType.LONG, palette, null, cycle, true, 108);

        assertMatrixCovered(cov);
        assertThat("some divide-by-zero position must have registered a warning", cov.divZeroWarnings, greaterThan(0));
    }

    /**
     * Deterministic regression for the ratified warning-<b>subset</b> invariant (IMPLEMENTATION_PLAN.md, "Coordinator
     * Note [2026-07-02] — Warning-parity ratification (iter 16)"), pinned so the exact mixed shape that first surfaced
     * the property can never silently regress.
     *
     * <p>The wired tree is {@code Add(Abs(mv), Div(a, z))} over three {@code long} columns, evaluated at a single
     * position that is <b>both</b> multi-valued (the {@code Abs} branch) <b>and</b> has a zero divisor (the {@code Div}
     * branch):
     * <ul>
     *   <li>the unfused columnar tree evaluates both siblings independently, so it registers <b>both</b>
     *       {@code single-value function encountered multi-value} and {@code / by zero}, then the parent {@code Add}
     *       nulls the row;</li>
     *   <li>the fused per-position loop's upfront guard short-circuits the whole position to null on the multi-value it
     *       sees first and never reaches the division, so it registers only the multi-value warning — a strict subset.</li>
     * </ul>
     * Both paths null exactly that position and agree on every other value, and the fused warning set is proven to be a
     * subset of (and here a strict subset of) the unfused set. The comparison runs through the real kill switch, not a
     * synthetic reference.
     */
    public void testMixedMultiValueAndDivByZeroWarningIsSubset() {
        FieldAttribute mv = field("mv", DataType.LONG);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute z = field("z", DataType.LONG);
        Layout layout = layout(mv, a, z);
        // Add(Abs(mv), Div(a, z)): kernel depth 2 => fully fusable through the real plan-time path.
        Expression expr = new Add(Source.EMPTY, new Abs(Source.EMPTY, mv), new Div(Source.EMPTY, a, z), EsqlTestUtils.TEST_CFG);

        FusionSettings.setEnabledForTests(true);
        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(false);
        ExpressionEvaluator.Factory unfusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(true);

        assertThat("mixed-shape tree must fuse with the kill switch ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat(
            "mixed-shape tree must NOT fuse with the kill switch OFF",
            unfusedFactory,
            not(instanceOf(FusedExpressionEvaluatorFactory.class))
        );

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
            Set<String> fusedWarnings = new HashSet<>(drainWarnings());
            referenceResult = reference.eval(page);
            Set<String> unfusedWarnings = new HashSet<>(drainWarnings());

            // (a) VALUES + NULL positions identical everywhere (the non-negotiable invariant). At the clean positions
            // the result is Add(Abs(3), Div(10, 2)) = 3 + 5 = 8; the mixed position is null in BOTH.
            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "mixed-shape");
            assertThat("fused mixed position must be null", fusedResult.isNull(mixed), is(true));
            assertThat("unfused mixed position must be null", referenceResult.isNull(mixed), is(true));

            // (b) The ratified subset invariant on the real wired path.
            Set<String> fusedOnly = new HashSet<>(fusedWarnings);
            fusedOnly.removeAll(unfusedWarnings);
            assertThat(
                "fused warnings must be a subset of unfused warnings; fused-only=" + fusedOnly,
                fusedOnly,
                is(Collections.emptySet())
            );

            // (c) The unfused tree raised BOTH mechanisms; the fused loop raised at least the multi-value one and, by
            // short-circuiting, did NOT raise the div-by-zero it never reached (proving a strict subset, not a superset).
            assertThat(
                "unfused must contain the multi-value warning " + unfusedWarnings,
                containsWarning(unfusedWarnings, MULTI_VALUE_MSG),
                is(true)
            );
            assertThat(
                "unfused must contain the / by zero warning " + unfusedWarnings,
                containsWarning(unfusedWarnings, DIV_ZERO_MSG),
                is(true)
            );
            assertThat(
                "fused must contain the multi-value warning " + fusedWarnings,
                containsWarning(fusedWarnings, MULTI_VALUE_MSG),
                is(true)
            );
            assertThat(
                "fused must NOT contain the / by zero warning (short-circuited past the division) " + fusedWarnings,
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
     * overflows the LEFT inner add; position 1 overflows the RIGHT inner add; the root add only ever sees a null
     * operand at those positions and so never overflows. The unfused chain therefore attributes one overflow warning to
     * EACH inner add's own source and NONE to the root. Because ESQL warning headers embed the source {@code line:col}
     * and source text (see {@code Warnings}), the pre-fix single-root-{@code Warnings} behaviour attributed every fused
     * overflow to the root {@code [OUTER_ADD]} source — a header the unfused set never contains — so the subset
     * assertion FAILS pre-fix and PASSES once per-source {@code Warnings[]} threading (including the checked-vector
     * overflow handler this dense-input tree exercises) lands. Values and null positions are unchanged.
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

        FusionSettings.setEnabledForTests(true);
        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(false);
        ExpressionEvaluator.Factory unfusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(true);

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
            assertThat("left inner source attribution", fusedWarnings.stream().anyMatch(w -> w.contains("[a+b]")), is(true));
            assertThat("right inner source attribution", fusedWarnings.stream().anyMatch(w -> w.contains("[c+d]")), is(true));
            // And the pre-fix root-source misattribution is gone.
            assertThat("no root-source misattribution", fusedWarnings.stream().anyMatch(w -> w.contains("[OUTER_ADD]")), is(false));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    private static boolean containsWarning(Set<String> warnings, String substring) {
        return warnings.stream().anyMatch(w -> w.contains(substring));
    }

    private static void assertMatrixCovered(Coverage cov) {
        assertThat("empty (positionCount==0) shape must be exercised", cov.empty, greaterThan(0));
        assertThat("single-row shape must be exercised", cov.single, greaterThan(0));
        assertThat("large (>=1024) shape must be exercised", cov.large, greaterThan(0));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core sweep: generate, build both evaluators through the real kill switch, evaluate, and differential-compare.
    // ---------------------------------------------------------------------------------------------------------------

    private Coverage runSweep(
        ElementKind kind,
        DataType type,
        List<Op> palette,
        List<Op> unaryPalette,
        Profile[] cycle,
        boolean division,
        int iterations
    ) {
        Coverage cov = new Coverage();
        for (int i = 0; i < iterations; i++) {
            int positionCount = positionCountFor(i);
            Profile profile = cycle[i % cycle.length];
            // Div and Abs are intentionally NOT excluded from any profile. NULLS / MULTIVALUE / OVERFLOW pages
            // deliberately mix warning mechanisms (e.g. a multi-value in one sibling and a "/ by zero" or a
            // differently-messaged Abs(MIN_VALUE) overflow in another sibling at the same position) so the harness
            // positively exercises the ratified fused warning-SUBSET invariant (see the class javadoc + the coordinator
            // note in IMPLEMENTATION_PLAN.md) rather than scoping the mixed case away.
            List<Op> pal = profile == Profile.CLEAN_UNARY ? unaryPalette : palette;

            int columns = randomIntBetween(1, 4);
            FieldAttribute[] cols = new FieldAttribute[columns];
            for (int c = 0; c < columns; c++) {
                cols[c] = field("c" + c, type);
            }
            Layout layout = layout(cols);

            Set<Integer> usedSet = new TreeSet<>();
            int maxDepth = randomIntBetween(2, 4);
            Expression expr = genExpr(pal, 2, maxDepth, cols, usedSet);
            int[] referenced = toArray(usedSet);

            // Build BOTH evaluators through the real plan-time path, toggling the production kill switch between them.
            FusionSettings.setEnabledForTests(true);
            ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            FusionSettings.setEnabledForTests(false);
            ExpressionEvaluator.Factory unfusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
            FusionSettings.setEnabledForTests(true);

            assertThat("fusion ON must fuse i=" + i + " expr=" + expr, fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
            assertThat(
                "fusion OFF (kill switch) must NOT fuse i=" + i + " expr=" + expr,
                unfusedFactory,
                not(instanceOf(FusedExpressionEvaluatorFactory.class))
            );

            recordStrategy(cov, ((FusedExpressionEvaluatorFactory) fusedFactory).vectorStrategy());
            recordShape(cov, positionCount);

            Block[] inputs = buildPage(kind, columns, positionCount, profile, division, referenced);
            ExpressionEvaluator fused = fusedFactory.get(driverContext);
            ExpressionEvaluator reference = unfusedFactory.get(driverContext);
            Page page = null;
            Block fusedResult = null;
            Block referenceResult = null;
            try {
                page = new Page(positionCount, inputs);

                drainWarnings(); // clean slate
                fusedResult = fused.eval(page);
                List<String> fusedWarnings = drainWarnings();
                referenceResult = reference.eval(page);
                List<String> referenceWarnings = drainWarnings();

                String ctx = "i=" + i + " profile=" + profile + " positions=" + positionCount + " expr=" + expr;
                assertResultsEqual(kind, fusedResult, referenceResult, positionCount, ctx);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Differential assertions
    // ---------------------------------------------------------------------------------------------------------------

    private static void assertResultsEqual(ElementKind kind, Block fused, Block reference, int positionCount, String ctx) {
        assertThat(ctx + " fused positionCount", fused.getPositionCount(), is(positionCount));
        assertThat(ctx + " reference positionCount", reference.getPositionCount(), is(positionCount));
        for (int p = 0; p < positionCount; p++) {
            boolean fusedNull = fused.isNull(p);
            assertThat(ctx + " null@" + p, fusedNull, is(reference.isNull(p)));
            if (fusedNull) {
                continue;
            }
            switch (kind) {
                case LONG -> {
                    long f = ((LongBlock) fused).getLong(fused.getFirstValueIndex(p));
                    long r = ((LongBlock) reference).getLong(reference.getFirstValueIndex(p));
                    assertThat(ctx + " value@" + p, f, equalTo(r));
                }
                case INT -> {
                    int f = ((IntBlock) fused).getInt(fused.getFirstValueIndex(p));
                    int r = ((IntBlock) reference).getInt(reference.getFirstValueIndex(p));
                    assertThat(ctx + " value@" + p, f, equalTo(r));
                }
                case DOUBLE -> {
                    double f = ((DoubleBlock) fused).getDouble(fused.getFirstValueIndex(p));
                    double r = ((DoubleBlock) reference).getDouble(reference.getFirstValueIndex(p));
                    // Bit comparison correctly equates -0.0/+0.0 and NaN payloads; identical op order => identical bits.
                    assertThat(ctx + " value@" + p, Double.doubleToLongBits(f), equalTo(Double.doubleToLongBits(r)));
                }
            }
        }
    }

    /**
     * Asserts the ratified warning contract (IMPLEMENTATION_PLAN.md, "Coordinator Note [2026-07-02] — Warning-parity
     * ratification"): the fused path's registered warnings must be a <b>subset</b> of the unfused path's warnings for the
     * same page — never a superset, never a spurious warning the unfused chain would not raise. Equality is the common
     * special case; the strict-subset case arises when the fused per-position loop short-circuits a position to null on
     * the first abnormal condition it meets while the unfused columnar tree evaluates every sibling independently and so
     * registers additional sibling warnings for that same (identically-null) position.
     */
    private static void assertWarningsSubset(List<String> fused, List<String> reference, String ctx) {
        Set<String> fusedSet = new HashSet<>(fused);
        Set<String> referenceSet = new HashSet<>(reference);
        Set<String> fusedOnly = new HashSet<>(fusedSet);
        fusedOnly.removeAll(referenceSet);
        assertThat(
            ctx + " fused warnings must be a SUBSET of unfused warnings (ratified subset invariant); fused-only=" + fusedOnly,
            fusedOnly,
            is(Collections.emptySet())
        );
    }

    /**
     * Reads and then clears the current thread's collected {@code Warning} response headers. Clearing (via
     * {@link org.elasticsearch.common.util.concurrent.ThreadContext#stashContext()}) resets the header set and the
     * per-context deduplication so the fused and unfused runs are measured independently — the same mechanism
     * {@code ESTestCase} uses to snapshot warnings.
     */
    private List<String> drainWarnings() {
        List<String> warnings = new ArrayList<>(threadContext.getResponseHeaders().getOrDefault("Warning", List.of()));
        threadContext.stashContext();
        return warnings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Random expression trees over the real @Fusable expressions
    // ---------------------------------------------------------------------------------------------------------------

    /** A fusable operator: a binary ({@code Add}/{@code Sub}/{@code Mul}/{@code Div}) or unary ({@code Neg}/{@code Abs}). */
    private enum Op {
        ADD(false),
        SUB(false),
        MUL(false),
        DIV(false),
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
                case DIV -> new Div(Source.EMPTY, left, right);
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
     * Builds a random tree whose deepest path has kernel depth in {@code [minDepth, maxDepth]} (so the root always has
     * kernel depth &ge; 2 and therefore fuses). Leaves are homogeneous {@link FieldAttribute}s of the sweep's element
     * type; the referenced column indices are collected into {@code used}.
     */
    private Expression genExpr(List<Op> palette, int minDepth, int maxDepth, FieldAttribute[] cols, Set<Integer> used) {
        if (maxDepth <= 0 || (minDepth <= 0 && randomBoolean())) {
            int idx = randomInt(cols.length - 1);
            used.add(idx);
            return cols[idx];
        }
        Op op = palette.get(randomInt(palette.size() - 1));
        if (op.unary) {
            return op.build(genExpr(palette, minDepth - 1, maxDepth - 1, cols, used));
        }
        // Force one child to carry the remaining depth; the other may be shallower. Randomize which side is deep so a
        // non-commutative op (Sub/Div) at the root exercises both operand orders.
        Expression deep = genExpr(palette, minDepth - 1, maxDepth - 1, cols, used);
        Expression shallow = genExpr(palette, 0, maxDepth - 1, cols, used);
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
        CLEAN_UNARY,
        NULLS,
        MULTIVALUE,
        OVERFLOW,
        DIVZERO
    }

    /** How one column's {@code Block} is physically encoded, which drives whether the vector or block path is taken. */
    private enum Structure {
        DENSE_VECTOR,
        CONSTANT,
        NULLABLE_BLOCK,
        MULTIVALUE_BLOCK
    }

    private Block[] buildPage(ElementKind kind, int columns, int positionCount, Profile profile, boolean division, int[] referenced) {
        // For NULLS / MULTIVALUE, force at least one *referenced* column to actually carry the abnormality so the block
        // path and its warning are genuinely exercised (an abnormal but unreferenced column would be a no-op).
        int forced = (profile == Profile.NULLS || profile == Profile.MULTIVALUE) ? referenced[randomInt(referenced.length - 1)] : -1;
        Block[] blocks = new Block[columns];
        for (int c = 0; c < columns; c++) {
            Structure structure = structureFor(profile, positionCount, c == forced);
            blocks[c] = buildColumn(kind, positionCount, structure, profile, division);
        }
        return blocks;
    }

    private Structure structureFor(Profile profile, int positionCount, boolean forcedAbnormal) {
        if (positionCount == 0) {
            // Empty pages have no positions to make abnormal; a plain empty dense vector is unambiguous.
            return Structure.DENSE_VECTOR;
        }
        return switch (profile) {
            case CLEAN_UNARY -> Structure.DENSE_VECTOR; // keep dense so the plain-vector fast path is taken
            case CLEAN, OVERFLOW, DIVZERO ->
                // Occasionally use a ConstantVector: a dense, no-null, non-ArrayVector input that forces the block path,
                // so the block path is exercised for clean/overflow/div-zero too (not only for null/multi-value pages).
                randomInt(3) == 0 ? Structure.CONSTANT : Structure.DENSE_VECTOR;
            case NULLS -> forcedAbnormal ? Structure.NULLABLE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK);
            case MULTIVALUE -> forcedAbnormal ? Structure.MULTIVALUE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.MULTIVALUE_BLOCK);
        };
    }

    private Block buildColumn(ElementKind kind, int positionCount, Structure structure, Profile profile, boolean division) {
        return switch (kind) {
            case LONG -> buildLongColumn(positionCount, structure, () -> nextLong(profile, division));
            case INT -> buildIntColumn(positionCount, structure, () -> nextInt(profile));
            case DOUBLE -> buildDoubleColumn(positionCount, structure, this::nextDouble);
        };
    }

    // Value generators. Non-overflow profiles stay small enough that a depth-4 all-multiply tree cannot overflow, so the
    // only warning source per page is the profile's single mechanism (null: none, multi-value, overflow, or div-zero).

    private long nextLong(Profile profile, boolean division) {
        return switch (profile) {
            case OVERFLOW -> randomBoolean()
                ? randomLongBetween(-100, 100)
                : (randomBoolean() ? Long.MAX_VALUE - randomLongBetween(0, 3) : Long.MIN_VALUE + randomLongBetween(0, 3));
            case DIVZERO -> randomLongBetween(-4, 4);
            default -> division ? nonZeroTinyLong() : randomLongBetween(-10, 10);
        };
    }

    private long nonZeroTinyLong() {
        long v = randomLongBetween(-4, 4);
        return v == 0 ? (randomBoolean() ? 1 : -1) : v;
    }

    private int nextInt(Profile profile) {
        return switch (profile) {
            case OVERFLOW -> randomBoolean()
                ? randomIntBetween(-100, 100)
                : (randomBoolean() ? Integer.MAX_VALUE - randomIntBetween(0, 3) : Integer.MIN_VALUE + randomIntBetween(0, 3));
            // A depth-4 all-multiply int tree with |v| <= 3 tops out at 3^16 < Integer.MAX_VALUE, so no accidental overflow.
            default -> randomIntBetween(-3, 3);
        };
    }

    private double nextDouble() {
        // Bounded so add/sub/mul over depth <= 4 stays far below Double.MAX_VALUE (no Infinity => asFiniteNumber never throws).
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

    /** Guarantees empty (0), single (1), and &ge; 1024 shapes appear, with a spread of small counts in between. */
    private int positionCountFor(int i) {
        return switch (i) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 1024 + randomIntBetween(0, 256);
            default -> i % 23 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 300);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Coverage bookkeeping (proves the matrix was actually exercised, so no assertion passes vacuously)
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
        private int divZeroWarnings;
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
            case MULTIVALUE -> cov.multiValueWarnings++;
            case DIVZERO -> cov.divZeroWarnings++;
            default -> {
                // CLEAN / CLEAN_UNARY / NULLS must not register warnings; if one did, the differential assertion above
                // already compared it against the unfused chain, so nothing extra to record here.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Small shared helpers
    // ---------------------------------------------------------------------------------------------------------------

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
