/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.VectorUnsafe;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.util.NumericUtils;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;

import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.Built;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.Case;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.configuration;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.doubles;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.field;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.layout;
import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.longs;

/**
 * The common expressions used by the fusion-strategy PoC, each with a dense page generator and a HAND-WRITTEN fused
 * loop (the ceiling: what javac + the JIT do with a hand-fused loop over raw arrays, same output contract as the spliced
 * checked/plain vector paths).
 */
public enum PocShape {
    ADD_MUL_LONG {
        @Override
        Built build() {
            return Case.NULLS_ADD_MUL_LONG.build();
        }

        @Override
        Page page(int size) {
            return new Page(longs(size, i -> i % 1000), longs(size, i -> i % 100), longs(size, i -> i % 10));
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
            return Case.DENSE_ADD_MUL_SUB_LONG.build();
        }

        @Override
        Page page(int size) {
            return Case.DENSE_ADD_MUL_SUB_LONG.page(size);
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
            return Case.DENSE_CHAIN8_LONG.build();
        }

        @Override
        Page page(int size) {
            return Case.DENSE_CHAIN8_LONG.page(size);
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
            return Case.LIT_ADD_MUL_LONG.build();
        }

        @Override
        Page page(int size) {
            return Case.LIT_ADD_MUL_LONG.page(size);
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
            return Case.NULLS_ADD_MUL_DOUBLE.build();
        }

        @Override
        Page page(int size) {
            return new Page(doubles(size, i -> i % 1000), doubles(size, i -> i % 100), doubles(size, i -> i % 10));
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
            FieldAttribute a = field("a", DataType.LONG), b = field("b", DataType.LONG), c = field("c", DataType.LONG);
            Expression expr = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, configuration()), c);
            return new Built(expr, layout(a, b, c));
        }

        @Override
        Page page(int size) {
            return new Page(longs(size, i -> i % 1000), longs(size, i -> i % 100), longs(size, i -> i % 1013));
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
            FieldAttribute a = field("a", DataType.LONG), b = field("b", DataType.LONG);
            FieldAttribute c = field("c", DataType.LONG), d = field("d", DataType.LONG);
            Expression expr = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, c, d));
            return new Built(expr, layout(a, b, c, d));
        }

        @Override
        Page page(int size) {
            return new Page(longs(size, i -> i % 50), longs(size, i -> i % 49), longs(size, i -> i % 40), longs(size, i -> i % 41));
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
