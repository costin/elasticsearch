/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.FilterEvalOperator;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * C2: build-time A/B verification of fused vs unfused results. A compilerForTests-injected fused class
 * that adds 1 to every {@code Add.processLongs} must fail under verify (the test-JVM default) and pass
 * silently when the comparison helper is forced off. Breaker-to-zero is asserted on both fused operator
 * paths with verify ON.
 */
public class FusionVerifyTests extends FusionDifferentialTestCase {

    /**
     * Kernel body the Stitcher splices in place of {@link Add#processLongs}: same {@code (JJ)J} overflow-checked
     * descriptor, but the result is 1 larger. Used by {@link #plusOneAddCompiler()} so the fused class is still
     * Stitcher-generated (no hand-rolled bytecode) while being deliberately wrong.
     */
    public static long processLongs(long lhs, long rhs) {
        return Math.addExact(Math.addExact(lhs, rhs), 1L);
    }

    @After
    public void resetVerifySkip() {
        FusionVerify.skipForTests = false;
    }

    public void testDeliberatelyWrongFusedClassThrowsAssertionErrorUnderVerify() throws Exception {
        assertThat("verify is on by default in test JVMs (-ea)", FusionSettings.isVerifyEnabled(), is(true));
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        FusionPlanner.compilerForTests = plusOneAddCompiler();
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));
        FusedExpressionEvaluatorFactory fusedFactory = (FusedExpressionEvaluatorFactory) factory;
        assertThat(fusedFactory.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));

        int n = 4;
        long[] av = { 1, 2, 3, 4 };
        long[] bv = { 10, 20, 30, 40 };
        long[] cv = { 2, 2, 2, 2 };
        Page page = longPage(n, av, bv, cv);
        ExpressionEvaluator evaluator = fusedFactory.get(driverContext);
        Block result = null;
        try {
            AssertionError error = expectThrows(AssertionError.class, () -> evaluator.eval(page));
            String message = error.getMessage();
            assertThat(message, containsString("shape="));
            assertThat(message, containsString("strategy=" + VectorStrategy.CHECKED_VECTOR));
            assertThat(message, containsString("path=" + LastEvalPath.RAW));
            assertThat(message, containsString("positionCount=" + n));
            assertThat(message, containsString("firstDiff@0"));
            // Unfused (a+b)*c at p0 = (1+10)*2 = 22; fused (a+b+1)*c = 24.
            assertThat(message, containsString("24"));
            assertThat(message, containsString("22"));
            assertThat(message, containsString("esql-fusion-dumps"));
            Path dump = FusionVerify.dumpPath(fusedFactory.shape());
            assertThat(message, containsString(dump.toString()));
            assertThat("mismatch must dump fused class bytes", Files.isRegularFile(dump), is(true));
            assertThat(Files.size(dump), greaterThan(0L));
        } finally {
            Releasables.closeExpectNoException(result, evaluator);
            page.releaseBlocks();
        }
    }

    public void testDeliberatelyWrongFusedClassPassesWhenVerifyForcedOff() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        FusionPlanner.compilerForTests = plusOneAddCompiler();
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));

        int n = 4;
        Page page = longPage(n, new long[] { 1, 2, 3, 4 }, new long[] { 10, 20, 30, 40 }, new long[] { 2, 2, 2, 2 });
        ExpressionEvaluator evaluator = factory.get(driverContext);
        FusionVerify.skipForTests = true;
        Block result = null;
        try {
            result = evaluator.eval(page);
            assertThat(result, instanceOf(LongBlock.class));
            // Comparison skipped: fused (a+b+1)*c at p0 = 24, not the unfused 22.
            assertThat(((LongBlock) result).getLong(0), is(24L));
            assertThat(FusedExpressionEvaluatorFactory.lastEvalPath(evaluator), is(LastEvalPath.RAW));
        } finally {
            FusionVerify.skipForTests = false;
            Releasables.closeExpectNoException(result, evaluator);
            page.releaseBlocks();
        }
    }

    public void testEvalOperatorBreakerReturnsToZeroWithVerifyOn() {
        assertThat(FusionSettings.isVerifyEnabled(), is(true));
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        ExpressionEvaluator.Factory factory = EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat(factory, instanceOf(FusedExpressionEvaluatorFactory.class));

        int pageSize = 8;
        ExpressionEvaluator evaluator = factory.get(driverContext);
        try (EvalOperator operator = new EvalOperator(driverContext, evaluator)) {
            for (int i = 0; i < 3; i++) {
                Page page = longPage(pageSize, boundedLongs(pageSize), boundedLongs(pageSize), boundedLongs(pageSize));
                operator.addInput(page);
                Page output = operator.getOutput();
                assertThat(output, notNullValue());
                output.releaseBlocks();
            }
            operator.finish();
        }
        assertThat(breaker.getUsed(), is(0L));
    }

    public void testFilterEvalOperatorBreakerReturnsToZeroWithVerifyOn() {
        assertThat(FusionSettings.isVerifyEnabled(), is(true));
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression predicate = new GreaterThan(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
        Expression projection = new Mul(Source.EMPTY, a, b);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);
        FilterEvalEvaluator.Factory factory = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat(factory, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        assertThat(((FusedFilterEvalEvaluatorFactory) factory).fused(), is(true));

        int pageSize = 8;
        FilterEvalEvaluator evaluator = factory.get(driverContext);
        try (FilterEvalOperator operator = new FilterEvalOperator(driverContext, evaluator)) {
            for (int i = 0; i < 3; i++) {
                Page page = longPage(pageSize, boundedLongs(pageSize), boundedLongs(pageSize), boundedLongs(pageSize));
                operator.addInput(page);
                Page output = operator.getOutput();
                if (output != null) {
                    output.releaseBlocks();
                }
            }
            operator.finish();
        }
        assertThat(breaker.getUsed(), is(0L));
    }

    /**
     * Direct {@link FusionVerify#describeMismatch} coverage: encoding fast-path, numeric constant false-positive,
     * NaN vs finite, sign-of-zero, identical blocks, and firstDiff for count/value divergence.
     */
    public void testDescribeMismatchTable() {
        List<Block> toClose = new ArrayList<>();
        try {
            // (a) ordinal-encoded unfused vs array fused, equal values -> lost fast path
            Block ordinal = ordinalBytes(new String[] { "Hello", "World" }, new int[] { 0, 1, 0 });
            toClose.add(ordinal);
            Block ordinalArray = bytesArray("Hello", "World", "Hello");
            toClose.add(ordinalArray);
            String ordinalMismatch = FusionVerify.describeMismatch(ordinalArray, ordinal);
            assertThat(ordinalMismatch, containsString("ordinal-encoded"));
            assertThat(ordinalMismatch, containsString("lost encoding fast path"));
            assertThat(ordinalMismatch, containsString("values equal"));

            // (b) ConstantBytesRefVector unfused vs array fused, equal values -> lost fast path
            Block constantBytes = blockFactory.newConstantBytesRefBlockWith(new BytesRef("Hi"), 4);
            toClose.add(constantBytes);
            Block constantArray = bytesArray("Hi", "Hi", "Hi", "Hi");
            toClose.add(constantArray);
            String constantMismatch = FusionVerify.describeMismatch(constantArray, constantBytes);
            assertThat(constantMismatch, containsString("constant-encoded"));
            assertThat(constantMismatch, containsString("lost encoding fast path"));
            assertThat(constantMismatch, containsString("values equal"));

            // (c) numeric Constant*Vector vs array with equal values -> NOT a mismatch
            Block constLong = blockFactory.newConstantLongVector(7L, 3).asBlock();
            toClose.add(constLong);
            Block arrayLong = blockFactory.newLongArrayVector(new long[] { 7, 7, 7 }, 3).asBlock();
            toClose.add(arrayLong);
            assertThat(FusionVerify.describeMismatch(arrayLong, constLong), nullValue());

            // (d) NaN vs finite
            Block nan = blockFactory.newDoubleArrayVector(new double[] { Double.NaN }, 1).asBlock();
            toClose.add(nan);
            Block finite = blockFactory.newDoubleArrayVector(new double[] { 1.0 }, 1).asBlock();
            toClose.add(finite);
            String nanMismatch = FusionVerify.describeMismatch(nan, finite);
            assertThat(nanMismatch, containsString("firstDiff@0"));
            assertThat(nanMismatch, containsString("positionCount=1"));

            // (e) -0.0 vs +0.0
            Block negZero = blockFactory.newDoubleArrayVector(new double[] { -0.0 }, 1).asBlock();
            toClose.add(negZero);
            Block posZero = blockFactory.newDoubleArrayVector(new double[] { 0.0 }, 1).asBlock();
            toClose.add(posZero);
            String signMismatch = FusionVerify.describeMismatch(negZero, posZero);
            assertThat(signMismatch, containsString("firstDiff@0"));
            assertThat(signMismatch, containsString("positionCount=1"));

            // (f) identical values, two distinct array blocks (and same instance)
            Block left = blockFactory.newLongArrayVector(new long[] { 1, 2, 3 }, 3).asBlock();
            toClose.add(left);
            Block right = blockFactory.newLongArrayVector(new long[] { 1, 2, 3 }, 3).asBlock();
            toClose.add(right);
            assertThat(FusionVerify.describeMismatch(left, right), nullValue());
            assertThat(FusionVerify.describeMismatch(left, left), nullValue());

            // (g) differing positionCount and differing value
            Block shortBlock = blockFactory.newLongArrayVector(new long[] { 1, 2 }, 2).asBlock();
            toClose.add(shortBlock);
            Block longBlock = blockFactory.newLongArrayVector(new long[] { 1, 2, 3 }, 3).asBlock();
            toClose.add(longBlock);
            String countMismatch = FusionVerify.describeMismatch(shortBlock, longBlock);
            assertThat(countMismatch, containsString("positionCount=2 vs 3"));
            assertThat(countMismatch, containsString("firstDiff@"));
            Block v1 = blockFactory.newLongArrayVector(new long[] { 1, 2 }, 2).asBlock();
            toClose.add(v1);
            Block v2 = blockFactory.newLongArrayVector(new long[] { 1, 9 }, 2).asBlock();
            toClose.add(v2);
            String valueMismatch = FusionVerify.describeMismatch(v1, v2);
            assertThat(valueMismatch, containsString("firstDiff@1"));
            assertThat(valueMismatch, containsString("2"));
            assertThat(valueMismatch, containsString("9"));
        } finally {
            Releasables.closeExpectNoException(toClose.toArray(Block[]::new));
        }
    }

    public void testDescribeSurvivorMismatch() {
        assertThat(FusionVerify.describeSurvivorMismatch(new int[] { 0, 2, 5 }, 3, new int[] { 0, 2, 5 }, 3), nullValue());
        String indexMismatch = FusionVerify.describeSurvivorMismatch(new int[] { 0, 2 }, 2, new int[] { 0, 1 }, 2);
        assertThat(indexMismatch, containsString("survivorCount=2 vs 2"));
        assertThat(indexMismatch, containsString("firstDiff@1"));
        assertThat(indexMismatch, containsString("fused=2"));
        assertThat(indexMismatch, containsString("unfused=1"));
        String countMismatch = FusionVerify.describeSurvivorMismatch(new int[] { 0, 1 }, 2, new int[] { 0 }, 1);
        assertThat(countMismatch, containsString("survivorCount=2 vs 1"));
        assertThat(countMismatch, containsString("firstDiff@1"));
    }

    public void testCompareFilterEvalSurvivorMismatchClosesBothAndRestoresPositions() {
        Block fused = blockFactory.newConstantLongBlockWith(1L, 2);
        Block unfused = blockFactory.newConstantLongBlockWith(1L, 2);
        int[] positions = { 0, 1, 99, 99 };
        int[] saved = { 0, 2 };
        AssertionError error = expectThrows(
            AssertionError.class,
            () -> FusionVerify.compareFilterEvalAndReturn(fused, unfused, saved, 2, positions, "filter-eval-survivors")
        );
        assertThat(error.getMessage(), containsString("survivorCount=2 vs 2"));
        assertThat(error.getMessage(), containsString("firstDiff@1"));
        assertThat(error.getMessage(), containsString("fused=2"));
        assertThat(error.getMessage(), containsString("unfused=1"));
        assertThat(error.getMessage(), containsString("esql-fusion-dumps"));
        assertThat("fused survivors restored even on mismatch", positions[0], is(0));
        assertThat("fused survivors restored even on mismatch", positions[1], is(2));
        assertThat(breaker.getUsed(), is(0L));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // compilerForTests: Stitcher-generated fused class whose Add.longs kernel returns lhs+rhs+1
    // ---------------------------------------------------------------------------------------------------------------

    private static FusionPlanner.FusedClassCompiler plusOneAddCompiler() {
        Stitcher real = new Stitcher(new TemplateRegistry());
        return new FusionPlanner.FusedClassCompiler() {
            @Override
            public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileBlockLoop(caller, bumpAddLongs(tree));
            }

            @Override
            public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileVectorLoop(caller, bumpAddLongs(tree));
            }

            @Override
            public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileVectorLoopChecked(caller, bumpAddLongs(tree));
            }

            @Override
            public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileLogicalBlockLoop(caller, bumpAddLongs(tree));
            }

            @Override
            public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileLogicalVectorLoop(caller, bumpAddLongs(tree));
            }

            @Override
            public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
                throws Stitcher.StitchingException {
                return real.compileFilterEvalBlockLoop(caller, bumpAddLongs(predicateTree), bumpAddLongs(projectionTree));
            }

            @Override
            public Class<?> compileMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
                return real.compileMappingBlockLoop(caller, bumpAddLongs(tree));
            }
        };
    }

    /**
     * Replaces every {@link Add#processLongs} kernel with {@link #processLongs} (same {@code (JJ)J} signature, result
     * plus one). Children, constants and the fused method's trailing-parameter list are unchanged, so {@code findStatic}
     * against the original plan's MethodType still binds.
     */
    private static FusionNode bumpAddLongs(FusionNode node) {
        if (node instanceof FusionNode.Kernel kernel) {
            List<FusionNode> children = kernel.children().stream().map(FusionVerifyTests::bumpAddLongs).toList();
            FusionDescriptor descriptor = kernel.descriptor();
            if (descriptor.kernelClass() == Add.class
                && "processLongs".equals(descriptor.kernelMethod())
                && "(JJ)J".equals(descriptor.kernelType())) {
                FusionDescriptor wrong = new FusionDescriptor(
                    FusionVerifyTests.class,
                    "processLongs",
                    "(JJ)J",
                    true,
                    true,
                    "java.lang.ArithmeticException"
                );
                return new FusionNode.Kernel(wrong, children);
            }
            return new FusionNode.Kernel(descriptor, children);
        }
        if (node instanceof FusionNode.Logical logical) {
            return new FusionNode.Logical(logical.op(), bumpAddLongs(logical.left()), bumpAddLongs(logical.right()));
        }
        return node;
    }

    private Block bytesArray(String... values) {
        try (BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(values.length)) {
            for (String value : values) {
                builder.appendBytesRef(new BytesRef(value));
            }
            return builder.build();
        }
    }

    private Block ordinalBytes(String[] dictionary, int[] ordinals) {
        BytesRefVector dict = null;
        IntVector ords = null;
        try (BytesRefVector.Builder dictBuilder = blockFactory.newBytesRefVectorBuilder(dictionary.length)) {
            for (String entry : dictionary) {
                dictBuilder.appendBytesRef(new BytesRef(entry));
            }
            dict = dictBuilder.build();
            ords = blockFactory.newIntArrayVector(ordinals, ordinals.length);
            Block block = new OrdinalBytesRefVector(ords, dict).asBlock();
            dict = null;
            ords = null;
            return block;
        } finally {
            Releasables.closeExpectNoException(dict, ords);
        }
    }

    private Page longPage(int positionCount, long[] a, long[] b, long[] c) {
        return new Page(
            blockFactory.newLongArrayVector(a, positionCount).asBlock(),
            blockFactory.newLongArrayVector(b, positionCount).asBlock(),
            blockFactory.newLongArrayVector(c, positionCount).asBlock()
        );
    }

    private long[] boundedLongs(int positionCount) {
        long[] values = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            values[p] = randomLongBetween(-1000, 1000);
        }
        return values;
    }
}
