/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
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
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Shared base for the fused-vs-unfused <b>differential</b> harnesses. Iter 23 (Stage 4) consolidated the ~3&times;
 * duplicated scaffolding that had accreted across the arithmetic ({@link FusionDifferentialTests}), comparison
 * ({@link ComparisonFusionDifferentialTests}) and logical ({@link LogicalFusionDifferentialTests}) harnesses into this
 * single abstract case, plus a declarative, spec-driven generative sweep in {@link FusionSpecDifferentialTests}.
 *
 * <p>All the machinery every differential test needs lives here:
 * <ul>
 *   <li>a real breaker-backed {@link BlockFactory}/{@link DriverContext} with a {@code @Before} that flips the
 *       production kill switch {@link FusionSettings} on and a {@code @After} that proves the breaker is balanced (every
 *       produced/consumed block released);</li>
 *   <li>the kill-switch toggle {@link #fusedFactory}/{@link #unfusedFactory} that builds the evaluator <b>twice through
 *       the real plan-time path</b> {@link EvalMapper#toEvaluator} — once with fusion ON (routed through
 *       {@link FusionPlanner} + the {@link org.elasticsearch.compute.operator.fusion.Stitcher} into a
 *       {@link FusedExpressionEvaluatorFactory}) and once with it OFF (the ordinary generated evaluator chain) — plus
 *       {@link #assertFuses}/{@link #assertDoesNotFuse} so no comparison is vacuous;</li>
 *   <li>{@link #drainWarnings}, and the ratified {@link #assertWarningsSubset} invariant (fused warnings &sube; unfused
 *       warnings — short-circuit can only drop a warning, never invent one);</li>
 *   <li>{@link #assertResultsEqual} comparing {@code long}/{@code int}/{@code double}/{@code boolean} results
 *       position-by-position (both VALUES and NULL positions), unconditionally;</li>
 *   <li>the {@code (ElementKind, Structure, Profile)} column builders and value generators, {@link #positionCountFor}
 *       (guarantees the 0 / 1 / &ge;1024 shapes), the {@link Coverage} bookkeeping (proves each {@link VectorStrategy}
 *       and warning mechanism was actually hit), and the {@link #field}/{@link #layout} helpers.</li>
 * </ul>
 *
 * <h2>The ratified warning-subset invariant</h2>
 * Per the coordinator note in {@code IMPLEMENTATION_PLAN.md} ("Warning-parity ratification (iter 16)"): <b>values and
 * null positions MUST be identical</b>, and the fused path's registered warnings <b>MUST be a subset</b> of the unfused
 * path's warnings for the same page — never a superset, never a spurious warning the unfused chain would not raise. The
 * strict-subset case is inherent: fusion evaluates one position at a time and short-circuits the whole position to null
 * on the first null / multi-value / overflow / div-by-zero it meets, whereas the unfused chain evaluates each operator
 * columnar and independently, so two independent sibling subtrees can each register a warning for the same
 * (identically-null) position before the parent nulls the row. The fused loop registers only the first, a strict subset.
 */
public abstract class FusionDifferentialTestCase extends ESTestCase {

    /** Substring of the warning raised when a single-value function meets a multi-valued position. */
    protected static final String MULTI_VALUE_MSG = "single-value function encountered multi-value";
    /** Substring of the warning raised by an integer/long divide-by-zero. */
    protected static final String DIV_ZERO_MSG = "/ by zero";

    protected CircuitBreakerService breakerService;
    protected CircuitBreaker breaker;
    protected BlockFactory blockFactory;
    protected MockBigArrays bigArrays;
    protected DriverContext driverContext;

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
        FusionSettings.setAdaptiveMinRowsForTests(0);
        FusionSettings.setSampleFractionForTests(1.0);
        assertThat(breaker.getUsed(), is(0L));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Kill-switch toggle: build BOTH evaluators through the real plan-time path
    // ---------------------------------------------------------------------------------------------------------------

    /** Builds the evaluator factory with the production kill switch ON (so a fusable subtree fuses). */
    protected ExpressionEvaluator.Factory fusedFactory(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        return EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
    }

    /** Builds the evaluator factory with the kill switch OFF (the ordinary generated evaluator chain). */
    protected ExpressionEvaluator.Factory unfusedFactory(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(false);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        FusionSettings.setEnabledForTests(true);
        return factory;
    }

    /**
     * Builds the evaluator factory with fusion ON <b>and</b> the adaptive threshold armed at {@code minRows == 1}, so
     * the returned {@link AdaptiveFusionEvaluatorFactory} starts on the unfused chain and switches to the stitched fused
     * evaluator on the first non-empty page. Driving {@link #runDifferentialWarnings} with this factory proves the
     * <em>adaptive</em> (Stage-6) path produces identical values/nulls (and a warning subset) to the unfused chain —
     * the same contract the eager path is held to — while genuinely exercising the runtime unfused→fused switch.
     */
    protected ExpressionEvaluator.Factory adaptiveFactory(Expression expr, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        FusionSettings.setAdaptiveMinRowsForTests(1);
        try {
            return EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        } finally {
            FusionSettings.setAdaptiveMinRowsForTests(0);
        }
    }

    /** Asserts the ON-switch, adaptive-armed factory really produced an adaptive factory (so the sweep is not vacuous). */
    protected static AdaptiveFusionEvaluatorFactory assertAdaptive(ExpressionEvaluator.Factory factory, String ctx) {
        assertThat(ctx + " must be adaptive with min_rows>0", factory, instanceOf(AdaptiveFusionEvaluatorFactory.class));
        return (AdaptiveFusionEvaluatorFactory) factory;
    }

    /** Asserts (and returns) that the ON-switch factory really fused, so the differential comparison is not vacuous. */
    protected static FusedExpressionEvaluatorFactory assertFuses(ExpressionEvaluator.Factory factory, String ctx) {
        assertThat(ctx + " must fuse with the kill switch ON", factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        return (FusedExpressionEvaluatorFactory) factory;
    }

    /** Asserts the OFF-switch factory did NOT fuse (the unfused reference is the ordinary generated chain). */
    protected static void assertDoesNotFuse(ExpressionEvaluator.Factory factory, String ctx) {
        assertThat(ctx + " must NOT fuse with the kill switch OFF", factory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Core differential runner: evaluate both, drain warnings, assert values/nulls equal + warnings subset
    // ---------------------------------------------------------------------------------------------------------------

    /** The fused and unfused warning lists drained separately for one differential run (see {@link #runDifferentialWarnings}). */
    protected record DifferentialWarnings(List<String> fused, List<String> reference) {}

    /**
     * Runs the fused and unfused evaluators over the same {@link Page} built from {@code inputs}, asserts identical
     * values + null positions (via {@link #assertResultsEqual} on {@code outputKind}) and the ratified warning-subset
     * invariant, and returns the <b>unfused</b> warning list (for optional coverage bookkeeping). The page and both
     * result blocks are released before returning.
     */
    protected List<String> runDifferential(
        ExpressionEvaluator.Factory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        ElementKind outputKind,
        Block[] inputs,
        int positionCount,
        String ctx
    ) {
        return runDifferentialWarnings(fusedFactory, unfusedFactory, outputKind, inputs, positionCount, ctx).reference();
    }

    /**
     * As {@link #runDifferential}, but returns BOTH the fused and the unfused warning lists (drained separately) so a
     * caller can assert warning <b>parity</b> — not just the subset — for a deterministic no-short-circuit shape where
     * the fused path MUST emit the same warning as the unfused chain (a silently-dropped fused warning would still pass
     * the subset rule, so these shapes need the fused side observed directly).
     */
    protected DifferentialWarnings runDifferentialWarnings(
        ExpressionEvaluator.Factory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        ElementKind outputKind,
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
            drainWarnings(); // clean slate
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            assertResultsEqual(outputKind, fusedResult, referenceResult, positionCount, ctx);
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
    // Differential assertions
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Asserts the two result blocks are position-by-position identical in both VALUES and NULL positions for the given
     * output {@code kind}. Double comparison uses {@code doubleToLongBits} so {@code -0.0}/{@code +0.0} and NaN payloads
     * compare correctly (identical op order ⇒ identical bits).
     */
    protected static void assertResultsEqual(ElementKind kind, Block fused, Block reference, int positionCount, String ctx) {
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
                    assertThat(ctx + " value@" + p, Double.doubleToLongBits(f), equalTo(Double.doubleToLongBits(r)));
                }
                case BOOLEAN -> {
                    boolean f = ((BooleanBlock) fused).getBoolean(fused.getFirstValueIndex(p));
                    boolean r = ((BooleanBlock) reference).getBoolean(reference.getFirstValueIndex(p));
                    assertThat(ctx + " value@" + p, f, equalTo(r));
                }
                case BYTES_REF -> {
                    BytesRef f = ((BytesRefBlock) fused).getBytesRef(fused.getFirstValueIndex(p), new BytesRef());
                    BytesRef r = ((BytesRefBlock) reference).getBytesRef(reference.getFirstValueIndex(p), new BytesRef());
                    assertThat(ctx + " value@" + p, f, equalTo(r));
                }
            }
        }
    }

    /**
     * Asserts both result blocks are <b>non-null</b> at position {@code p} and hold <b>different</b> values there. Used
     * by the distinct-constant proof: two fused trees that differ only in an embedded constant value MUST produce
     * different results at a crafted position, so a stale cache that returned the wrong-valued class would fail here.
     * Double comparison uses {@code doubleToLongBits} so {@code -0.0}/{@code +0.0} compare as distinct only if the bits
     * differ.
     */
    protected static void assertResultsDifferAt(ElementKind kind, Block x, Block y, int p, String ctx) {
        assertThat(ctx + " x must be non-null@" + p, x.isNull(p), is(false));
        assertThat(ctx + " y must be non-null@" + p, y.isNull(p), is(false));
        switch (kind) {
            case LONG -> {
                long xv = ((LongBlock) x).getLong(x.getFirstValueIndex(p));
                long yv = ((LongBlock) y).getLong(y.getFirstValueIndex(p));
                assertThat(ctx + " values must differ@" + p, xv, not(equalTo(yv)));
            }
            case INT -> {
                int xv = ((IntBlock) x).getInt(x.getFirstValueIndex(p));
                int yv = ((IntBlock) y).getInt(y.getFirstValueIndex(p));
                assertThat(ctx + " values must differ@" + p, xv, not(equalTo(yv)));
            }
            case DOUBLE -> {
                double xv = ((DoubleBlock) x).getDouble(x.getFirstValueIndex(p));
                double yv = ((DoubleBlock) y).getDouble(y.getFirstValueIndex(p));
                assertThat(ctx + " values must differ@" + p, Double.doubleToLongBits(xv), not(equalTo(Double.doubleToLongBits(yv))));
            }
            case BOOLEAN -> {
                boolean xv = ((BooleanBlock) x).getBoolean(x.getFirstValueIndex(p));
                boolean yv = ((BooleanBlock) y).getBoolean(y.getFirstValueIndex(p));
                assertThat(ctx + " values must differ@" + p, xv, not(equalTo(yv)));
            }
        }
    }

    /**
     * Asserts the ratified warning contract: the fused path's registered warnings must be a <b>subset</b> of the
     * unfused path's warnings for the same page — never a superset. Equality is the common case; the strict-subset case
     * arises when the fused per-position loop short-circuits a position to null on the first abnormal condition it meets
     * while the unfused columnar tree evaluates every sibling independently and registers additional sibling warnings.
     */
    protected static void assertWarningsSubset(List<String> fused, List<String> reference, String ctx) {
        Set<String> fusedOnly = new HashSet<>(fused);
        fusedOnly.removeAll(new HashSet<>(reference));
        assertThat(
            ctx + " fused warnings must be a SUBSET of unfused warnings (ratified subset invariant); fused-only=" + fusedOnly,
            fusedOnly,
            is(Collections.emptySet())
        );
    }

    /** True if any warning header contains {@code substring}. */
    protected static boolean containsWarning(List<String> warnings, String substring) {
        return warnings.stream().anyMatch(w -> w.contains(substring));
    }

    /**
     * Strict warning <b>parity</b> for a deterministic, no-short-circuit, single-warning shape: the <b>fused</b> path
     * must ITSELF emit a warning containing {@code substring}. The subset rule ({@link #assertWarningsSubset}) is not
     * enough here — a fused path that silently dropped the warning would still be a subset of the unfused warnings, so
     * for these shapes the fused side is observed directly (via {@link #runDifferentialWarnings}) and asserted here.
     */
    protected static void assertFusedEmitsWarning(List<String> fusedWarnings, String substring, String ctx) {
        assertThat(
            ctx
                + " the FUSED path must itself emit a warning containing ["
                + substring
                + "] (parity, not just subset); fused="
                + fusedWarnings,
            containsWarning(fusedWarnings, substring),
            is(true)
        );
    }

    /**
     * Reads and then clears the current thread's collected {@code Warning} response headers. Clearing (via
     * {@link org.elasticsearch.common.util.concurrent.ThreadContext#stashContext()}) resets the header set and the
     * per-context deduplication so the fused and unfused runs are measured independently.
     */
    protected List<String> drainWarnings() {
        List<String> warnings = new ArrayList<>(threadContext.getResponseHeaders().getOrDefault("Warning", List.of()));
        threadContext.stashContext();
        return warnings;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Input page construction: the (ElementKind, Structure, Profile) column model
    // ---------------------------------------------------------------------------------------------------------------

    /** The primitive element of a column (input) or a result block (output; {@link #BOOLEAN} only appears as output). */
    protected enum ElementKind {
        LONG,
        INT,
        DOUBLE,
        BOOLEAN,
        BYTES_REF
    }

    /**
     * A value/density profile that drives which physical {@link Structure}s a page uses and which single warning
     * mechanism (if any) it can raise.
     */
    protected enum Profile {
        /** Dense, bounded, no nulls/multi-values: the vector fast path, no warnings. */
        CLEAN,
        /** Dense {@code Neg}/{@code Abs}-only (non-overflow) inputs: keeps the plain-vector fast path reachable. */
        CLEAN_UNARY,
        /** {@code *Block}s with random nulls (no multi-values): the block path, null propagation. */
        NULLS,
        /** {@code *Block}s with random multi-values (no nulls): the block path + the single-value multi-value warning. */
        MULTIVALUE,
        /** Dense values seeded at the MAX/MIN boundary so overflow-checked kernels throw: null + overflow warning. */
        OVERFLOW,
        /** Dense tiny values including {@code 0} divisors: {@code Div}'s {@code / by zero} null + warning. */
        DIVZERO,
        /** {@code *Block}s carrying both random nulls and multi-values (used by the logical sweeps). */
        NULLS_AND_MULTIVALUE
    }

    /** How one column's {@code Block} is physically encoded, which drives whether the vector or block path is taken. */
    protected enum Structure {
        DENSE_VECTOR,
        CONSTANT,
        NULLABLE_BLOCK,
        MULTIVALUE_BLOCK
    }

    /**
     * Builds one input page of {@code columns} columns for the given {@code profile}. For NULLS / MULTIVALUE /
     * NULLS_AND_MULTIVALUE it forces at least one <em>referenced</em> column to actually carry the abnormality so the
     * block path and its warning are genuinely exercised (an abnormal but unreferenced column would be a no-op).
     */
    protected Block[] buildPage(ElementKind kind, int columns, int positionCount, Profile profile, boolean division, int[] referenced) {
        boolean abnormal = profile == Profile.NULLS || profile == Profile.MULTIVALUE || profile == Profile.NULLS_AND_MULTIVALUE;
        int forced = abnormal && referenced.length > 0 ? referenced[randomInt(referenced.length - 1)] : -1;
        Block[] blocks = new Block[columns];
        for (int c = 0; c < columns; c++) {
            Structure structure = structureFor(profile, positionCount, c == forced);
            blocks[c] = buildColumn(kind, positionCount, structure, profile, division);
        }
        return blocks;
    }

    protected Structure structureFor(Profile profile, int positionCount, boolean forcedAbnormal) {
        if (positionCount == 0) {
            // Empty pages have no positions to make abnormal; a plain empty dense vector is unambiguous.
            return Structure.DENSE_VECTOR;
        }
        return switch (profile) {
            case CLEAN_UNARY -> Structure.DENSE_VECTOR; // keep dense so the plain-vector fast path is taken
            case CLEAN, OVERFLOW, DIVZERO ->
                // Occasionally use a ConstantVector: a dense, no-null, non-ArrayVector input that forces the block path.
                randomInt(3) == 0 ? Structure.CONSTANT : Structure.DENSE_VECTOR;
            case NULLS -> forcedAbnormal ? Structure.NULLABLE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK);
            case MULTIVALUE -> forcedAbnormal ? Structure.MULTIVALUE_BLOCK : randomFrom(Structure.DENSE_VECTOR, Structure.MULTIVALUE_BLOCK);
            case NULLS_AND_MULTIVALUE -> forcedAbnormal
                ? randomFrom(Structure.NULLABLE_BLOCK, Structure.MULTIVALUE_BLOCK)
                : randomFrom(Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK, Structure.MULTIVALUE_BLOCK);
        };
    }

    protected Block buildColumn(ElementKind kind, int positionCount, Structure structure, Profile profile, boolean division) {
        return switch (kind) {
            case LONG -> buildLongColumn(positionCount, structure, () -> nextLong(profile, division));
            case INT -> buildIntColumn(positionCount, structure, () -> nextInt(profile));
            case DOUBLE -> buildDoubleColumn(positionCount, structure, this::nextDouble);
            case BOOLEAN -> throw new AssertionError("boolean columns are output-only in these harnesses");
            case BYTES_REF -> throw new AssertionError("BytesRef columns are supplied directly by BytesRef tests, not generated here");
        };
    }

    // Value generators. Non-overflow profiles stay small enough that a depth-4 all-multiply tree cannot overflow, so the
    // only warning source per page is the profile's single mechanism (null: none, multi-value, overflow, or div-zero).

    protected long nextLong(Profile profile, boolean division) {
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

    protected int nextInt(Profile profile) {
        return switch (profile) {
            case OVERFLOW -> randomBoolean()
                ? randomIntBetween(-100, 100)
                : (randomBoolean() ? Integer.MAX_VALUE - randomIntBetween(0, 3) : Integer.MIN_VALUE + randomIntBetween(0, 3));
            // A depth-4 all-multiply int tree with |v| <= 3 tops out at 3^16 < Integer.MAX_VALUE, so no accidental overflow.
            default -> randomIntBetween(-3, 3);
        };
    }

    protected double nextDouble() {
        // Bounded so add/sub/mul over depth <= 4 stays far below Double.MAX_VALUE (no Infinity => asFiniteNumber never throws).
        return randomDoubleBetween(-1000.0, 1000.0, true);
    }

    protected Block buildLongColumn(int positionCount, Structure structure, LongSupplier values) {
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

    protected Block buildIntColumn(int positionCount, Structure structure, IntSupplier values) {
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

    protected Block buildDoubleColumn(int positionCount, Structure structure, DoubleSupplier values) {
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

    // ---------------------------------------------------------------------------------------------------------------
    // Deterministic single-column helpers used by the edge-case tests
    // ---------------------------------------------------------------------------------------------------------------

    /** A deterministic dense (no null / multi-value) column of {@code kind}, seeded so distinct columns differ. */
    protected Block denseColumn(ElementKind kind, int positions, int seed) {
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
            case BOOLEAN -> throw new AssertionError("boolean columns are output-only in these harnesses");
            case BYTES_REF -> throw new AssertionError("BytesRef columns are supplied directly by BytesRef tests, not generated here");
        };
    }

    /** A {@code long} block from boxed values, mapping {@code null} entries to {@code appendNull()}. */
    protected Block longColumn(Long[] vals) {
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

    /** A single-position {@code long} block whose only position holds {@code values} as a multi-value entry. */
    protected Block multiValueLong(long[] values) {
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
    protected Block nullLong(int positions) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positions)) {
            for (int p = 0; p < positions; p++) {
                builder.appendNull();
            }
            return builder.build();
        }
    }

    /** A single-position {@code long} block holding one value. */
    protected Block singleLong(long value) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(1)) {
            return builder.appendLong(value).build();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shape selection
    // ---------------------------------------------------------------------------------------------------------------

    /** Guarantees empty (0), single (1), and &ge;1024 shapes appear, with a spread of small counts in between. */
    protected int positionCountFor(int i) {
        return switch (i) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 1024 + randomIntBetween(0, 256);
            default -> i % 17 == 0 ? 1024 + randomIntBetween(0, 512) : randomIntBetween(2, 300);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Coverage bookkeeping (proves the matrix was actually exercised, so no assertion passes vacuously)
    // ---------------------------------------------------------------------------------------------------------------

    protected static final class Coverage {
        protected int empty;
        protected int single;
        protected int large;
        protected int plainVector;
        protected int checkedVector;
        protected int none;
        protected int overflowWarnings;
        protected int multiValueWarnings;
        protected int divZeroWarnings;
        protected int crossCategory;
        /** Per-{@link Profile} iteration counter (by ordinal), so a sweep can prove every applicable profile was run. */
        protected final int[] profileHits = new int[Profile.values().length];
        /**
         * Per-({@link ElementKind}, {@link VectorStrategy}) counter (by ordinal), so a sweep can prove a specific
         * element reached a specific fast path — e.g. that a {@code double} overflow-checked tree reaches
         * {@code CHECKED_VECTOR} (the iter-22 VectorUnsafe/NumericUtils-links-on-the-vector-path win).
         */
        protected final int[][] strategyByElement = new int[ElementKind.values().length][VectorStrategy.values().length];
    }

    protected static void assertShapeMatrixCovered(Coverage cov) {
        assertThat("empty (positionCount==0) shape must be exercised", cov.empty, greaterThan(0));
        assertThat("single-row shape must be exercised", cov.single, greaterThan(0));
        assertThat("large (>=1024) shape must be exercised", cov.large, greaterThan(0));
    }

    protected static void recordShape(Coverage cov, int positionCount) {
        if (positionCount == 0) {
            cov.empty++;
        } else if (positionCount == 1) {
            cov.single++;
        } else if (positionCount >= 1024) {
            cov.large++;
        }
    }

    /** Records the compiled {@link VectorStrategy}, both overall and per numeric {@code element}. */
    protected static void recordStrategy(Coverage cov, VectorStrategy strategy, ElementKind element) {
        switch (strategy) {
            case PLAIN_VECTOR -> cov.plainVector++;
            case CHECKED_VECTOR -> cov.checkedVector++;
            case NONE -> cov.none++;
        }
        cov.strategyByElement[element.ordinal()][strategy.ordinal()]++;
    }

    /** How many times {@code strategy} was compiled for trees whose numeric input element was {@code element}. */
    protected static int strategyHits(Coverage cov, ElementKind element, VectorStrategy strategy) {
        return cov.strategyByElement[element.ordinal()][strategy.ordinal()];
    }

    /** Records that one iteration ran the given {@code profile} (independent of whether it raised a warning). */
    protected static void recordProfile(Coverage cov, Profile profile) {
        cov.profileHits[profile.ordinal()]++;
    }

    protected static int profileHits(Coverage cov, Profile profile) {
        return cov.profileHits[profile.ordinal()];
    }

    /** Asserts each of the given profiles was actually exercised, so a sweep that stopped producing one FAILS. */
    protected static void assertProfilesCovered(Coverage cov, Profile... profiles) {
        for (Profile profile : profiles) {
            assertThat("profile " + profile + " must be exercised in the sweep", profileHits(cov, profile), greaterThan(0));
        }
    }

    protected static void recordWarnings(Coverage cov, Profile profile, List<String> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        switch (profile) {
            case OVERFLOW -> cov.overflowWarnings++;
            case MULTIVALUE, NULLS_AND_MULTIVALUE -> {
                if (containsWarning(warnings, MULTI_VALUE_MSG)) {
                    cov.multiValueWarnings++;
                }
            }
            case DIVZERO -> {
                if (containsWarning(warnings, DIV_ZERO_MSG)) {
                    cov.divZeroWarnings++;
                }
            }
            default -> {
                // CLEAN / CLEAN_UNARY / NULLS must not register a mechanism warning; the differential assertion above
                // already compared any warning against the unfused chain, so nothing extra to record here.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Small shared helpers
    // ---------------------------------------------------------------------------------------------------------------

    protected static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(
            Source.EMPTY,
            name,
            new EsField(name, type, Collections.emptyMap(), false, EsField.TimeSeriesFieldType.NONE)
        );
    }

    protected static Layout layout(FieldAttribute... fields) {
        Layout.Builder builder = new Layout.Builder();
        for (FieldAttribute field : fields) {
            builder.append(field);
        }
        return builder.build();
    }

    protected static int[] toArray(Set<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (int v : values) {
            out[i++] = v;
        }
        return out;
    }
}
