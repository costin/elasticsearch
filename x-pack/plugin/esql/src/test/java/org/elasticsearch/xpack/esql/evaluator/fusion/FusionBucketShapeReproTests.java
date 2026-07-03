/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.expression.function.grouping.Bucket;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Regression for the {@code BUCKET(field, span)} fusion value bug the csv-spec corpus surfaced (fusion on: wrong
 * values; fusion off: correct). Root cause: {@code BUCKET.toEvaluator} DELEGATES — it lowers to
 * {@code Mul(Floor(Div(field, span)), span)} and returns that {@code Mul}'s ({@code FusionAware}) evaluator. The
 * planner read the {@code Mul} kernel descriptor but recursed into {@code Bucket}'s own children {@code [field, span]}
 * (NOT the {@code Mul}'s operands), building {@code Mul(cast(field), span)} — dropping the {@code Floor(Div(...))} and
 * computing {@code field * span}. The fix refuses to fuse whenever the {@code @Fusable} kernel is not declared on the
 * expression's own class, so delegating functions fall back to the (correct) unfused chain.
 */
public class FusionBucketShapeReproTests extends FusionDifferentialTestCase {

    /** The bug's home: a delegating BUCKET must NOT fuse, and must compute correct bucket values via the unfused chain. */
    public void testDelegatingBucketDoesNotFuseAndIsCorrect() {
        FieldAttribute salary = field("salary", DataType.INTEGER);
        Layout layout = layout(salary);
        double span = 5000.0;
        Expression bucket = new Bucket(
            Source.EMPTY,
            salary,
            new Literal(Source.EMPTY, span, DataType.DOUBLE),
            null,
            null,
            EsqlTestUtils.TEST_CFG
        );

        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), bucket, layout);
        assertThat(
            "a delegating function (BUCKET) must NOT fuse — its FusionAware Mul evaluator's children are not Bucket's",
            factory.getClass().getSimpleName(),
            not(is("FusedExpressionEvaluatorFactory"))
        );

        int positionCount = 64;
        int[] salaries = new int[positionCount];
        for (int p = 0; p < positionCount; p++) {
            salaries[p] = 20000 + p * 977;
        }
        ExpressionEvaluator evaluator = factory.get(driverContext);
        Page page = null;
        DoubleBlock result = null;
        try {
            page = new Page(blockFactory.newIntArrayVector(salaries, positionCount).asBlock());
            result = (DoubleBlock) evaluator.eval(page);
            for (int p = 0; p < positionCount; p++) {
                double expected = Math.floor(salaries[p] / span) * span;
                double actual = result.getDouble(result.getFirstValueIndex(p));
                assertThat("bucket@" + p, Double.doubleToLongBits(actual), is(Double.doubleToLongBits(expected)));
            }
        } finally {
            Releasables.closeExpectNoException(result, evaluator);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bonus coverage the investigation produced: multi-DISTINCT-constant trees (with and without an int->double cast)
    // DO fuse and stay correct — proving the fix narrowly targets delegation, not multi-constant fusion.
    // ---------------------------------------------------------------------------------------------------------------

    public void testTwoDistinctDoubleConstantsMatchUnfused() {
        FieldAttribute x = field("x", DataType.DOUBLE);
        Expression expr = new Add(Source.EMPTY, new Mul(Source.EMPTY, x, lit(3.0)), lit(7.0), EsqlTestUtils.TEST_CFG);
        assertFusesAndMatches(expr, x, "(x*3.0)+7.0");
    }

    public void testCastIntPlusTwoDistinctDoubleConstantsMatchUnfused() {
        FieldAttribute x = field("x", DataType.INTEGER);
        Expression expr = new Add(Source.EMPTY, new Mul(Source.EMPTY, x, lit(3.0)), lit(7.0), EsqlTestUtils.TEST_CFG);
        assertFusesAndMatches(expr, x, "(intX*3.0)+7.0 cast");
    }

    public void testThreeDistinctDoubleConstantsMatchUnfused() {
        FieldAttribute x = field("x", DataType.DOUBLE);
        Expression inner = new Add(Source.EMPTY, new Mul(Source.EMPTY, x, lit(3.0)), lit(7.0), EsqlTestUtils.TEST_CFG);
        Expression expr = new Mul(Source.EMPTY, inner, lit(2000.0));
        assertFusesAndMatches(expr, x, "((x*3.0)+7.0)*2000.0");
    }

    private static Literal lit(double v) {
        return new Literal(Source.EMPTY, v, DataType.DOUBLE);
    }

    private void assertFusesAndMatches(Expression expr, FieldAttribute x, String ctx) {
        Layout layout = layout(x);
        ExpressionEvaluator.Factory fusedFactory = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfusedFactory = unfusedFactory(expr, layout);
        assertThat(ctx + " must fuse", fusedFactory.getClass().getSimpleName(), is("FusedExpressionEvaluatorFactory"));
        assertDoesNotFuse(unfusedFactory, ctx);

        int positionCount = 64;
        Block input = switch (x.dataType()) {
            case INTEGER -> {
                int[] v = new int[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    v[p] = 20000 + p * 977;
                }
                yield blockFactory.newIntArrayVector(v, positionCount).asBlock();
            }
            case DOUBLE -> {
                double[] v = new double[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    v[p] = 20000 + p * 977;
                }
                yield blockFactory.newDoubleArrayVector(v, positionCount).asBlock();
            }
            default -> throw new AssertionError("unexpected " + x.dataType());
        };

        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedOut = null;
        Block refOut = null;
        try {
            page = new Page(positionCount, input);
            fusedOut = fused.eval(page);
            refOut = reference.eval(page);
            boolean isLongOut = expr.dataType() == DataType.LONG;
            for (int p = 0; p < positionCount; p++) {
                assertThat(ctx + " null@" + p, fusedOut.isNull(p), is(refOut.isNull(p)));
                if (fusedOut.isNull(p)) {
                    continue;
                }
                if (isLongOut) {
                    long f = ((LongBlock) fusedOut).getLong(fusedOut.getFirstValueIndex(p));
                    long r = ((LongBlock) refOut).getLong(refOut.getFirstValueIndex(p));
                    assertThat(ctx + " value@" + p, f, is(r));
                } else {
                    double f = ((DoubleBlock) fusedOut).getDouble(fusedOut.getFirstValueIndex(p));
                    double r = ((DoubleBlock) refOut).getDouble(refOut.getFirstValueIndex(p));
                    assertThat(ctx + " value@" + p, Double.doubleToLongBits(f), is(Double.doubleToLongBits(r)));
                }
            }
        } finally {
            Releasables.closeExpectNoException(fusedOut, refOut, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }
}
