/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.benchmark.compute.operator.FusionStrategy.assertSameOutput;
import static org.elasticsearch.benchmark.compute.operator.FusionStrategy.driverContext;
import static org.elasticsearch.benchmark.compute.operator.FusionStrategy.isFused;

/**
 * Isolated per-row throughput of each {@link FusionStrategy} on each {@link FusionStrategy.PocShape}
 * over dense 8192-row pages. Run the JIT regimes with JVM flags: default (C2), {@code -XX:TieredStopAtLevel=1} (C1),
 * {@code -Xint} (interpreter). {@code pollute=true} first runs every other shape under every strategy so shared
 * classes (unfused evaluators, block accessors, the MH lambda forms) see mixed receivers, as on a busy node.
 * Setup asserts the strategy's output equals the unfused output.
 */
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(3)
public class FusionStrategyBenchmark {

    static {
        BenchmarkLogging.configure();
    }

    private static final int SIZE = 8192;
    private static final int TARGET_POSITIONS = 1 << 20;
    private static final int ITERATIONS = TARGET_POSITIONS / SIZE;

    @Param
    public FusionStrategy.PocShape shape;

    @Param
    public FusionStrategy strategy;

    /**
     * Profile-pollution ablation, so we know WHICH pollution drives the unfused slowdown: {@code TYPES} = other receiver
     * types and block shapes with no overflow, {@code OVERFLOW} = overflowing rows through the shared kernels (trap
     * history and hot catch edges in their profiles), {@code ALL} = both.
     */
    public enum Pollution {
        NONE,
        TYPES,
        OVERFLOW,
        ALL
    }

    @Param({ "NONE" })
    public Pollution pollute;

    private ExpressionEvaluator.Factory factory;
    private ExpressionEvaluator evaluator;
    private Page page;

    @Setup(Level.Trial)
    public void setup() {
        FusionBenchmark.assertVerifyDisabled();
        if (pollute == Pollution.TYPES || pollute == Pollution.ALL) {
            polluteTypes();
        }
        if (pollute == Pollution.OVERFLOW || pollute == Pollution.ALL) {
            polluteOverflow();
        }
        page = shape.page(SIZE);
        FusionStrategy.UNFUSED.prepare();
        Block expected;
        try (ExpressionEvaluator reference = FusionStrategy.UNFUSED.factory(shape).get(driverContext)) {
            expected = reference.eval(page);
        }
        strategy.prepare();
        factory = strategy.factory(shape);
        if (strategy == FusionStrategy.SPLICED && isFused(factory) == false) {
            throw new AssertionError(shape + " did not fuse: " + factory.getClass().getName());
        }
        evaluator = factory.get(driverContext);
        try (Block actual = evaluator.eval(page)) {
            assertSameOutput(shape + "/" + strategy, actual, expected);
        }
        expected.close();
    }

    /** Every other (shape, strategy) pair over dense data, plus non-array vectors and null/MV blocks; no overflow. */
    private void polluteTypes() {
        for (FusionStrategy s : FusionStrategy.values()) {
            if (s == FusionStrategy.HANDWRITTEN_RAW) {
                // keep the pollution recipe identical to the runs made before this strategy existed
                continue;
            }
            s.prepare();
            for (FusionStrategy.PocShape other : FusionStrategy.PocShape.values()) {
                if (other == shape && s == strategy) {
                    continue;
                }
                Page p = other.page(SIZE);
                try (ExpressionEvaluator e = s.factory(other).get(driverContext)) {
                    for (int i = 0; i < 300; i++) {
                        e.eval(p).close();
                    }
                }
            }
        }
        polluteExpr(FusionBenchmark.Expr.NULLS_ADD_MUL_LONG);
        polluteExpr(FusionBenchmark.Expr.MV_ADD_MUL_LONG);
    }

    /** Overflowing rows through {@code (a+b)*c} so shared kernels see trap history. */
    private void polluteOverflow() {
        for (int every : new int[] { 10, 1000 }) {
            Page p = FusionStrategy.overflowAddMul(SIZE, every);
            for (FusionStrategy s : new FusionStrategy[] { FusionStrategy.UNFUSED, FusionStrategy.SPLICED }) {
                s.prepare();
                try (ExpressionEvaluator e = s.factory(FusionStrategy.PocShape.ADD_MUL_LONG).get(driverContext)) {
                    for (int i = 0; i < 300; i++) {
                        e.eval(p).close();
                    }
                }
            }
        }
    }

    private static void polluteExpr(FusionBenchmark.Expr expr) {
        Page p = expr.page(FusionStrategy.blockFactory, SIZE);
        FusionBenchmark.Built built = expr.build();
        FusionStrategy.UNFUSED.prepare();
        try (ExpressionEvaluator e = EvalMapper.toEvaluator(FoldContext.small(), built.expr(), built.layout()).get(driverContext)) {
            for (int i = 0; i < 300; i++) {
                e.eval(p).close();
            }
        }
        FusionStrategy.SPLICED.prepare();
        try (ExpressionEvaluator e = EvalMapper.toEvaluator(FoldContext.small(), built.expr(), built.layout()).get(driverContext)) {
            for (int i = 0; i < 300; i++) {
                e.eval(p).close();
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        evaluator.close();
    }

    @Benchmark
    @OperationsPerInvocation(TARGET_POSITIONS)
    public void run(Blackhole bh) {
        for (int i = 0; i < ITERATIONS; i++) {
            Block b = evaluator.eval(page);
            bh.consume(b);
            b.close();
        }
    }

    /** Plan-time cost per query with a warm cache (SPLICED: class cache hit; others: build). */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public ExpressionEvaluator.Factory planCached() {
        return strategy.factory(shape);
    }

    /**
     * Plan-time cost per query with a cold class cache. Each invocation drops the process-wide fused-class cache
     * then builds the factory (SPLICED: a stitch miss; others: the same build as {@link #planCached}).
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public ExpressionEvaluator.Factory planMiss() {
        FusionSettings.resetClassCacheForBenchmarks();
        return strategy.factory(shape);
    }

    /** {@code get()} repetition count for {@link #planCachedThenGet}. */
    @State(Scope.Thread)
    public static class GetCount {
        @Param({ "1", "4", "16" })
        public int n;
    }

    /**
     * Cached-factory {@code get()} cost: the factory is built once in {@link #setup} (warm class cache for SPLICED),
     * then this cell calls {@code factory.get} {@link GetCount#n} times.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void planCachedThenGet(GetCount gs, Blackhole bh) {
        for (int i = 0; i < gs.n; i++) {
            ExpressionEvaluator e = factory.get(driverContext);
            bh.consume(e);
            e.close();
        }
    }
}
