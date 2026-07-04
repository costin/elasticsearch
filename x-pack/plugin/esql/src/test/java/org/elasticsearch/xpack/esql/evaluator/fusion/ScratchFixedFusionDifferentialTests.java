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
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.planner.Layout;

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
}
