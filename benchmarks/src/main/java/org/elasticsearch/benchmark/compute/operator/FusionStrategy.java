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
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;

import static org.elasticsearch.benchmark.compute.operator.SlowPathFusionBenchmark.Built;

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

    private static final FoldContext FOLD = FoldContext.small();

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
            Warnings warnings = Warnings.createWarnings(context.warningsMode(), Source.EMPTY);
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
}
