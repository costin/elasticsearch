/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.session.Configuration;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Follow-up fusion benchmark cases that must not be added to {@link SlowPathFusionBenchmark.Case}: that enum's
 * {@code pollute} recipe iterates {@code values()}, so a new constant would change pollution of every existing cell.
 *
 * <p>Helpers ({@code driverContext}, {@code longs}, {@code dictEntry}, {@code assertSameOutput}, ...) are reused from
 * {@link SlowPathFusionBenchmark}. Nested {@code TO_LOWER} cases fuse via D3/F3. {@link Case#DEEP_ARITH} is a deep
 * arithmetic chain that fuses INLINE under the 8000-bytecode {@code DontCompileHugeMethods} huge-method guard.
 * {@link Case#DEEP_LOGICAL} is a deep balanced AND/OR nest of long comparisons that also fuses INLINE (comfortably
 * under the same guard). Both are correctness-checked in {@link #setup} via {@code assertSameOutput}.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class FusionFollowupBenchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    private static final FoldContext FOLD_CONTEXT = FoldContext.small();

    private static final int TARGET_POSITIONS = 1 << 20;

    /** ~100 distinct ordinal/plain dictionary entries across the 8192-row pages. */
    private static final int DICT = 100;

    /**
     * How many Add/Mul/Sub steps the {@link Case#DEEP_ARITH} chain uses. Depth 24 is a meaningful fused body that
     * stays well under the 8000-bytecode huge-method guard, so the stitcher emits it INLINE.
     */
    private static final int DEEP_ARITH_CHAIN_DEPTH = 24;

    /**
     * Nesting depth of the {@link Case#DEEP_LOGICAL} balanced AND/OR tree. Depth 6 is 64 comparison leaves — a
     * meaningful fused body that stays comfortably under the 8000-bytecode huge-method guard so the cell fuses INLINE.
     */
    private static final int DEEP_LOGICAL_DEPTH = 6;

    public enum Case {
        /**
         * A long arithmetic chain that fuses INLINE under the 8000-bytecode {@code DontCompileHugeMethods}
         * huge-method guard (no longer split into {@code compute()}). Fuses today.
         */
        DEEP_ARITH {
            @Override
            Built build() {
                FieldAttribute a = SlowPathFusionBenchmark.field("a", DataType.LONG);
                Configuration cfg = SlowPathFusionBenchmark.configuration();
                Expression e = a;
                for (int k = 0; k < DEEP_ARITH_CHAIN_DEPTH; k++) {
                    e = switch (k % 3) {
                        case 0 -> new Add(Source.EMPTY, e, a, cfg);
                        case 1 -> new Mul(Source.EMPTY, e, new Literal(Source.EMPTY, 1L, DataType.LONG));
                        case 2 -> new Sub(Source.EMPTY, e, new Literal(Source.EMPTY, 0L, DataType.LONG), cfg);
                        default -> throw new AssertionError("unreachable k % 3: " + (k % 3));
                    };
                }
                return new Built(e, SlowPathFusionBenchmark.layout(a));
            }

            @Override
            Page page(int size) {
                return new Page(SlowPathFusionBenchmark.longs(size, i -> 1 + (i % 3)));
            }

            @Override
            boolean fusesToday() {
                return true;
            }
        },
        /**
         * A deep balanced AND/OR nest of long comparisons over two LONG fields {@code a,b}
         * ({@code ((a>1 AND b<2) AND (a>3 AND b<4)) ...}). Fuses today, comfortably under the 8000-bytecode
         * huge-method guard. Correctness-only: {@link #setup} asserts fused==unfused via {@code assertSameOutput}.
         */
        DEEP_LOGICAL {
            @Override
            Built build() {
                FieldAttribute a = SlowPathFusionBenchmark.field("a", DataType.LONG);
                FieldAttribute b = SlowPathFusionBenchmark.field("b", DataType.LONG);
                return new Built(deepLogical(a, b, DEEP_LOGICAL_DEPTH, new int[] { 0 }), SlowPathFusionBenchmark.layout(a, b));
            }

            @Override
            Page page(int size) {
                return new Page(SlowPathFusionBenchmark.longs(size, i -> 1 + (i % 50)), SlowPathFusionBenchmark.longs(size, i -> i % 49));
            }

            @Override
            boolean fusesToday() {
                return true;
            }
        },
        /**
         * {@code TO_LOWER(k) == "x"} over an {@code OrdinalBytesRefVector} with ~100 distinct values. Fuses via D3
         * nested mapping + F3 keyword Equals.
         */
        EQ_TO_LOWER_ORDINAL {
            @Override
            Built build() {
                return eqToLower();
            }

            @Override
            Page page(int size) {
                return ordinalPage(size);
            }

            @Override
            boolean fusesToday() {
                return true;
            }
        },
        /**
         * {@code LENGTH(TO_LOWER(k))} over the same ordinal input. Fuses via D3 nested mapping.
         */
        LENGTH_TO_LOWER_ORDINAL {
            @Override
            Built build() {
                FieldAttribute k = SlowPathFusionBenchmark.field("k", DataType.KEYWORD);
                Expression expr = new Length(Source.EMPTY, new ToLower(Source.EMPTY, k, SlowPathFusionBenchmark.configuration()));
                return new Built(expr, SlowPathFusionBenchmark.layout(k));
            }

            @Override
            Page page(int size) {
                return ordinalPage(size);
            }

            @Override
            boolean fusesToday() {
                return true;
            }
        },
        /**
         * {@code TO_LOWER(k) == "x"} over a plain (non-ordinal) {@code BytesRefArrayVector} with the same distinct
         * values. Fuses via D3 nested mapping + F3 keyword Equals.
         */
        EQ_TO_LOWER_PLAIN {
            @Override
            Built build() {
                return eqToLower();
            }

            @Override
            Page page(int size) {
                try (var builder = SlowPathFusionBenchmark.blockFactory.newBytesRefVectorBuilder(size)) {
                    for (int i = 0; i < size; i++) {
                        builder.appendBytesRef(SlowPathFusionBenchmark.dictEntry(i % DICT));
                    }
                    return new Page(builder.build().asBlock());
                }
            }

            @Override
            boolean fusesToday() {
                return true;
            }
        };

        abstract Built build();

        abstract Page page(int size);

        /** {@code true} where fusion is expected today ({@code DEEP_ARITH}, {@code DEEP_LOGICAL}, and the D3/F3 cells). */
        boolean fusesToday() {
            return false;
        }
    }

    record Built(Expression expr, Layout layout) {}

    private static Built eqToLower() {
        FieldAttribute k = SlowPathFusionBenchmark.field("k", DataType.KEYWORD);
        Expression expr = new Equals(
            Source.EMPTY,
            new ToLower(Source.EMPTY, k, SlowPathFusionBenchmark.configuration()),
            new Literal(Source.EMPTY, new BytesRef("x"), DataType.KEYWORD)
        );
        return new Built(expr, SlowPathFusionBenchmark.layout(k));
    }

    private static Page ordinalPage(int size) {
        BytesRefVector dict;
        try (var builder = SlowPathFusionBenchmark.blockFactory.newBytesRefVectorBuilder(DICT)) {
            for (int i = 0; i < DICT; i++) {
                builder.appendBytesRef(SlowPathFusionBenchmark.dictEntry(i));
            }
            dict = builder.build();
        }
        int[] ords = new int[size];
        for (int i = 0; i < size; i++) {
            ords[i] = i % DICT;
        }
        IntVector ordinals = SlowPathFusionBenchmark.blockFactory.newIntArrayVector(ords, size);
        return new Page(new OrdinalBytesRefVector(ordinals, dict).asBlock());
    }

    @Param
    public Case shape;

    @Param({ "8192" })
    public int size;

    @Param({ "true", "false" })
    public boolean fusion;

    @Param({ "false" })
    public boolean pollute;

    private ExpressionEvaluator evaluator;
    private Page page;
    private int iterations;

    @Setup(Level.Trial)
    public void setup() {
        FusionBenchmarkGuard.assertVerifyDisabled();
        if (pollute) {
            pollute(shape);
        }
        ExpressionEvaluator.Factory unfused = factory(shape, false);
        ExpressionEvaluator.Factory fused = factory(shape, true);
        if (SlowPathFusionBenchmark.isFused(unfused)) {
            throw new AssertionError(shape + " fused with fusion=false: " + unfused);
        }
        if (shape.fusesToday()) {
            if (SlowPathFusionBenchmark.isFused(fused) == false) {
                throw new AssertionError(shape + " did not fuse with fusion=true: " + fused.getClass().getName());
            }
        }
        this.page = shape.page(size);
        SlowPathFusionBenchmark.assertSameOutput(
            shape,
            fused.get(SlowPathFusionBenchmark.driverContext).eval(page),
            unfused.get(SlowPathFusionBenchmark.driverContext).eval(page)
        );
        this.evaluator = (fusion ? fused : unfused).get(SlowPathFusionBenchmark.driverContext);
        this.iterations = TARGET_POSITIONS / size;
    }

    @Benchmark
    @OperationsPerInvocation(TARGET_POSITIONS)
    public void run(Blackhole bh) {
        for (int i = 0; i < iterations; i++) {
            bh.consume(evaluator.eval(page));
        }
    }

    static void pollute(Case skip) {
        for (Case c : Case.values()) {
            if (c == skip) {
                continue;
            }
            Page p = c.page(8192);
            for (boolean f : new boolean[] { false, true }) {
                ExpressionEvaluator e = factory(c, f).get(SlowPathFusionBenchmark.driverContext);
                for (int i = 0; i < 300; i++) {
                    e.eval(p);
                }
            }
        }
    }

    /**
     * Balanced AND/OR nest of long comparisons over {@code a} and {@code b}. Depth 0 is a leaf
     * {@code a > k} or {@code b < k}; even depths combine with {@code AND}, odd with {@code OR}.
     */
    private static Expression deepLogical(FieldAttribute a, FieldAttribute b, int depth, int[] leaf) {
        if (depth <= 0) {
            int k = leaf[0]++;
            long value = k + 1L;
            if ((k & 1) == 0) {
                return new GreaterThan(Source.EMPTY, a, new Literal(Source.EMPTY, value, DataType.LONG));
            }
            return new LessThan(Source.EMPTY, b, new Literal(Source.EMPTY, value, DataType.LONG));
        }
        Expression left = deepLogical(a, b, depth - 1, leaf);
        Expression right = deepLogical(a, b, depth - 1, leaf);
        return (depth & 1) == 0 ? new And(Source.EMPTY, left, right) : new Or(Source.EMPTY, left, right);
    }

    static ExpressionEvaluator.Factory factory(Case c, boolean fusion) {
        Built built = c.build();
        FusionSettings.setEnabledForBenchmarks(fusion);
        try {
            return EvalMapper.toEvaluator(FOLD_CONTEXT, built.expr(), built.layout());
        } finally {
            FusionSettings.setEnabledForBenchmarks(true);
        }
    }
}
