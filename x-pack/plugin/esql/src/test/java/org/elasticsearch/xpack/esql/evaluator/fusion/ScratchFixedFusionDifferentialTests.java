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
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

/**
 * B3b/#8b end-to-end: a real string function with per-driver SCRATCH {@code @Fixed} buffers ({@code LEFT}, whose
 * {@code process} takes a reusable {@code BytesRef} + a {@code UTF8CodePoint} as {@code THREAD_LOCAL @Fixed} params)
 * fuses through the planner. The factory builds those scratch buffers fresh per driver (never captured/shared), the
 * loop-bodied kernel splices, and the fused block evaluator must match the unfused chain position-by-position. Covered
 * as a depth-2 pure-scratch tree ({@code LEFT(LEFT(s,5),3)}) and mixed with an object-{@code @Fixed} kernel
 * ({@code LEFT(TO_UPPER(s),3)}), over a keyword column with nulls, unicode, and length-boundary values.
 */
public class ScratchFixedFusionDifferentialTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    private static Literal intLit(int v) {
        return new Literal(Source.EMPTY, v, DataType.INTEGER);
    }

    private void runLeftDifferential(String label, FieldAttribute s, Expression expr) {
        Layout layout = layout(s);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, label);

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
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, label);
    }

    public void testNestedLeftScratchFusesAndMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        // LEFT(LEFT(s,5),3): depth-2, four per-driver scratch buffers + two int constants; pure-scratch proof.
        Expression expr = new Left(Source.EMPTY, new Left(Source.EMPTY, s, intLit(5)), intLit(3));
        runLeftDifferential("left(left(s,5),3)", s, expr);
    }

    public void testLeftOverToUpperScratchAndObjectFixed() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        // LEFT(TO_UPPER(s),3): scratch @Fixed (Left) over object @Fixed (ChangeCase Locale/Case) — both in one tree.
        Expression expr = new Left(Source.EMPTY, new ToUpper(Source.EMPTY, s, CFG), intLit(3));
        runLeftDifferential("left(toUpper(s),3)", s, expr);
    }
}
