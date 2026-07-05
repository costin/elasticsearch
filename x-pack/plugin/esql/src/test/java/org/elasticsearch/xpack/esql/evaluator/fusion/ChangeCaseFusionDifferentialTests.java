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
 * Task 4 (multi-value mapping) end-to-end: {@code TO_LOWER}/{@code TO_UPPER} ({@link
 * org.elasticsearch.xpack.esql.expression.function.scalar.string.ChangeCase}) — {@code @ConvertEvaluator}s that MAP
 * over every value of a position — now fuse via the multi-value mapping block loop and must match the unfused
 * {@code ChangeCaseEvaluator} position-by-position, INCLUDING multi-valued positions (mapped to a multi-valued result,
 * not warn+null). This is the regression the earlier single-value fusion attempt got wrong (iter77); the mapping loop
 * fixes it. Depth-1 (a bare TO_LOWER) fuses via the mapping-path depth relaxation.
 */
public class ChangeCaseFusionDifferentialTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    private Block keyword(String... vals) {
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

    public void testToLowerFusesAndMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToLower(Source.EMPTY, s, CFG);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "toLower(s)");

        Block in = keyword("Hello", "WORLD", "MiXeD", null, "", "Grüße δΔ", "already lower");
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, 7, "toLower(s)");
    }

    /**
     * The critical case: a MULTI-VALUED keyword position. The unfused convert evaluator maps each value (multi-valued
     * result, no warning); the fused mapping loop must do the same (NOT warn+null). Covers single, empty/null, and
     * multi-value positions in one block.
     */
    public void testToUpperMultiValueMapsMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToUpper(Source.EMPTY, s, CFG);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, "toUpper(s)");

        int positionCount = 4;
        Block in;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("single"));      // 0: single -> "SINGLE"
            b.beginPositionEntry();                          // 1: multi-value -> ["ALPHA","béta"->"BÉTA"]
            b.appendBytesRef(new BytesRef("alpha"));
            b.appendBytesRef(new BytesRef("béta"));
            b.endPositionEntry();
            b.appendNull();                                  // 2: null
            b.appendBytesRef(new BytesRef("δΔ"));            // 3: single unicode
            in = b.build();
        }
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, "toUpper(s) mv");
    }
}
