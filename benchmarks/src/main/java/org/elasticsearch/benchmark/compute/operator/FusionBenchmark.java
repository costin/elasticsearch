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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.analysis.AnalyzerSettings;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-position throughput of <b>fused</b> vs <b>unfused</b> expression evaluation for representative fusable
 * expressions — the perf justification for the ESQL expression-fusion feature. For each {@code (expression, size,
 * fusion)} combination the benchmark builds the evaluator through the <b>real</b> plan-time path
 * {@link EvalMapper#toEvaluator} (so it produces a fused {@code FusedExpressionEvaluatorFactory} exactly when the
 * {@link FusionSettings} kill switch is on) and evaluates a dense, no-null primitive {@link Page} in a tight loop.
 *
 * <h2>Dimensions</h2>
 * <ul>
 *   <li>{@code expression} — {@link Expr}: {@code (a+b)*c} over long and over double, {@code ((a+b)*c)-d} (deeper,
 *       under budget), {@code a+b>c} (comparison → boolean), {@code a>b AND c<d} (3VL logical, block path), and
 *       {@code a>25 AND b<25} (3VL logical with embedded literal thresholds — the common WHERE shape, iter 24);</li>
 *   <li>{@code size} — the page position count. QUICK defaults to {@code {1024, 8192}}; the FULL matrix adds
 *       {@code {1, 64, 65536}} (all powers of two dividing {@link #TARGET_POSITIONS}, so ns/op stays ns/position);</li>
 *   <li>{@code fusion} — {@code true}/{@code false}, flipped in-process via {@link FusionSettings#setEnabledForBenchmarks}
 *       in {@link #setup} <b>before</b> the factory is built.</li>
 * </ul>
 * Every invocation processes {@link #TARGET_POSITIONS} positions regardless of {@code size} (it loops
 * {@code TARGET_POSITIONS / size} times), and {@link OperationsPerInvocation} is that same constant, so the reported
 * average time is directly comparable across sizes as a per-position cost.
 *
 * <h2>Correctness / fusion self-test</h2>
 * A static self-test (mirroring {@code EvalBenchmark}) builds every {@code (expression × fusion)} combination once at
 * class-init: with fusion on it asserts the produced factory really is a {@code FusedExpressionEvaluatorFactory} (so
 * the benchmark cannot silently measure the unfused path twice) and that its output equals the unfused chain's, and it
 * checks both against the independently computed expected values. The companion {@code FusionBenchmarkTests} drives
 * this same self-test as a fast unit so the benchmark cannot bit-rot.
 *
 * <h2>Running (see {@code benchmarks/AGENTS.md} for the general {@code run} convention)</h2>
 * QUICK (~10 min; clear directional fused-vs-unfused signal):
 * <pre>{@code
 * cd benchmarks
 * ../gradlew run --args "org.elasticsearch.benchmark.compute.operator.FusionBenchmark \
 *   -p size=1024,8192 -wi 2 -i 3 -f 1 -rf json -rff build/jmh-fusion.json" | tee /tmp/bench/fusion_quick
 * }</pre>
 * FULL (overnight; full size matrix, default warmup/measurement/forks):
 * <pre>{@code
 * cd benchmarks
 * ../gradlew run --args "org.elasticsearch.benchmark.compute.operator.FusionBenchmark \
 *   -p size=1,64,1024,8192,65536 -rf json -rff build/jmh-fusion-full.json" | tee /tmp/bench/fusion_full
 * }</pre>
 */
@Warmup(iterations = 5)
@Measurement(iterations = 7)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class FusionBenchmark {

    /**
     * A representative fusable expression: its stable id, how to build it (as a real ESQL {@link Expression} + the
     * {@link Layout} mapping its column leaves to page channels), the dense no-null input {@link Page} it reads, and a
     * {@code checkExpected} that verifies the output against independently computed values.
     */
    public enum Expr {
        /** {@code (a + b) * c} over long columns — overflow-checked long arithmetic (checked-vector fast path). */
        ADD_MUL_LONG("add_mul_long") {
            @Override
            Built build() {
                FieldAttribute a = longField("a"), b = longField("b"), c = longField("c");
                Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
                return new Built(expr, layout(a, b, c));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                return new Page(
                    longVector(bf, size, FusionBenchmark::a),
                    longVector(bf, size, FusionBenchmark::b),
                    longVector(bf, size, FusionBenchmark::c)
                );
            }

            @Override
            void checkExpected(Block out, int size) {
                LongBlock r = (LongBlock) out;
                for (int i = 0; i < size; i++) {
                    long expected = (a(i) + b(i)) * c(i);
                    long actual = r.getLong(r.getFirstValueIndex(i));
                    if (actual != expected) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        },
        /** {@code (a + b) * c} over double columns — overflow-checked double arithmetic (hard-gated to the block path). */
        ADD_MUL_DOUBLE("add_mul_double") {
            @Override
            Built build() {
                FieldAttribute a = doubleField("a"), b = doubleField("b"), c = doubleField("c");
                Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
                return new Built(expr, layout(a, b, c));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                return new Page(
                    doubleVector(bf, size, FusionBenchmark::a),
                    doubleVector(bf, size, FusionBenchmark::b),
                    doubleVector(bf, size, FusionBenchmark::c)
                );
            }

            @Override
            void checkExpected(Block out, int size) {
                DoubleBlock r = (DoubleBlock) out;
                for (int i = 0; i < size; i++) {
                    double expected = ((double) a(i) + (double) b(i)) * (double) c(i);
                    double actual = r.getDouble(r.getFirstValueIndex(i));
                    if (Double.doubleToLongBits(actual) != Double.doubleToLongBits(expected)) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        },
        /** {@code ((a + b) * c) - d} over long columns — a deeper (kernel-depth 3) but under-budget tree. */
        ADD_MUL_SUB_LONG("add_mul_sub_long") {
            @Override
            Built build() {
                FieldAttribute a = longField("a"), b = longField("b"), c = longField("c"), d = longField("d");
                Expression addMul = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
                Expression expr = new Sub(Source.EMPTY, addMul, d, configuration());
                return new Built(expr, layout(a, b, c, d));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                return new Page(
                    longVector(bf, size, FusionBenchmark::a),
                    longVector(bf, size, FusionBenchmark::b),
                    longVector(bf, size, FusionBenchmark::c),
                    longVector(bf, size, FusionBenchmark::d)
                );
            }

            @Override
            void checkExpected(Block out, int size) {
                LongBlock r = (LongBlock) out;
                for (int i = 0; i < size; i++) {
                    long expected = ((a(i) + b(i)) * c(i)) - d(i);
                    long actual = r.getLong(r.getFirstValueIndex(i));
                    if (actual != expected) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        },
        /** {@code a + b > c} over long columns — a comparison root over overflow-checked arithmetic (boolean output). */
        ADD_GT_LONG("add_gt_long") {
            @Override
            Built build() {
                FieldAttribute a = longField("a"), b = longField("b"), c = longField("c");
                Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
                return new Built(expr, layout(a, b, c));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                // Use generators whose magnitudes overlap so (a + b) > c yields a genuine mix of true/false.
                return new Page(
                    longVector(bf, size, FusionBenchmark::cmpA),
                    longVector(bf, size, FusionBenchmark::cmpB),
                    longVector(bf, size, FusionBenchmark::cmpC)
                );
            }

            @Override
            void checkExpected(Block out, int size) {
                BooleanBlock r = (BooleanBlock) out;
                for (int i = 0; i < size; i++) {
                    boolean expected = (cmpA(i) + cmpB(i)) > cmpC(i);
                    boolean actual = r.getBoolean(r.getFirstValueIndex(i));
                    if (actual != expected) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        },
        /** {@code a > b AND c < d} over long columns — a 3VL logical tree (block-path only, nullable boolean output). */
        AND_CMP_LONG("and_cmp_long") {
            @Override
            Built build() {
                FieldAttribute a = longField("a"), b = longField("b"), c = longField("c"), d = longField("d");
                Expression expr = new And(
                    Source.EMPTY,
                    new GreaterThan(Source.EMPTY, a, b),
                    new LessThan(Source.EMPTY, c, d)
                );
                return new Built(expr, layout(a, b, c, d));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                return new Page(
                    longVector(bf, size, FusionBenchmark::logA),
                    longVector(bf, size, FusionBenchmark::logB),
                    longVector(bf, size, FusionBenchmark::logC),
                    longVector(bf, size, FusionBenchmark::logD)
                );
            }

            @Override
            void checkExpected(Block out, int size) {
                BooleanBlock r = (BooleanBlock) out;
                for (int i = 0; i < size; i++) {
                    boolean expected = (logA(i) > logB(i)) && (logC(i) < logD(i));
                    boolean actual = r.getBoolean(r.getFirstValueIndex(i));
                    if (actual != expected) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        },
        /**
         * {@code a > 25 AND b < 25} over long columns — the common WHERE shape with embedded literal thresholds (iter
         * 24): a 3VL logical whose two comparison operands each bake a constant into their {@code cmpN} helper (block
         * path only, nullable boolean output). Proves the constant-leaf fusion carries the vector-to-fused speedup on
         * the shape queries most often use.
         */
        AND_CMP_CONST_LONG("and_cmp_const_long") {
            @Override
            Built build() {
                FieldAttribute a = longField("a"), b = longField("b");
                Expression expr = new And(
                    Source.EMPTY,
                    new GreaterThan(Source.EMPTY, a, new Literal(Source.EMPTY, CONST_K1, DataType.LONG)),
                    new LessThan(Source.EMPTY, b, new Literal(Source.EMPTY, CONST_K2, DataType.LONG))
                );
                return new Built(expr, layout(a, b));
            }

            @Override
            Page page(BlockFactory bf, int size) {
                return new Page(longVector(bf, size, FusionBenchmark::constA), longVector(bf, size, FusionBenchmark::constB));
            }

            @Override
            void checkExpected(Block out, int size) {
                BooleanBlock r = (BooleanBlock) out;
                for (int i = 0; i < size; i++) {
                    boolean expected = (constA(i) > CONST_K1) && (constB(i) < CONST_K2);
                    boolean actual = r.getBoolean(r.getFirstValueIndex(i));
                    if (actual != expected) {
                        throw new AssertionError("[" + this + "] @" + i + " expected [" + expected + "] but was [" + actual + "]");
                    }
                }
            }
        };

        private final String id;

        Expr(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        /** Builds the real ESQL expression + the layout mapping its column leaves to page channels (in page order). */
        abstract Built build();

        /** A dense, no-null primitive input page whose columns are in the same order as {@link #build}'s layout. */
        abstract Page page(BlockFactory bf, int size);

        /** Verifies {@code out} against the independently computed expected values for this expression. */
        abstract void checkExpected(Block out, int size);
    }

    /** A built expression together with the {@link Layout} that resolves its column leaves to page channels. */
    record Built(Expression expr, Layout layout) {}

    // Initialize logging before any BlockFactory / DriverContext field touches LogManager — otherwise BlockFactory's
    // static initializer fires before the LogConfigurator SPI is set up and NPEs. Mirrors EvalBenchmark.
    static {
        Utils.configureBenchmarkLogging();
    }

    private static final BlockFactory blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("none"))
        .build();

    private static final FoldContext FOLD_CONTEXT = FoldContext.small();

    static final DriverContext driverContext = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, blockFactory, null);

    /** Positions processed per @Benchmark invocation, independent of {@code size}, so the reported time is per-position. */
    private static final int TARGET_POSITIONS = 1 << 20;

    /** Page size used by the self-test (any small size; correctness is size-independent for these dense inputs). */
    private static final int SELF_TEST_SIZE = 1024;

    static {
        if (false == "true".equals(System.getProperty("skipSelfTest"))) {
            selfTest();
        }
    }

    /**
     * Builds every {@code (expression × fusion)} combination once and asserts (a) fusion actually happens when enabled,
     * and (b) the fused output equals the unfused output and both match the expected values. This is the correctness +
     * fusion guard driven by {@code FusionBenchmarkTests}.
     */
    static void selfTest() {
        Logger log = LogManager.getLogger(FusionBenchmark.class);
        try {
            for (Expr expr : Expr.values()) {
                log.info("self testing {}", expr);

                ExpressionEvaluator.Factory unfusedFactory = factory(expr, false);
                if (isFused(unfusedFactory)) {
                    throw new AssertionError("[" + expr + "] must NOT fuse with the kill switch OFF but got [" + unfusedFactory + "]");
                }
                ExpressionEvaluator.Factory fusedFactory = factory(expr, true);
                if (isFused(fusedFactory) == false) {
                    throw new AssertionError(
                        "[" + expr + "] must fuse with the kill switch ON but produced [" + unfusedClassName(fusedFactory) + "]"
                    );
                }

                Page page = expr.page(blockFactory, SELF_TEST_SIZE);
                ExpressionEvaluator unfused = unfusedFactory.get(driverContext);
                ExpressionEvaluator fused = fusedFactory.get(driverContext);
                Block unfusedOut = unfused.eval(page);
                Block fusedOut = fused.eval(page);
                // Fusion contract: identical output; plus both match the expected values computed here.
                expr.checkExpected(unfusedOut, SELF_TEST_SIZE);
                expr.checkExpected(fusedOut, SELF_TEST_SIZE);
                assertSameOutput(expr, fusedOut, unfusedOut, SELF_TEST_SIZE);
            }
        } finally {
            // Leave the switch in its default (enabled) state; @Setup re-sets it per trial anyway.
            FusionSettings.setEnabledForBenchmarks(true);
        }
    }

    @Param
    public Expr expression;

    @Param({ "1024", "8192" })
    public int size;

    @Param({ "true", "false" })
    public boolean fusion;

    private ExpressionEvaluator evaluator;
    private Page page;
    private int iterations;

    @Setup(Level.Trial)
    public void setup() {
        // The fusion decision is made when the factory is built, so the switch must be set FIRST (criterion #3 path).
        ExpressionEvaluator.Factory factory = factory(expression, fusion);
        if (fusion && isFused(factory) == false) {
            throw new AssertionError("[" + expression + "] must fuse when fusion=true but produced [" + unfusedClassName(factory) + "]");
        }
        if (fusion == false && isFused(factory)) {
            throw new AssertionError("[" + expression + "] must NOT fuse when fusion=false but produced [" + factory + "]");
        }
        this.evaluator = factory.get(driverContext);
        this.page = expression.page(blockFactory, size);
        this.iterations = TARGET_POSITIONS / size;
    }

    @Benchmark
    @OperationsPerInvocation(TARGET_POSITIONS)
    public void run(Blackhole bh) {
        Block out = null;
        for (int i = 0; i < iterations; i++) {
            out = evaluator.eval(page);
            bh.consume(out);
        }
        // Validate the last output so a broken fused body can't measure fast-but-wrong work (like EvalBenchmark).
        expression.checkExpected(out, size);
    }

    /** Builds the factory for {@code expr} through the real plan-time path with fusion flipped {@code fusion}. */
    private static ExpressionEvaluator.Factory factory(Expr expr, boolean fusion) {
        FusionSettings.setEnabledForBenchmarks(fusion);
        Built built = expr.build();
        return EvalMapper.toEvaluator(FOLD_CONTEXT, built.expr(), built.layout());
    }

    /**
     * Whether the produced factory is the fused one. {@code FusedExpressionEvaluatorFactory} is package-private in the
     * esql module, so it cannot be named with {@code instanceof} from this (benchmarks) module; matching its simple
     * name is the equivalent, robust signal that fusion actually happened.
     */
    private static boolean isFused(ExpressionEvaluator.Factory factory) {
        return factory.getClass().getSimpleName().equals("FusedExpressionEvaluatorFactory");
    }

    private static String unfusedClassName(ExpressionEvaluator.Factory factory) {
        return factory.getClass().getName();
    }

    private static void assertSameOutput(Expr expr, Block fused, Block unfused, int size) {
        if (fused.getPositionCount() != size || unfused.getPositionCount() != size) {
            throw new AssertionError("[" + expr + "] position count mismatch");
        }
        for (int p = 0; p < size; p++) {
            boolean fusedNull = fused.isNull(p);
            if (fusedNull != unfused.isNull(p)) {
                throw new AssertionError("[" + expr + "] null mismatch @" + p);
            }
            if (fusedNull) {
                continue;
            }
            if (fused instanceof LongBlock fl && unfused instanceof LongBlock ul) {
                if (fl.getLong(fl.getFirstValueIndex(p)) != ul.getLong(ul.getFirstValueIndex(p))) {
                    throw new AssertionError("[" + expr + "] long value mismatch @" + p);
                }
            } else if (fused instanceof DoubleBlock fd && unfused instanceof DoubleBlock ud) {
                long fb = Double.doubleToLongBits(fd.getDouble(fd.getFirstValueIndex(p)));
                long ub = Double.doubleToLongBits(ud.getDouble(ud.getFirstValueIndex(p)));
                if (fb != ub) {
                    throw new AssertionError("[" + expr + "] double value mismatch @" + p);
                }
            } else if (fused instanceof BooleanBlock fbool && unfused instanceof BooleanBlock ubool) {
                if (fbool.getBoolean(fbool.getFirstValueIndex(p)) != ubool.getBoolean(ubool.getFirstValueIndex(p))) {
                    throw new AssertionError("[" + expr + "] boolean value mismatch @" + p);
                }
            } else {
                throw new AssertionError("[" + expr + "] unexpected block types fused=" + fused + " unfused=" + unfused);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Column value generators (formulas are mirrored in each Expr's checkExpected). Kept small so overflow-checked
    // long arithmetic over these depth-3 trees never overflows (the dense fast path stays warning-free).
    // ---------------------------------------------------------------------------------------------------------------

    private static long a(int i) {
        return i % 1000;
    }

    private static long b(int i) {
        return i % 100;
    }

    private static long c(int i) {
        return i % 10;
    }

    private static long d(int i) {
        return i % 7;
    }

    // Comparison generators: overlapping magnitudes so (a + b) > c is a real mix of true/false.
    private static long cmpA(int i) {
        return i % 50;
    }

    private static long cmpB(int i) {
        return i % 30;
    }

    private static long cmpC(int i) {
        return i % 70;
    }

    // Constant-threshold logical generators (iter 24): columns straddle the fixed thresholds so both a > K1 and b < K2
    // yield a genuine mix of true/false.
    private static final long CONST_K1 = 25;
    private static final long CONST_K2 = 25;

    private static long constA(int i) {
        return i % 50;
    }

    private static long constB(int i) {
        return i % 50;
    }

    // Logical generators: overlapping magnitudes so both (a > b) and (c < d) vary.
    private static long logA(int i) {
        return i % 50;
    }

    private static long logB(int i) {
        return i % 50 == 0 ? 1 : i % 49;
    }

    private static long logC(int i) {
        return i % 40;
    }

    private static long logD(int i) {
        return i % 41;
    }

    private interface LongGen {
        long value(int i);
    }

    private static Block longVector(BlockFactory bf, int size, LongGen gen) {
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            values[i] = gen.value(i);
        }
        return bf.newLongArrayVector(values, size).asBlock();
    }

    /** Dense double column whose values are the (small) long generator's values cast to double. */
    private static Block doubleVector(BlockFactory bf, int size, LongGen gen) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = gen.value(i);
        }
        return bf.newDoubleArrayVector(values, size).asBlock();
    }

    private static FieldAttribute longField(String name) {
        return field(name, DataType.LONG);
    }

    private static FieldAttribute doubleField(String name) {
        return field(name, DataType.DOUBLE);
    }

    private static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(Source.EMPTY, name, new EsField(name, type, Map.of(), true, EsField.TimeSeriesFieldType.NONE));
    }

    private static Layout layout(FieldAttribute... fields) {
        Layout.Builder layout = new Layout.Builder();
        layout.append(Arrays.asList(fields));
        return layout.build();
    }

    private static Configuration configuration() {
        return new Configuration(
            ZoneOffset.UTC,
            Instant.now(),
            Locale.ROOT,
            null,
            null,
            QueryPragmas.EMPTY,
            AnalyzerSettings.QUERY_RESULT_TRUNCATION_MAX_SIZE.get(Settings.EMPTY),
            AnalyzerSettings.QUERY_RESULT_TRUNCATION_DEFAULT_SIZE.get(Settings.EMPTY),
            null,
            false,
            Map.of(),
            0,
            false,
            AnalyzerSettings.QUERY_TIMESERIES_RESULT_TRUNCATION_MAX_SIZE.getDefault(Settings.EMPTY),
            AnalyzerSettings.QUERY_TIMESERIES_RESULT_TRUNCATION_DEFAULT_SIZE.getDefault(Settings.EMPTY),
            null,
            null,
            Map.of()
        );
    }
}
