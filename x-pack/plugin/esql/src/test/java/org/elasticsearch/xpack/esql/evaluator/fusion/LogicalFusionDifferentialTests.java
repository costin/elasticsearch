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
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Differential, fused-vs-unfused sweep for <b>logical</b> fusion (iter 20, S3.1): three-valued-logic (3VL)
 * {@code AND}/{@code OR} trees whose operands are column-column comparisons (from S3.0) or nested logicals — e.g.
 * {@code a > b AND c < d}, {@code a > b OR c < d}, {@code (a > b AND c < d) OR e > f}, over {@code long}/{@code int}/
 * {@code double} columns.
 *
 * <p>Each tree is built twice through the real plan-time path {@link EvalMapper#toEvaluator}: once with the kill switch
 * {@link FusionSettings} <b>enabled</b> (routing through {@link FusionPlanner} + the stitcher's logical block loop into
 * a {@link FusedExpressionEvaluatorFactory}) and once <b>disabled</b> (the ordinary
 * {@code BooleanLogicExpressionEvaluator} chain over generated comparison evaluators). Both run over the same random
 * {@link Page} and must satisfy the ratified fusion contract: the result is a {@link BooleanBlock} whose VALUES and
 * NULL positions are <b>identical</b> to the unfused chain's, and the fused WARNING set is a <b>subset</b> of the
 * unfused set (short-circuit can only drop a warning the unfused eager both-sides evaluation still emits).
 *
 * <h2>What this iter exercises</h2>
 * <ul>
 *   <li><b>3VL truth tables</b> matching {@code BinaryLogicOperation}: {@code false AND null = false},
 *       {@code true OR null = true}, {@code null AND true = null}, {@code null OR false = null}, … — verified both
 *       differentially against the unfused chain and against a hand-coded oracle in
 *       {@link #testThreeValuedLogicDeterministic()}.</li>
 *   <li><b>Short-circuit</b>: when the left operand alone decides the result (AND left false / OR left true) the right
 *       operand is not evaluated, so a multi-value warning the right subtree would raise is suppressed — proven in
 *       {@link #testShortCircuitSuppressesRightMultiValueWarning()} (fused emits no warning; unfused, which evaluates
 *       both eagerly, does; values still identical).</li>
 *   <li>the empty / single-row / large (&ge;1024) / null-input / multi-value-input shapes.</li>
 * </ul>
 * Everything runs on a real breaker-backed {@link BlockFactory}; a {@code @After} asserts the breaker is balanced.
 */
public class LogicalFusionDifferentialTests extends ESTestCase {

    private static final String MULTI_VALUE_MSG = "single-value function encountered multi-value";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private MockBigArrays bigArrays;
    private DriverContext driverContext;

    @Before
    public void setUpFusion() {
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
    // Randomized sweeps over the shape / null / multi-value matrix
    // ---------------------------------------------------------------------------------------------------------------

    public void testAndLongMatchesUnfused() {
        randomSweep(Kind.LONG, Connective.AND, TreeShape.FLAT);
    }

    public void testOrLongMatchesUnfused() {
        randomSweep(Kind.LONG, Connective.OR, TreeShape.FLAT);
    }

    public void testAndIntMatchesUnfused() {
        randomSweep(Kind.INT, Connective.AND, TreeShape.FLAT);
    }

    public void testOrIntMatchesUnfused() {
        randomSweep(Kind.INT, Connective.OR, TreeShape.FLAT);
    }

    public void testAndDoubleMatchesUnfused() {
        randomSweep(Kind.DOUBLE, Connective.AND, TreeShape.FLAT);
    }

    public void testOrDoubleMatchesUnfused() {
        randomSweep(Kind.DOUBLE, Connective.OR, TreeShape.FLAT);
    }

    /** Left-nested {@code (a > b AND c < d) <op> e > f}: mixes AND/OR and exercises a depth-3 logical tree. */
    public void testNestedMixedLongMatchesUnfused() {
        randomSweep(Kind.LONG, Connective.OR, TreeShape.LEFT_NESTED);
    }

    public void testNestedMixedIntMatchesUnfused() {
        randomSweep(Kind.INT, Connective.AND, TreeShape.LEFT_NESTED);
    }

    /**
     * Right-nested {@code e > f <op> (a > b AND c < d)}: the nested logical is the RIGHT operand, exercising the
     * {@code emit(right)}-into-{@code Logical} slot and the short-circuit path where a decisive left skips the whole
     * nested subtree (iter-20 P3).
     */
    public void testRightNestedMixedLongMatchesUnfused() {
        randomSweep(Kind.LONG, Connective.OR, TreeShape.RIGHT_NESTED);
    }

    public void testRightNestedMixedIntMatchesUnfused() {
        randomSweep(Kind.INT, Connective.AND, TreeShape.RIGHT_NESTED);
    }

    /**
     * Drives {@code iterations} random pages through a fused-vs-unfused logical tree, cycling the shape (empty / single
     * / large / small) and the null/multi-value profile. Asserts the tree fuses ON, does not fuse OFF, and produces
     * byte-identical values + null positions with a subset warning set.
     */
    private void randomSweep(Kind kind, Connective top, TreeShape shape) {
        boolean sawEmpty = false;
        boolean sawSingle = false;
        boolean sawLarge = false;
        boolean sawMultiValueWarning = false;

        int columns = shape == TreeShape.FLAT ? 4 : 6;
        FieldAttribute[] cols = new FieldAttribute[columns];
        for (int c = 0; c < columns; c++) {
            cols[c] = field("c" + c, kind.dataType);
        }
        Layout layout = layout(cols);
        Expression expr = switch (shape) {
            case FLAT -> flatTree(top, cols);
            case LEFT_NESTED -> nestedTree(top, cols);
            case RIGHT_NESTED -> rightNestedTree(top, cols);
        };

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat("logical tree must fuse ON: " + expr, fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat("logical tree must NOT fuse OFF: " + expr, unfusedFactory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        Profile[] cycle = { Profile.CLEAN, Profile.NULLS, Profile.MULTIVALUE, Profile.NULLS_AND_MULTIVALUE };
        for (int i = 0; i < 64; i++) {
            int positionCount = positionCountFor(i);
            Profile profile = cycle[i % cycle.length];
            if (positionCount == 0) {
                sawEmpty = true;
            } else if (positionCount == 1) {
                sawSingle = true;
            } else if (positionCount >= 1024) {
                sawLarge = true;
            }

            Block[] inputs = new Block[columns];
            for (int c = 0; c < columns; c++) {
                inputs[c] = column(kind, positionCount, profile);
            }
            List<String> warnings = runAndAssertEqual(fusedFactory, unfusedFactory, inputs, positionCount, "i=" + i + " " + expr);
            if (warnings.stream().anyMatch(w -> w.contains(MULTI_VALUE_MSG))) {
                sawMultiValueWarning = true;
            }
        }

        assertThat("empty shape exercised", sawEmpty, is(true));
        assertThat("single-row shape exercised", sawSingle, is(true));
        assertThat("large (>=1024) shape exercised", sawLarge, is(true));
        assertThat("a multi-value input must register a warning in the unfused reference", sawMultiValueWarning, is(true));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Deterministic 3VL truth-table coverage (fused == unfused == oracle at every combination)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Constructs positions exercising every {@code (left, right)} combination of {true, false, null} for both AND and
     * OR, and asserts the fused result equals both the unfused {@code BooleanLogicExpressionEvaluator} chain and a
     * hand-coded 3VL oracle — covering {@code false AND null = false}, {@code true OR null = true},
     * {@code null AND true = null}, {@code null OR false = null}, {@code true AND true = true}, etc.
     */
    public void testThreeValuedLogicDeterministic() {
        assertThreeValuedLogic(Connective.AND);
        assertThreeValuedLogic(Connective.OR);
    }

    private void assertThreeValuedLogic(Connective connective) {
        // Expression: (a > b) <op> (c > d). left = a>b, right = c>d. Force each operand to true/false/null per position.
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        GreaterThan left = new GreaterThan(Source.EMPTY, a, b);
        GreaterThan right = new GreaterThan(Source.EMPTY, c, d);
        Expression expr = connective == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);

        Boolean[] states = { Boolean.TRUE, Boolean.FALSE, null };
        int positions = states.length * states.length;
        Long[] av = new Long[positions];
        Long[] bv = new Long[positions];
        Long[] cv = new Long[positions];
        Long[] dv = new Long[positions];
        Boolean[] expected = new Boolean[positions];
        int p = 0;
        for (Boolean l : states) {
            for (Boolean r : states) {
                fillComparison(av, bv, p, l); // a>b == l
                fillComparison(cv, dv, p, r); // c>d == r
                expected[p] = connective == Connective.AND ? and3(l, r) : or3(l, r);
                p++;
            }
        }

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat(connective + " tree must fuse ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));

        Block[] inputs = { longColumn(av), longColumn(bv), longColumn(cv), longColumn(dv) };
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positions, inputs);
            fusedResult = fused.eval(page);
            referenceResult = reference.eval(page);
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            for (int q = 0; q < positions; q++) {
                String ctx = connective + " @" + q + " expected=" + expected[q];
                if (expected[q] == null) {
                    assertThat(ctx + " fused null", fb.isNull(q), is(true));
                } else {
                    assertThat(ctx + " fused non-null", fb.isNull(q), is(false));
                    assertThat(ctx + " fused value", fb.getBoolean(fb.getFirstValueIndex(q)), equalTo(expected[q]));
                }
                // Differential parity with the unfused 3VL chain.
                assertThat(ctx + " null parity", fb.isNull(q), is(rb.isNull(q)));
                if (rb.isNull(q) == false) {
                    assertThat(
                        ctx + " value parity",
                        fb.getBoolean(fb.getFirstValueIndex(q)),
                        equalTo(rb.getBoolean(rb.getFirstValueIndex(q)))
                    );
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Short-circuit evidence: a decisive left operand suppresses the right operand's multi-value warning
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The right operand ({@code c < d}) has a multi-value input at the only position, so evaluating it registers the
     * single-value multi-value warning. The left operand decides the whole expression (AND: left false; OR: left true),
     * so the fused loop short-circuits and never evaluates the right operand — hence emits NO warning. The unfused
     * chain evaluates both operands eagerly and DOES emit the warning. Values remain identical, and the fused warning
     * set is a strict subset, proving the short-circuit occurred.
     */
    public void testShortCircuitSuppressesRightMultiValueWarning() {
        assertShortCircuitSuppressesWarning(Connective.AND);
        assertShortCircuitSuppressesWarning(Connective.OR);
    }

    private void assertShortCircuitSuppressesWarning(Connective connective) {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        GreaterThan left = new GreaterThan(Source.EMPTY, a, b);
        LessThan right = new LessThan(Source.EMPTY, c, d);
        Expression expr = connective == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat(connective + " tree must fuse ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));

        // AND: make left false (a=0 > b=1 is false) so AND short-circuits to false.
        // OR: make left true (a=1 > b=0 is true) so OR short-circuits to true.
        long aVal = connective == Connective.AND ? 0L : 1L;
        long bVal = connective == Connective.AND ? 1L : 0L;
        boolean expected = connective != Connective.AND; // AND -> false, OR -> true
        Block[] inputs = {
            longColumn(new Long[] { aVal }),
            longColumn(new Long[] { bVal }),
            multiValueLong(new long[] { 1L, 2L }), // c: multi-value -> c < d would warn if evaluated
            longColumn(new Long[] { 5L }) };

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(1, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // Values identical, and both non-null (the decisive short-circuit result is never null here).
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            assertThat(connective + " fused non-null", fb.isNull(0), is(false));
            assertThat(connective + " fused value", fb.getBoolean(fb.getFirstValueIndex(0)), is(expected));
            assertThat(connective + " value parity", fb.isNull(0), is(rb.isNull(0)));
            assertThat(connective + " value parity", fb.getBoolean(fb.getFirstValueIndex(0)), is(rb.getBoolean(rb.getFirstValueIndex(0))));

            // Short-circuit evidence: unfused emits the right operand's multi-value warning, fused does not.
            assertThat(
                connective + " unfused (eager) must emit the right operand's multi-value warning",
                referenceWarnings,
                hasItem(containsString(MULTI_VALUE_MSG))
            );
            assertThat(
                connective + " fused must SHORT-CIRCUIT and NOT emit the right operand's multi-value warning",
                fusedWarnings.stream().anyMatch(w -> w.contains(MULTI_VALUE_MSG)),
                is(false)
            );
            assertWarningsSubset(fusedWarnings, referenceWarnings, connective + " short-circuit");
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Warning-source fidelity (iter-20 P1): each sub-comparison warning is attributed to ITS OWN source, not the root
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * With DISTINCT NON-EMPTY {@link Source}s per comparison and root, both operands are reached (a multi-value input
     * makes the left comparison {@code null}, which does not short-circuit either AND or OR), so each sub-comparison
     * registers a multi-value warning attributed to <b>its own</b> source. The fused warning HEADERS must therefore be
     * a subset of the unfused chain's — which builds every generated comparison evaluator with its own node {@link
     * Source}. Because ESQL warning headers embed the source {@code line:col} and source text (see {@code Warnings}),
     * the pre-fix single-root-{@code Warnings} behaviour attributed every fused warning to the ROOT ({@code And}/{@code
     * Or}) source, emitting a {@code [ROOT_LOGICAL]} header the unfused set never contains — so this test FAILS pre-fix
     * and PASSES once per-source {@code Warnings[]} threading lands.
     */
    public void testDistinctSourceWarningsAttributedPerComparison() {
        assertDistinctSourceWarnings(Connective.AND);
        assertDistinctSourceWarnings(Connective.OR);
    }

    private void assertDistinctSourceWarnings(Connective connective) {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        // Distinct non-empty sources per comparison and root -> every warning header is unambiguous.
        GreaterThan left = new GreaterThan(new Source(1, 3, "aaa>bbb"), a, b);
        LessThan right = new LessThan(new Source(2, 7, "ccc<ddd"), c, d);
        Source rootSource = new Source(7, 14, "ROOT_LOGICAL");
        Expression expr = connective == Connective.AND ? new And(rootSource, left, right) : new Or(rootSource, left, right);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat(connective + " tree must fuse ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));

        // Single position: a and c are multi-value, so both comparisons warn and return null. left null does NOT
        // short-circuit AND (short-circuits only on left=false) or OR (only on left=true), so BOTH operands are
        // reached in fused and unfused alike -> the two multi-value warnings are reached in both paths.
        Block[] inputs = {
            multiValueLong(new long[] { 1L, 2L }), // a -> left (a>b) multi-value warning, null
            longColumn(new Long[] { 0L }),          // b
            multiValueLong(new long[] { 3L, 4L }),  // c -> right (c<d) multi-value warning, null
            longColumn(new Long[] { 9L }) };        // d

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(1, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            // Both operands null -> result null in both paths (values/nulls unchanged by the source-fidelity fix).
            BooleanBlock fb = (BooleanBlock) fusedResult;
            BooleanBlock rb = (BooleanBlock) referenceResult;
            assertThat(connective + " fused null", fb.isNull(0), is(true));
            assertThat(connective + " null parity", fb.isNull(0), is(rb.isNull(0)));

            // The critical invariant: fused headers subset of unfused. Pre-fix the fused path attributed both
            // warnings to the ROOT source ("[ROOT_LOGICAL]"), a header the unfused set never contains -> subset fails.
            assertWarningsSubset(fusedWarnings, referenceWarnings, connective + " distinct-source");
            // Attribution is correct (not merely empty): each comparison's own source text appears fused.
            assertThat(connective + " left source attribution", fusedWarnings, hasItem(containsString("[aaa>bbb]")));
            assertThat(connective + " right source attribution", fusedWarnings, hasItem(containsString("[ccc<ddd]")));
            // And the pre-fix root-source misattribution is gone.
            assertThat(
                connective + " no root-source misattribution",
                fusedWarnings.stream().anyMatch(w -> w.contains("[ROOT_LOGICAL]")),
                is(false)
            );
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core differential runner
    // ---------------------------------------------------------------------------------------------------------------

    private List<String> runAndAssertEqual(
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

            assertThat(ctx + " fused output is a BooleanBlock", fusedResult, instanceOf(BooleanBlock.class));
            assertThat(ctx + " unfused output is a BooleanBlock", referenceResult, instanceOf(BooleanBlock.class));
            assertBooleanResultsEqual(fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
            return referenceWarnings;
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

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
    // 3VL oracle + expression builders
    // ---------------------------------------------------------------------------------------------------------------

    private static Boolean and3(Boolean l, Boolean r) {
        if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
            return Boolean.FALSE;
        }
        if (l == null || r == null) {
            return null;
        }
        return l && r;
    }

    private static Boolean or3(Boolean l, Boolean r) {
        if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) {
            return Boolean.TRUE;
        }
        if (l == null || r == null) {
            return null;
        }
        return l || r;
    }

    /** Sets {@code x[p]}, {@code y[p]} so that {@code x > y} yields {@code state} (null when {@code state == null}). */
    private static void fillComparison(Long[] x, Long[] y, int p, Boolean state) {
        if (state == null) {
            x[p] = null; // null input -> comparison is null (viral)
            y[p] = 0L;
        } else if (state) {
            x[p] = 1L;
            y[p] = 0L;
        } else {
            x[p] = 0L;
            y[p] = 1L;
        }
    }

    private enum Connective {
        AND,
        OR
    }

    private enum TreeShape {
        FLAT,
        LEFT_NESTED,
        RIGHT_NESTED
    }

    /** {@code (c0 <op> c1) <op> (c2 <op> c3)} — two comparisons under one logical node. */
    private Expression flatTree(Connective top, FieldAttribute[] cols) {
        Expression left = new GreaterThan(Source.EMPTY, cols[0], cols[1]);
        Expression right = new LessThan(Source.EMPTY, cols[2], cols[3]);
        return top == Connective.AND ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);
    }

    /** {@code (c0 > c1 AND c2 < c3) <op> (c4 > c5)} — a depth-3 mixed AND/OR tree. */
    private Expression nestedTree(Connective top, FieldAttribute[] cols) {
        Expression inner = new And(
            Source.EMPTY,
            new GreaterThan(Source.EMPTY, cols[0], cols[1]),
            new LessThan(Source.EMPTY, cols[2], cols[3])
        );
        Expression tail = new GreaterThan(Source.EMPTY, cols[4], cols[5]);
        return top == Connective.AND ? new And(Source.EMPTY, inner, tail) : new Or(Source.EMPTY, inner, tail);
    }

    /** {@code (c4 > c5) <op> (c0 > c1 AND c2 < c3)} — the nested logical is the RIGHT operand (depth-3, P3). */
    private Expression rightNestedTree(Connective top, FieldAttribute[] cols) {
        Expression head = new GreaterThan(Source.EMPTY, cols[4], cols[5]);
        Expression inner = new And(
            Source.EMPTY,
            new GreaterThan(Source.EMPTY, cols[0], cols[1]),
            new LessThan(Source.EMPTY, cols[2], cols[3])
        );
        return top == Connective.AND ? new And(Source.EMPTY, head, inner) : new Or(Source.EMPTY, head, inner);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Column construction
    // ---------------------------------------------------------------------------------------------------------------

    private enum Kind {
        LONG(DataType.LONG),
        INT(DataType.INTEGER),
        DOUBLE(DataType.DOUBLE);

        private final DataType dataType;

        Kind(DataType dataType) {
            this.dataType = dataType;
        }
    }

    private enum Profile {
        CLEAN,
        NULLS,
        MULTIVALUE,
        NULLS_AND_MULTIVALUE
    }

    private Block column(Kind kind, int positionCount, Profile profile) {
        return switch (kind) {
            case LONG -> longColumn(positionCount, profile);
            case INT -> intColumn(positionCount, profile);
            case DOUBLE -> doubleColumn(positionCount, profile);
        };
    }

    private boolean makeNull(Profile profile) {
        return (profile == Profile.NULLS || profile == Profile.NULLS_AND_MULTIVALUE) && randomInt(3) == 0;
    }

    private boolean makeMulti(Profile profile) {
        return (profile == Profile.MULTIVALUE || profile == Profile.NULLS_AND_MULTIVALUE) && randomInt(3) == 0;
    }

    private Block longColumn(int positionCount, Profile profile) {
        if (profile == Profile.CLEAN) {
            long[] v = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                v[p] = randomLongBetween(-20, 20);
            }
            return blockFactory.newLongArrayVector(v, positionCount).asBlock();
        }
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (makeNull(profile)) {
                    builder.appendNull();
                } else if (makeMulti(profile)) {
                    builder.beginPositionEntry();
                    int count = randomIntBetween(2, 3);
                    for (int v = 0; v < count; v++) {
                        builder.appendLong(randomLongBetween(-20, 20));
                    }
                    builder.endPositionEntry();
                } else {
                    builder.appendLong(randomLongBetween(-20, 20));
                }
            }
            return builder.build();
        }
    }

    private Block intColumn(int positionCount, Profile profile) {
        if (profile == Profile.CLEAN) {
            int[] v = new int[positionCount];
            for (int p = 0; p < positionCount; p++) {
                v[p] = randomIntBetween(-20, 20);
            }
            return blockFactory.newIntArrayVector(v, positionCount).asBlock();
        }
        try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (makeNull(profile)) {
                    builder.appendNull();
                } else if (makeMulti(profile)) {
                    builder.beginPositionEntry();
                    int count = randomIntBetween(2, 3);
                    for (int v = 0; v < count; v++) {
                        builder.appendInt(randomIntBetween(-20, 20));
                    }
                    builder.endPositionEntry();
                } else {
                    builder.appendInt(randomIntBetween(-20, 20));
                }
            }
            return builder.build();
        }
    }

    private Block doubleColumn(int positionCount, Profile profile) {
        if (profile == Profile.CLEAN) {
            double[] v = new double[positionCount];
            for (int p = 0; p < positionCount; p++) {
                v[p] = randomDoubleBetween(-20, 20, true);
            }
            return blockFactory.newDoubleArrayVector(v, positionCount).asBlock();
        }
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                if (makeNull(profile)) {
                    builder.appendNull();
                } else if (makeMulti(profile)) {
                    builder.beginPositionEntry();
                    int count = randomIntBetween(2, 3);
                    for (int v = 0; v < count; v++) {
                        builder.appendDouble(randomDoubleBetween(-20, 20, true));
                    }
                    builder.endPositionEntry();
                } else {
                    builder.appendDouble(randomDoubleBetween(-20, 20, true));
                }
            }
            return builder.build();
        }
    }

    private Block longColumn(Long[] vals) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(vals.length)) {
            for (Long v : vals) {
                if (v == null) {
                    builder.appendNull();
                } else {
                    builder.appendLong(v);
                }
            }
            return builder.build();
        }
    }

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

    private int positionCountFor(int i) {
        return switch (i) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 1024 + randomIntBetween(0, 256);
            default -> i % 13 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 200);
        };
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
}
