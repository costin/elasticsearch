/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.VectorUnsafe;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.xpack.esql.analysis.AnalyzerSettings;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.util.NumericUtils;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.plan.ResolvedSettings;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.util.Map;

/**
 * Per-row strategies compared by {@link FusionStrategyBenchmark}. {@link #prepare()} sets the process-wide fusion
 * switch once (it is a static in {@code FusionSettings}), so a single JMH trial must use a single strategy.
 */
public enum FusionStrategy {
    /** Existing generated evaluator chain. */
    UNFUSED,
    /** Branch bytecode splicing (eager). */
    SPLICED,
    /** Hand-written fused loop per shape writing through {@code LongBlock.Builder}: the ceiling with builder output. */
    HANDWRITTEN,
    /** Same loop writing a raw array (builder fallback on overflow): the ceiling with raw output. */
    HANDWRITTEN_RAW;

    private static final org.elasticsearch.xpack.esql.core.expression.FoldContext FOLD =
        org.elasticsearch.xpack.esql.core.expression.FoldContext.small();

    void prepare() {
        FusionSettings.setEnabledForBenchmarks(this == SPLICED);
    }

    /** Plans the shape (plan-time cost included: stitch/cache for SPLICED). */
    ExpressionEvaluator.Factory factory(PocShape shape) {
        Built built = shape.build();
        ExpressionEvaluator.Factory planned = EvalMapper.toEvaluator(FOLD, built.expr(), built.layout());
        return switch (this) {
            case UNFUSED, SPLICED -> planned;
            case HANDWRITTEN -> new Handwritten(shape, planned, false);
            case HANDWRITTEN_RAW -> new Handwritten(shape, planned, true);
        };
    }

    record Handwritten(PocShape shape, ExpressionEvaluator.Factory unfused, boolean raw) implements ExpressionEvaluator.Factory {
        @Override
        public ExpressionEvaluator get(DriverContext context) {
            ExpressionEvaluator fallback = unfused.get(context);
            Warnings warnings = Warnings.createWarnings(context, Source.EMPTY);
            return new ExpressionEvaluator() {
                @Override
                public Block eval(Page page) {
                    for (int c = 0; c < page.getBlockCount(); c++) {
                        if (page.getBlock(c).asVector() == null) {
                            return fallback.eval(page);
                        }
                    }
                    return raw
                        ? shape.handwrittenRaw(context.blockFactory(), warnings, page)
                        : shape.handwritten(context.blockFactory(), warnings, page);
                }

                @Override
                public long baseRamBytesUsed() {
                    return 0;
                }

                @Override
                public void close() {
                    fallback.close();
                }
            };
        }
    }

    public enum PocShape {
        ADD_MUL_LONG {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.LONG), b = FusionStrategy.field("b", DataType.LONG), c =
                    FusionStrategy.field("c", DataType.LONG);
                return new FusionStrategy.Built(
                    new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, FusionStrategy.configuration()), c),
                    FusionStrategy.layout(a, b, c)
                );
            }

            @Override
            Page page(int size) {
                return new Page(
                    FusionStrategy.longs(size, i -> i % 1000),
                    FusionStrategy.longs(size, i -> i % 100),
                    FusionStrategy.longs(size, i -> i % 10)
                );
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0), b = l(page, 1), c = l(page, 2);
                int n = page.getPositionCount();
                try (LongBlock.Builder out = bf.newLongBlockBuilder(n)) {
                    for (int p = 0; p < n; p++) {
                        try {
                            out.appendLong(Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]));
                        } catch (ArithmeticException e) {
                            w.registerException(e);
                            out.appendNull();
                        }
                    }
                    return out.build();
                }
            }
        },
        ADD_MUL_SUB_LONG {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.LONG), b = FusionStrategy.field("b", DataType.LONG);
                FieldAttribute c = FusionStrategy.field("c", DataType.LONG), d = FusionStrategy.field("d", DataType.LONG);
                Expression addMul = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, FusionStrategy.configuration()), c);
                return new FusionStrategy.Built(
                    new Sub(Source.EMPTY, addMul, d, FusionStrategy.configuration()),
                    FusionStrategy.layout(a, b, c, d)
                );
            }

            @Override
            Page page(int size) {
                return new Page(
                    FusionStrategy.longs(size, i -> i % 1000),
                    FusionStrategy.longs(size, i -> i % 100),
                    FusionStrategy.longs(size, i -> i % 10),
                    FusionStrategy.longs(size, i -> i % 7)
                );
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0), b = l(page, 1), c = l(page, 2), d = l(page, 3);
                int n = page.getPositionCount();
                try (LongBlock.Builder out = bf.newLongBlockBuilder(n)) {
                    for (int p = 0; p < n; p++) {
                        try {
                            out.appendLong(Math.subtractExact(Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]), d[p]));
                        } catch (ArithmeticException e) {
                            w.registerException(e);
                            out.appendNull();
                        }
                    }
                    return out.build();
                }
            }

            @Override
            Block handwrittenRaw(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0), b = l(page, 1), c = l(page, 2), d = l(page, 3);
                int n = page.getPositionCount();
                long[] out = new long[n];
                try {
                    for (int p = 0; p < n; p++) {
                        out[p] = Math.subtractExact(Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]), d[p]);
                    }
                } catch (ArithmeticException e) {
                    return handwritten(bf, w, page);
                }
                return bf.newLongArrayVector(out, n).asBlock();
            }
        },
        CHAIN8_LONG {
            @Override
            Built build() {
                FieldAttribute[] f = new FieldAttribute[9];
                for (int k = 0; k < 9; k++) {
                    f[k] = FusionStrategy.field("f" + k, DataType.LONG);
                }
                Expression e = f[0];
                for (int k = 1; k < 9; k++) {
                    e = switch (k % 3) {
                        case 1 -> new Add(Source.EMPTY, e, f[k], FusionStrategy.configuration());
                        case 2 -> new Mul(Source.EMPTY, e, f[k]);
                        default -> new Sub(Source.EMPTY, e, f[k], FusionStrategy.configuration());
                    };
                }
                return new FusionStrategy.Built(e, FusionStrategy.layout(f));
            }

            @Override
            Page page(int size) {
                Block[] blocks = new Block[9];
                for (int k = 0; k < 9; k++) {
                    int m = 7 + k;
                    blocks[k] = FusionStrategy.longs(size, i -> i % m);
                }
                return new Page(blocks);
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[][] f = new long[9][];
                for (int k = 0; k < 9; k++) {
                    f[k] = l(page, k);
                }
                long[] f0 = f[0], f1 = f[1], f2 = f[2], f3 = f[3], f4 = f[4], f5 = f[5], f6 = f[6], f7 = f[7], f8 = f[8];
                int n = page.getPositionCount();
                try (LongBlock.Builder out = bf.newLongBlockBuilder(n)) {
                    for (int p = 0; p < n; p++) {
                        try {
                            long e = Math.addExact(f0[p], f1[p]);
                            e = Math.multiplyExact(e, f2[p]);
                            e = Math.subtractExact(e, f3[p]);
                            e = Math.addExact(e, f4[p]);
                            e = Math.multiplyExact(e, f5[p]);
                            e = Math.subtractExact(e, f6[p]);
                            e = Math.addExact(e, f7[p]);
                            e = Math.multiplyExact(e, f8[p]);
                            out.appendLong(e);
                        } catch (ArithmeticException e) {
                            w.registerException(e);
                            out.appendNull();
                        }
                    }
                    return out.build();
                }
            }

            @Override
            Block handwrittenRaw(BlockFactory bf, Warnings w, Page page) {
                long[][] f = new long[9][];
                for (int k = 0; k < 9; k++) {
                    f[k] = l(page, k);
                }
                long[] f0 = f[0], f1 = f[1], f2 = f[2], f3 = f[3], f4 = f[4], f5 = f[5], f6 = f[6], f7 = f[7], f8 = f[8];
                int n = page.getPositionCount();
                long[] out = new long[n];
                try {
                    for (int p = 0; p < n; p++) {
                        long e = Math.addExact(f0[p], f1[p]);
                        e = Math.multiplyExact(e, f2[p]);
                        e = Math.subtractExact(e, f3[p]);
                        e = Math.addExact(e, f4[p]);
                        e = Math.multiplyExact(e, f5[p]);
                        e = Math.subtractExact(e, f6[p]);
                        e = Math.addExact(e, f7[p]);
                        e = Math.multiplyExact(e, f8[p]);
                        out[p] = e;
                    }
                } catch (ArithmeticException e) {
                    return handwritten(bf, w, page);
                }
                return bf.newLongArrayVector(out, n).asBlock();
            }
        },
        LIT_ADD_MUL_LONG {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.LONG);
                Expression add = new Add(
                    Source.EMPTY,
                    a,
                    new org.elasticsearch.xpack.esql.core.expression.Literal(Source.EMPTY, 7L, DataType.LONG),
                    FusionStrategy.configuration()
                );
                return new FusionStrategy.Built(
                    new Mul(Source.EMPTY, add, new org.elasticsearch.xpack.esql.core.expression.Literal(Source.EMPTY, 3L, DataType.LONG)),
                    FusionStrategy.layout(a)
                );
            }

            @Override
            Page page(int size) {
                return new Page(FusionStrategy.longs(size, i -> i % 1000));
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0);
                int n = page.getPositionCount();
                try (LongBlock.Builder out = bf.newLongBlockBuilder(n)) {
                    for (int p = 0; p < n; p++) {
                        try {
                            out.appendLong(Math.multiplyExact(Math.addExact(a[p], 7L), 3L));
                        } catch (ArithmeticException e) {
                            w.registerException(e);
                            out.appendNull();
                        }
                    }
                    return out.build();
                }
            }
        },
        ADD_MUL_DOUBLE {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.DOUBLE), b = FusionStrategy.field("b", DataType.DOUBLE), c =
                    FusionStrategy.field("c", DataType.DOUBLE);
                return new FusionStrategy.Built(
                    new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, FusionStrategy.configuration()), c),
                    FusionStrategy.layout(a, b, c)
                );
            }

            @Override
            Page page(int size) {
                return new Page(
                    FusionStrategy.doubles(size, i -> i % 1000),
                    FusionStrategy.doubles(size, i -> i % 100),
                    FusionStrategy.doubles(size, i -> i % 10)
                );
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                double[] a = d(page, 0), b = d(page, 1), c = d(page, 2);
                int n = page.getPositionCount();
                try (DoubleBlock.Builder out = bf.newDoubleBlockBuilder(n)) {
                    for (int p = 0; p < n; p++) {
                        try {
                            out.appendDouble(NumericUtils.asFiniteNumber(NumericUtils.asFiniteNumber(a[p] + b[p]) * c[p]));
                        } catch (ArithmeticException e) {
                            w.registerException(e);
                            out.appendNull();
                        }
                    }
                    return out.build();
                }
            }
        },
        ADD_GT_LONG {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.LONG), b = FusionStrategy.field("b", DataType.LONG), c =
                    FusionStrategy.field("c", DataType.LONG);
                Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, FusionStrategy.configuration()), c);
                return new FusionStrategy.Built(expr, FusionStrategy.layout(a, b, c));
            }

            @Override
            Page page(int size) {
                return new Page(
                    FusionStrategy.longs(size, i -> i % 1000),
                    FusionStrategy.longs(size, i -> i % 100),
                    FusionStrategy.longs(size, i -> i % 1013)
                );
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0), b = l(page, 1), c = l(page, 2);
                int n = page.getPositionCount();
                boolean[] out = new boolean[n];
                for (int p = 0; p < n; p++) {
                    out[p] = Math.addExact(a[p], b[p]) > c[p];
                }
                return bf.newBooleanArrayVector(out, n).asBlock();
            }
        },
        AND_CMP_LONG {
            @Override
            Built build() {
                FieldAttribute a = FusionStrategy.field("a", DataType.LONG), b = FusionStrategy.field("b", DataType.LONG);
                FieldAttribute c = FusionStrategy.field("c", DataType.LONG), d = FusionStrategy.field("d", DataType.LONG);
                Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
                return new FusionStrategy.Built(expr, FusionStrategy.layout(a, b, c, d));
            }

            @Override
            Page page(int size) {
                return new Page(
                    FusionStrategy.longs(size, i -> i % 50),
                    FusionStrategy.longs(size, i -> i % 49),
                    FusionStrategy.longs(size, i -> i % 40),
                    FusionStrategy.longs(size, i -> i % 41)
                );
            }

            @Override
            Block handwritten(BlockFactory bf, Warnings w, Page page) {
                long[] a = l(page, 0), b = l(page, 1), c = l(page, 2), d = l(page, 3);
                int n = page.getPositionCount();
                boolean[] out = new boolean[n];
                for (int p = 0; p < n; p++) {
                    out[p] = a[p] > b[p] && c[p] < d[p];
                }
                return bf.newBooleanArrayVector(out, n).asBlock();
            }
        };

        abstract Built build();

        abstract Page page(int size);

        abstract Block handwritten(BlockFactory bf, Warnings w, Page page);

        /**
         * {@link #handwritten} writing into a raw array instead of a block builder; any overflow abandons the page and re-runs
         * it through {@link #handwritten} so results and warnings are identical. Shapes without a raw variant delegate.
         */
        Block handwrittenRaw(BlockFactory bf, Warnings w, Page page) {
            return handwritten(bf, w, page);
        }

        private static long[] l(Page page, int channel) {
            return VectorUnsafe.longs((LongArrayVector) page.getBlock(channel).asVector());
        }

        private static double[] d(Page page, int channel) {
            return VectorUnsafe.doubles((DoubleArrayVector) page.getBlock(channel).asVector());
        }
    }

    record Built(Expression expr, Layout layout) {}

    static final BlockFactory blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("none"))
        .build();

    static final DriverContext driverContext = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, blockFactory, null);

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
                } else if (fused instanceof BooleanBlock l && unfused instanceof BooleanBlock u) {
                    fv = l.getBoolean(fi + v);
                    uv = u.getBoolean(ui + v);
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

    static Page overflowAddMul(int size, int every) {
        return new Page(
            longs(size, i -> i % every == 0 ? Long.MAX_VALUE - 1 : i % 1000),
            longs(size, i -> 2 + i % 100),
            longs(size, i -> i % 10)
        );
    }

    static Block doubles(int size, LongGen gen) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = gen.value(i);
        }
        return blockFactory.newDoubleArrayVector(values, size).asBlock();
    }

    static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(Source.EMPTY, name, new EsField(name, type, Map.of(), true, EsField.TimeSeriesFieldType.NONE));
    }

    static Layout layout(FieldAttribute... fields) {
        Layout.Builder layout = new Layout.Builder();
        layout.append(java.util.Arrays.asList(fields));
        return layout.build();
    }

    static Configuration configuration() {
        return new Configuration(
            java.time.Instant.now(),
            java.util.Locale.ROOT,
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
            ResolvedSettings.EMPTY,
            Map.of()
        );
    }
}
