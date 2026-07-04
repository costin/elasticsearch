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
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

/**
 * B3b end-to-end: a real {@code BytesRef -> BytesRef} string function ({@code TO_LOWER}/{@code TO_UPPER}, i.e.
 * {@link org.elasticsearch.xpack.esql.expression.function.scalar.string.ChangeCase}) fuses through the planner using
 * object {@code @Fixed} constants (the query {@link java.util.Locale} and the {@code UPPER}/{@code LOWER} case). A
 * nested {@code TO_LOWER(TO_UPPER(field))} is depth-2 so it fuses under the normal depth rule, and its fused block
 * evaluator must produce, position-by-position, the same {@code BytesRef} values (and nulls) as the unfused generated
 * {@code ChangeCaseEvaluator} chain — proving reference-constant threading, the spliced {@code ChangeCase.process}
 * body (now cross-package linkable), and the BytesRef output path all work end to end.
 */
public class ChangeCaseFusionDifferentialTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    public void testChangeCaseFusesAndMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        // depth-2 BytesRef tree: TO_LOWER(TO_UPPER(s)) — two ChangeCase kernels, each with its Locale + Case @Fixed.
        Expression expr = new ToLower(Source.EMPTY, new ToUpper(Source.EMPTY, s, CFG), CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "toLower(toUpper(s))");

        String[] vals = { "Hello", "WORLD", "MiXeD", null, "", "Grüße δΔ", "already lower", "ALREADY UPPER" };
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
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "toLower(toUpper(s))");
    }

    public void testToUpperOfToLowerFusesAndMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToUpper(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "toUpper(toLower(s))");

        String[] vals = { "Hello", "café", null, "MIX", "z" };
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
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "toUpper(toLower(s))");
    }
}
