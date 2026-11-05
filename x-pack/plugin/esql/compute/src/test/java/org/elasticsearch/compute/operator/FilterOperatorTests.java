/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.compute.test.OperatorTestCase;
import org.elasticsearch.compute.test.TestDriverRunner;
import org.elasticsearch.compute.test.operator.blocksource.SequenceBooleanBlockSourceOperator;
import org.elasticsearch.compute.test.operator.blocksource.TupleLongLongBlockSourceOperator;
import org.elasticsearch.core.Tuple;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class FilterOperatorTests extends OperatorTestCase {
    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int end) {
        return new TupleLongLongBlockSourceOperator(blockFactory, LongStream.range(0, end).mapToObj(l -> Tuple.tuple(l, end - l)));
    }

    record SameLastDigit(DriverContext context, int lhs, int rhs) implements ExpressionEvaluator {
        @Override
        public Block eval(Page page) {
            LongVector lhsVector = page.<LongBlock>getBlock(0).asVector();
            LongVector rhsVector = page.<LongBlock>getBlock(1).asVector();
            BooleanVector.FixedBuilder result = context.blockFactory().newBooleanVectorFixedBuilder(page.getPositionCount());
            for (int p = 0; p < page.getPositionCount(); p++) {
                result.appendBoolean(lhsVector.getLong(p) % 10 == rhsVector.getLong(p) % 10);
            }
            return result.build().asBlock();
        }

        @Override
        public long baseRamBytesUsed() {
            return 0;
        }

        @Override
        public String toString() {
            return "SameLastDigit[lhs=" + lhs + ", rhs=" + rhs + ']';
        }

        @Override
        public void close() {}
    }

    @Override
    protected Operator.OperatorFactory simple(SimpleOptions options) {
        return new FilterOperator.FilterOperatorFactory(new ExpressionEvaluator.Factory() {

            @Override
            public ExpressionEvaluator get(DriverContext context) {
                return new SameLastDigit(context, 0, 1);
            }

            @Override
            public String toString() {
                return "SameLastDigit[lhs=0, rhs=1]";
            }
        });
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return equalTo("FilterOperator[evaluator=SameLastDigit[lhs=0, rhs=1]]");
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return expectedDescriptionOfSimple();
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        int expectedCount = 0;
        for (var page : input) {
            LongVector lhs = page.<LongBlock>getBlock(0).asVector();
            LongVector rhs = page.<LongBlock>getBlock(1).asVector();
            for (int p = 0; p < page.getPositionCount(); p++) {
                if (lhs.getLong(p) % 10 == rhs.getLong(p) % 10) {
                    expectedCount++;
                }
            }
        }
        int actualCount = 0;
        for (var page : results) {
            LongVector lhs = page.<LongBlock>getBlock(0).asVector();
            LongVector rhs = page.<LongBlock>getBlock(1).asVector();
            for (int p = 0; p < page.getPositionCount(); p++) {
                assertThat(lhs.getLong(p) % 10, equalTo(rhs.getLong(p) % 10));
                actualCount++;
            }
        }
        assertThat(actualCount, equalTo(expectedCount));
    }

    public void testNoResults() {
        assertSimple(driverContext(), 3);
    }

    /**
     * {@link FilterOperator#toString()} is read by the {@link Driver} on every status update.
     * Recomputing the (often deep) evaluator-tree string each time can dominate CPU. Verify that
     * the same {@code String} instance is returned across calls so the description is computed
     * at most once per operator.
     */
    public void testToStringIsCached() {
        DriverContext ctx = driverContext();
        try (FilterOperator op = new FilterOperator(new SameLastDigit(ctx, 3, 7))) {
            String first = op.toString();
            String second = op.toString();
            assertThat(first, equalTo("FilterOperator[evaluator=SameLastDigit[lhs=3, rhs=7]]"));
            assertThat(second, sameInstance(first));
        }
    }

    public void testReadFromBlock() {
        var runner = new TestDriverRunner().builder(driverContext());
        runner.input(new SequenceBooleanBlockSourceOperator(runner.blockFactory(), List.of(true, false, true, false)));
        List<Page> results = runner.run(new FilterOperator.FilterOperatorFactory(dvrCtx -> new EvalOperatorTests.LoadFromPage(0)));
        List<Boolean> found = new ArrayList<>();
        for (var page : results) {
            BooleanVector lb = page.<BooleanBlock>getBlock(0).asVector();
            IntStream.range(0, lb.getPositionCount()).forEach(pos -> found.add(lb.getBoolean(pos)));
        }
        assertThat(found, equalTo(List.of(true, true)));
        results.forEach(Page::releaseBlocks);
        assertThat(runner.context().breaker().getUsed(), equalTo(0L));
    }

    /**
     * Selects the same rows as {@link SameLastDigit} but writes the positions itself, so {@link FilterOperator} must
     * take its {@link PositionsEvaluator#filterPositions} path: {@link #eval} fails the test if it is ever called. Each
     * call registers one warning so a test can see that warnings registered while selecting reach the driver.
     */
    static final class SameLastDigitPositions implements PositionsEvaluator {
        private final Warnings warnings;
        private int calls;

        SameLastDigitPositions(DriverContext context) {
            this.warnings = Warnings.createWarnings(context, new WarningSourceLocation() {
                @Override
                public int lineNumber() {
                    return 1;
                }

                @Override
                public int columnNumber() {
                    return 1;
                }

                @Override
                public String viewName() {
                    return null;
                }

                @Override
                public String text() {
                    return "same_last_digit";
                }
            });
        }

        @Override
        public int filterPositions(Page page, int[] positions) {
            calls++;
            warnings.registerException(IllegalArgumentException.class, "selected through positions");
            LongVector lhs = page.<LongBlock>getBlock(0).asVector();
            LongVector rhs = page.<LongBlock>getBlock(1).asVector();
            int count = 0;
            for (int p = 0; p < page.getPositionCount(); p++) {
                if (lhs.getLong(p) % 10 == rhs.getLong(p) % 10) {
                    positions[count++] = p;
                }
            }
            return count;
        }

        @Override
        public Block eval(Page page) {
            throw new AssertionError("FilterOperator must select through filterPositions, not eval");
        }

        @Override
        public long baseRamBytesUsed() {
            return 0;
        }

        @Override
        public void close() {}
    }

    /** A positions evaluator is used instead of eval; the selected rows and the registered warnings reach the output. */
    public void testPositionsEvaluatorSelectsWithoutEval() {
        DriverContext context = driverContext();
        BlockFactory blockFactory = context.blockFactory();
        SameLastDigitPositions evaluator = new SameLastDigitPositions(context);
        try (FilterOperator operator = new FilterOperator(evaluator)) {
            Page page = new Page(
                blockFactory.newLongArrayVector(new long[] { 1, 12, 23, 34, 5 }, 5).asBlock(),
                blockFactory.newLongArrayVector(new long[] { 11, 2, 13, 4, 6 }, 5).asBlock()
            );
            operator.addInput(page);
            Page out = operator.getOutput();
            try {
                LongVector lhs = out.<LongBlock>getBlock(0).asVector();
                List<Long> selected = new ArrayList<>();
                for (int p = 0; p < out.getPositionCount(); p++) {
                    selected.add(lhs.getLong(p));
                }
                assertThat(selected, contains(1L, 12L, 23L, 34L));
            } finally {
                out.releaseBlocks();
            }
        }
        assertThat(evaluator.calls, equalTo(1));
        List<String> warnings = collectWarnings(context);
        assertThat(warnings.toString(), warnings, hasSize(2));
        assertThat(warnings.get(1), containsString("selected through positions"));
    }

    /** Every row selected: the output shares the input's blocks (a shallow copy), and nothing leaks. */
    public void testPositionsAllSelectedIsShallowCopy() {
        DriverContext context = driverContext();
        BlockFactory blockFactory = context.blockFactory();
        try (FilterOperator operator = new FilterOperator(new SameLastDigitPositions(context))) {
            Block lhs = blockFactory.newLongArrayVector(new long[] { 1, 2, 3 }, 3).asBlock();
            Block rhs = blockFactory.newLongArrayVector(new long[] { 11, 22, 33 }, 3).asBlock();
            operator.addInput(new Page(lhs, rhs));
            Page out = operator.getOutput();
            try {
                assertThat(out.getPositionCount(), equalTo(3));
                assertThat(out.getBlock(0), sameInstance(lhs));
                assertThat(out.getBlock(1), sameInstance(rhs));
            } finally {
                out.releaseBlocks();
            }
        }
        assertThat(collectWarnings(context), hasSize(2));
    }

    /** No row selected: no output page, and the input is released. */
    public void testPositionsNoneSelectedIsNull() {
        DriverContext context = driverContext();
        BlockFactory blockFactory = context.blockFactory();
        try (FilterOperator operator = new FilterOperator(new SameLastDigitPositions(context))) {
            operator.addInput(
                new Page(
                    blockFactory.newLongArrayVector(new long[] { 1, 2, 3 }, 3).asBlock(),
                    blockFactory.newLongArrayVector(new long[] { 4, 5, 6 }, 3).asBlock()
                )
            );
            assertThat(operator.getOutput(), nullValue());
        }
        assertThat(collectWarnings(context), hasSize(2));
    }

    /**
     * {@link PositionsEvaluator#selectedPositions} on a block: exactly-one-{@code true} positions are selected, while
     * {@code false}, {@code null} and multivalued positions (even all-{@code true} ones) are not.
     */
    public void testSelectedPositionsOnBlockSkipsNullsAndMultivalues() {
        BlockFactory blockFactory = blockFactory();
        try (BooleanBlock.Builder builder = blockFactory.newBooleanBlockBuilder(7)) {
            builder.appendBoolean(true);
            builder.appendBoolean(false);
            builder.appendNull();
            builder.beginPositionEntry().appendBoolean(true).appendBoolean(true).endPositionEntry();
            builder.beginPositionEntry().appendBoolean(true).appendBoolean(false).endPositionEntry();
            builder.appendBoolean(true);
            builder.appendNull();
            try (BooleanBlock mask = builder.build()) {
                assertThat(selected(mask), equalTo(new int[] { 0, 5 }));
            }
        }
    }

    /** {@link PositionsEvaluator#selectedPositions} on vectors (array and constant) and all-null blocks. */
    public void testSelectedPositionsOnVectorsAndAllNull() {
        BlockFactory blockFactory = blockFactory();
        try (BooleanBlock mask = blockFactory.newBooleanArrayVector(new boolean[] { false, true, true, false, true }, 5).asBlock()) {
            assertThat(selected(mask), equalTo(new int[] { 1, 2, 4 }));
        }
        try (BooleanBlock mask = blockFactory.newConstantBooleanBlockWith(true, 4)) {
            assertThat(selected(mask), equalTo(new int[] { 0, 1, 2, 3 }));
        }
        try (BooleanBlock mask = blockFactory.newConstantBooleanBlockWith(false, 4)) {
            assertThat(selected(mask), equalTo(new int[0]));
        }
        try (Block mask = blockFactory.newConstantNullBlock(4)) {
            assertThat(selected((BooleanBlock) mask), equalTo(new int[0]));
        }
        try (BooleanBlock mask = blockFactory.newBooleanArrayVector(new boolean[0], 0).asBlock()) {
            assertThat(selected(mask), equalTo(new int[0]));
        }
    }

    /** The scan path over a predicate block with nulls and multivalues, end to end through the operator. */
    public void testReadFromBlockWithNullsAndMultivalues() {
        DriverContext context = driverContext();
        BlockFactory blockFactory = context.blockFactory();
        BooleanBlock mask;
        try (BooleanBlock.Builder builder = blockFactory.newBooleanBlockBuilder(5)) {
            builder.appendBoolean(true);
            builder.appendNull();
            builder.beginPositionEntry().appendBoolean(true).appendBoolean(true).endPositionEntry();
            builder.appendBoolean(false);
            builder.appendBoolean(true);
            mask = builder.build();
        }
        Block ids = blockFactory.newLongArrayVector(new long[] { 0, 1, 2, 3, 4 }, 5).asBlock();
        try (FilterOperator operator = new FilterOperator(new EvalOperatorTests.LoadFromPage(0))) {
            operator.addInput(new Page(mask, ids));
            Page out = operator.getOutput();
            try {
                LongVector selected = out.<LongBlock>getBlock(1).asVector();
                assertThat(selected.getPositionCount(), equalTo(2));
                assertThat(selected.getLong(0), equalTo(0L));
                assertThat(selected.getLong(1), equalTo(4L));
            } finally {
                out.releaseBlocks();
            }
        }
    }

    private static int[] selected(BooleanBlock mask) {
        int[] positions = new int[mask.getPositionCount()];
        int count = PositionsEvaluator.selectedPositions(mask, positions);
        return Arrays.copyOf(positions, count);
    }
}
