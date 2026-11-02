/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.UnicodeUtil;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.FilterEvalOperator;
import org.elasticsearch.compute.operator.FilterOperator;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionDump;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.compute.test.CannedSourceOperator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * H1: a fused BLOCK loop that reads two BytesRef columns into one shared spare BytesRef silently overwrites the
 * first operand. {@code k1 == LEFT(k2, 2)} then computes {@code k2 == LEFT(k2, 2)}. These differentials pin the
 * per-column spare: the three two-column shapes must still fuse, and fused values must match the unfused chain
 * over plain / ordinal / constant / null / multi-value pages, including the FILTER-EVAL path.
 */
public class FusionBytesRefAliasingTests extends FusionDifferentialTestCase {

    private static final String[] K1_EQ_LEFT = { "ab", "xy", "ab", "zz" };
    private static final String[] K2_EQ_LEFT = { "abc", "xyz", "zzz", "zzq" };

    /** Chosen so {@code k1 != RIGHT(k2,1)} differs from the aliased {@code k2 != RIGHT(k2,1)}. */
    private static final String[] K1_NE_RIGHT = { "c", "xy", "z", "zz" };
    private static final String[] K2_NE_RIGHT = { "abc", "xyz", "zzz", "zzq" };

    private static Literal intLit(int v) {
        return new Literal(Source.EMPTY, v, DataType.INTEGER);
    }

    private static Expression equalsLeft(FieldAttribute k1, FieldAttribute k2) {
        return new Equals(Source.EMPTY, k1, new Left(Source.EMPTY, k2, intLit(2)));
    }

    private static Expression notEqualsRight(FieldAttribute k1, FieldAttribute k2) {
        return new NotEquals(Source.EMPTY, k1, new Right(Source.EMPTY, k2, intLit(1)));
    }

    private static Expression equalsLeftLeft(FieldAttribute k1, FieldAttribute k2) {
        return new Equals(Source.EMPTY, new Left(Source.EMPTY, k1, intLit(1)), new Left(Source.EMPTY, k2, intLit(1)));
    }

    private static Expression equalsTwoColumns(FieldAttribute k1, FieldAttribute k2) {
        return new Equals(Source.EMPTY, k1, k2);
    }

    private static Expression notEqualsTwoColumns(FieldAttribute k1, FieldAttribute k2) {
        return new NotEquals(Source.EMPTY, k1, k2);
    }

    private static Expression equalsTrim(FieldAttribute k1, FieldAttribute k2) {
        return new Equals(Source.EMPTY, k1, new Trim(Source.EMPTY, k2));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // BLOCK path: fuse + fused-vs-unfused parity over every BytesRef encoding
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code k1 == LEFT(k2, 2)} over keyword columns. A shared spare computes {@code k2 == LEFT(k2, 2)} (all false
     * on the pinned page); per-column spares match the unfused chain: true, true, false, true.
     */
    public void testEqualsLeftOfOtherColumn() {
        assertBlockShape("k1 == LEFT(k2, 2)", FusionBytesRefAliasingTests::equalsLeft, K1_EQ_LEFT, K2_EQ_LEFT);
    }

    /**
     * {@code k1 != RIGHT(k2, 1)} over keyword columns. A shared spare computes {@code k2 != RIGHT(k2, 1)};
     * per-column spares match the unfused chain on a page where those two answers diverge.
     */
    public void testNotEqualsRightOfOtherColumn() {
        assertBlockShape("k1 != RIGHT(k2, 1)", FusionBytesRefAliasingTests::notEqualsRight, K1_NE_RIGHT, K2_NE_RIGHT);
    }

    /**
     * {@code LEFT(k1, 1) == LEFT(k2, 1)} over two keyword columns. Coverage-only: this shape does NOT reproduce the
     * H1 aliasing bug and passes both before and after the fix. Neither Equals operand is the raw per-column spare —
     * each is a distinct {@code LEFT} output whose {@code @Fixed out} scratch aliases into its own column's block
     * backing array, so repointing the shared spare never corrupts an operand. Kept to pin that two-column
     * BytesRef-kernel trees keep fusing and matching unfused values.
     */
    public void testEqualsLeftLeftOfTwoColumns() {
        assertBlockShape("LEFT(k1,1) == LEFT(k2,1)", FusionBytesRefAliasingTests::equalsLeftLeft, K1_EQ_LEFT, K2_EQ_LEFT);
    }

    private void assertBlockShape(String ctx, ShapeBuilder shape, String[] k1vals, String[] k2vals) {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Layout layout = layout(k1, k2);
        Expression expr = shape.build(k1, k2);

        ExpressionEvaluator.Factory fused = fusedFactory(expr, layout);
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);
        assertFuses(fused, ctx);
        assertDoesNotFuse(unfused, ctx);

        for (BytesRefEncoding encoding : BytesRefEncoding.values()) {
            Block[] inputs = columns(k1vals, k2vals, encoding);
            runDifferential(fused, unfused, ElementKind.BOOLEAN, inputs, inputs[0].getPositionCount(), ctx + " " + encoding);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // FILTER-EVAL path: WHERE <two-BytesRef-column shape> EVAL LENGTH(k1)+LENGTH(k2)
    // FilterEval cannot stitch LEFT/RIGHT today (unsupported reference @Fixed scratch constants, element 'L'),
    // so these are the FilterEval-fusable analogs of the BLOCK shapes: two BytesRef columns into one
    // BytesRef-consuming comparison, which is the same shared-spare overwrite.
    // ---------------------------------------------------------------------------------------------------------------

    public void testEqualsTwoColumnsFilterEval() {
        assertFilterEvalShape(
            "WHERE k1 == k2 EVAL LENGTH(k1)+LENGTH(k2)",
            FusionBytesRefAliasingTests::equalsTwoColumns,
            K1_EQ_LEFT,
            K2_EQ_LEFT
        );
    }

    public void testNotEqualsTwoColumnsFilterEval() {
        assertFilterEvalShape(
            "WHERE k1 != k2 EVAL LENGTH(k1)+LENGTH(k2)",
            FusionBytesRefAliasingTests::notEqualsTwoColumns,
            K1_NE_RIGHT,
            K2_NE_RIGHT
        );
    }

    public void testEqualsTrimOfOtherColumnFilterEval() {
        assertFilterEvalShape(
            "WHERE k1 == TRIM(k2) EVAL LENGTH(k1)+LENGTH(k2)",
            FusionBytesRefAliasingTests::equalsTrim,
            K1_EQ_LEFT,
            K2_EQ_LEFT
        );
    }

    private void assertFilterEvalShape(String ctx, ShapeBuilder shape, String[] k1vals, String[] k2vals) {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Layout layout = layout(k1, k2);
        Expression predicate = shape.build(k1, k2);
        Expression projection = new Add(Source.EMPTY, new Length(Source.EMPTY, k1), new Length(Source.EMPTY, k2), EsqlTestUtils.TEST_CFG);

        FilterEvalEvaluator.Factory fusedFactory = fuseFilterEval(predicate, projection, layout);
        assertThat(ctx + " must actually fuse (not fall back)", asFusedFilterEval(fusedFactory).fused(), is(true));

        for (BytesRefEncoding encoding : BytesRefEncoding.values()) {
            Block[] inputs = columns(k1vals, k2vals, encoding);
            runFilterEvalBothChains(fusedFactory, predicate, projection, layout, inputs, ctx + " " + encoding);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Structural: two BytesRef columns => two distinct spare locals (two NEW BytesRef, two ASTORE slots)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A fused class for {@code k1 == LEFT(k2, 2)} must allocate a distinct {@code new BytesRef()} spare per column.
     * Pre-fix the BLOCK loop emits a single spare; both leaves {@code ALOAD} that one slot.
     */
    public void testFusedClassHasDistinctSparePerBytesRefColumn() throws Exception {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Layout layout = layout(k1, k2);
        Expression expr = equalsLeft(k1, k2);
        assertFuses(fusedFactory(expr, layout), "k1 == LEFT(k2, 2) structural");

        FusionDescriptor equalsBytesRef = new FusionDescriptor(
            Equals.class,
            "processBytesRef",
            "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/BytesRef;)Z",
            false,
            true,
            "",
            false,
            false,
            false,
            true
        );
        FusionDescriptor left = new FusionDescriptor(
            Left.class,
            "process",
            "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/UnicodeUtil$UTF8CodePoint;"
                + "Lorg/apache/lucene/util/BytesRef;I)Lorg/apache/lucene/util/BytesRef;",
            false,
            true,
            "",
            false,
            false,
            false,
            true
        );
        FusionNode tree = new FusionNode.Kernel(
            equalsBytesRef,
            List.of(
                new FusionNode.Input(0),
                new FusionNode.Kernel(
                    left,
                    List.of(
                        FusionNode.Constant.scratch(ctx -> new BytesRef(), BytesRef.class),
                        FusionNode.Constant.scratch(ctx -> new UnicodeUtil.UTF8CodePoint(), UnicodeUtil.UTF8CodePoint.class),
                        new FusionNode.Input(1),
                        new FusionNode.Constant(2, 'I')
                    )
                )
            )
        );

        byte[][] captured = new byte[1][];
        FusionDump.setSink((name, bytes) -> captured[0] = bytes);
        try {
            assertNotNull(new Stitcher(new TemplateRegistry()).compileBlockLoop(MethodHandles.lookup(), tree));
        } finally {
            FusionDump.clearSink();
        }
        assertThat("fused BLOCK class bytes must be captured", captured[0], notNullValue());
        SpareAllocs allocs = spareAllocs(captured[0]);
        assertThat("two BytesRef columns must emit two NEW BytesRef() spares", allocs.newCount, is(2));
        assertThat("the two spares must land in distinct ASTORE slots", allocs.astoreSlots, hasSize(2));
    }

    private static SpareAllocs spareAllocs(byte[] bytecode) {
        int[] newCount = { 0 };
        Set<Integer> slots = new HashSet<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (Stitcher.FUSED_METHOD_NAME.equals(name) == false) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean pendingSpare;

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && "org/apache/lucene/util/BytesRef".equals(type)) {
                            pendingSpare = true;
                            newCount[0]++;
                        }
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        if (pendingSpare && opcode == Opcodes.ASTORE) {
                            slots.add(varIndex);
                            pendingSpare = false;
                        }
                    }
                };
            }
        }, 0);
        return new SpareAllocs(newCount[0], slots);
    }

    private record SpareAllocs(int newCount, Set<Integer> astoreSlots) {}

    @FunctionalInterface
    private interface ShapeBuilder {
        Expression build(FieldAttribute k1, FieldAttribute k2);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Page construction: plain / ordinal / constant / null / multi-value
    // ---------------------------------------------------------------------------------------------------------------

    private enum BytesRefEncoding {
        PLAIN,
        ORDINAL,
        CONSTANT,
        NULL_CONTAINING,
        MULTI_VALUED
    }

    private Block[] columns(String[] k1vals, String[] k2vals, BytesRefEncoding encoding) {
        return new Block[] { buildColumn(k1vals, encoding), buildColumn(k2vals, encoding) };
    }

    private Block buildColumn(String[] vals, BytesRefEncoding encoding) {
        return switch (encoding) {
            case PLAIN -> dense(vals);
            case ORDINAL -> ordinal(vals);
            case CONSTANT -> {
                // A constant vector of the first value, same length as the pinned page.
                yield blockFactory.newConstantBytesRefBlockWith(new BytesRef(vals[0]), vals.length);
            }
            case NULL_CONTAINING -> nullable(vals);
            case MULTI_VALUED -> multiValued(vals);
        };
    }

    private Block dense(String[] vals) {
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(vals.length)) {
            for (String v : vals) {
                b.appendBytesRef(new BytesRef(v));
            }
            return b.build();
        }
    }

    private Block nullable(String[] vals) {
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(vals.length)) {
            for (int i = 0; i < vals.length; i++) {
                if (i == 1) {
                    b.appendNull();
                } else {
                    b.appendBytesRef(new BytesRef(vals[i]));
                }
            }
            return b.build();
        }
    }

    private Block multiValued(String[] vals) {
        try (BytesRefBlock.Builder b = blockFactory.newBytesRefBlockBuilder(vals.length)) {
            for (int i = 0; i < vals.length; i++) {
                if (i == 1) {
                    b.beginPositionEntry();
                    b.appendBytesRef(new BytesRef(vals[i]));
                    b.appendBytesRef(new BytesRef(vals[i] + "x"));
                    b.endPositionEntry();
                } else {
                    b.appendBytesRef(new BytesRef(vals[i]));
                }
            }
            return b.build();
        }
    }

    private Block ordinal(String[] vals) {
        BytesRefVector dict = null;
        IntVector ords = null;
        try (BytesRefVector.Builder dictBuilder = blockFactory.newBytesRefVectorBuilder(vals.length)) {
            int[] ordinals = new int[vals.length];
            for (int i = 0; i < vals.length; i++) {
                dictBuilder.appendBytesRef(new BytesRef(vals[i]));
                ordinals[i] = i;
            }
            dict = dictBuilder.build();
            ords = blockFactory.newIntArrayVector(ordinals, vals.length);
            Block block = new OrdinalBytesRefVector(ords, dict).asBlock();
            dict = null;
            ords = null;
            return block;
        } finally {
            Releasables.closeExpectNoException(dict, ords);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // FILTER-EVAL helpers (same operator path as FilterEvalFusionDifferentialTests)
    // ---------------------------------------------------------------------------------------------------------------

    private FilterEvalEvaluator.Factory fuseFilterEval(Expression predicate, Expression projection, Layout layout) {
        FusionSettings.setEnabledForTests(true);
        Function<Expression, ExpressionEvaluator.Factory> rawFactory = e -> unfusedFactory(e, layout);
        FilterEvalEvaluator.Factory factory = FusionPlanner.maybeFuseFilterEval(predicate, projection, layout, rawFactory);
        assertThat("maybeFuseFilterEval must produce a factory for a fusable pair", factory, notNullValue());
        return factory;
    }

    private static FusedFilterEvalEvaluatorFactory asFusedFilterEval(FilterEvalEvaluator.Factory factory) {
        assertThat("factory must be a FusedFilterEvalEvaluatorFactory", factory, instanceOf(FusedFilterEvalEvaluatorFactory.class));
        return (FusedFilterEvalEvaluatorFactory) factory;
    }

    private void runFilterEvalBothChains(
        FilterEvalEvaluator.Factory fusedFactory,
        Expression predicate,
        Expression projection,
        Layout layout,
        Block[] columns,
        String ctx
    ) {
        int positionCount = columns[0].getPositionCount();
        Page source = new Page(positionCount, columns);
        Page fusedIn = CannedSourceOperator.deepCopyOf(blockFactory, List.of(source)).get(0);
        Page unfusedIn = CannedSourceOperator.deepCopyOf(blockFactory, List.of(source)).get(0);
        source.releaseBlocks();

        ExpressionEvaluator.Factory predFactory = unfusedFactory(predicate, layout);
        ExpressionEvaluator.Factory projFactory = unfusedFactory(projection, layout);

        Page fusedOut = null;
        Page unfusedOut = null;
        try {
            drainWarnings();
            fusedOut = runFilterEval(fusedFactory, fusedIn);
            List<String> fusedWarnings = drainWarnings();
            fusedIn = null;
            unfusedOut = runFilterThenEval(predFactory, projFactory, unfusedIn);
            List<String> unfusedWarnings = drainWarnings();
            unfusedIn = null;
            assertMixedPagesEqual(fusedOut, unfusedOut, ctx);
            assertWarningsSubset(fusedWarnings, unfusedWarnings, ctx);
        } finally {
            if (fusedOut != null) {
                fusedOut.releaseBlocks();
            }
            if (unfusedOut != null) {
                unfusedOut.releaseBlocks();
            }
            if (fusedIn != null) {
                fusedIn.releaseBlocks();
            }
            if (unfusedIn != null) {
                unfusedIn.releaseBlocks();
            }
        }
    }

    private Page runFilterEval(FilterEvalEvaluator.Factory factory, Page page) {
        try (FilterEvalOperator op = new FilterEvalOperator(driverContext, factory.get(driverContext))) {
            op.addInput(page);
            op.finish();
            return op.getOutput();
        }
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

    /**
     * Keyword inputs survive as BytesRef columns; the appended projection is INT ({@code LENGTH+LENGTH}).
     * Drop-all (null page) must match on both chains.
     */
    private static void assertMixedPagesEqual(Page fused, Page unfused, String ctx) {
        if (fused == null || unfused == null) {
            assertThat(ctx + " both chains must drop all rows (null page)", fused == null && unfused == null, is(true));
            return;
        }
        int positions = fused.getPositionCount();
        assertThat(ctx + " positionCount parity", positions, is(unfused.getPositionCount()));
        assertThat(ctx + " blockCount parity", fused.getBlockCount(), is(unfused.getBlockCount()));
        assertThat(ctx + " expected k1, k2, projection", fused.getBlockCount(), greaterThan(2));
        for (int b = 0; b < fused.getBlockCount(); b++) {
            ElementKind kind = b < fused.getBlockCount() - 1 ? ElementKind.BYTES_REF : ElementKind.INT;
            assertResultsEqual(kind, fused.getBlock(b), unfused.getBlock(b), positions, ctx + " block" + b);
        }
    }
}
