/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
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

import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.assertSameOutput;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.driverContext;

/**
 * B1 of the MethodHandle-chain PoC: isolated per-row throughput of each {@link FusionStrategy} on each {@link PocShape}
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
        Utils.configureBenchmarkLogging();
    }

    private static final int SIZE = 8192;
    private static final int TARGET_POSITIONS = 1 << 20;
    private static final int ITERATIONS = TARGET_POSITIONS / SIZE;

    @Param
    public PocShape shape;

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

    private ExpressionEvaluator evaluator;
    private Page page;

    @Setup(Level.Trial)
    public void setup() {
        if (pollute == Pollution.TYPES || pollute == Pollution.ALL) {
            polluteTypes();
        }
        if (pollute == Pollution.OVERFLOW || pollute == Pollution.ALL) {
            polluteCases(SlowPathFusionBenchmark.Case.OVERFLOW10_ADD_MUL_LONG, SlowPathFusionBenchmark.Case.OVERFLOW01_ADD_MUL_LONG);
        }
        page = shape.page(SIZE);
        FusionStrategy.UNFUSED.prepare();
        Block expected;
        try (ExpressionEvaluator reference = FusionStrategy.UNFUSED.factory(shape).get(driverContext)) {
            expected = reference.eval(page);
        }
        strategy.prepare();
        ExpressionEvaluator.Factory factory = strategy.factory(shape);
        if (strategy == FusionStrategy.SPLICED && SlowPathFusionBenchmark.isFused(factory) == false) {
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
            for (PocShape other : PocShape.values()) {
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
        polluteCases(
            SlowPathFusionBenchmark.Case.CONST_COL_ADD_MUL_LONG,
            SlowPathFusionBenchmark.Case.BIGARRAY_ADD_MUL_LONG,
            SlowPathFusionBenchmark.Case.NULLS_ADD_MUL_LONG,
            SlowPathFusionBenchmark.Case.MV_ADD_MUL_LONG,
            SlowPathFusionBenchmark.Case.OR_CMP_LONG,
            SlowPathFusionBenchmark.Case.AND_CMP_DOUBLE
        );
    }

    /** Runs the given slow-path cases fused and unfused (the unfused evaluators call the shared kernel methods). */
    private static void polluteCases(SlowPathFusionBenchmark.Case... cases) {
        for (SlowPathFusionBenchmark.Case c : cases) {
            Page p = c.page(SIZE);
            for (boolean fused : new boolean[] { false, true }) {
                try (ExpressionEvaluator e = SlowPathFusionBenchmark.factory(c, fused).get(driverContext)) {
                    for (int i = 0; i < 300; i++) {
                        e.eval(p).close();
                    }
                }
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
}
