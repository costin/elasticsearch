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
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionDump;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 *: a fused BLOCK loop that reads two BytesRef columns into one shared spare BytesRef silently overwrites the
 * first operand. {@code k1 == LEFT(k2, 2)} then computes {@code k2 == LEFT(k2, 2)}. These differentials pin the
 * per-column spare: the three two-column shapes must still fuse, and fused values must match the unfused chain
 * over plain / ordinal / constant / null / multi-value pages, both as EVAL values and as WHERE positions.
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

    private static Expression equalsTrim(FieldAttribute k1, FieldAttribute k2) {
        return new Equals(Source.EMPTY, k1, new Trim(Source.EMPTY, k2));
    }

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
     * aliasing bug and passes both before and after the fix. Neither Equals operand is the raw per-column spare —
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

    /** {@code WHERE k1 == LEFT(k2, 2)}: the positions path reads both columns through per-column spares too. */
    public void testEqualsLeftOfOtherColumnWhere() {
        assertWhereShape("WHERE k1 == LEFT(k2, 2)", FusionBytesRefAliasingTests::equalsLeft, K1_EQ_LEFT, K2_EQ_LEFT);
    }

    /** {@code WHERE k1 != RIGHT(k2, 1)} over a page where the aliased answer would select different rows. */
    public void testNotEqualsRightOfOtherColumnWhere() {
        assertWhereShape("WHERE k1 != RIGHT(k2, 1)", FusionBytesRefAliasingTests::notEqualsRight, K1_NE_RIGHT, K2_NE_RIGHT);
    }

    /** {@code WHERE k1 == TRIM(k2)}: a unary string kernel over the second column. */
    public void testEqualsTrimOfOtherColumnWhere() {
        assertWhereShape("WHERE k1 == TRIM(k2)", FusionBytesRefAliasingTests::equalsTrim, K1_EQ_LEFT, K2_EQ_LEFT);
    }

    private void assertWhereShape(String ctx, ShapeBuilder shape, String[] k1vals, String[] k2vals) {
        FieldAttribute k1 = field("k1", DataType.KEYWORD);
        FieldAttribute k2 = field("k2", DataType.KEYWORD);
        Layout layout = layout(k1, k2);
        Expression predicate = shape.build(k1, k2);

        ExpressionEvaluator.Factory fused = filterFactory(predicate, layout);
        assertFilterFuses(fused, ctx);
        ExpressionEvaluator.Factory unfused = unfusedFactory(predicate, layout);

        for (BytesRefEncoding encoding : BytesRefEncoding.values()) {
            Page page = new Page(columns(k1vals, k2vals, encoding));
            CapturedFilter actual = null;
            CapturedFilter expected = null;
            try {
                actual = filterCapturing(fused, page);
                expected = filterCapturing(unfused, page);
                assertPagesEqual(actual.page(), expected.page(), ctx + " " + encoding);
                assertWarningsSubset(actual.warnings(), expected.warnings(), ctx + " " + encoding);
            } finally {
                page.releaseBlocks();
                for (CapturedFilter captured : new CapturedFilter[] { actual, expected }) {
                    if (captured != null && captured.page() != null) {
                        captured.page().releaseBlocks();
                    }
                }
            }
        }
    }

    /** A fused class for {@code k1 == LEFT(k2, 2)} must allocate a distinct {@code new BytesRef()} spare per column. */
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
}
