/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Deterministic, pinned-shape regressions for <b>arithmetic-rooted</b> expression fusion. The randomized arithmetic
 * sweeps that used to live here (long/int/double arithmetic + long division) are now subsumed by the unified,
 * spec-driven sweep in {@link FusionSpecDifferentialTests} (arithmetic root over the same overflow-checked / unary /
 * division palettes), and all the shared machinery lives in {@link FusionDifferentialTestCase}. What remains here are
 * the two hand-built shapes that a random sweep cannot reliably hit, kept as explicit regressions:
 * <ul>
 * <li>{@link #testMixedMultiValueAndDivByZeroWarningIsSubset()} — the exact mixed shape that first surfaced the
 * ratified warning-<b>subset</b> invariant (a single position that is simultaneously multi-valued in one sibling
 * and zero-divisor in another);</li>
 * <li>{@link #testArithmeticOverflowWarningsAttributedPerKernel()} — per-kernel warning-source attribution
 * ({@code (a+b)+(c+d)} with distinct non-empty {@link Source}s), which fails if fused warnings are misattributed
 * to the root source.</li>
 * </ul>
 */
public class FusionDifferentialTests extends FusionDifferentialTestCase {

    /**
     * Deterministic regression for the ratified warning-<b>subset</b> invariant, pinned so the exact mixed shape that
     * first surfaced the property can never silently regress.
     *
     * <p>The wired tree is {@code Add(Abs(mv), Div(a, z))} over three {@code long} columns, evaluated at a single
     * position that is <b>both</b> multi-valued (the {@code Abs} branch) <b>and</b> has a zero divisor (the {@code Div}
     * branch): the unfused columnar tree evaluates both siblings and registers <b>both</b> the multi-value and the
     * {@code / by zero} warning before {@code Add} nulls the row, while the fused per-position loop short-circuits on
     * the multi-value it sees first and never reaches the division — a strict subset.
     */
    public void testMixedMultiValueAndDivByZeroWarningIsSubset() {
        FieldAttribute mv = field("mv", DataType.LONG);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute z = field("z", DataType.LONG);
        Layout layout = layout(mv, a, z);
        Expression expr = new Add(Source.EMPTY, new Abs(Source.EMPTY, mv), new Div(Source.EMPTY, a, z), EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, "mixed-shape tree");
        assertDoesNotFuse(unfusedFactory, "mixed-shape tree");

        int positions = 5;
        int mixed = 2; // the one position that is simultaneously multi-valued (mv) and zero-divisor (z)

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

            CapturedEval fusedCap = evalCapturing(fusedFactory, page);
            fusedResult = fusedCap.block();
            CapturedEval referenceCap = evalCapturing(unfusedFactory, page);
            referenceResult = referenceCap.block();
            List<String> fusedWarnings = fusedCap.warnings();
            List<String> unfusedWarnings = referenceCap.warnings();

            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "mixed-shape");
            assertThat("fused mixed position must be null", fusedResult.isNull(mixed), is(true));
            assertThat("unfused mixed position must be null", referenceResult.isNull(mixed), is(true));

            assertWarningsSubset(fusedWarnings, unfusedWarnings, "mixed-shape");

            assertThat("unfused must contain the multi-value warning", containsWarning(unfusedWarnings, MULTI_VALUE_MSG), is(true));
            assertThat("unfused must contain the / by zero warning", containsWarning(unfusedWarnings, DIV_ZERO_MSG), is(true));
            assertThat("fused must contain the multi-value warning", containsWarning(fusedWarnings, MULTI_VALUE_MSG), is(true));
            assertThat(
                "fused must NOT contain the / by zero warning (short-circuited past the division)",
                containsWarning(fusedWarnings, DIV_ZERO_MSG),
                is(false)
            );
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * PRIORITY-1 warning-source fidelity for ARITHMETIC overflow: {@code (a + b) + (c + d)} over long
     * columns with DISTINCT NON-EMPTY {@link Source}s on the two inner {@code Add}s and the root {@code Add}. Position 0
     * overflows the LEFT inner add; position 1 overflows the RIGHT inner add; the root add only ever sees a null operand
     * at those positions and so never overflows. The unfused chain attributes one overflow warning to EACH inner add's
     * own source and NONE to the root, so the fused warnings must be a subset — a pre-fix single-root-{@code Warnings}
     * path attributed every fused overflow to the root source, a header the unfused set never contains, breaking subset.
     */
    public void testArithmeticOverflowWarningsAttributedPerKernel() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute d = field("d", DataType.LONG);
        Layout layout = layout(a, b, c, d);
        Expression leftInner = new Add(new Source(1, 1, "a+b"), a, b, EsqlTestUtils.TEST_CFG);
        Expression rightInner = new Add(new Source(2, 1, "c+d"), c, d, EsqlTestUtils.TEST_CFG);
        Expression expr = new Add(new Source(3, 1, "OUTER_ADD"), leftInner, rightInner, EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fusedFactory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat("overflow tree must fuse ON", fusedFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
        assertThat("overflow tree must NOT fuse OFF", unfusedFactory, not(instanceOf(FusedExpressionEvaluatorFactory.class)));

        int positions = 4;
        long[] av = { Long.MAX_VALUE, 1, 2, 3 };
        long[] bv = { 1, 1, 3, 4 };
        long[] cv = { 1, Long.MAX_VALUE, 5, 6 };
        long[] dv = { 1, 1, 7, 8 };

        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            Block aBlock = blockFactory.newLongArrayVector(av, positions).asBlock();
            Block bBlock = blockFactory.newLongArrayVector(bv, positions).asBlock();
            Block cBlock = blockFactory.newLongArrayVector(cv, positions).asBlock();
            Block dBlock = blockFactory.newLongArrayVector(dv, positions).asBlock();
            page = new Page(positions, aBlock, bBlock, cBlock, dBlock);

            CapturedEval fusedCap = evalCapturing(fusedFactory, page);
            fusedResult = fusedCap.block();
            CapturedEval referenceCap = evalCapturing(unfusedFactory, page);
            referenceResult = referenceCap.block();
            List<String> fusedWarnings = fusedCap.warnings();
            List<String> referenceWarnings = referenceCap.warnings();

            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "overflow-attribution");
            assertThat("p0 null (left inner overflow)", fusedResult.isNull(0), is(true));
            assertThat("p1 null (right inner overflow)", fusedResult.isNull(1), is(true));

            assertWarningsSubset(fusedWarnings, referenceWarnings, "overflow-attribution");
            assertThat("left inner source attribution", containsWarning(fusedWarnings, "[a+b]"), is(true));
            assertThat("right inner source attribution", containsWarning(fusedWarnings, "[c+d]"), is(true));
            assertThat("no root-source misattribution", containsWarning(fusedWarnings, "[OUTER_ADD]"), is(false));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * Task 3 (a) at eval: a CHECKED_VECTOR page with no overflow must return a {@link LongArrayVector}. A 1-row dense
     * page is the discriminator — TODAY the builder-based checked loop collapses it to {@code ConstantLongVector}.
     */
    public void testCheckedVectorNoOverflowPageReturnsLongArrayVector() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory, "checked-vector raw output");
        assertThat(fused.vectorStrategy(), is(FusedExpressionEvaluatorFactory.VectorStrategy.CHECKED_VECTOR));

        int positions = 1;
        ExpressionEvaluator evaluator = fusedFactory.get(driverContext);
        Page page = null;
        Block result = null;
        try {
            page = new Page(
                positions,
                blockFactory.newLongArrayVector(new long[] { 3 }, positions).asBlock(),
                blockFactory.newLongArrayVector(new long[] { 4 }, positions).asBlock(),
                blockFactory.newLongArrayVector(new long[] { 5 }, positions).asBlock()
            );
            result = evaluator.eval(page);
            assertThat(result.isNull(0), is(false));
            assertThat(((LongBlock) result).getLong(0), equalTo(35L));
            assertThat(
                "CHECKED_VECTOR no-overflow page must return a LongArrayVector (not the builder's ConstantLongVector)",
                result.asVector().getClass(),
                equalTo(LongArrayVector.class)
            );
        } finally {
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * Task 3 (b): a dense CHECKED_VECTOR page that overflows at row k is null at k and registers EXACTLY the unfused
     * warnings (count and header text). fusedRaw abandons on the first overflow and the builder re-run supplies the
     * warnings, matching unfused.
     */
    public void testCheckedVectorOverflowPageMatchesUnfusedWarningsExactly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, "checked-vector overflow abandon");
        assertDoesNotFuse(unfused, "checked-vector overflow abandon");
        assertThat(
            ((FusedExpressionEvaluatorFactory) fusedFactory).vectorStrategy(),
            is(FusedExpressionEvaluatorFactory.VectorStrategy.CHECKED_VECTOR)
        );

        int positions = 4;
        long[] av = { Long.MAX_VALUE, 3, 2, 10 };
        long[] bv = { 1, 4, 3, 20 };
        long[] cv = { 7, 5, Long.MAX_VALUE, 2 };

        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(
                positions,
                blockFactory.newLongArrayVector(av, positions).asBlock(),
                blockFactory.newLongArrayVector(bv, positions).asBlock(),
                blockFactory.newLongArrayVector(cv, positions).asBlock()
            );
            CapturedEval fusedCap = evalCapturing(fusedFactory, page);
            fusedResult = fusedCap.block();
            CapturedEval referenceCap = evalCapturing(unfused, page);
            referenceResult = referenceCap.block();
            List<String> fusedWarnings = fusedCap.warnings();
            List<String> unfusedWarnings = referenceCap.warnings();

            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, positions, "checked-vector overflow");
            assertThat("p0 overflow -> null", fusedResult.isNull(0), is(true));
            assertThat("p2 overflow -> null", fusedResult.isNull(2), is(true));
            assertThat("fused overflow warnings must equal unfused (count and header text)", fusedWarnings, equalTo(unfusedWarnings));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /** end-to-end eviction-under-a-live-factory proof through the REAL stitch and the shared process-wide cache. */
    public void testEvictedShapeStillEvaluatesFromPinnedFactory() {
        FusionCompilationService service = FusionPlanner.compilation();
        service.reconfigureCache(16L * 1024, 1);
        try {
            FieldAttribute a = field("a", DataType.LONG);
            FieldAttribute b = field("b", DataType.LONG);
            FieldAttribute c = field("c", DataType.LONG);
            Layout layout = layout(a, b, c);
            Expression target = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

            ExpressionEvaluator.Factory heldFused = fusedFactory(target, layout);
            assertFuses(heldFused, "evicted-but-pinned target");
            List<String> targetSignatures = service.cache()
                .entriesForTests()
                .stream()
                .map(FusedClassCache.Shape::signature)
                .distinct()
                .toList();
            assertThat("the held target must have cached at least its block class", targetSignatures.size(), greaterThan(0));

            boolean evicted = false;
            for (int depth = 2; depth <= 80 && evicted == false; depth++) {
                fusedFactory(addChain(a, b, c, depth), layout); // not held: free to evict
                evicted = targetSignatures.stream().noneMatch(signature -> cacheHoldsShape(service, signature));
            }
            assertThat("the target's classes must have been evicted by the growing distinct shapes", evicted, is(true));
            assertThat("the tiny cache must have evicted entries", service.cache().evictions(), is(greaterThan(0L)));

            int positions = 64;
            Block[] inputs = {
                denseColumn(ElementKind.LONG, positions, 1),
                denseColumn(ElementKind.LONG, positions, 2),
                denseColumn(ElementKind.LONG, positions, 3) };
            ExpressionEvaluator.Factory unfused = unfusedFactory(target, layout);
            assertDoesNotFuse(unfused, "evicted-but-pinned reference");
            runDifferential(heldFused, unfused, ElementKind.LONG, inputs, positions, "evicted-but-pinned");
        } finally {
            service.reconfigureCache(FusedClassCache.DEFAULT_MAX_WEIGHT_BYTES, 1);
        }
    }

    /** A left-deep all-{@code Add} chain of the given kernel {@code depth} (&ge; 2): {@code (((a+b)+c)+c)+...}. */
    private static Expression addChain(FieldAttribute a, FieldAttribute b, FieldAttribute c, int depth) {
        Expression e = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);
        for (int level = 1; level < depth; level++) {
            e = new Add(Source.EMPTY, e, c, EsqlTestUtils.TEST_CFG);
        }
        return e;
    }

    /** True if the live cache currently holds any entry whose plan shape equals {@code signature} (non-promoting). */
    private static boolean cacheHoldsShape(FusionCompilationService service, String signature) {
        return service.cache().entriesForTests().stream().anyMatch(shape -> shape.signature().equals(signature));
    }
}
