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
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.OrdinalBytesRefBlock;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToUpper;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.util.List;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * D1: fused trees with a single BYTES_REF column and no warn kernel are encoding-aware. Ordinal and constant pages
 * are evaluated by the fused handle over the dictionary / one position. A lone TO_LOWER/TO_UPPER does not fuse
 * (D4); encoding-preservation coverage lives on the chain tests ({@code LENGTH(TO_LOWER)}, {@code TO_LOWER==literal}).
 */
public class MappingEncodingAwareTests extends FusionDifferentialTestCase {

    private static final Configuration CFG = EsqlTestUtils.TEST_CFG;

    /**
     * A lone {@code TO_LOWER} does not fuse (D4). Values over an ordinal keyword page still match the unfused chain.
     * Encoding-aware ordinal gather lives on {@link #testLengthToLowerOrdinalTakesEncodingAwarePath}.
     */
    public void testLoneToLowerOrdinalDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        String[] dictionary = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße" };
        int[] ordinals = { 0, 1, 2, 0, 1, 3, 2, 4, 0, 1, 3, 4 };
        Block input = ordinalKeyword(dictionary, ordinals);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: input must be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(true));
        evalLoneMappingDoesNotFuse(new ToLower(Source.EMPTY, s, CFG), layout(s), input, ordinals.length, "toLower(ordinal keyword)");
    }

    /**
     * A lone {@code TO_LOWER} does not fuse (D4). Values over a constant keyword page still match the unfused chain.
     */
    public void testLoneToLowerConstantDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        int positionCount = 8;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        assertThat("test setup: input must be a constant vector", input.asVector(), notNullValue());
        assertThat("test setup: input must be constant", input.asVector().isConstant(), is(true));
        evalLoneMappingDoesNotFuse(new ToLower(Source.EMPTY, s, CFG), layout(s), input, positionCount, "toLower(constant keyword)");
    }

    /**
     * A lone {@code TO_LOWER} does not fuse (D4). Values over a plain BytesRef vector still match the unfused chain.
     */
    public void testLoneToLowerPlainVectorMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        String[] values = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße δΔ", "", "café" };
        Block input = keyword(values);
        BytesRefBlock inputBytes = (BytesRefBlock) input;
        assertThat("test setup: plain input must not be ordinal-encoded", hasOrdinalEncoding(inputBytes), is(false));
        assertThat("test setup: plain input must be a vector", inputBytes.asVector(), notNullValue());
        assertThat("test setup: plain input must not be constant", inputBytes.asVector().isConstant(), is(false));
        evalLoneMappingDoesNotFuse(new ToLower(Source.EMPTY, s, CFG), layout(s), input, values.length, "toLower(plain keyword)");
    }

    /**
     * A lone {@code TO_LOWER} does not fuse (D4). Multi-value / null structure still matches the unfused convert
     * evaluator (mapped values, not warn+null).
     */
    public void testLoneToLowerMultiValueAndNullsMatchUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
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
        evalLoneMappingDoesNotFuse(new ToLower(Source.EMPTY, s, CFG), layout(s), input, positionCount, "toLower(mv/null keyword)");
    }

    /** A lone {@code TO_UPPER} does not fuse (D4). Values over an ordinal keyword page still match unfused. */
    public void testLoneToUpperOrdinalDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        String[] dictionary = { "Hello", "WORLD", "MiXeD" };
        int[] ordinals = { 0, 1, 2, 0, 1, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalLoneMappingDoesNotFuse(new ToUpper(Source.EMPTY, s, CFG), layout(s), input, ordinals.length, "toUpper(ordinal keyword)");
    }

    /** A lone {@code TO_UPPER} does not fuse (D4). Values over a constant keyword page still match unfused. */
    public void testLoneToUpperConstantDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        int positionCount = 5;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        evalLoneMappingDoesNotFuse(new ToUpper(Source.EMPTY, s, CFG), layout(s), input, positionCount, "toUpper(constant keyword)");
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

    // ---------------------------------------------------------------------------------------------------------------
    // D3: nested mapping under a single-value parent (LENGTH(TO_LOWER(k)))
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code LENGTH(TO_LOWER(k))} over an ordinal keyword page gathers an int vector via the encoding-aware path. */
    public void testLengthToLowerOrdinalTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG));
        String[] dictionary = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße" };
        int[] ordinals = { 0, 1, 2, 0, 1, 3, 2, 4, 0, 1, 3, 4 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(expr, layout(s), input, ordinals.length, ElementKind.INT, "LENGTH(TO_LOWER(k)) ordinal", (fused, unfused, path) -> {
            assertThat("LENGTH(TO_LOWER) ordinal must take encoding-aware gather", path, is(LastEvalPath.ENCODING_AWARE));
            assertThat("LENGTH(TO_LOWER) ordinal must return a vector, not a row-by-row block", fused.asVector(), notNullValue());
        });
    }

    /** {@code LENGTH(TO_LOWER(k))} over a plain (non-ordinal) BytesRef vector matches unfused on the fused block path. */
    public void testLengthToLowerPlainVectorMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG));
        String[] values = { "Hello", "WORLD", "MiXeD", "already lower", "Grüße δΔ", "", "café" };
        Block input = keyword(values);
        evalExpr(expr, layout(s), input, values.length, ElementKind.INT, "LENGTH(TO_LOWER(k)) plain", (fused, unfused, path) -> {
            assertThat("plain LENGTH(TO_LOWER) stays on the fused block path", path, is(LastEvalPath.BLOCK));
        });
    }

    /** {@code LENGTH(TO_LOWER(k))} over a constant keyword page evaluates once and expands a constant int vector. */
    public void testLengthToLowerConstantTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG));
        int positionCount = 8;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        evalExpr(expr, layout(s), input, positionCount, ElementKind.INT, "LENGTH(TO_LOWER(k)) constant", (fused, unfused, path) -> {
            assertThat("constant LENGTH(TO_LOWER) must take encoding-aware", path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(fused.asVector(), notNullValue());
            assertThat(fused.asVector().isConstant(), is(true));
        });
    }

    /**
     * Nested mapping on a multi-value / null block must fall back to the unfused chain so MV semantics stay exact
     * (the single-value block path would warn+null at the parent).
     */
    public void testLengthToLowerMultiValueFallsBackToUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG));
        int positionCount = 4;
        Block input;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("Hello"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("WORLD"));
            b.appendBytesRef(new BytesRef("MiXeD"));
            b.endPositionEntry();
            b.appendNull();
            b.appendBytesRef(new BytesRef("café"));
            input = b.build();
        }
        evalExpr(expr, layout(s), input, positionCount, ElementKind.INT, "LENGTH(TO_LOWER(k)) mv", (fused, unfused, path) -> {
            assertThat("nested mapping on a non-vector page must fall back to unfused", path, is(LastEvalPath.UNFUSED_FALLBACK));
        });
    }

    /** A mapping kernel under another mapping ({@code TO_LOWER(TO_UPPER(k))}) does not fuse. */
    public void testToLowerOfToUpperDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new ToLower(Source.EMPTY, new ToUpper(Source.EMPTY, s, CFG), CFG);
        assertDoesNotFuse(fusedFactory(expr, layout(s)), "TO_LOWER(TO_UPPER(k))");
    }

    /** A mapping kernel under a LOGICAL root does not fuse (keyword comparison is out of AND/OR scope). */
    public void testToLowerEqualsUnderAndDoesNotFuse() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        FieldAttribute n = field("n", DataType.LONG);
        Expression eq = new Equals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("x"));
        Expression gt = new GreaterThan(Source.EMPTY, n, new Literal(Source.EMPTY, 0L, DataType.LONG));
        Expression and = new And(Source.EMPTY, eq, gt);
        assertDoesNotFuse(fusedFactory(and, layout(s, n)), "TO_LOWER(k)==\"x\" AND n>0");
    }

    /**
     * Two BytesRef columns with a nested mapping ({@code k1 == TO_LOWER(k2)}) fuse now that each column has its own
     * BytesRef spare. A dense page chosen so a shared-spare overwrite would flip the boolean ({@code k1==TO_LOWER(k2)}
     * is true, {@code k2==TO_LOWER(k2)} is false) must match the unfused chain. An MV page takes the nested-mapping
     * non-vector fallback (unfused MV semantics) and still matches.
     */
    public void testEqualsColumnToLowerOtherColumnFuses() {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, k1, new ToLower(Source.EMPTY, k2, CFG));
        Layout layout = layout(k1, k2);
        assertFuses(fusedFactory(expr, layout), "k1==TO_LOWER(k2)");
        Block plainK1 = keyword("world", "hello");
        Block plainK2 = keyword("WORLD", "HELLO");
        evalTwoColumnsWithoutRequiringFusion(expr, layout, plainK1, plainK2, 2, "k1==TO_LOWER(k2) plain");
        Block mvK1;
        Block mvK2;
        try (
            BytesRefBlock.Builder b1 = blockFactory.newBytesRefBlockBuilder(2);
            BytesRefBlock.Builder b2 = blockFactory.newBytesRefBlockBuilder(2)
        ) {
            b1.appendBytesRef(new BytesRef("world"));
            b1.beginPositionEntry();
            b1.appendBytesRef(new BytesRef("a"));
            b1.appendBytesRef(new BytesRef("b"));
            b1.endPositionEntry();
            b2.appendBytesRef(new BytesRef("WORLD"));
            b2.beginPositionEntry();
            b2.appendBytesRef(new BytesRef("A"));
            b2.appendBytesRef(new BytesRef("B"));
            b2.endPositionEntry();
            mvK1 = b1.build();
            mvK2 = b2.build();
        }
        evalTwoColumnsWithoutRequiringFusion(expr, layout, mvK1, mvK2, 2, "k1==TO_LOWER(k2) mv");
    }

    /** Same two-column nested-mapping fusion for {@code k1 != TO_LOWER(k2)}. */
    public void testNotEqualsColumnToLowerOtherColumnFuses() {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Expression expr = new NotEquals(Source.EMPTY, k1, new ToLower(Source.EMPTY, k2, CFG));
        Layout layout = layout(k1, k2);
        assertFuses(fusedFactory(expr, layout), "k1!=TO_LOWER(k2)");
        Block plainK1 = keyword("world", "hello");
        Block plainK2 = keyword("WORLD", "HELLO");
        evalTwoColumnsWithoutRequiringFusion(expr, layout, plainK1, plainK2, 2, "k1!=TO_LOWER(k2) plain");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F3: keyword Equals / NotEquals, including TO_LOWER(k) == "x" (rides on D3) and depth-1 column == literal
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code TO_LOWER(k) == "x"} over an ordinal keyword page gathers a boolean vector via encoding-aware. */
    public void testToLowerEqualsLiteralOrdinalTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("hello"));
        String[] dictionary = { "Hello", "WORLD", "HELLO", "hello" };
        int[] ordinals = { 0, 1, 2, 3, 0, 2, 3, 1 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(expr, layout(s), input, ordinals.length, ElementKind.BOOLEAN, "TO_LOWER(k)==\"hello\" ordinal", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.ENCODING_AWARE));
            assertThat("ordinal TO_LOWER==literal must gather a boolean vector", fused.asVector(), notNullValue());
        });
    }

    public void testToLowerEqualsLiteralPlainMatchesUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("hello"));
        String[] values = { "Hello", "WORLD", "HELLO", "hello", "HeLLo" };
        Block input = keyword(values);
        evalExpr(expr, layout(s), input, values.length, ElementKind.BOOLEAN, "TO_LOWER(k)==\"hello\" plain", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.BLOCK));
        });
    }

    public void testToLowerEqualsLiteralConstantTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("hello"));
        int positionCount = 6;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("HeLLo"), positionCount);
        evalExpr(expr, layout(s), input, positionCount, ElementKind.BOOLEAN, "TO_LOWER(k)==\"hello\" constant", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(fused.asVector(), notNullValue());
            assertThat(fused.asVector().isConstant(), is(true));
        });
    }

    public void testToLowerEqualsLiteralMultiValueFallsBackToUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("hello"));
        int positionCount = 3;
        Block input;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("Hello"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("WORLD"));
            b.appendBytesRef(new BytesRef("hello"));
            b.endPositionEntry();
            b.appendNull();
            input = b.build();
        }
        evalExpr(expr, layout(s), input, positionCount, ElementKind.BOOLEAN, "TO_LOWER(k)==\"hello\" mv", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.UNFUSED_FALLBACK));
        });
    }

    public void testToLowerNotEqualsLiteralOrdinalTakesEncodingAwarePath() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression expr = new NotEquals(Source.EMPTY, new ToLower(Source.EMPTY, s, CFG), keywordLit("hello"));
        String[] dictionary = { "Hello", "WORLD", "hello" };
        int[] ordinals = { 0, 1, 2, 0, 1, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(expr, layout(s), input, ordinals.length, ElementKind.BOOLEAN, "TO_LOWER(k)!=\"hello\" ordinal", (fused, unfused, path) -> {
            assertThat(path, is(LastEvalPath.ENCODING_AWARE));
            assertThat(fused.asVector(), notNullValue());
        });
    }

    /**
     * Depth-1 {@code instance == "instance-0"}: encoding-aware dictionary gather, no nested mapping.
     */
    public void testKeywordEqualsLiteralOrdinalTakesEncodingAwarePath() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, instance, keywordLit("instance-0"));
        String[] dictionary = { "instance-0", "instance-1", "other" };
        int[] ordinals = { 0, 1, 2, 0, 1, 0, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(
            expr,
            layout(instance),
            input,
            ordinals.length,
            ElementKind.BOOLEAN,
            "instance==\"instance-0\" ordinal",
            (fused, unfused, path) -> {
                assertThat(path, is(LastEvalPath.ENCODING_AWARE));
                assertThat("depth-1 keyword equals ordinal must gather a boolean vector", fused.asVector(), notNullValue());
            }
        );
    }

    public void testKeywordEqualsLiteralPlainMatchesUnfused() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, instance, keywordLit("instance-0"));
        String[] values = { "instance-0", "instance-1", "other", "instance-0" };
        Block input = keyword(values);
        evalExpr(
            expr,
            layout(instance),
            input,
            values.length,
            ElementKind.BOOLEAN,
            "instance==\"instance-0\" plain",
            (fused, unfused, path) -> {
                assertThat(path, is(LastEvalPath.BLOCK));
            }
        );
    }

    public void testKeywordEqualsLiteralMultiValueMatchesUnfusedOnBlockPath() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, instance, keywordLit("instance-0"));
        int positionCount = 3;
        Block input;
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(positionCount)) {
            b.appendBytesRef(new BytesRef("instance-0"));
            b.beginPositionEntry();
            b.appendBytesRef(new BytesRef("instance-0"));
            b.appendBytesRef(new BytesRef("instance-1"));
            b.endPositionEntry();
            b.appendNull();
            input = b.build();
        }
        evalExpr(
            expr,
            layout(instance),
            input,
            positionCount,
            ElementKind.BOOLEAN,
            "instance==\"instance-0\" mv",
            (fused, unfused, path) -> {
                // No nested mapping: the fused single-value block path matches unfused Equals (warn+null on MV).
                assertThat(path, is(LastEvalPath.BLOCK));
            }
        );
    }

    /** Depth-1 {@code instance != "instance-0"}: encoding-aware dictionary gather, same exemption as {@code ==}. */
    public void testKeywordNotEqualsLiteralOrdinalTakesEncodingAwarePath() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new NotEquals(Source.EMPTY, instance, keywordLit("instance-0"));
        String[] dictionary = { "instance-0", "instance-1", "other" };
        int[] ordinals = { 0, 1, 2, 0, 1, 0, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(
            expr,
            layout(instance),
            input,
            ordinals.length,
            ElementKind.BOOLEAN,
            "instance!=\"instance-0\" ordinal",
            (fused, unfused, path) -> {
                assertThat(path, is(LastEvalPath.ENCODING_AWARE));
                assertThat(fused.asVector(), notNullValue());
            }
        );
    }

    /** Constant keyword page for {@code instance == "instance-0"} evaluates once and expands a constant boolean. */
    public void testKeywordEqualsLiteralConstantTakesEncodingAwarePath() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, instance, keywordLit("instance-0"));
        int positionCount = 6;
        Block input = blockFactory.newConstantBytesRefBlockWith(new BytesRef("instance-0"), positionCount);
        evalExpr(
            expr,
            layout(instance),
            input,
            positionCount,
            ElementKind.BOOLEAN,
            "instance==\"instance-0\" constant",
            (fused, unfused, path) -> {
                assertThat(path, is(LastEvalPath.ENCODING_AWARE));
                assertThat(fused.asVector(), notNullValue());
                assertThat(fused.asVector().isConstant(), is(true));
            }
        );
    }

    /**
     * H5: {@code LENGTH(CONCAT(k, "x"))} must not be encoding-aware. CONCAT can throw on input content (over-length),
     * so evaluating an unreferenced dictionary entry would fail a query no row would have failed. The planner reads
     * that from the CONCAT descriptor ({@code dictionarySafe=false}), not from {@code overflowChecked}.
     */
    public void testLengthConcatIsNotEncodingAware() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new Concat(Source.EMPTY, k, List.of(keywordLit("x"))));
        ExpressionEvaluator.Factory factory = fusedFactory(expr, layout(k));
        if (factory instanceof FusedExpressionEvaluatorFactory fused) {
            assertThat("LENGTH(CONCAT(k,\"x\")) must not take the encoding-aware path", fused.encodingAware(), is(false));
        }

        // Structural: a LENGTH(CONCAT(single BytesRef leaf)) tree must not be encoding-aware even when CONCAT
        // is not overflow-checked and declares no warnExceptions (the previous heuristic treated it as safe).
        FusionDescriptor length = new FusionDescriptor(
            Length.class,
            "process",
            "(Lorg/apache/lucene/util/BytesRef;)I",
            false,
            true,
            "",
            false,
            false,
            false,
            true
        );
        FusionNode tree = new FusionNode.Kernel(
            length,
            List.of(new FusionNode.Kernel(FusionPlanner.CONCAT_DESCRIPTOR, List.of(new FusionNode.Input(0))))
        );
        assertThat(
            "LENGTH(CONCAT(k,...)) must not be encoding-aware",
            FusionPlanner.encodingAwareForTests(
                tree,
                new FusedExpressionEvaluatorFactory.ElementKind[] { FusedExpressionEvaluatorFactory.ElementKind.BYTES_REF }
            ),
            is(false)
        );
    }

    /**
     * H5: an ordinal page whose dictionary holds an entry that would make CONCAT throw (BytesRef past 1MB), but which
     * no row references, must evaluate without exception and match unfused. The encoding-aware dictionary path is
     * forbidden for CONCAT; the per-row path never sees the unreferenced entry.
     */
    public void testLengthConcatUnusedHugeDictionaryEntryDoesNotThrow() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new Concat(Source.EMPTY, k, List.of(keywordLit("x"))));
        Layout layout = layout(k);

        BytesRef small = new BytesRef("ok");
        BytesRef huge = new BytesRef(new byte[1024 * 1024 + 1]);
        int[] ordinals = { 0, 0, 0, 0 };
        Block input = ordinalKeyword(new BytesRef[] { small, huge }, ordinals);
        ExpressionEvaluator fused = null;
        ExpressionEvaluator reference = null;
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
            ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
            assertFuses(fusedFactory, "LENGTH(CONCAT(k,\"x\")) unused huge dict");
            assertDoesNotFuse(unfusedFactory, "LENGTH(CONCAT(k,\"x\")) unused huge dict");

            fused = fusedFactory.get(driverContext);
            reference = unfusedFactory.get(driverContext);
            page = new Page(ordinals.length, input);
            drainWarnings();
            fusedResult = fused.eval(page);
            LastEvalPath path = FusedExpressionEvaluatorFactory.lastEvalPath(fused);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();
            assertThat(
                "LENGTH(CONCAT(k,\"x\")) must not take the encoding-aware dictionary path",
                path,
                not(is(LastEvalPath.ENCODING_AWARE))
            );
            assertResultsEqual(ElementKind.INT, fusedResult, referenceResult, ordinals.length, "LENGTH(CONCAT(k,\"x\")) unused huge dict");
            assertWarningsSubset(fusedWarnings, referenceWarnings, "LENGTH(CONCAT(k,\"x\")) unused huge dict");
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            } else {
                Releasables.closeExpectNoException(input);
            }
        }
    }

    /**
     * H4: a sparse ordinal page (dictionary much larger than the row count) must not take the dictionary path.
     * {@link OrdinalBytesRefVector#filter} rebuilds a plain vector and is not the source of a sparse ordinal page;
     * {@code slice}/{@code keepMask} retain the full dictionary. This test builds 10 ordinals over a 10_000-entry
     * dictionary directly to model that layout.
     */
    public void testKeywordEqualsLiteralSparseDictionaryDoesNotTakeEncodingAwarePath() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, k, keywordLit("x"));
        int dictSize = 10_000;
        int rows = 10;
        String[] dictionary = new String[dictSize];
        dictionary[0] = "x";
        for (int i = 1; i < dictSize; i++) {
            dictionary[i] = "v" + i;
        }
        int[] ordinals = new int[rows];
        for (int i = 0; i < rows; i++) {
            ordinals[i] = i;
        }
        Block input = ordinalKeyword(dictionary, ordinals);
        BytesRefVector vector = ((BytesRefBlock) input).asVector();
        assertThat("test setup: sparse page must be a vector", vector, notNullValue());
        OrdinalBytesRefVector ordinalVector = vector.asOrdinals();
        assertThat("test setup: sparse page must stay ordinal-encoded", ordinalVector, notNullValue());
        assertThat("test setup: 10 rows / 10_000 dict is not dense", ordinalVector.isDense(), is(false));
        assertThat(ordinalVector.getDictionaryVector().getPositionCount(), is(dictSize));
        assertThat(ordinalVector.getPositionCount(), is(rows));

        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout(k));
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout(k));
        assertFuses(fusedFactory, "k==\"x\" sparse ordinal");
        assertDoesNotFuse(unfusedFactory, "k==\"x\" sparse ordinal");

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(rows, input);
            drainWarnings();
            fusedResult = fused.eval(page);
            LastEvalPath path = FusedExpressionEvaluatorFactory.lastEvalPath(fused);
            int dictPositions = FusedExpressionEvaluatorFactory.lastEncodingAwareDictPositions(fused);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();
            assertThat("sparse k==\"x\" must not evaluate the whole dictionary", dictPositions, lessThanOrEqualTo(rows));
            assertThat("sparse k==\"x\" must not take the encoding-aware dictionary path", path, not(is(LastEvalPath.ENCODING_AWARE)));
            assertThat("sparse k==\"x\" falls through to the fused block path", path, is(LastEvalPath.BLOCK));
            assertResultsEqual(ElementKind.BOOLEAN, fusedResult, referenceResult, rows, "k==\"x\" sparse ordinal");
            assertWarningsSubset(fusedWarnings, referenceWarnings, "k==\"x\" sparse ordinal");
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /** H4: an ordinal BLOCK with nulls under {@code k == "x"} matches unfused on the fused block path. */
    public void testKeywordEqualsLiteralOrdinalBlockWithNullsMatchesUnfused() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, k, keywordLit("x"));
        String[] dictionary = { "x", "y", "z" };
        Integer[] ordinals = { 0, null, 1, 0, null, 2, 1 };
        Block input = ordinalKeywordWithNulls(dictionary, ordinals);
        BytesRefBlock bytes = (BytesRefBlock) input;
        assertThat("test setup: null ordinals are not a dense vector", bytes.asVector(), nullValue());
        assertThat("test setup: null ordinals stay ordinal-encoded", bytes.asOrdinals(), notNullValue());
        evalExpr(
            expr,
            layout(k),
            input,
            ordinals.length,
            ElementKind.BOOLEAN,
            "k==\"x\" ordinal block with nulls",
            (fused, unfused, path) -> {
                assertThat("ordinal block with nulls stays on the fused block path", path, is(LastEvalPath.BLOCK));
            }
        );
    }

    /**
     * H4: a fused encoding-aware BytesRef-output shape over a DENSE ordinal page rewraps as
     * {@link OrdinalBytesRefVector}. Lone {@code TO_LOWER} does not fuse (D4); {@code TRIM(TO_LOWER(k))} is a nested
     * mapping under a unary string parent, fuses, and is encoding-aware-eligible (single BYTES_REF column, no warn).
     */
    public void testTrimToLowerDenseOrdinalPreservesOrdinalEncoding() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Trim(Source.EMPTY, new ToLower(Source.EMPTY, k, CFG));
        String[] dictionary = { " Hello ", "WORLD", "MiXeD", " already lower ", "Grüße" };
        int[] ordinals = { 0, 1, 2, 0, 3, 1, 2, 4, 0, 1, 3, 4, 2, 0, 1, 4, 3, 2, 1, 0 };
        Block input = ordinalKeyword(dictionary, ordinals);
        BytesRefVector vector = ((BytesRefBlock) input).asVector();
        assertThat("test setup: dense page must be a vector", vector, notNullValue());
        OrdinalBytesRefVector ordinalVector = vector.asOrdinals();
        assertThat("test setup: dense page must be ordinal-encoded", ordinalVector, notNullValue());
        assertThat("test setup: 20 rows / 5 dict is dense", ordinalVector.isDense(), is(true));
        evalExpr(
            expr,
            layout(k),
            input,
            ordinals.length,
            ElementKind.BYTES_REF,
            "TRIM(TO_LOWER(k)) dense ordinal",
            (fused, unfused, path) -> {
                assertThat("TRIM(TO_LOWER) dense ordinal must take encoding-aware rewrap", path, is(LastEvalPath.ENCODING_AWARE));
                assertThat(
                    "TRIM(TO_LOWER) dense ordinal must rewrap as OrdinalBytesRefVector",
                    fused.asVector(),
                    instanceOf(OrdinalBytesRefVector.class)
                );
            }
        );
    }

    /** Literal on the left ({@code "instance-0" == instance}) is the same depth-1 encoding-aware comparison. */
    public void testLiteralEqualsKeywordOrdinalTakesEncodingAwarePath() {
        FieldAttribute instance = field("instance", DataType.KEYWORD);
        Expression expr = new Equals(Source.EMPTY, keywordLit("instance-0"), instance);
        String[] dictionary = { "instance-0", "instance-1", "other" };
        int[] ordinals = { 0, 1, 2, 0, 1, 0, 2 };
        Block input = ordinalKeyword(dictionary, ordinals);
        evalExpr(
            expr,
            layout(instance),
            input,
            ordinals.length,
            ElementKind.BOOLEAN,
            "\"instance-0\"==instance ordinal",
            (fused, unfused, path) -> {
                assertThat(path, is(LastEvalPath.ENCODING_AWARE));
                assertThat(fused.asVector(), notNullValue());
            }
        );
    }

    /**
     * FilterEval does not have a mapping loop or nested-mapping MV fallback. {@code WHERE n>0 EVAL TO_LOWER(k)}
     * (root mapping projection) must stay on the unfused Filter→Eval chain.
     */
    public void testFilterEvalRootMappingProjectionDoesNotFuse() {
        FieldAttribute n = field("n", DataType.LONG);
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression pred = new GreaterThan(Source.EMPTY, n, new Literal(Source.EMPTY, 0L, DataType.LONG));
        Expression proj = new ToLower(Source.EMPTY, k, CFG);
        Layout layout = layout(n, k);
        FusionSettings.setEnabledForTests(true);
        FilterEvalEvaluator.Factory factory = FusionPlanner.maybeFuseFilterEval(pred, proj, layout, e -> unfusedFactory(e, layout));
        assertThat("WHERE n>0 EVAL TO_LOWER(k) must not fuse-filter-eval", factory, nullValue());
    }

    /**
     * A BytesRef literal whose {@link DataType} is not KEYWORD/TEXT must not be treated as a fusable keyword
     * constant. {@code LENGTH(CONCAT(k, IP-literal))} refuses rather than hitching a ride on the keyword path.
     */
    public void testIpTypedBytesRefLiteralDoesNotFuseAsKeywordConstant() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Literal ipLit = new Literal(Source.EMPTY, new BytesRef("x"), DataType.IP);
        Expression expr = new Length(Source.EMPTY, new Concat(Source.EMPTY, k, List.of(ipLit)));
        assertDoesNotFuse(fusedFactory(expr, layout(k)), "LENGTH(CONCAT(k, IP-literal)) must not treat IP as a keyword constant");
    }

    public void testVersionTypedBytesRefLiteralDoesNotFuseAsKeywordConstant() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Literal versionLit = new Literal(Source.EMPTY, new BytesRef("1.2.3"), DataType.VERSION);
        Expression expr = new Length(Source.EMPTY, new Concat(Source.EMPTY, k, List.of(versionLit)));
        assertDoesNotFuse(fusedFactory(expr, layout(k)), "LENGTH(CONCAT(k, VERSION-literal)) must not treat VERSION as a keyword constant");
    }

    private static Literal keywordLit(String value) {
        return new Literal(Source.EMPTY, new BytesRef(value), DataType.KEYWORD);
    }

    /**
     * Evaluates the ON-switch factory against the unfused chain without requiring fusion, then releases the page.
     * Used to catch a fused-but-wrong values bug (shared BytesRef spare) before the plan-time refusal lands.
     */
    private void evalTwoColumnsWithoutRequiringFusion(
        Expression expr,
        Layout layout,
        Block col0,
        Block col1,
        int positionCount,
        String ctx
    ) {
        ExpressionEvaluator.Factory on = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory off = unfusedFactory(expr, layout);
        ExpressionEvaluator fused = on.get(driverContext);
        ExpressionEvaluator reference = off.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, col0, col1);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();
            assertResultsEqual(ElementKind.BOOLEAN, fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * A lone mapping convert does not fuse (D4). Values and warnings still match the unfused convert evaluator.
     * Layout must use the same {@link FieldAttribute} instance as {@code expr}.
     */
    private void evalLoneMappingDoesNotFuse(Expression expr, Layout layout, Block input, int positionCount, String ctx) {
        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertDoesNotFuse(fused, ctx);
        runDifferentialWarnings(fused, unfused, ElementKind.BYTES_REF, new Block[] { input }, positionCount, ctx);
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
            drainWarnings();
            fusedResult = fused.eval(page);
            LastEvalPath path = FusedExpressionEvaluatorFactory.lastEvalPath(fused);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();
            assertions.accept(fusedResult, referenceResult, path);
            assertResultsEqual(outputKind, fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
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
        BytesRef[] entries = new BytesRef[dictionary.length];
        for (int i = 0; i < dictionary.length; i++) {
            entries[i] = new BytesRef(dictionary[i]);
        }
        return ordinalKeyword(entries, ordinals);
    }

    private Block ordinalKeyword(BytesRef[] dictionary, int[] ordinals) {
        BytesRefVector dict = null;
        IntVector ords = null;
        try (BytesRefVector.Builder dictBuilder = blockFactory.newBytesRefVectorBuilder(dictionary.length)) {
            for (BytesRef entry : dictionary) {
                dictBuilder.appendBytesRef(entry);
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

    private Block ordinalKeywordWithNulls(String[] dictionary, Integer[] ordinals) {
        BytesRefVector dict = null;
        IntBlock ords = null;
        try (
            BytesRefVector.Builder dictBuilder = blockFactory.newBytesRefVectorBuilder(dictionary.length);
            IntBlock.Builder ordBuilder = blockFactory.newIntBlockBuilder(ordinals.length)
        ) {
            for (String entry : dictionary) {
                dictBuilder.appendBytesRef(new BytesRef(entry));
            }
            dict = dictBuilder.build();
            for (Integer ordinal : ordinals) {
                if (ordinal == null) {
                    ordBuilder.appendNull();
                } else {
                    ordBuilder.appendInt(ordinal);
                }
            }
            ords = ordBuilder.build();
            Block block = new OrdinalBytesRefBlock(ords, dict);
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
