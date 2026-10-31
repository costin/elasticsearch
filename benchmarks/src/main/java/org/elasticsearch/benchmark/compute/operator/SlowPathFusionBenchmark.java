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
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBigArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
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
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
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
 * Fused vs unfused evaluation over the inputs {@link FusionBenchmark} does not cover: nulls, multi-values, constant and
 * non-array vectors, OR / double logical trees, ordinal-encoded keywords and overflowing arithmetic. Each case is
 * checked in {@link #setup} for (a) fusion actually happening when enabled and (b) fused output equal to the unfused
 * output, so the benchmark cannot measure a fast-but-wrong body.
 *
 * <p>{@code pollute=true} first drives every other case (fused and unfused) for a while so the shared unfused evaluator
 * classes ({@code AddLongsEvaluator}, block accessors, ...) see several receiver types before the measured shape is
 * JIT-compiled, as they would on a node serving many query shapes.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class SlowPathFusionBenchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    static final BlockFactory blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("none"))
        .build();

    static final DriverContext driverContext = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, blockFactory, null);

    private static final FoldContext FOLD_CONTEXT = FoldContext.small();

    private static final int TARGET_POSITIONS = 1 << 20;

    public enum Case {
        /** Reference: {@code ((a + b) * c) - d}, long, dense ArrayVectors (the FusionBenchmark fast path). */
        DENSE_ADD_MUL_SUB_LONG {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.LONG), b = field("b", DataType.LONG);
                FieldAttribute c = field("c", DataType.LONG), d = field("d", DataType.LONG);
                Expression addMul = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
                return new Built(new Sub(Source.EMPTY, addMul, d, configuration()), layout(a, b, c, d));
            }

            @Override
            Page page(int size) {
                return new Page(longs(size, i -> i % 1000), longs(size, i -> i % 100), longs(size, i -> i % 10), longs(size, i -> i % 7));
            }
        },
        /** Reference: depth-8 left-deep chain {@code ((((((((a+b)*c)-d)+e)*f)-g)+h)*i}, long, dense. */
        DENSE_CHAIN8_LONG {
            @Override
            Built build() {
                FieldAttribute[] f = new FieldAttribute[9];
                for (int k = 0; k < 9; k++) {
                    f[k] = field("f" + k, DataType.LONG);
                }
                Expression e = f[0];
                for (int k = 1; k < 9; k++) {
                    e = switch (k % 3) {
                        case 1 -> new Add(Source.EMPTY, e, f[k], configuration());
                        case 2 -> new Mul(Source.EMPTY, e, f[k]);
                        default -> new Sub(Source.EMPTY, e, f[k], configuration());
                    };
                }
                return new Built(e, layout(f));
            }

            @Override
            Page page(int size) {
                Block[] blocks = new Block[9];
                for (int k = 0; k < 9; k++) {
                    int m = 7 + k;
                    blocks[k] = longs(size, i -> i % m);
                }
                return new Page(blocks);
            }
        },
        /** {@code (a + b) * c}, long, 10% nulls in {@code a} (forces the block path on both sides). */
        NULLS_ADD_MUL_LONG {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.LONG), b = field("b", DataType.LONG), c = field("c", DataType.LONG);
                return new Built(new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c), layout(a, b, c));
            }

            @Override
            Page page(int size) {
                return new Page(longBlockWithNulls(size, 10), longs(size, i -> i % 100), longs(size, i -> i % 10));
            }
        },
        /** {@code (a + b) * c}, double, 10% nulls in {@code a}. */
        NULLS_ADD_MUL_DOUBLE {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.DOUBLE), b = field("b", DataType.DOUBLE), c = field("c", DataType.DOUBLE);
                return new Built(new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c), layout(a, b, c));
            }

            @Override
            Page page(int size) {
                return new Page(doubleBlockWithNulls(size, 10), doubles(size, i -> i % 100), doubles(size, i -> i % 10));
            }
        },
        /** {@code (a + b) * c}, long, every 256th position of {@code a} is multi-valued (2 values) -> null + warning. */
        MV_ADD_MUL_LONG {
            @Override
            Built build() {
                return NULLS_ADD_MUL_LONG.build();
            }

            @Override
            Page page(int size) {
                return new Page(longBlockWithMv(size, 256), longs(size, i -> i % 100), longs(size, i -> i % 10));
            }
        },
        /** {@code (a + k) * c}, long, where {@code k} is a column backed by a {@code ConstantLongVector}. */
        CONST_COL_ADD_MUL_LONG {
            @Override
            Built build() {
                return NULLS_ADD_MUL_LONG.build();
            }

            @Override
            Page page(int size) {
                return new Page(longs(size, i -> i % 1000), blockFactory.newConstantLongBlockWith(42, size), longs(size, i -> i % 10));
            }
        },
        /** {@code (a + b) * c}, long, where {@code a} is a {@code LongBigArrayVector} (not an ArrayVector). */
        BIGARRAY_ADD_MUL_LONG {
            @Override
            Built build() {
                return NULLS_ADD_MUL_LONG.build();
            }

            @Override
            Page page(int size) {
                LongArray array = BigArrays.NON_RECYCLING_INSTANCE.newLongArray(size);
                for (int i = 0; i < size; i++) {
                    array.set(i, i % 1000);
                }
                Block a = new LongBigArrayVector(array, size, blockFactory).asBlock();
                return new Page(a, longs(size, i -> i % 100), longs(size, i -> i % 10));
            }
        },
        /** {@code (a + 7) * 3}, long, dense: literal leaves on the vector path. */
        LIT_ADD_MUL_LONG {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.LONG);
                Expression add = new Add(Source.EMPTY, a, new Literal(Source.EMPTY, 7L, DataType.LONG), configuration());
                return new Built(new Mul(Source.EMPTY, add, new Literal(Source.EMPTY, 3L, DataType.LONG)), layout(a));
            }

            @Override
            Page page(int size) {
                return new Page(longs(size, i -> i % 1000));
            }
        },
        /** {@code a > b OR c < d}, long, dense. */
        OR_CMP_LONG {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.LONG), b = field("b", DataType.LONG);
                FieldAttribute c = field("c", DataType.LONG), d = field("d", DataType.LONG);
                Expression expr = new Or(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
                return new Built(expr, layout(a, b, c, d));
            }

            @Override
            Page page(int size) {
                return new Page(longs(size, i -> i % 50), longs(size, i -> i % 49), longs(size, i -> i % 40), longs(size, i -> i % 41));
            }
        },
        /** {@code a > b AND c < d}, double, dense. */
        AND_CMP_DOUBLE {
            @Override
            Built build() {
                FieldAttribute a = field("a", DataType.DOUBLE), b = field("b", DataType.DOUBLE);
                FieldAttribute c = field("c", DataType.DOUBLE), d = field("d", DataType.DOUBLE);
                Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
                return new Built(expr, layout(a, b, c, d));
            }

            @Override
            Page page(int size) {
                return new Page(
                    doubles(size, i -> i % 50),
                    doubles(size, i -> i % 49),
                    doubles(size, i -> i % 40),
                    doubles(size, i -> i % 41)
                );
            }
        },
        /** {@code TO_LOWER(s)} over an ordinal-encoded keyword vector with a 16-entry dictionary. */
        TO_LOWER_ORDINAL {
            @Override
            Built build() {
                FieldAttribute s = field("s", DataType.KEYWORD);
                return new Built(new ToLower(Source.EMPTY, s, configuration()), layout(s));
            }

            @Override
            Page page(int size) {
                BytesRefVector dict;
                try (var builder = blockFactory.newBytesRefVectorBuilder(DICT)) {
                    for (int i = 0; i < DICT; i++) {
                        builder.appendBytesRef(dictEntry(i));
                    }
                    dict = builder.build();
                }
                int[] ords = new int[size];
                for (int i = 0; i < size; i++) {
                    ords[i] = i % DICT;
                }
                IntVector ordinals = blockFactory.newIntArrayVector(ords, size);
                return new Page(new OrdinalBytesRefVector(ordinals, dict).asBlock());
            }
        },
        /** {@code TO_LOWER(s)} over a plain (non-ordinal) keyword vector with the same 16 distinct values. */
        TO_LOWER_PLAIN {
            @Override
            Built build() {
                return TO_LOWER_ORDINAL.build();
            }

            @Override
            Page page(int size) {
                try (var builder = blockFactory.newBytesRefVectorBuilder(size)) {
                    for (int i = 0; i < size; i++) {
                        builder.appendBytesRef(dictEntry(i % DICT));
                    }
                    return new Page(builder.build().asBlock());
                }
            }
        },
        /** {@code (a + b) * c}, long, dense, but 10% of rows overflow on {@code a + b} -> null + warning. */
        OVERFLOW10_ADD_MUL_LONG {
            @Override
            Built build() {
                return NULLS_ADD_MUL_LONG.build();
            }

            @Override
            Page page(int size) {
                return new Page(
                    longs(size, i -> i % 10 == 0 ? Long.MAX_VALUE - 1 : i % 1000),
                    longs(size, i -> 2 + i % 100),
                    longs(size, i -> i % 10)
                );
            }
        },
        /** {@code (a + b) * c}, long, dense, 0.1% of rows overflow. */
        OVERFLOW01_ADD_MUL_LONG {
            @Override
            Built build() {
                return NULLS_ADD_MUL_LONG.build();
            }

            @Override
            Page page(int size) {
                return new Page(
                    longs(size, i -> i % 1000 == 0 ? Long.MAX_VALUE - 1 : i % 1000),
                    longs(size, i -> 2 + i % 100),
                    longs(size, i -> i % 10)
                );
            }
        };

        abstract Built build();

        abstract Page page(int size);
    }

    record Built(Expression expr, Layout layout) {}

    private static final int DICT = 16;

    static BytesRef dictEntry(int i) {
        return new BytesRef(("Value-" + i + "-MiXeD-CaSe").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        if (isFused(unfused)) {
            throw new AssertionError(shape + " fused with fusion=false: " + unfused);
        }
        if (isFused(fused) == false) {
            throw new AssertionError(shape + " did not fuse with fusion=true: " + fused.getClass().getName());
        }
        this.page = shape.page(size);
        assertSameOutput(shape, fused.get(driverContext).eval(page), unfused.get(driverContext).eval(page));
        this.evaluator = (fusion ? fused : unfused).get(driverContext);
        this.iterations = TARGET_POSITIONS / size;
    }

    @Benchmark
    @OperationsPerInvocation(TARGET_POSITIONS)
    public void run(Blackhole bh) {
        for (int i = 0; i < iterations; i++) {
            bh.consume(evaluator.eval(page));
        }
    }

    /** Drives every case other than {@code skip} through both evaluator chains so shared classes see mixed profiles. */
    static void pollute(Case skip) {
        for (Case c : Case.values()) {
            if (c == skip) {
                continue;
            }
            Page p = c.page(8192);
            for (boolean f : new boolean[] { false, true }) {
                ExpressionEvaluator e = factory(c, f).get(driverContext);
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

    static boolean isFused(ExpressionEvaluator.Factory factory) {
        return factory.getClass().getSimpleName().contains("Fused");
    }

    static void assertSameOutput(Object what, Block fused, Block unfused) {
        if (fused.getPositionCount() != unfused.getPositionCount()) {
            throw new AssertionError(what + " position count mismatch");
        }
        for (int p = 0; p < fused.getPositionCount(); p++) {
            if (fused.isNull(p) != unfused.isNull(p)) {
                throw new AssertionError(what + " null mismatch @" + p);
            }
            if (fused.isNull(p)) {
                continue;
            }
            int count = fused.getValueCount(p);
            if (count != unfused.getValueCount(p)) {
                throw new AssertionError(what + " value count mismatch @" + p);
            }
            int fi = fused.getFirstValueIndex(p), ui = unfused.getFirstValueIndex(p);
            for (int v = 0; v < count; v++) {
                Object fv, uv;
                if (fused instanceof LongBlock l && unfused instanceof LongBlock u) {
                    fv = l.getLong(fi + v);
                    uv = u.getLong(ui + v);
                } else if (fused instanceof DoubleBlock l && unfused instanceof DoubleBlock u) {
                    fv = l.getDouble(fi + v);
                    uv = u.getDouble(ui + v);
                } else if (fused instanceof IntBlock l && unfused instanceof IntBlock u) {
                    fv = l.getInt(fi + v);
                    uv = u.getInt(ui + v);
                } else if (fused instanceof BooleanBlock l && unfused instanceof BooleanBlock u) {
                    fv = l.getBoolean(fi + v);
                    uv = u.getBoolean(ui + v);
                } else if (fused instanceof BytesRefBlock l && unfused instanceof BytesRefBlock u) {
                    fv = BytesRef.deepCopyOf(l.getBytesRef(fi + v, new BytesRef()));
                    uv = BytesRef.deepCopyOf(u.getBytesRef(ui + v, new BytesRef()));
                } else {
                    throw new AssertionError(what + " unexpected block types " + fused + " / " + unfused);
                }
                if (fv.equals(uv) == false) {
                    throw new AssertionError(what + " value mismatch @" + p + ": " + fv + " vs " + uv);
                }
            }
        }
    }

    interface LongGen {
        long value(int i);
    }

    static Block longs(int size, LongGen gen) {
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            values[i] = gen.value(i);
        }
        return blockFactory.newLongArrayVector(values, size).asBlock();
    }

    static Block doubles(int size, LongGen gen) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = gen.value(i);
        }
        return blockFactory.newDoubleArrayVector(values, size).asBlock();
    }

    static Block longBlockWithNulls(int size, int nullEvery) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(size)) {
            for (int i = 0; i < size; i++) {
                if (i % nullEvery == 3) {
                    builder.appendNull();
                } else {
                    builder.appendLong(i % 1000);
                }
            }
            return builder.build();
        }
    }

    static Block doubleBlockWithNulls(int size, int nullEvery) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(size)) {
            for (int i = 0; i < size; i++) {
                if (i % nullEvery == 3) {
                    builder.appendNull();
                } else {
                    builder.appendDouble(i % 1000);
                }
            }
            return builder.build();
        }
    }

    static Block longBlockWithMv(int size, int mvEvery) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(size)) {
            for (int i = 0; i < size; i++) {
                if (i % mvEvery == 5) {
                    builder.beginPositionEntry();
                    builder.appendLong(i % 1000);
                    builder.appendLong(i % 1000 + 1);
                    builder.endPositionEntry();
                } else {
                    builder.appendLong(i % 1000);
                }
            }
            return builder.build();
        }
    }

    static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(Source.EMPTY, name, new EsField(name, type, Map.of(), true, EsField.TimeSeriesFieldType.NONE));
    }

    static Layout layout(FieldAttribute... fields) {
        Layout.Builder layout = new Layout.Builder();
        layout.append(Arrays.asList(fields));
        return layout.build();
    }

    static Configuration configuration() {
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
