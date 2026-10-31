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
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionPlanner;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
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
 * {@link SlowPathFusionBenchmark}. Nested {@code TO_LOWER} cases are expected unfused today (until a later task);
 * {@code split} fuses today and {@link #setup} asserts the stitched class contains {@code compute()}.
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
     * How many Add/Mul/Sub steps the {@link Case#split} chain uses. Must push {@code fused}/{@code fusedRaw} over the
     * default 325-bytecode inlining budget so the stitcher lifts the body into {@code compute()}.
     */
    private static final int SPLIT_CHAIN_DEPTH = 24;

    public enum Case {
        /**
         * A long arithmetic chain whose fused method exceeds the 325-bytecode budget and is lifted into
         * {@code compute()}. Fuses today.
         */
        split {
            @Override
            Built build() {
                FieldAttribute a = SlowPathFusionBenchmark.field("a", DataType.LONG);
                Configuration cfg = SlowPathFusionBenchmark.configuration();
                Expression e = a;
                for (int k = 0; k < SPLIT_CHAIN_DEPTH; k++) {
                    e = switch (k % 3) {
                        case 0 -> new Add(Source.EMPTY, e, a, cfg);
                        case 1 -> new Mul(Source.EMPTY, e, new Literal(Source.EMPTY, 1L, DataType.LONG));
                        default -> new Sub(Source.EMPTY, e, new Literal(Source.EMPTY, 0L, DataType.LONG), cfg);
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
         * {@code TO_LOWER(k) == "x"} over an {@code OrdinalBytesRefVector} with ~100 distinct values. Expected
         * unfused until a later task (D3); measured fused-flag-on and -off anyway.
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
        },
        /**
         * {@code LENGTH(TO_LOWER(k))} over the same ordinal input. Expected unfused until D3.
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
        },
        /**
         * {@code TO_LOWER(k) == "x"} over a plain (non-ordinal) {@code BytesRefArrayVector} with the same distinct
         * values. Expected unfused until D3.
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
        };

        abstract Built build();

        abstract Page page(int size);

        /** {@code true} only where fusion is expected today; nested TO_LOWER cases stay unfused until D3. */
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
            if (FusionPlanner.fusedClassHasMethod(fused, "compute") == false) {
                throw new AssertionError(shape + " fused but the stitched class has no compute() split method: " + fused);
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
