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
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.util.function.Consumer;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Task 1 (R1): fused {@code TO_LOWER}/{@code TO_UPPER} mapping must not lose the unfused ordinal/constant encoding
 * fast paths. The fused mapping loop maps every row; the unfused {@code ChangeCaseEvaluator.evalVector} maps the
 * dictionary (and short-circuits constants) and keeps the output encoding. These tests pin that the fused evaluator
 * falls back to the unfused chain for ordinal- and constant-encoded pages, while plain and multi-value pages stay
 * fused and still match value-by-value.
 */
public class MappingEncodingGuardTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    /**
     * Fused {@code TO_LOWER} over an {@link OrdinalBytesRefVector} page must preserve ordinal encoding (the unfused
     * dictionary fast path) and match the unfused values. Without the encoding guard the fused mapping loop would emit
     * a plain (non-ordinal) block, losing that encoding.
     */
    public void testToLowerOrdinalEncodingPreserved() {
        String[] dictionary = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße" };
        int[] ordinals = { 0, 1, 2, 0, 1, 3, 2, 4, 0, 1, 3, 4 };
        Block input = ordinalKeyword(dictionary, ordinals);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: input must be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(true));

        evalToLower(input, ordinals.length, "toLower(ordinal keyword)", fused -> {
            BytesRefBlock fusedBytes = (BytesRefBlock) fused;
            assertThat(
                "fused TO_LOWER over an ordinal keyword page must preserve ordinal encoding",
                hasOrdinalEncoding(fusedBytes),
                is(true)
            );
        });
    }

    /**
     * Fused {@code TO_LOWER} over a constant {@code BytesRefVector} page must return a constant vector and match the
     * unfused values. Without the encoding guard the fused mapping loop would emit a non-constant per-row block.
     */
    public void testToLowerConstantEncodingPreserved() {
        int positionCount = 8;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        assertThat("test setup: input must be a constant vector", input.asVector(), notNullValue());
        assertThat("test setup: input must be constant", input.asVector().isConstant(), is(true));

        evalToLower(input, positionCount, "toLower(constant keyword)", fused -> {
            BytesRefBlock fusedBytes = (BytesRefBlock) fused;
            BytesRefVector fusedVector = fusedBytes.asVector();
            assertThat("fused TO_LOWER over a constant keyword page must return a vector", fusedVector, notNullValue());
            assertThat("fused TO_LOWER over a constant keyword page must return a constant vector", fusedVector.isConstant(), is(true));
        });
    }

    /**
     * Fused {@code TO_LOWER} over a plain (non-ordinal, non-constant) {@code BytesRefVector} still matches unfused
     * values. This is the mapping path that should stay fused; it must pass both before and after the encoding guard.
     */
    public void testToLowerPlainVectorMatchesUnfused() {
        String[] values = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße δΔ", "", "café" };
        Block input = keyword(values);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: plain input must not be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(false));
        assertThat("test setup: plain input must be a vector", inputBytes.asVector(), notNullValue());
        assertThat("test setup: plain input must not be constant", inputBytes.asVector().isConstant(), is(false));

        evalToLower(input, values.length, "toLower(plain keyword)", fused -> {});
    }

    /**
     * Fused {@code TO_LOWER} over a multi-value / null-containing {@code BytesRefBlock} still matches unfused values,
     * nulls, and multi-value structure. The encoding guard must not fire for this shape (no vector, no ordinals).
     */
    public void testToLowerMultiValueAndNullsMatchUnfused() {
        int positionCount = 5;
        Block input;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("Hello"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("WORLD"));
            b.appendBytesRef(new BytesRef("MiXeD"));
            b.endPositionEntry();
            b.appendNull();
            b.appendBytesRef(new BytesRef("already lower"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("Grüße"));
            b.appendBytesRef(new BytesRef("δΔ"));
            b.endPositionEntry();
            input = b.build();
        }
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: multi-value/null block must not be a vector", inputBytes.asVector(), nullValue());
        assertThat("test setup: multi-value/null block must not be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(false));

        evalToLower(input, positionCount, "toLower(mv/null keyword)", fused -> {});
    }

    private void evalToLower(Block input, int positionCount, String ctx, Consumer<Block> fusedAssertions) {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new ToLower(Source.EMPTY, s, CFG);
        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertFuses(fusedFactory, ctx);
        assertDoesNotFuse(unfusedFactory, ctx);

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, input);
            fusedResult = fused.eval(page);
            referenceResult = reference.eval(page);
            fusedAssertions.accept(fusedResult);
            assertResultsEqual(ElementKind.BYTES_REF, fusedResult, referenceResult, positionCount, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

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

    private Block ordinalKeyword(String[] dictionary, int[] ordinals) {
        BytesRefVector dict = null;
        IntVector ords = null;
        try (BytesRefVector.Builder dictBuilder = blockFactory.newBytesRefVectorBuilder(dictionary.length)) {
            for (String entry : dictionary) {
                dictBuilder.appendBytesRef(new BytesRef(entry));
            }
            dict = dictBuilder.build();
            ords = blockFactory.newIntArrayVector(ordinals, ordinals.length);
            Block block = new OrdinalBytesRefVector(ords, dict).asBlock();
            dict = null;
            ords = null;
            return block;
        } finally {
            Releasables.closeExpectNoException(dict, ords);
        }
    }

    private static boolean hasOrdinalEncoding(BytesRefBlock block) {
        if (block.asOrdinals() != null) {
            return true;
        }
        BytesRefVector vector = block.asVector();
        return vector != null && vector.asOrdinals() != null;
    }
}
