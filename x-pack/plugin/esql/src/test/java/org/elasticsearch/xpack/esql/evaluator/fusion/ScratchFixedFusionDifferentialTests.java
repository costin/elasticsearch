/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Space;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

/**
 * B3b/#8b end-to-end: a real single-value string function with per-driver SCRATCH {@code @Fixed} buffers ({@code LEFT},
 * whose {@code process} takes a reusable {@code BytesRef} + a {@code UTF8CodePoint} as {@code THREAD_LOCAL @Fixed}
 * params) fuses through the planner. The factory builds those scratch buffers fresh per driver (never captured/shared),
 * the loop-bodied kernel splices, and the fused block evaluator must match the unfused {@code LeftEvaluator}
 * position-by-position — including the single-value {@code multi-value} warn+null behaviour (a {@code @Evaluator},
 * unlike a multi-value-mapping convert evaluator). Uses a depth-2 tree ({@code LEFT(LEFT(s,5),3)}) so it actually fuses.
 */
public class ScratchFixedFusionDifferentialTests extends FusionDifferentialTestCase {

    private static Literal intLit(int v) {
        return new Literal(Source.EMPTY, v, DataType.INTEGER);
    }

    /** {@code LEFT(LEFT(s,5),3)} — depth-2, four per-driver scratch buffers + two int constants. */
    private static Expression nestedLeft(FieldAttribute s) {
        return new Left(Source.EMPTY, new Left(Source.EMPTY, s, intLit(5)), intLit(3));
    }

    public void testNestedLeftScratchFusesAndMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = nestedLeft(s);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "left(left(s,5),3)");

        String[] vals = { "Hello", "MiXeD", null, "", "Grüße δΔ", "ab", "abcdefghij", "z" };
        int positionCount = vals.length;
        Block in;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            for (String v : vals) {
                if (v == null) {
                    b.appendNull();
                } else {
                    b.appendBytesRef(new BytesRef(v));
                }
            }
            in = b.build();
        }
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "left(left(s,5),3)");
    }

    /**
     * {@code LEFT} is a single-value {@code @Evaluator}: a multi-valued input position warns and nulls (it does NOT map
     * over the values). The fused scratch kernel must reproduce that exactly — same null, and a fused warning subset —
     * so a multi-valued position is safe to fuse (unlike a multi-value-mapping convert evaluator, which is not fused).
     */
    public void testNestedLeftMultiValueWarnAndNullParity() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = nestedLeft(s);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "left(left(s,5),3) mv");

        int positionCount = 4;
        Block in;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("single"));       // 0: single value -> "sin"
            b.beginPositionEntry();                          // 1: multi-value -> warn + null
            b.appendBytesRef(new BytesRef("multiOne"));
            b.appendBytesRef(new BytesRef("multiTwo"));
            b.endPositionEntry();
            b.appendNull();                                  // 2: null -> null
            b.appendBytesRef(new BytesRef("δΔname"));        // 3: single value (unicode)
            in = b.build();
        }
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "left(left(s,5),3) mv");
    }

    /**
     * {@code SPACE(number)} owns a per-driver {@link org.elasticsearch.compute.operator.BreakingBytesRefBuilder} — a
     * circuit-breaker-accounted {@code Releasable} scratch — so this proves the breaker-accounted scratch lifecycle:
     * built from the driver's breaker in {@code get(DriverContext)} and released on {@code close()}. The base harness's
     * {@code @After} asserts the request breaker is back to 0, so a leaked scratch fails the test. Uses a non-foldable
     * int column (a literal would fold SPACE away) wrapped in LEFT to make a depth-2 fusable tree, with a negative value
     * to exercise the warn+null (IllegalArgumentException) path. Fused must match the unfused chain.
     */
    public void testSpaceBreakerScratchFusesAndMatchesUnfused() {
        FieldAttribute n = field("n", DataType.INTEGER);
        Layout layout = layout(n);
        // LEFT(SPACE(n), 3): SPACE (breaker scratch) inside LEFT (plain scratch) -> depth-2, both scratch kinds at once.
        Expression expr = new Left(Source.EMPTY, new Space(Source.EMPTY, n), intLit(3));
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "left(space(n),3)");

        Integer[] vals = { 0, 1, 3, 10, -1, 5, 2, 7 }; // -1 -> SPACE warns + nulls (both paths)
        int positionCount = vals.length;
        Block in;
        try (org.elasticsearch.compute.data.IntBlock.Builder b = blockFactory.newIntBlockBuilder(positionCount)) {
            for (Integer v : vals) {
                if (v == null) {
                    b.appendNull();
                } else {
                    b.appendInt(v);
                }
            }
            in = b.build();
        }
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "left(space(n),3)");
    }

    private Block keywordBlock(String... vals) {
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(vals.length)) {
            for (String v : vals) {
                if (v == null) {
                    b.appendNull();
                } else {
                    b.appendBytesRef(new BytesRef(v));
                }
            }
            return b.build();
        }
    }

    /**
     * CONCAT(a, b, ...) is a variadic kernel: {@code process(@Fixed BreakingBytesRefBuilder scratch, BytesRef[] values)}.
     * The N string operands become the array's elements, which the stitcher builds per row (each read into a fresh
     * BytesRef). Wrapped in LEFT to make a depth-2 fusable tree. A null operand nulls the position (standard null
     * propagation); fused must match the unfused ConcatEvaluator. Also exercises the breaker-accounted CONCAT scratch
     * (the harness {@code @After} proves the breaker balances).
     */
    public void testConcatVariadicFusesAndMatchesUnfused() {
        FieldAttribute a = field("a", DataType.KEYWORD);
        FieldAttribute b = field("b", DataType.KEYWORD);
        Layout layout = layout(a, b);
        Expression expr = new Left(Source.EMPTY, new Concat(Source.EMPTY, a, List.of(b)), intLit(3));
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "left(concat(a,b),3)");

        Block ba = keywordBlock("foo", "", "abc", null, "δΔx", "z");
        Block bb = keywordBlock("bar", "Q", null, "y", "ΩΩ", "");
        int positionCount = 6;
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { ba, bb }, positionCount, "left(concat(a,b),3)");
    }

    /**
     * Opt-in depth-1 string fusion: a bare {@code LEFT(s, 3)} (a lone depth-1 kernel) does NOT fuse by default, but
     * DOES fuse when {@code esql.fusion.string_depth1} is on — and then matches the unfused evaluator (including the
     * single-value multi-value warn+null and null propagation).
     */
    public void testBareLeftDepth1FusesOnlyWhenEnabled() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new Left(Source.EMPTY, s, intLit(3));

        // Default: a lone depth-1 string function is not worth fusing.
        assertDoesNotFuse(fusedFactory(expr, layout), "left(s,3) default off");

        FusionSettings.setStringDepth1EnabledForTests(true);
        try {
            ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
            assertFuses(fused, "left(s,3) depth1 on");
            Block in = keywordBlock("hello", "", null, "δΔxyz", "ab", "z");
            runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, 6, "left(s,3) depth1");
        } finally {
            FusionSettings.setStringDepth1EnabledForTests(false);
        }
    }

    /** A 3-operand CONCAT proves the variadic array-gather for N &gt; 2 (three distinct fresh per-element BytesRefs). */
    public void testConcatThreeArgVariadic() {
        FieldAttribute a = field("a", DataType.KEYWORD);
        FieldAttribute b = field("b", DataType.KEYWORD);
        FieldAttribute c = field("c", DataType.KEYWORD);
        Layout layout = layout(a, b, c);
        Expression expr = new Left(Source.EMPTY, new Concat(Source.EMPTY, a, List.of(b, c)), intLit(5));
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "left(concat(a,b,c),5)");

        Block ba = keywordBlock("A", "one", null, "x");
        Block bb = keywordBlock("B", "two", "y", "");
        Block bc = keywordBlock("C", "three", "z", "w");
        int positionCount = 4;
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { ba, bb, bc }, positionCount, "left(concat(a,b,c),5)");
    }
}
