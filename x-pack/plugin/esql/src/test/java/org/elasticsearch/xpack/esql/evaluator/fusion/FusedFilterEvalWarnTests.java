/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.FilterOperator;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.test.CannedSourceOperator;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Iter5 I4: a filter-eval predicate or projection that carries a BytesRef/{@code 'L'} constant must not WARN on every
 * plan (or miscount stitch failures). The planner refuses those shapes silently — the single-expression path already
 * handles {@code 'L'} constants — and any residual non-{@link Stitcher.StitchingException} in
 * {@link FusedFilterEvalEvaluatorFactory#create} WARNs at most once per shape.
 */
public class FusedFilterEvalWarnTests extends FusionDifferentialTestCase {

    @Before
    public void clearFilterEvalFailedShapes() {
        FusedFilterEvalEvaluatorFactory.clearFailedShapesForTests();
    }

    @After
    public void restoreCompilerAndFailedShapes() {
        FusionPlanner.compilerForTests = null;
        FusedFilterEvalEvaluatorFactory.clearFailedShapesForTests();
    }

    /**
     * {@code WHERE k == "x" | EVAL a + b}: a keyword predicate with a BytesRef literal. Planned three times this must
     * emit zero {@code fusion stitch failed} WARNs and stay on the ordinary unfused {@code Filter → Eval} chain.
     */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedFilterEvalEvaluatorFactory:DEBUG",
        reason = "assert a BytesRef-literal filter-eval predicate emits zero stitch-failed WARNs"
    )
    public void testKeywordLiteralPredicateRefusesSilentlyAndMatchesUnfused() {
        FieldAttribute k = field("k", DataType.KEYWORD);
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Layout layout = layout(k, a, b);
        Expression predicate = new Equals(Source.EMPTY, k, keywordLit("x"));
        Expression projection = new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);

        long failuresBefore = FusionTelemetry.totalFailures();
        FilterEvalEvaluator.Factory factory;
        try (var mockLog = MockLog.capture(FusedFilterEvalEvaluatorFactory.class)) {
            mockLog.addExpectation(
                new MockLog.UnseenEventExpectation(
                    "no fusion stitch failed WARN for a BytesRef-literal filter-eval predicate",
                    FusedFilterEvalEvaluatorFactory.class.getCanonicalName(),
                    Level.WARN,
                    "*fusion stitch failed*"
                )
            );
            factory = null;
            for (int i = 0; i < 3; i++) {
                factory = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
            }
            mockLog.assertAllExpectationsMatched();
        }
        assertThat("WHERE k==\"x\" EVAL a+b must refuse filter-eval fusion silently", factory, nullValue());
        assertThat("a silent plan-time refusal must not count as a stitch failure", FusionTelemetry.totalFailures(), is(failuresBefore));

        Block kBlock = keywordBlock("x", "y", "x");
        Block aBlock = longBlock(1L, 2L, 3L);
        Block bBlock = longBlock(10L, 20L, 30L);
        Page source = new Page(3, kBlock, aBlock, bBlock);
        Page unfusedIn = CannedSourceOperator.deepCopyOf(blockFactory, List.of(source)).get(0);
        source.releaseBlocks();
        Page unfusedOut = null;
        try {
            unfusedOut = runFilterThenEval(unfusedFactory(predicate, layout), unfusedFactory(projection, layout), unfusedIn);
            unfusedIn = null;
            assertThat("k==\"x\" keeps two rows", unfusedOut.getPositionCount(), is(2));
            LongBlock projected = (LongBlock) unfusedOut.getBlock(unfusedOut.getBlockCount() - 1);
            assertThat(projected.getLong(projected.getFirstValueIndex(0)), is(11L));
            assertThat(projected.getLong(projected.getFirstValueIndex(1)), is(33L));
        } finally {
            if (unfusedOut != null) {
                unfusedOut.releaseBlocks();
            }
            if (unfusedIn != null) {
                unfusedIn.releaseBlocks();
            }
        }
    }

    /**
     * An injected {@link IllegalArgumentException} from {@code compileFilterEvalBlockLoop}, planned three times, WARNs
     * exactly once on the filter-eval factory logger (repeats stay DEBUG via the per-shape first-failure flag).
     */
    @TestLogging(
        value = "org.elasticsearch.xpack.esql.evaluator.fusion.FusedFilterEvalEvaluatorFactory:DEBUG",
        reason = "assert a residual IAE in filter-eval create WARNs once then DEBUG on repeats"
    )
    public void testInjectedIllegalArgumentExceptionWarnsOnce() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression projection = new Mul(Source.EMPTY, a, b);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);

        FusionPlanner.compilerForTests = throwingIaeFilterEvalCompiler();
        try (var mockLog = MockLog.capture(FusedFilterEvalEvaluatorFactory.class)) {
            mockLog.addExpectation(exactlyOneUnfusedWarn());
            for (int i = 0; i < 3; i++) {
                FilterEvalEvaluator.Factory candidate = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
                assertThat(candidate, instanceOf(FusedFilterEvalEvaluatorFactory.class));
                assertThat(
                    "an injected IAE must fall back to the unfused pair, not abort planning",
                    ((FusedFilterEvalEvaluatorFactory) candidate).fused(),
                    is(false)
                );
            }
            mockLog.assertAllExpectationsMatched();
        } finally {
            FusionPlanner.compilerForTests = null;
        }
    }

    private static FusionPlanner.FusedClassCompiler throwingIaeFilterEvalCompiler() {
        return new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree) {
                throw new IllegalArgumentException("injected filter-eval IAE");
            }
        };
    }

    private Page runFilterThenEval(ExpressionEvaluator.Factory predFactory, ExpressionEvaluator.Factory projFactory, Page page) {
        Page filtered;
        try (FilterOperator filter = new FilterOperator(predFactory.get(driverContext))) {
            filter.addInput(page);
            filter.finish();
            filtered = filter.getOutput();
        }
        if (filtered == null) {
            return null;
        }
        try (EvalOperator eval = new EvalOperator(driverContext, projFactory.get(driverContext))) {
            eval.addInput(filtered);
            eval.finish();
            return eval.getOutput();
        }
    }

    private Block keywordBlock(String... values) {
        try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(values.length)) {
            for (String value : values) {
                builder.appendBytesRef(new BytesRef(value));
            }
            return builder.build();
        }
    }

    private Block longBlock(long... values) {
        return blockFactory.newLongArrayVector(values, values.length).asBlock();
    }

    private static Literal keywordLit(String value) {
        return new Literal(Source.EMPTY, new BytesRef(value), DataType.KEYWORD);
    }

    private static MockLog.LoggingExpectation exactlyOneUnfusedWarn() {
        return new ExactlyOnceWarnExpectation(
            "exactly one unfused WARN at FusedFilterEvalEvaluatorFactory",
            FusedFilterEvalEvaluatorFactory.class.getCanonicalName(),
            "*unfused*"
        );
    }

    /** Counts WARN events matching {@code message} and asserts the count is exactly 1. */
    private static final class ExactlyOnceWarnExpectation implements MockLog.LoggingExpectation {
        private final String name;
        private final String logger;
        private final String message;
        private final AtomicInteger count = new AtomicInteger();

        ExactlyOnceWarnExpectation(String name, String logger, String message) {
            this.name = name;
            this.logger = logger;
            this.message = message;
        }

        @Override
        public void match(LogEvent event) {
            if (event.getLevel() == Level.WARN && event.getLoggerName().equals(logger)) {
                String formatted = event.getMessage().getFormattedMessage();
                boolean matched = Regex.isSimpleMatchPattern(message) ? Regex.simpleMatch(message, formatted) : formatted.contains(message);
                if (matched) {
                    count.incrementAndGet();
                }
            }
        }

        @Override
        public void assertMatched() {
            assertThat(name + ": expected exactly 1 WARN", count.get(), is(1));
        }
    }
}
