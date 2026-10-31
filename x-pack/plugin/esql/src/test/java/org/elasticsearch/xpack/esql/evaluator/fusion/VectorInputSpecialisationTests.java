/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.common.util.IntArray;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBigArrayVector;
import org.elasticsearch.compute.data.IntBigArrayVector;
import org.elasticsearch.compute.data.LongBigArrayVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Task 4: dense arithmetic inputs that are not exact {@code *ArrayVector}s ({@code Constant*Vector},
 * {@code *BigArrayVector}) must take the INTERFACE-VECTOR path rather than falling the whole tree to the block path.
 * Pins per-page RAW vs INTERFACE vs BLOCK so an always-block (or always-raw) regression is caught even when values
 * still match the unfused chain.
 */
public class VectorInputSpecialisationTests extends FusionDifferentialTestCase {

    /**
     * {@code Abs(Abs(x))} over double has no overflow-checked kernel, so it compiles {@link VectorStrategy#PLAIN_VECTOR}.
     * Exact {@code DoubleArrayVector} pages take ALL-RAW; {@code ConstantDoubleVector} / {@code DoubleBigArrayVector}
     * take ALL-INTERFACE; a null-carrying page takes BLOCK.
     */
    public void testPlainVectorDoubleAbsPicksRawVsInterfaceVsBlock() {
        FieldAttribute x = field("x", DataType.DOUBLE);
        Layout layout = layout(x);
        Expression expr = new Abs(Source.EMPTY, new Abs(Source.EMPTY, x));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "Abs(Abs(x)) plain-vector");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.PLAIN_VECTOR));

        ExpressionEvaluator eval = fused.get(driverContext);
        try {
            int n = 8;
            assertLastEvalPath(eval, LastEvalPath.RAW, new Block[] { doubleArrayVectorColumn(n, 0) }, "exact DoubleArrayVector -> RAW");
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] { constantDoubleVectorColumn(n, 1) },
                "ConstantDoubleVector -> INTERFACE"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.INTERFACE,
                new Block[] { doubleBigArrayVectorColumn(n, 2) },
                "DoubleBigArrayVector -> INTERFACE"
            );
            assertLastEvalPath(
                eval,
                LastEvalPath.BLOCK,
                new Block[] { doubleColumn(new Double[] { 1.0, null, 3.0, 4.0 }) },
                "null-carrying page -> BLOCK"
            );
        } finally {
            Releasables.closeExpectNoException(eval);
        }
    }

    /**
     * {@code (a+b)*c} over long compiles {@link VectorStrategy#CHECKED_VECTOR}. Every combination of
     * {exact array, constant, big-array} matches the unfused chain value-by-value, and non-all-array combos take
     * ALL-INTERFACE (today they fall to BLOCK).
     */
    public void testCheckedVectorLongAddMulCombinationsMatchUnfused() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "(a+b)*c long");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        DenseKind[] kinds = DenseKind.values();
        for (DenseKind ka : kinds) {
            for (DenseKind kb : kinds) {
                for (DenseKind kc : kinds) {
                    int n = 8;
                    Block[] inputs = { longColumn(ka, n, 0), longColumn(kb, n, 1), longColumn(kc, n, 2) };
                    LastEvalPath expected = (ka == DenseKind.ARRAY && kb == DenseKind.ARRAY && kc == DenseKind.ARRAY)
                        ? LastEvalPath.RAW
                        : LastEvalPath.INTERFACE;
                    runDifferentialAssertingPath(
                        fused,
                        unfused,
                        ElementKind.LONG,
                        inputs,
                        n,
                        expected,
                        "(a+b)*c long " + ka + "/" + kb + "/" + kc
                    );
                }
            }
        }
    }

    /**
     * Overflow rows of {@code (a+b)*c} over mixed constant / big-array / array long inputs are null with EXACT
     * unfused warning parity (count + header text). fusedRawInterface abandons on the first overflow and the builder
     * interface re-run supplies the warnings — the same contract as the raw CHECKED path, for inputs that are not
     * exact {@code *ArrayVector}s.
     */
    public void testCheckedVectorOverflowWithConstantAndBigArrayMatchesUnfusedWarningsExactly() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "overflow mixed vectors");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        int n = 4;
        // Mixed array / constant / big-array. b is ConstantLongVector(1) so p0 (MAX+1) and p2 (3*MAX) still overflow;
        // p1 and p3 stay clean.
        long[] av = { Long.MAX_VALUE, 3, 2, 10 };
        long[] cv = { 7, 5, Long.MAX_VALUE, 2 };

        ExpressionEvaluator fusedEval = fused.get(driverContext);
        ExpressionEvaluator reference = unfused.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(n, longArrayVector(av, n), constantLongVector(1L, n), longBigArrayVector(cv, n));
            drainWarnings();
            fusedResult = fusedEval.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> unfusedWarnings = drainWarnings();

            assertThat(
                "mixed constant/bigarray/array overflow page must take ALL-INTERFACE",
                FusedExpressionEvaluatorFactory.lastEvalPath(fusedEval),
                is(LastEvalPath.INTERFACE)
            );
            assertResultsEqual(ElementKind.LONG, fusedResult, referenceResult, n, "overflow mixed vectors");
            assertThat("p0 overflow -> null", fusedResult.isNull(0), is(true));
            assertThat("p2 overflow -> null", fusedResult.isNull(2), is(true));
            assertThat("fused overflow warnings must equal unfused (count and header text)", fusedWarnings, equalTo(unfusedWarnings));
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fusedEval, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    /**
     * {@code (a+b)*c} over double (overflow-checked via {@code NumericUtils.asFiniteNumber}) with constant and
     * big-array inputs matches unfused and takes ALL-INTERFACE.
     */
    public void testCheckedVectorDoubleAddMulConstantAndBigArrayMatchUnfused() {
        FieldAttribute a = field("a", DataType.DOUBLE);
        FieldAttribute b = field("b", DataType.DOUBLE);
        FieldAttribute c = field("c", DataType.DOUBLE);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "(a+b)*c double");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        int n = 8;
        runDifferentialAssertingPath(
            fused,
            unfused,
            ElementKind.DOUBLE,
            new Block[] { constantDoubleVectorColumn(n, 0), doubleBigArrayVectorColumn(n, 1), doubleArrayVectorColumn(n, 2) },
            n,
            LastEvalPath.INTERFACE,
            "(a+b)*c double constant/bigarray/array"
        );
        runDifferentialAssertingPath(
            fused,
            unfused,
            ElementKind.DOUBLE,
            new Block[] { constantDoubleVectorColumn(n, 3), constantDoubleVectorColumn(n, 4), constantDoubleVectorColumn(n, 5) },
            n,
            LastEvalPath.INTERFACE,
            "(a+b)*c double all-constant"
        );
    }

    /**
     * {@code (a+b)*c} over int with {@code ConstantIntVector} / {@code IntBigArrayVector} matches unfused and takes
     * ALL-INTERFACE.
     */
    public void testCheckedVectorIntAddMulConstantAndBigArrayMatchUnfused() {
        FieldAttribute a = field("a", DataType.INTEGER);
        FieldAttribute b = field("b", DataType.INTEGER);
        FieldAttribute c = field("c", DataType.INTEGER);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "(a+b)*c int");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        int n = 8;
        runDifferentialAssertingPath(
            fused,
            unfused,
            ElementKind.INT,
            new Block[] { constantIntVectorColumn(n, 0), intBigArrayVectorColumn(n, 1), intArrayVectorColumn(n, 2) },
            n,
            LastEvalPath.INTERFACE,
            "(a+b)*c int constant/bigarray/array"
        );
    }

    /**
     * PLAIN_VECTOR {@code Abs(Abs(x))} with {@code ConstantDoubleVector} / {@code DoubleBigArrayVector} matches the
     * unfused chain (not merely the path pick).
     */
    public void testPlainVectorDoubleAbsConstantAndBigArrayMatchUnfused() {
        FieldAttribute x = field("x", DataType.DOUBLE);
        Layout layout = layout(x);
        Expression expr = new Abs(Source.EMPTY, new Abs(Source.EMPTY, x));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "Abs(Abs(x)) values");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.PLAIN_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        int n = 16;
        runDifferentialAssertingPath(
            fused,
            unfused,
            ElementKind.DOUBLE,
            new Block[] { constantDoubleVectorColumn(n, 7) },
            n,
            LastEvalPath.INTERFACE,
            "Abs(Abs(x)) ConstantDoubleVector"
        );
        runDifferentialAssertingPath(
            fused,
            unfused,
            ElementKind.DOUBLE,
            new Block[] { doubleBigArrayVectorColumn(n, 8) },
            n,
            LastEvalPath.INTERFACE,
            "Abs(Abs(x)) DoubleBigArrayVector"
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private enum DenseKind {
        ARRAY,
        CONSTANT,
        BIG_ARRAY
    }

    private void runDifferentialAssertingPath(
        FusedExpressionEvaluatorFactory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        ElementKind outputKind,
        Block[] inputs,
        int positionCount,
        LastEvalPath expectedPath,
        String ctx
    ) {
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, inputs);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            assertThat(ctx + " lastEvalPath", FusedExpressionEvaluatorFactory.lastEvalPath(fused), is(expectedPath));
            assertResultsEqual(outputKind, fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    private static void assertLastEvalPath(ExpressionEvaluator eval, LastEvalPath expected, Block[] inputs, String ctx) {
        int n = inputs[0].getPositionCount();
        Page page = new Page(n, inputs);
        Block result = null;
        try {
            result = eval.eval(page);
            assertThat(ctx, FusedExpressionEvaluatorFactory.lastEvalPath(eval), is(expected));
        } finally {
            Releasables.closeExpectNoException(result);
            page.releaseBlocks();
        }
    }

    private Block longColumn(DenseKind kind, int n, int seed) {
        return switch (kind) {
            case ARRAY -> longArrayVectorColumn(n, seed);
            case CONSTANT -> constantLongVectorColumn(n, seed);
            case BIG_ARRAY -> longBigArrayVectorColumn(n, seed);
        };
    }

    private Block longArrayVectorColumn(int n, int seed) {
        long[] v = new long[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7L + seed * 3L) % 11) - 5;
        }
        return longArrayVector(v, n);
    }

    private Block constantLongVectorColumn(int n, int seed) {
        return constantLongVector(((seed * 3L) % 11) - 5, n);
    }

    private Block longBigArrayVectorColumn(int n, int seed) {
        long[] v = new long[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7L + seed * 3L) % 11) - 5;
        }
        return longBigArrayVector(v, n);
    }

    private Block longArrayVector(long[] v, int n) {
        return blockFactory.newLongArrayVector(v, n).asBlock();
    }

    private Block constantLongVector(long value, int n) {
        return blockFactory.newConstantLongVector(value, n).asBlock();
    }

    private Block longBigArrayVector(long[] v, int n) {
        LongArray array = bigArrays.newLongArray(n);
        for (int p = 0; p < n; p++) {
            array.set(p, v[p]);
        }
        return new LongBigArrayVector(array, n, blockFactory).asBlock();
    }

    private Block doubleArrayVectorColumn(int n, int seed) {
        double[] v = new double[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7 + seed * 3) % 11) - 5;
        }
        return blockFactory.newDoubleArrayVector(v, n).asBlock();
    }

    private Block constantDoubleVectorColumn(int n, int seed) {
        return blockFactory.newConstantDoubleVector(((seed * 3) % 11) - 5, n).asBlock();
    }

    private Block doubleBigArrayVectorColumn(int n, int seed) {
        DoubleArray array = bigArrays.newDoubleArray(n);
        for (int p = 0; p < n; p++) {
            array.set(p, ((p * 7 + seed * 3) % 11) - 5);
        }
        return new DoubleBigArrayVector(array, n, blockFactory).asBlock();
    }

    private Block intArrayVectorColumn(int n, int seed) {
        int[] v = new int[n];
        for (int p = 0; p < n; p++) {
            v[p] = ((p * 7 + seed * 3) % 11) - 5;
        }
        return blockFactory.newIntArrayVector(v, n).asBlock();
    }

    private Block constantIntVectorColumn(int n, int seed) {
        return blockFactory.newConstantIntVector(((seed * 3) % 11) - 5, n).asBlock();
    }

    private Block intBigArrayVectorColumn(int n, int seed) {
        IntArray array = bigArrays.newIntArray(n);
        for (int p = 0; p < n; p++) {
            array.set(p, ((p * 7 + seed * 3) % 11) - 5);
        }
        return new IntBigArrayVector(array, n, blockFactory).asBlock();
    }

    private Block doubleColumn(Double[] values) {
        try (var builder = blockFactory.newDoubleBlockBuilder(values.length)) {
            for (Double v : values) {
                if (v == null) {
                    builder.appendNull();
                } else {
                    builder.appendDouble(v);
                }
            }
            return builder.build();
        }
    }
}
