/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.test.ComputeTestCase;

import static org.hamcrest.Matchers.equalTo;

/**
 * Breaker accounting for {@link FilterEvalOperator}. A fused evaluator's {@code baseRamBytesUsed()}
 * can grow after construction (adaptive switch), so close must release exactly what the constructor
 * reserved.
 */
public class FilterEvalOperatorTests extends ComputeTestCase {

    /**
     * Mimics an adaptive {@link FilterEvalEvaluator}: {@link #baseRamBytesUsed()} is small until the
     * first {@link #evalProjection}, then grows as if a fused evaluator had been materialised mid-query.
     * Every row survives and the projection is an extra ref of channel 0.
     */
    static final class GrowingRamFilterEvalEvaluator implements FilterEvalEvaluator {
        static final long RAM_BEFORE_EVAL = 16L;
        static final long RAM_AFTER_EVAL = 1024L;

        private boolean grown;

        @Override
        public Block evalProjection(Page page, int[] positions) {
            grown = true;
            int count = page.getPositionCount();
            for (int i = 0; i < count; i++) {
                positions[i] = i;
            }
            Block block = page.getBlock(0);
            block.incRef();
            return block;
        }

        @Override
        public long baseRamBytesUsed() {
            return grown ? RAM_AFTER_EVAL : RAM_BEFORE_EVAL;
        }

        @Override
        public void close() {}
    }

    /**
     * An evaluator whose reported RAM grows after {@code evalProjection} must not make
     * {@link FilterEvalOperator#close()} release more than it reserved, or the breaker drifts negative.
     */
    public void testBreakerReturnsToZeroWhenEvaluatorRamGrows() {
        BlockFactory blockFactory = blockFactory();
        DriverContext ctx = new DriverContext(blockFactory.bigArrays(), blockFactory, null);
        GrowingRamFilterEvalEvaluator evaluator = new GrowingRamFilterEvalEvaluator();
        assertThat(evaluator.baseRamBytesUsed(), equalTo(GrowingRamFilterEvalEvaluator.RAM_BEFORE_EVAL));
        try (FilterEvalOperator operator = new FilterEvalOperator(ctx, evaluator)) {
            for (int i = 0; i < 3; i++) {
                Page page = new Page(blockFactory.newConstantLongBlockWith(i, 4));
                operator.addInput(page);
                Page output = operator.getOutput();
                assertNotNull(output);
                output.releaseBlocks();
            }
            operator.finish();
            assertThat(evaluator.baseRamBytesUsed(), equalTo(GrowingRamFilterEvalEvaluator.RAM_AFTER_EVAL));
        }
        assertThat(ctx.breaker().getUsed(), equalTo(0L));
    }
}
