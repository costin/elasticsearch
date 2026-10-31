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
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * D1: fused mapping (and other single-BYTES_REF, no-warn trees) is encoding-aware. Ordinal and constant pages are
 * evaluated by the fused handle over the dictionary / one position — not deferred to the unfused chain — and the
 * fused output encoding is never weaker than the unfused output for the same page.
 */
public class MappingEncodingAwareTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    /**
     * Fused {@code TO_LOWER} over an {@link OrdinalBytesRefVector} page must itself produce an ordinal-encoded
     * output (not a row-by-row block, not an unfused fallback) and match the unfused values.
     */
    public void testToLowerOrdinalEncodingPreserved() {
        String[] dictionary = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße" };
        int[] ordinals = { 0, 1, 2, 0, 1, 3, 2, 4, 0, 1, 3, 4 };
        Block input = ordinalKeyword(dictionary, ordinals);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: input must be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(true));

        evalToLower(input, ordinals.length, "toLower(ordinal keyword)", (fused, unfused, path) -> {
            assertThat("ordinal TO_LOWER must take the encoding-aware fused path", path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(
                "fused TO_LOWER over an ordinal keyword page must produce ordinal encoding",
                hasOrdinalEncoding((BytesRefBlock) fused),
                is(true)
            );
            assertEncodingNoWeaker(fused, unfused, "toLower(ordinal keyword)");
        });
    }

    /**
     * Fused {@code TO_LOWER} over a constant {@code BytesRefVector} page must return a constant vector from the
     * encoding-aware fused path (one position evaluated, expanded to the page's position count).
     */
    public void testToLowerConstantEncodingPreserved() {
        int positionCount = 8;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        assertThat("test setup: input must be a constant vector", input.asVector(), notNullValue());
        assertThat("test setup: input must be constant", input.asVector().isConstant(), is(true));

        evalToLower(input, positionCount, "toLower(constant keyword)", (fused, unfused, path) -> {
            assertThat("constant TO_LOWER must take the encoding-aware fused path", path, is(LastEvalPath.ENCODING_AWARE));
            BytesRefVector fusedVector = ((BytesRefBlock) fused).asVector();
            assertThat("fused TO_LOWER over a constant keyword page must return a vector", fusedVector, notNullValue());
            assertThat("fused TO_LOWER over a constant keyword page must return a constant vector", fusedVector.isConstant(), is(true));
            assertEncodingNoWeaker(fused, unfused, "toLower(constant keyword)");
        });
    }

    /**
     * Fused {@code TO_LOWER} over a plain (non-ordinal, non-constant) {@code BytesRefVector} still matches unfused
     * values on the normal mapping block path.
     */
    public void testToLowerPlainVectorMatchesUnfused() {
        String[] values = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße δΔ", "", "café" };
        Block input = keyword(values);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: plain input must not be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(false));
        assertThat("test setup: plain input must be a vector", inputBytes.asVector(), notNullValue());
        assertThat("test setup: plain input must not be constant", inputBytes.asVector().isConstant(), is(false));

        evalToLower(input, values.length, "toLower(plain keyword)", (fused, unfused, path) -> {
            assertThat("plain TO_LOWER must stay on the mapping block path", path, is(LastEvalPath.BLOCK));
            assertEncodingNoWeaker(fused, unfused, "toLower(plain keyword)");
        });
    }

    /**
     * Fused {@code TO_LOWER} over a multi-value / null-containing {@code BytesRefBlock} still matches unfused values,
     * nulls, and multi-value structure. Encoding-aware does not fire (no dense vector).
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

        evalToLower(input, positionCount, "toLower(mv/null keyword)", (fused, unfused, path) -> {
            assertThat("multi-value/null TO_LOWER must stay on the mapping block path", path, is(LastEvalPath.BLOCK));
            assertEncodingNoWeaker(fused, unfused, "toLower(mv/null keyword)");
        });
    }

    /** {@code TO_UPPER} over an ordinal keyword page takes the encoding-aware path and keeps ordinal encoding. */
    public void testToUpperOrdinalEncodingPreserved() {
        String[] dictionary = { "Hello", "WORLD", "MiXeD" };
        int[] ordinals = { 0, 1, 2, 0, 1, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalToUpper(input, ordinals.length, "toUpper(ordinal keyword)", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(hasOrdinalEncoding((BytesRefBlock) fused), is(true));
            assertEncodingNoWeaker(fused, unfused, "toUpper(ordinal keyword)");
        });
    }

    /** {@code TO_UPPER} over a constant keyword page takes the encoding-aware path and keeps constant encoding. */
    public void testToUpperConstantEncodingPreserved() {
        int positionCount = 5;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        evalToUpper(input, positionCount, "toUpper(constant keyword)", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(((BytesRefBlock) fused).asVector(), notNullValue());
            assertThat(((BytesRefBlock) fused).asVector().isConstant(), is(true));
            assertEncodingNoWeaker(fused, unfused, "toUpper(constant keyword)");
        });
    }

    /**
     * Primitive/boolean gather: {@code LENGTH(x) > 5} is a single BYTES_REF column with no warn kernel, so ordinal
     * pages take the encoding-aware path and gather booleans from the dictionary result. Values match unfused.
     */
    public void testLengthGreaterThanConstantOrdinalTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, s), new Literal(Source.EMPTY, 5, DataType.INTEGER));
        String[] dictionary = { "hi", "hello", "elasticsearch", "café" };
        int[] ordinals = { 0, 1, 2, 0, 3, 1, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(expr, layout, input, ordinals.length, ElementKind.BOOLEAN, "LENGTH(x)>5 ordinal", (fused, unfused, path) -> {
            assertThat("LENGTH(x)>5 over ordinal BytesRef must take encoding-aware gather", path, is(LastEvalPath.ENCODING_AWARE));
        });
    }

    /** Constant keyword input to {@code LENGTH(x) > 5} evaluates once and returns a constant boolean vector. */
    public void testLengthGreaterThanConstantConstantInputTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Layout layout = layout(s);
        Expression expr = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, s), new Literal(Source.EMPTY, 5, DataType.INTEGER));
        int positionCount = 6;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("hello"), positionCount);
        evalExpr(expr, layout, input, positionCount, ElementKind.BOOLEAN, "LENGTH(x)>5 constant", (fused, unfused, path) -> {
            assertThat("LENGTH(x)>5 over constant BytesRef must take encoding-aware", path, is(LastEvalPath.ENCODING_AWARE));
            assertThat("constant LENGTH comparison should return a constant boolean vector", fused.asVector(), notNullValue());
            assertThat(fused.asVector().isConstant(), is(true));
        });
    }

    private void evalToLower(Block input, int positionCount, String ctx, EncodingAssertions assertions) {
        FieldAttribute s = field("s", DataType.KEYWORD);
        evalExpr(new ToLower(Source.EMPTY, s, CFG), layout(s), input, positionCount, ElementKind.BYTES_REF, ctx, assertions);
    }

    private void evalToUpper(Block input, int positionCount, String ctx, EncodingAssertions assertions) {
        FieldAttribute s = field("s", DataType.KEYWORD);
        evalExpr(new ToUpper(Source.EMPTY, s, CFG), layout(s), input, positionCount, ElementKind.BYTES_REF, ctx, assertions);
    }

    private void evalExpr(
        Expression expr,
        Layout layout,
        Block input,
        int positionCount,
        ElementKind outputKind,
        String ctx,
        EncodingAssertions assertions
    ) {
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
            assertions.accept(fusedResult, referenceResult, FusedExpressionEvaluatorFactory.lastEvalPath(fused));
            assertResultsEqual(outputKind, fusedResult, referenceResult, positionCount, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    private static void assertEncodingNoWeaker(Block fused, Block unfused, String ctx) {
        if (FusionVerify.isOrdinalEncoded(unfused)) {
            assertThat(
                ctx + ": fused output encoding must not be weaker than unfused ordinal",
                FusionVerify.isOrdinalEncoded(fused),
                is(true)
            );
        }
        if (FusionVerify.isConstantEncoded(unfused)) {
            assertThat(
                ctx + ": fused output encoding must not be weaker than unfused constant",
                FusionVerify.isConstantEncoded(fused),
                is(true)
            );
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

    @FunctionalInterface
    private interface EncodingAssertions {
        void accept(Block fused, Block unfused, LastEvalPath path);
    }
}
