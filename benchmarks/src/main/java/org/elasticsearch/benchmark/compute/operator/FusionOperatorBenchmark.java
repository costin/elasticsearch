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
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * The <b>macro</b> (operator-level) counterpart to {@link FusionBenchmark}: instead of calling
 * {@link ExpressionEvaluator#eval} in a tight loop, it drives the same fusable expressions through a real
 * {@link EvalOperator} ({@code addInput} / {@code getOutput}, exactly as a {@code Driver} does), so the measured
 * fused-vs-unfused delta includes the operator plumbing the micro benchmark omits: the per-page block append that
 * grows the output page, the {@code AbstractPageMappingOperator} row/page accounting, and the borrow-don't-close input
 * handling. It answers the question the micro benchmark cannot — <b>does the per-expression speedup survive the
 * operator pipeline?</b>
 *
 * <p>It deliberately reuses {@link FusionBenchmark.Expr} (same package) as the expression palette, so a shape added
 * there is measured here too, and the value generators / expected-value checks stay in one place. The
 * {@code (expression, size, fusion)} dimensions mirror {@link FusionBenchmark}: {@code fusion} is flipped in-process
 * via {@link FusionSettings#setEnabledForBenchmarks} <b>before</b> the factory is built (the fusion decision is
 * plan-time), so {@code fusion=true} really produces the stitched {@code FusedExpressionEvaluatorFactory} and
 * {@code fusion=false} the ordinary generated chain — the self-test enforces exactly that.
 *
 * <p>Every invocation pushes {@link #TARGET_POSITIONS} positions through the operator (looping
 * {@code TARGET_POSITIONS / size} pages), and {@link OperationsPerInvocation} is that same constant, so the reported
 * average time is a per-position operator cost directly comparable across sizes and to {@link FusionBenchmark}.
 *
 * <h2>Running (see {@code benchmarks/AGENTS.md})</h2>
 * <pre>{@code
 * cd benchmarks
 * ../gradlew run --args "org.elasticsearch.benchmark.compute.operator.FusionOperatorBenchmark \
 *   -p size=1024,8192 -wi 2 -i 3 -f 1 -rf json -rff build/jmh-fusion-operator.json" | tee /tmp/bench/fusion_operator
 * }</pre>
 */
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class FusionOperatorBenchmark {

    static {
        // Configure logging before any BlockFactory/DriverContext static init touches LogManager (mirrors EvalBenchmark).
        Utils.configureBenchmarkLogging();
    }

    private static final BlockFactory blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("none"))
        .build();

    private static final FoldContext FOLD_CONTEXT = FoldContext.small();

    static final DriverContext driverContext = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, blockFactory, null);

    /** Positions pushed through the operator per @Benchmark invocation, independent of {@code size} (per-position time). */
    private static final int TARGET_POSITIONS = 1 << 20;

    private static final int SELF_TEST_SIZE = 1024;

    static {
        if (false == "true".equals(System.getProperty("skipSelfTest"))) {
            selfTest();
        }
    }

    /**
     * Builds every {@code (expression × fusion)} combination once and drives it through an {@link EvalOperator} over a
     * small page, asserting (a) fusion happens exactly when enabled, and (b) the appended result block matches the
     * expression's independently computed expected values. Driven as a fast unit by {@code FusionOperatorBenchmarkTests}.
     */
    static void selfTest() {
        Logger log = LogManager.getLogger(FusionOperatorBenchmark.class);
        try {
            for (FusionBenchmark.Expr expr : FusionBenchmark.Expr.values()) {
                log.info("self testing {}", expr);
                for (boolean fusion : new boolean[] { false, true }) {
                    ExpressionEvaluator.Factory factory = factory(expr, fusion);
                    if (fusion && isFused(factory) == false) {
                        throw new AssertionError(
                            "[" + expr + "] must fuse with fusion=true but got [" + factory.getClass().getName() + "]"
                        );
                    }
                    if (fusion == false && isFused(factory)) {
                        throw new AssertionError("[" + expr + "] must NOT fuse with fusion=false but got [" + factory + "]");
                    }
                    Page in = expr.page(blockFactory, SELF_TEST_SIZE);
                    try (Operator operator = new EvalOperator(driverContext, factory.get(driverContext))) {
                        operator.addInput(in);
                        Page out = operator.getOutput();
                        expr.checkExpected(resultBlock(out), SELF_TEST_SIZE);
                    }
                }
            }
        } finally {
            FusionSettings.setEnabledForBenchmarks(true);
        }
    }

    @Param
    public FusionBenchmark.Expr expression;

    @Param({ "1024", "8192" })
    public int size;

    @Param({ "true", "false" })
    public boolean fusion;

    private ExpressionEvaluator.Factory factory;
    private Page page;
    private int iterations;

    @Setup(Level.Trial)
    public void setup() {
        this.factory = factory(expression, fusion);
        if (fusion && isFused(factory) == false) {
            throw new AssertionError("[" + expression + "] must fuse when fusion=true but got [" + factory.getClass().getName() + "]");
        }
        if (fusion == false && isFused(factory)) {
            throw new AssertionError("[" + expression + "] must NOT fuse when fusion=false but got [" + factory + "]");
        }
        this.page = expression.page(blockFactory, size);
        this.iterations = TARGET_POSITIONS / size;
    }

    @Benchmark
    @OperationsPerInvocation(TARGET_POSITIONS)
    public void run(Blackhole bh) {
        // A fresh operator per invocation (as EvalBenchmark does) — the operator holds the evaluator; its construction
        // is amortised over the TARGET_POSITIONS pushed through it. The input page's blocks are borrowed (the operator
        // appends its result as a new trailing block and returns a new page), matching the Driver's page lifecycle.
        try (Operator operator = new EvalOperator(driverContext, factory.get(driverContext))) {
            Page out = null;
            for (int i = 0; i < iterations; i++) {
                operator.addInput(page);
                out = operator.getOutput();
                bh.consume(out);
            }
            // Validate the last output so a broken fused body cannot measure fast-but-wrong work (like EvalBenchmark).
            expression.checkExpected(resultBlock(out), size);
        }
    }

    /** The eval result is the trailing block the {@link EvalOperator} appends to the input page. */
    private static Block resultBlock(Page out) {
        return out.getBlock(out.getBlockCount() - 1);
    }

    /** Builds the factory for {@code expr} through the real plan-time path with fusion flipped {@code fusion}. */
    private static ExpressionEvaluator.Factory factory(FusionBenchmark.Expr expr, boolean fusion) {
        // Build the expression FIRST: expr.build() touches FusionBenchmark's OUTER-class statics (e.g. configuration()),
        // which lazily triggers FusionBenchmark.<clinit> — and FusionBenchmark's own self-test finally-resets the fusion
        // switch to true. So set the switch AFTER building, immediately before the (fusion-gated) plan-time toEvaluator.
        FusionBenchmark.Built built = expr.build();
        FusionSettings.setEnabledForBenchmarks(fusion);
        return EvalMapper.toEvaluator(FOLD_CONTEXT, built.expr(), built.layout());
    }

    /**
     * {@code FusedExpressionEvaluatorFactory} is package-private in the esql module, so it cannot be named from this
     * (benchmarks) module; matching its simple name is the robust signal that fusion actually happened.
     */
    private static boolean isFused(ExpressionEvaluator.Factory factory) {
        return factory.getClass().getSimpleName().equals("FusedExpressionEvaluatorFactory");
    }
}
