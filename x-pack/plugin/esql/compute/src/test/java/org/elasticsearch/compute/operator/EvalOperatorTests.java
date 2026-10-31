/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.EvalOperator.EvalOperatorFactory;
import org.elasticsearch.compute.test.OperatorTestCase;
import org.elasticsearch.compute.test.TestDriverRunner;
import org.elasticsearch.compute.test.operator.blocksource.TupleLongLongBlockSourceOperator;
import org.elasticsearch.core.Tuple;
import org.hamcrest.Matcher;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class EvalOperatorTests extends OperatorTestCase {
    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int end) {
        return new TupleLongLongBlockSourceOperator(blockFactory, LongStream.range(0, end).mapToObj(l -> Tuple.tuple(l, end - l)));
    }

    record Addition(DriverContext driverContext, int lhs, int rhs) implements ExpressionEvaluator {
        @Override
        public Block eval(Page page) {
            LongVector lhsVector = page.<LongBlock>getBlock(0).asVector();
            LongVector rhsVector = page.<LongBlock>getBlock(1).asVector();
            try (LongVector.FixedBuilder result = driverContext.blockFactory().newLongVectorFixedBuilder(page.getPositionCount())) {
                for (int p = 0; p < page.getPositionCount(); p++) {
                    result.appendLong(lhsVector.getLong(p) + rhsVector.getLong(p));
                }
                return result.build().asBlock();
            }
        }

        @Override
        public long baseRamBytesUsed() {
            return 1;
        }

        @Override
        public String toString() {
            return "Addition[lhs=" + lhs + ", rhs=" + rhs + ']';
        }

        @Override
        public void close() {}
    }

    record LoadFromPage(int channel) implements ExpressionEvaluator {
        @Override
        public Block eval(Page page) {
            Block block = page.getBlock(channel);
            block.incRef();
            return block;
        }

        @Override
        public long baseRamBytesUsed() {
            return 2;
        }

        @Override
        public void close() {}
    }

    /**
     * Mimics an adaptive evaluator: {@link #baseRamBytesUsed()} is small until the first
     * {@link #eval(Page)}, then grows as if a fused evaluator had been materialised mid-query.
     */
    static final class GrowingRamEvaluator implements ExpressionEvaluator {
        static final long RAM_BEFORE_EVAL = 16L;
        static final long RAM_AFTER_EVAL = 1024L;

        private boolean grown;

        @Override
        public Block eval(Page page) {
            grown = true;
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

    @Override
    protected Operator.OperatorFactory simple(SimpleOptions options) {
        return new EvalOperator.EvalOperatorFactory(new ExpressionEvaluator.Factory() {
            @Override
            public ExpressionEvaluator get(DriverContext context) {
                return new Addition(context, 0, 1);
            }

            @Override
            public String toString() {
                return "Addition[lhs=0, rhs=1]";
            }
        });
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return equalTo("EvalOperator[evaluator=Addition[lhs=0, rhs=1]]");
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return expectedDescriptionOfSimple();
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        final int positions = input.stream().map(page -> page.<Block>getBlock(0)).mapToInt(Block::getPositionCount).sum();
        final int expectedValue = positions;
        final int resultChannel = 2;
        for (var page : results) {
            LongBlock lb = page.getBlock(resultChannel);
            IntStream.range(0, lb.getPositionCount()).forEach(pos -> assertEquals(expectedValue, lb.getLong(pos)));
        }
    }

    /**
     * {@link EvalOperator#toString()} is read by the {@link Driver} on every status update. Verify
     * that the same {@code String} instance is returned across calls so the evaluator-tree
     * description is computed at most once per operator.
     */
    public void testToStringIsCached() {
        DriverContext ctx = driverContext();
        try (EvalOperator op = new EvalOperator(ctx, new Addition(ctx, 4, 9))) {
            String first = op.toString();
            String second = op.toString();
            assertThat(first, equalTo("EvalOperator[evaluator=Addition[lhs=4, rhs=9]]"));
            assertThat(second, sameInstance(first));
        }
        assertThat(ctx.breaker().getUsed(), equalTo(0L));
    }

    public void testReadFromBlock() {
        var runner = new TestDriverRunner().builder(driverContext());
        runner.input(simpleInput(runner.blockFactory(), 10));
        List<Page> results = runner.run(new EvalOperatorFactory(dvrCtx -> new LoadFromPage(0)));
        Set<Long> found = new TreeSet<>();
        for (var page : results) {
            LongBlock lb = page.getBlock(2);
            IntStream.range(0, lb.getPositionCount()).forEach(pos -> found.add(lb.getLong(pos)));
        }
        assertThat(found, equalTo(LongStream.range(0, 10).mapToObj(Long::valueOf).collect(Collectors.toSet())));
        results.forEach(Page::releaseBlocks);
        assertThat(runner.context().breaker().getUsed(), equalTo(0L));
    }

    /**
     * An evaluator whose reported RAM grows after {@code eval} (the adaptive-fusion switch) must not
     * make {@link EvalOperator#close()} release more than it reserved, or the breaker drifts negative.
     */
    public void testBreakerReturnsToZeroWhenEvaluatorRamGrows() {
        DriverContext ctx = driverContext();
        GrowingRamEvaluator evaluator = new GrowingRamEvaluator();
        assertThat(evaluator.baseRamBytesUsed(), equalTo(GrowingRamEvaluator.RAM_BEFORE_EVAL));
        try (EvalOperator operator = new EvalOperator(ctx, evaluator)) {
            for (int i = 0; i < 3; i++) {
                Page page = new Page(ctx.blockFactory().newConstantLongBlockWith(i, 4));
                operator.addInput(page);
                Page output = operator.getOutput();
                assertNotNull(output);
                output.releaseBlocks();
            }
            operator.finish();
            assertThat(evaluator.baseRamBytesUsed(), equalTo(GrowingRamEvaluator.RAM_AFTER_EVAL));
        }
        assertThat(ctx.breaker().getUsed(), equalTo(0L));
    }
}
