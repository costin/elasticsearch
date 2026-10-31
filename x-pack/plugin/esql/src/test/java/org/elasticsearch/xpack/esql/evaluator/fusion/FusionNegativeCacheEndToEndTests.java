/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.logging.log4j.Level;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * A5: end-to-end proof that a vector-path-only stitch failure is negatively cached and degrades to block-only
 * fusion through the <b>production</b> factory and the <b>real</b> {@link FusedClassCache}.
 *
 * <p>WHY a package-private cache hook rather than a natural over-budget tree: the plain-vector and checked-vector
 * emitters now <em>split</em> an oversize body into {@code compute()} instead of refusing, and a deep logical tree
 * refuses the <em>block</em> path (full unfused fallback) rather than degrading. {@code FusionPlanner.compilerForTests}
 * cannot be used here because it bypasses the cache, so the negative-cache counters would not move. The hook injects
 * a {@link org.elasticsearch.compute.operator.fusion.Stitcher.StitchingException} into the cache's stitch lambda for
 * one {@link Path} only; the block path still stitches for real.
 */
public class FusionNegativeCacheEndToEndTests extends FusionDifferentialTestCase {

    /**
     * Plan {@code (a + b) * c} twice through {@link #fusedFactory}. The CHECKED_VECTOR path fails once, the second
     * plan is a negative-cache hit, both factories are block-only ({@link VectorStrategy#NONE}), fused output matches
     * unfused (values / nulls / multi-values / warnings), and neither plan WARNs (degrade-to-block is DEBUG).
     */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache:DEBUG,"
            + "org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory:DEBUG",
        reason = "assert a vector-path degrade through the production factory is DEBUG (no WARN) on both plans"
    )
    public void testNegativeCacheVectorDegradeThroughProductionFactory() {
        assertThat(
            "A5 must use the production compiler (compilerForTests bypasses the cache)",
            FusionPlanner.compilerForTests,
            nullValue()
        );

        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        // Long arithmetic is overflow-checked, so the production factory compiles CHECKED_VECTOR after BLOCK.
        cache.injectFailureForPathForTests = Path.CHECKED_VECTOR;

        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(unfused, "kill switch OFF");

        FusedExpressionEvaluatorFactory first;
        FusedExpressionEvaluatorFactory second;
        try (var mockLog = MockLog.capture(FusedClassCache.class, FusedExpressionEvaluatorFactory.class)) {
            mockLog.addExpectation(
                new MockLog.UnseenEventExpectation(
                    "vector-path degrade must not WARN at cache (both plans)",
                    FusedClassCache.class.getCanonicalName(),
                    Level.WARN,
                    "*fusion stitch*"
                )
            );
            mockLog.addExpectation(
                new MockLog.UnseenEventExpectation(
                    "vector-path degrade must not WARN at factory (both plans)",
                    FusedExpressionEvaluatorFactory.class.getCanonicalName(),
                    Level.WARN,
                    "*fusion stitch*"
                )
            );
            mockLog.addExpectation(
                new MockLog.SeenEventExpectation(
                    "vector-path degrade DEBUG names the failed path",
                    FusedExpressionEvaluatorFactory.class.getCanonicalName(),
                    Level.DEBUG,
                    "*on path [CHECKED_VECTOR]*degrading to block-only fusion*"
                )
            );

            first = assertFuses(fusedFactory(expr, layout), "first plan through production factory");
            assertThat(
                "first plan degrades to block-only (vector stitch failed, block succeeded)",
                first.vectorStrategy(),
                is(VectorStrategy.NONE)
            );
            assertThat("the failing vector path is attempted once on the first plan", cache.failures(), is(1L));
            assertThat("no negative-cache hit yet (first plan is the miss that stored the Failure marker)", cache.negativeHits(), is(0L));
            long spinsAfterFirst = cache.spins();
            assertThat("first plan stitches BLOCK (success) and CHECKED_VECTOR (failure) — two spins", spinsAfterFirst, is(2L));

            second = assertFuses(fusedFactory(expr, layout), "second plan through production factory");
            assertThat(
                "second plan is the same block-only strategy (negative-cache hit, not a restitch)",
                second.vectorStrategy(),
                is(VectorStrategy.NONE)
            );
            assertThat("the failing vector path is still attempted only once across both plans", cache.failures(), is(1L));
            assertThat("the second plan is a negative-cache hit on the vector path", cache.negativeHits(), is(1L));
            assertThat("the second plan must not invoke the stitcher again", cache.spins(), is(spinsAfterFirst));
            assertThat("still on the production compiler", FusionPlanner.compilerForTests, nullValue());

            mockLog.assertAllExpectationsMatched();
        }

        // Fused (block-only) output is identical to unfused over values, nulls, multi-values, and warnings.
        int[] referenced = { 0, 1, 2 };
        assertParity(first, unfused, referenced, "first plan");
        assertParity(second, unfused, referenced, "second plan");
    }

    /**
     * Values (CLEAN), nulls, multi-values + the multi-value warning, and overflow + overflow warning — the
     * representative page set the A5 parity helper must cover.
     */
    private void assertParity(ExpressionEvaluator.Factory fused, ExpressionEvaluator.Factory unfused, int[] referenced, String plan) {
        int positions = 64;
        Block[] clean = buildPage(ElementKind.LONG, 3, positions, Profile.CLEAN, false, referenced);
        runDifferential(fused, unfused, ElementKind.LONG, clean, positions, plan + " CLEAN values");

        Block[] nulls = buildPage(ElementKind.LONG, 3, positions, Profile.NULLS, false, referenced);
        runDifferential(fused, unfused, ElementKind.LONG, nulls, positions, plan + " NULLS");

        // One deterministic multi-value position (not a random MULTIVALUE page, which can roll zero multi-values).
        Block[] multi = new Block[] {
            denseColumn(ElementKind.LONG, 1, 0),
            multiValueLong(new long[] { 1L, 2L }),
            denseColumn(ElementKind.LONG, 1, 2) };
        DifferentialWarnings mv = runDifferentialWarnings(fused, unfused, ElementKind.LONG, multi, 1, plan + " MULTIVALUE");
        assertFusedEmitsWarning(mv.fused(), MULTI_VALUE_MSG, plan + " MULTIVALUE");

        Block[] overflow = buildPage(ElementKind.LONG, 3, positions, Profile.OVERFLOW, false, referenced);
        runDifferential(fused, unfused, ElementKind.LONG, overflow, positions, plan + " OVERFLOW");
    }
}
