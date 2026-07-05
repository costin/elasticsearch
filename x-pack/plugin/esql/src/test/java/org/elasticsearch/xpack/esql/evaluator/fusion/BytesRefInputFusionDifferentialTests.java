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
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.function.Supplier;

import static org.hamcrest.Matchers.is;

/**
 * Differential fused-vs-unfused regressions for the <b>BYTES_REF input seam</b>: string functions that CONSUME a
 * {@code keyword}/{@code text} ({@code BytesRef}) column and RETURN a primitive/boolean can now fuse with the existing
 * numeric/comparison kernels. This slice wires {@link Length} ({@code BytesRef -> int}) as the first such kernel; a
 * {@code BytesRef} leaf is read on the <b>block path only</b> (via {@code getBytesRef} into a reusable spare), so every
 * tree here must fuse with {@link VectorStrategy#NONE} (no vector fast path) and still match the unfused chain
 * byte-for-byte over dense / null / multi-value {@code BytesRef} inputs — including empty strings, non-ASCII/UTF-8
 * strings, and empty / large pages.
 *
 * <p>The trees mix a {@code BytesRef}-input string kernel with numeric/comparison kernels exactly as the Stage-7 goal
 * requires: {@code LENGTH(x) > const}, {@code LENGTH(x) + LENGTH(y)}, and {@code LENGTH(x) > LENGTH(y)}. Per-node typing
 * already supports mixed-element trees — the {@code BytesRef} leaf feeds the string kernel, whose {@code int} result
 * feeds the numeric/comparison kernel.
 */
public class BytesRefInputFusionDifferentialTests extends FusionDifferentialTestCase {

    /** A spread of ASCII, empty, and multi-byte UTF-8 strings so codePointCount != byte-length is genuinely exercised. */
    private static final String[] SAMPLE_STRINGS = {
        "",
        "a",
        "hello",
        "elasticsearch",
        "café",          // 4 code points, 5 UTF-8 bytes (é = 2 bytes)
        "naïve",         // combining/accented
        "日本語",         // 3 code points, 9 UTF-8 bytes
        "emoji-\uD83D\uDE00", // surrogate pair (grinning face)
        "Ω≈ç√∫",         // assorted multi-byte
        "  spaced  " };

    private BytesRef randomSampleBytesRef() {
        return new BytesRef(randomFrom(SAMPLE_STRINGS));
    }

    /** {@code LENGTH(x) > 5} fuses (block-only) and matches the unfused chain over dense/null/multivalue keyword inputs. */
    public void testLengthGreaterThanConstantFuses() {
        FieldAttribute x = field("x", DataType.KEYWORD);
        Layout layout = layout(x);
        Expression expr = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, x), new Literal(Source.EMPTY, 5, DataType.INTEGER));

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, "LENGTH(x) > 5");
        assertDoesNotFuse(unfused, "LENGTH(x) > 5");
        assertThat(
            "a BytesRef-input tree must be block-only (no vector fast path)",
            fusedFactory.vectorStrategy(),
            is(VectorStrategy.NONE)
        );

        sweepColumns(fused, unfused, 1, ElementKind.BOOLEAN, "LENGTH(x)>5");
    }

    /** {@code LENGTH(x) + LENGTH(y)} fuses (block-only) with two BytesRef leaves feeding one arithmetic kernel. */
    public void testLengthPlusLengthFuses() {
        FieldAttribute x = field("x", DataType.KEYWORD);
        FieldAttribute y = field("y", DataType.TEXT);
        Layout layout = layout(x, y);
        Expression expr = new Add(Source.EMPTY, new Length(Source.EMPTY, x), new Length(Source.EMPTY, y), EsqlTestUtils.TEST_CFG);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, "LENGTH(x) + LENGTH(y)");
        assertDoesNotFuse(unfused, "LENGTH(x) + LENGTH(y)");
        assertThat("a BytesRef-input tree must be block-only", fusedFactory.vectorStrategy(), is(VectorStrategy.NONE));

        sweepColumns(fused, unfused, 2, ElementKind.INT, "LENGTH(x)+LENGTH(y)");
    }

    /** {@code LENGTH(x) > LENGTH(y)} fuses (block-only) with two BytesRef leaves feeding a comparison kernel. */
    public void testLengthGreaterThanLengthFuses() {
        FieldAttribute x = field("x", DataType.KEYWORD);
        FieldAttribute y = field("y", DataType.KEYWORD);
        Layout layout = layout(x, y);
        Expression expr = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, x), new Length(Source.EMPTY, y));

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        FusedExpressionEvaluatorFactory fusedFactory = assertFuses(fused, "LENGTH(x) > LENGTH(y)");
        assertDoesNotFuse(unfused, "LENGTH(x) > LENGTH(y)");
        assertThat("a BytesRef-input tree must be block-only", fusedFactory.vectorStrategy(), is(VectorStrategy.NONE));

        sweepColumns(fused, unfused, 2, ElementKind.BOOLEAN, "LENGTH(x)>LENGTH(y)");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sweeps: dense / nulls / multivalue / constant keyword columns across empty / single / large pages
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Drives {@code columns} keyword/text columns through the empty / single / large page shapes with a random mix of
     * dense / constant / nullable / multi-value {@code BytesRef} encodings each iteration, asserting fused-vs-unfused
     * value/null parity and the ratified warning-subset invariant on every one.
     */
    private void sweepColumns(
        ExpressionEvaluator.Factory fused,
        ExpressionEvaluator.Factory unfused,
        int columns,
        ElementKind outputKind,
        String ctx
    ) {
        for (int i = 0; i < 40; i++) {
            int positions = positionCountFor(i);
            Block[] inputs = new Block[columns];
            StringBuilder shapes = new StringBuilder();
            for (int c = 0; c < columns; c++) {
                BytesRefStructure structure = randomFrom(BytesRefStructure.values());
                inputs[c] = buildBytesRefColumn(positions, structure);
                shapes.append(c == 0 ? "" : ",").append(structure);
            }
            runDifferential(fused, unfused, outputKind, inputs, positions, ctx + " [" + shapes + ",n=" + positions + "]");
        }
    }

    /** How one keyword/text column's {@code BytesRefBlock} is physically encoded for a sweep iteration. */
    private enum BytesRefStructure {
        DENSE,
        CONSTANT,
        NULLABLE,
        MULTIVALUE
    }

    private Block buildBytesRefColumn(int positions, BytesRefStructure structure) {
        Supplier<BytesRef> values = this::randomSampleBytesRef;
        switch (structure) {
            case DENSE -> {
                try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(positions)) {
                    for (int p = 0; p < positions; p++) {
                        builder.appendBytesRef(values.get());
                    }
                    return builder.build();
                }
            }
            case CONSTANT -> {
                if (positions == 0) {
                    // A constant vector needs at least the shape; an empty page still yields an empty block.
                    return blockFactory.newBytesRefBlockBuilder(0).build();
                }
                return blockFactory.newConstantBytesRefBlockWith(values.get(), positions);
            }
            case NULLABLE -> {
                try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(positions)) {
                    for (int p = 0; p < positions; p++) {
                        if (randomInt(3) == 0) {
                            builder.appendNull();
                        } else {
                            builder.appendBytesRef(values.get());
                        }
                    }
                    return builder.build();
                }
            }
            case MULTIVALUE -> {
                try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(positions)) {
                    for (int p = 0; p < positions; p++) {
                        if (randomInt(3) == 0) {
                            builder.beginPositionEntry();
                            int count = randomIntBetween(2, 3);
                            for (int v = 0; v < count; v++) {
                                builder.appendBytesRef(values.get());
                            }
                            builder.endPositionEntry();
                        } else {
                            builder.appendBytesRef(values.get());
                        }
                    }
                    return builder.build();
                }
            }
            default -> throw new AssertionError(structure);
        }
    }
}
