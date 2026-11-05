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
import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.FilterOperator;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
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

import java.util.Arrays;
import java.util.Map;
import java.util.Random;
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
 * <p>{@link Where} adds the WHERE cells: a {@link FilterOperator} over an unfused predicate ({@code OFF}), over the
 * boolean-output fused predicate whose {@code BooleanBlock} the filter scans ({@code BOOLEAN}), and over the fused
 * predicate that writes the selected positions itself ({@code POSITIONS}).
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
        BenchmarkLogging.configure();
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
            Where.selfTest();
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
        FusionBenchmark.assertVerifyDisabled();
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

    /**
     * WHERE cells: one {@link FilterOperator} per invocation, fed {@code TARGET_POSITIONS / size} shallow copies of the
     * trial page (the filter releases its input). Each predicate keeps about half the rows of seeded random data.
     */
    @Warmup(iterations = 3)
    @Measurement(iterations = 5)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Thread)
    @Fork(1)
    public static class Where {
        /**
         * {@code a * 2 + b > 40} over longs, {@code x * y > 1.5} over doubles, {@code a > b AND a < c} over longs, and
         * {@code LENGTH(k) > 5} over a dense keyword column (no vector path: the fused predicate runs its block loop).
         */
        public enum Shape {
            LONG_ARITHMETIC,
            DOUBLE_ARITHMETIC,
            LOGICAL_AND,
            KEYWORD_LENGTH
        }

        /**
         * How the predicate reaches the filter.
         * <ul>
         *   <li>{@code OFF}: the unfused evaluator tree builds a {@code BooleanBlock}, the filter scans it.</li>
         *   <li>{@code BOOLEAN}: the fused predicate builds the {@code BooleanBlock}, the filter scans it.</li>
         *   <li>{@code POSITIONS}: the fused predicate writes the selected positions; no block, no scan.</li>
         * </ul>
         */
        public enum Mode {
            OFF,
            BOOLEAN,
            POSITIONS
        }

        @Param
        public Shape shape;

        @Param({ "1024", "8192" })
        public int size;

        @Param
        public Mode mode;

        private ExpressionEvaluator.Factory factory;
        private Page page;
        private int iterations;
        private int expectedSurvivors;

        static void selfTest() {
            Logger log = LogManager.getLogger(Where.class);
            for (Shape shape : Shape.values()) {
                for (Mode mode : Mode.values()) {
                    log.info("self testing where {} mode={}", shape, mode);
                    Where cell = new Where();
                    cell.shape = shape;
                    cell.size = SELF_TEST_SIZE;
                    cell.mode = mode;
                    cell.setupCell();
                    cell.iterations = 1;
                    cell.checkExpected(cell.runInvocation(null));
                }
            }
        }

        @Setup(Level.Trial)
        public void setup() {
            FusionBenchmark.assertVerifyDisabled();
            setupCell();
        }

        void setupCell() {
            Built built = build(shape, size);
            this.page = built.page();
            this.expectedSurvivors = built.survivors();
            this.iterations = TARGET_POSITIONS / size;
            FusionSettings.setEnabledForBenchmarks(mode != Mode.OFF);
            try {
                this.factory = mode == Mode.POSITIONS
                    ? EvalMapper.toFilterEvaluator(FOLD_CONTEXT, built.predicate(), built.layout())
                    : EvalMapper.toEvaluator(FOLD_CONTEXT, built.predicate(), built.layout());
            } finally {
                FusionSettings.setEnabledForBenchmarks(true);
            }
            String expected = switch (mode) {
                case OFF -> null;
                case BOOLEAN -> "FusedExpressionEvaluatorFactory";
                case POSITIONS -> "FusedFilterEvaluatorFactory";
            };
            String actual = factory.getClass().getSimpleName();
            if (expected == null ? actual.startsWith("Fused") : actual.equals(expected) == false) {
                throw new AssertionError("[" + shape + "] mode " + mode + " planned [" + actual + "]");
            }
        }

        @Benchmark
        @OperationsPerInvocation(TARGET_POSITIONS)
        public void run(Blackhole bh) {
            checkExpected(runInvocation(bh));
        }

        /** Pushes {@link #iterations} shallow copies of the page through one filter; returns the last output's size. */
        private int runInvocation(Blackhole bh) {
            int last = -1;
            try (FilterOperator filter = new FilterOperator(factory.get(driverContext))) {
                for (int i = 0; i < iterations; i++) {
                    filter.addInput(page.shallowCopy());
                    Page out = filter.getOutput();
                    last = out == null ? 0 : out.getPositionCount();
                    if (bh != null) {
                        bh.consume(out);
                    }
                    if (out != null) {
                        out.releaseBlocks();
                    }
                }
            }
            return last;
        }

        private void checkExpected(int survivors) {
            if (survivors != expectedSurvivors) {
                throw new AssertionError("[" + shape + "] mode " + mode + " kept " + survivors + " rows, expected " + expectedSurvivors);
            }
        }

        private static final long DATA_SEED = 42L;

        private static Built build(Shape shape, int size) {
            Random r = new Random(DATA_SEED);
            int survivors = 0;
            return switch (shape) {
                case LONG_ARITHMETIC -> {
                    FieldAttribute a = field("a", DataType.LONG);
                    FieldAttribute b = field("b", DataType.LONG);
                    Expression predicate = new GreaterThan(
                        Source.EMPTY,
                        new Add(
                            Source.EMPTY,
                            new Mul(Source.EMPTY, a, new Literal(Source.EMPTY, 2L, DataType.LONG)),
                            b,
                            FusionBenchmark.configuration()
                        ),
                        new Literal(Source.EMPTY, 40L, DataType.LONG)
                    );
                    long[] av = new long[size];
                    long[] bv = new long[size];
                    for (int i = 0; i < size; i++) {
                        av[i] = r.nextInt(41);
                        bv[i] = r.nextInt(41) - 20;
                        survivors += av[i] * 2 + bv[i] > 40 ? 1 : 0;
                    }
                    Page page = new Page(
                        blockFactory.newLongArrayVector(av, size).asBlock(),
                        blockFactory.newLongArrayVector(bv, size).asBlock()
                    );
                    yield new Built(predicate, layout(a, b), page, survivors);
                }
                case DOUBLE_ARITHMETIC -> {
                    FieldAttribute x = field("x", DataType.DOUBLE);
                    FieldAttribute y = field("y", DataType.DOUBLE);
                    Expression predicate = new GreaterThan(
                        Source.EMPTY,
                        new Mul(Source.EMPTY, x, y),
                        new Literal(Source.EMPTY, 1.5, DataType.DOUBLE)
                    );
                    double[] xv = new double[size];
                    double[] yv = new double[size];
                    for (int i = 0; i < size; i++) {
                        xv[i] = 0.5 + r.nextDouble() * 1.5;
                        yv[i] = 0.5 + r.nextDouble() * 1.5;
                        survivors += xv[i] * yv[i] > 1.5 ? 1 : 0;
                    }
                    Page page = new Page(
                        blockFactory.newDoubleArrayVector(xv, size).asBlock(),
                        blockFactory.newDoubleArrayVector(yv, size).asBlock()
                    );
                    yield new Built(predicate, layout(x, y), page, survivors);
                }
                case LOGICAL_AND -> {
                    FieldAttribute a = field("a", DataType.LONG);
                    FieldAttribute b = field("b", DataType.LONG);
                    FieldAttribute c = field("c", DataType.LONG);
                    Expression predicate = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, a, c));
                    long[] av = new long[size];
                    long[] bv = new long[size];
                    long[] cv = new long[size];
                    for (int i = 0; i < size; i++) {
                        av[i] = r.nextInt(100);
                        bv[i] = r.nextInt(30);
                        cv[i] = r.nextInt(100) + 40;
                        survivors += av[i] > bv[i] && av[i] < cv[i] ? 1 : 0;
                    }
                    Page page = new Page(
                        blockFactory.newLongArrayVector(av, size).asBlock(),
                        blockFactory.newLongArrayVector(bv, size).asBlock(),
                        blockFactory.newLongArrayVector(cv, size).asBlock()
                    );
                    yield new Built(predicate, layout(a, b, c), page, survivors);
                }
                case KEYWORD_LENGTH -> {
                    FieldAttribute k = field("k", DataType.KEYWORD);
                    Expression predicate = new GreaterThan(
                        Source.EMPTY,
                        new Length(Source.EMPTY, k),
                        new Literal(Source.EMPTY, 5, DataType.INTEGER)
                    );
                    Block keywords;
                    try (BytesRefVector.Builder builder = blockFactory.newBytesRefVectorBuilder(size)) {
                        for (int i = 0; i < size; i++) {
                            // ASCII, so LENGTH is the byte length: 1..10, about half above 5.
                            byte[] bytes = new byte[1 + r.nextInt(10)];
                            Arrays.fill(bytes, (byte) 'a');
                            builder.appendBytesRef(new BytesRef(bytes));
                            survivors += bytes.length > 5 ? 1 : 0;
                        }
                        keywords = builder.build().asBlock();
                    }
                    yield new Built(predicate, layout(k), new Page(keywords), survivors);
                }
            };
        }

        private static FieldAttribute field(String name, DataType type) {
            return new FieldAttribute(Source.EMPTY, name, new EsField(name, type, Map.of(), true, EsField.TimeSeriesFieldType.NONE));
        }

        private static Layout layout(FieldAttribute... fields) {
            Layout.Builder layout = new Layout.Builder();
            layout.append(Arrays.asList(fields));
            return layout.build();
        }

        private record Built(Expression predicate, Layout layout, Page page, int survivors) {}
    }
}
