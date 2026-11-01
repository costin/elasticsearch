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
import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ChangeCase;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * D4 + D3: a bare {@code TO_LOWER}/{@code TO_UPPER} ({@link ChangeCase} {@code @ConvertEvaluator}) stays unfused.
 * A converter fuses only as a nested mapping kernel inside a {@code depth >= 2} chain ({@code LENGTH(TO_LOWER(k))},
 * {@code TO_LOWER(k)=="x"}), compiling via the ordinary block path. There is no dedicated mapping-block emit path.
 * Values still match the unfused {@code ChangeCaseEvaluator} position-by-position, including multi-valued
 * positions (mapped result, not warn+null).
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

    /** A lone {@code TO_LOWER} does not fuse (D4). Values and warnings still match the unfused convert evaluator. */
    public void testLoneToLowerDoesNotFuseButMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToLower(Source.EMPTY, s, CFG);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "toLower(s)");

        Block in = keyword("Hello", "WORLD", "MiXeD", null, "", "Grüße δΔ", "already lower");
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { in }, 7, "toLower(s)");
    }

    /**
     * D2: TO_LOWER still fuses as a nested mapping kernel in a chain, through the generated {@link FusionAware}
     * descriptor (not a hand-written {@code CHANGE_CASE_DESCRIPTOR}). The mapping flag and {@code fusionFixedArgs}
     * come from the convert Factory. A lone TO_LOWER does not fuse (D4).
     */
    public void testToLowerFusesThroughGeneratedFusionAwareDescriptor() {
        for (Field field : FusionPlanner.class.getDeclaredFields()) {
            assertThat(
                "CHANGE_CASE_DESCRIPTOR must be deleted; mapping converters use generated FusionAware",
                field.getName(),
                not("CHANGE_CASE_DESCRIPTOR")
            );
        }
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression toLower = new ToLower(Source.EMPTY, s, CFG);
        Expression expr = new Length(Source.EMPTY, toLower);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "LENGTH(TO_LOWER) generated FusionAware");
        assertThat(
            "fused chain shape must carry the nested ChangeCase mapping kernel",
            fused.shape(),
            containsString("ChangeCase#process")
        );
        ExpressionEvaluator.Factory toLowerFactory = unfusedFactory(toLower, layout);
        assertThat("ChangeCaseEvaluator.Factory must implement FusionAware", toLowerFactory, instanceOf(FusionAware.class));
        FusionDescriptor descriptor = ((FusionAware) toLowerFactory).fusionDescriptor();
        assertThat(descriptor.kernelClass(), is(ChangeCase.class));
        assertThat(descriptor.kernelMethod(), is("process"));
        assertThat("generated convert descriptor is a mapping kernel", descriptor.mapping(), is(true));
        List<Object> fixed = ((FusionAware) toLowerFactory).fusionFixedArgs();
        assertThat(fixed.size(), is(2));
        assertThat(fixed.get(0), instanceOf(Locale.class));
        assertThat(fixed.get(1), is(ChangeCase.Case.LOWER));
    }

    /**
     * A MULTI-VALUED keyword position: a lone TO_UPPER does not fuse (D4). The unfused convert evaluator maps each
     * value (multi-valued result, no warning); the ON-switch factory must match that, not warn+null. Covers single,
     * empty/null, and multi-value positions in one block.
     */
    public void testToUpperMultiValueMapsMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToUpper(Source.EMPTY, s, CFG);
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, "toUpper(s)");

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
