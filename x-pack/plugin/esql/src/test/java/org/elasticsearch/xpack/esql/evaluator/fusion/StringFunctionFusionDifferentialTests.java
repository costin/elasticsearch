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
import org.elasticsearch.xpack.esql.expression.function.scalar.string.LTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.RTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.function.Function;

/**
 * Item-2 end-to-end: more single-value {@code @Evaluator} BytesRef string functions fuse and match the unfused
 * evaluator — RIGHT (same scratch shape as LEFT) and the unary TRIM/LTRIM/RTRIM (no scratch, loop bodies, public-only).
 * These are depth-1 so the test enables {@code esql.fusion.string_depth1} to fuse them bare; a keyword column mixes
 * leading/trailing whitespace, unicode, empty, null and a multi-value position (which a single-value function
 * warn+nulls, both fused and unfused).
 */
public class StringFunctionFusionDifferentialTests extends FusionDifferentialTestCase {

    private void runStringFnDifferential(String label, Function<FieldAttribute, Expression> build) {
        FusionSettings.setStringDepth1EnabledForTests(true);
        try {
            FieldAttribute s = field("s", DataType.KEYWORD);
            Layout layout = layout(s);
            Expression expr = build.apply(s);
            ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
            assertFuses(fused, label);

            int positionCount = 7;
            Block in;
            try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
                b.appendBytesRef(new BytesRef("  hello  "));   // 0: leading+trailing spaces
                b.appendBytesRef(new BytesRef("world"));        // 1: no spaces
                b.appendBytesRef(new BytesRef(""));             // 2: empty
                b.appendNull();                                  // 3: null
                b.appendBytesRef(new BytesRef("  δΔ ω "));      // 4: unicode + spaces
                b.beginPositionEntry();                          // 5: multi-value -> single-value fn warn+null
                b.appendBytesRef(new BytesRef(" a "));
                b.appendBytesRef(new BytesRef(" b "));
                b.endPositionEntry();
                b.appendBytesRef(new BytesRef("trailing   "));  // 6: trailing spaces
                in = b.build();
            }
            runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, positionCount, label);
        } finally {
            FusionSettings.setStringDepth1EnabledForTests(false);
        }
    }

    private static Literal intLit(int v) {
        return new Literal(Source.EMPTY, v, DataType.INTEGER);
    }

    public void testRightFusesAndMatchesUnfused() {
        runStringFnDifferential("right(s,3)", s -> new Right(Source.EMPTY, s, intLit(3)));
    }

    public void testTrimFusesAndMatchesUnfused() {
        runStringFnDifferential("trim(s)", s -> new Trim(Source.EMPTY, s));
    }

    public void testLTrimFusesAndMatchesUnfused() {
        runStringFnDifferential("ltrim(s)", s -> new LTrim(Source.EMPTY, s));
    }

    public void testRTrimFusesAndMatchesUnfused() {
        runStringFnDifferential("rtrim(s)", s -> new RTrim(Source.EMPTY, s));
    }
}
