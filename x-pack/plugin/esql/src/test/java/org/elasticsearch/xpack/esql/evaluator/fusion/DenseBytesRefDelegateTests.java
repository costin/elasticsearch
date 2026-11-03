/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
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
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * A tree that is block-only only because it reads a {@code BYTES_REF} column ({@code LENGTH(k) > 5}) serves a page of
 * plain dense vectors from the unfused chain, whose vector paths beat the fused block loop there. Ordinal and constant
 * pages keep the fused encoding-aware path, null and multi-value pages the fused block path, and a tree that produces a
 * {@code BytesRef} (whose unfused chain would materialise the intermediate column) stays fused on every page. Each case
 * checks values and warnings against the unfused chain.
 */
public class DenseBytesRefDelegateTests extends FusionDifferentialTestCase {

    public void testPlainDenseVectorPageIsServedUnfused() {
        assertPath(lengthGt(), Structure.DENSE_VECTOR, LastEvalPath.UNFUSED_DENSE_BYTES_REF);
    }

    public void testOrdinalPageStaysFused() {
        assertPath(lengthGt(), Structure.ORDINAL, LastEvalPath.ENCODING_AWARE);
    }

    public void testConstantPageStaysFused() {
        assertPath(lengthGt(), Structure.CONSTANT, LastEvalPath.ENCODING_AWARE);
    }

    public void testNullablePageStaysFused() {
        assertPath(lengthGt(), Structure.NULLABLE_BLOCK, LastEvalPath.BLOCK);
    }

    public void testMultiValuePageStaysFused() {
        assertPath(lengthGt(), Structure.MULTIVALUE_BLOCK, LastEvalPath.BLOCK);
    }

    /** Every input must be a plain dense vector: a dense keyword column next to a nullable int column stays fused. */
    public void testEveryInputMustBePlainDense() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        FieldAttribute i = field("i", DataType.INTEGER);
        Expression expr = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, k), i);
        Layout layout = layout(k, i);
        int positions = pathTestPositions();
        assertPath(expr, layout, page(positions, Structure.DENSE_VECTOR, Structure.DENSE_VECTOR), LastEvalPath.UNFUSED_DENSE_BYTES_REF);
        assertPath(expr, layout, page(positions, Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK), LastEvalPath.BLOCK);
        assertPath(expr, layout, page(positions, Structure.ORDINAL, Structure.DENSE_VECTOR), LastEvalPath.BLOCK);
    }

    /** {@code LENGTH(TO_LOWER(k))} produces a {@code BytesRef} per row, so a dense page stays on the fused block path. */
    public void testTreeProducingBytesRefStaysFusedOnDenseVectors() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, k, EsqlTestUtils.TEST_CFG));
        assertPath(expr, layout(k), page(pathTestPositions(), Structure.DENSE_VECTOR), LastEvalPath.BLOCK);
    }

    /** Fused and unfused agree on values and warnings over random pages of every keyword encoding. */
    public void testDifferentialAcrossEncodings() {
        Structure[] structures = {
            Structure.DENSE_VECTOR,
            Structure.ORDINAL,
            Structure.CONSTANT,
            Structure.NULLABLE_BLOCK,
            Structure.MULTIVALUE_BLOCK };
        FieldAttribute k = field("k", DataType.KEYWORD);
        Layout layout = layout(k);
        Expression expr = lengthGt(k);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "LENGTH(k) > 5");
        assertThat("block-only because of the BytesRef input", fused.vectorStrategy(), is(VectorStrategy.NONE));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        for (int iteration = 0; iteration < 20; iteration++) {
            Structure structure = randomFrom(structures);
            int positions = between(2, 300);
            String ctx = "LENGTH(k) > 5 over " + structure + " x" + positions;
            DifferentialWarnings warnings = runDifferentialWarnings(
                fused,
                unfused,
                ElementKind.BOOLEAN,
                new Block[] { buildBytesRefColumn(positions, structure) },
                positions,
                ctx
            );
            assertThat(ctx + " warnings", warnings.fused(), is(warnings.reference()));
        }
    }

    private static Expression lengthGt() {
        return lengthGt(field("k", DataType.KEYWORD));
    }

    private static Expression lengthGt(FieldAttribute k) {
        return new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, k), new Literal(Source.EMPTY, 5, DataType.INTEGER));
    }

    /** Positions per path-test page: enough that a nullable or multi-value column has at least one such row. */
    private static int pathTestPositions() {
        return between(64, 200);
    }

    private void assertPath(Expression expr, Structure structure, LastEvalPath expected) {
        FieldAttribute k = (FieldAttribute) ((Length) ((GreaterThan) expr).left()).field();
        assertPath(expr, layout(k), page(pathTestPositions(), structure), expected);
    }

    /** Page of a keyword column in {@code keyword} plus, when given, an int column in {@code ints}. */
    private Page page(int positions, Structure keyword, Structure... ints) {
        Block[] blocks = new Block[1 + ints.length];
        blocks[0] = buildBytesRefColumn(positions, keyword);
        for (int i = 0; i < ints.length; i++) {
            blocks[1 + i] = buildIntColumn(positions, ints[i], () -> randomIntBetween(0, 10));
        }
        return new Page(positions, blocks);
    }

    /**
     * Evaluates {@code page} with the fused and unfused factories, asserts the fused evaluator took {@code expected}, and
     * that values and warnings match. The page is released.
     */
    private void assertPath(Expression expr, Layout layout, Page page, LastEvalPath expected) {
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fusedFactory(expr, layout), expr.toString());
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        var fusedContext = freshDriverContext();
        var unfusedContext = freshDriverContext();
        ExpressionEvaluator fused = fusedFactory.get(fusedContext);
        ExpressionEvaluator unfused = unfusedFactory.get(unfusedContext);
        Block fusedResult = null;
        Block unfusedResult = null;
        try {
            fusedResult = fused.eval(page);
            LastEvalPath path = FusedExpressionEvaluatorFactory.lastEvalPath(fused);
            unfusedResult = unfused.eval(page);
            String ctx = expr + " over " + describe(page);
            assertThat(ctx + " path", path, is(expected));
            if (expected != LastEvalPath.UNFUSED_DENSE_BYTES_REF) {
                assertThat(ctx + " stays fused", path, not(LastEvalPath.UNFUSED_FALLBACK));
            }
            ElementKind kind = expr instanceof GreaterThan ? ElementKind.BOOLEAN : ElementKind.INT;
            assertResultsEqual(kind, fusedResult, unfusedResult, page.getPositionCount(), ctx);
            List<String> fusedWarnings = warningsOf(fusedContext);
            List<String> unfusedWarnings = warningsOf(unfusedContext);
            assertThat(ctx + " warnings", fusedWarnings, is(unfusedWarnings));
        } finally {
            Releasables.closeExpectNoException(fusedResult, unfusedResult, fused, unfused);
            page.releaseBlocks();
        }
    }

    private static String describe(Page page) {
        StringBuilder out = new StringBuilder();
        for (int b = 0; b < page.getBlockCount(); b++) {
            out.append(b == 0 ? "" : ", ").append(page.getBlock(b).getClass().getSimpleName());
        }
        return out.toString();
    }
}
