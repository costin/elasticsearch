/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.WarningSourceLocation;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Runtime differential for the POSITIONS output ({@link Stitcher#compileBlockPositionsLoop},
 * {@link Stitcher#compileVectorPositionsLoop}): over random pages carrying nulls, multi-values, overflowing values and
 * dense or constant vectors, the positions a fused predicate writes must be exactly those
 * {@link PositionsEvaluator#selectedPositions} selects from the value-output class's {@link BooleanBlock} for the same
 * tree, and the block path must register exactly the same warnings. The kernels are local fixtures shaped like the
 * production comparison and overflow-checked arithmetic kernels.
 */
public class StitcherPositionsFusionTests extends ESTestCase {

    public static final class Kernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static boolean gt(long a, long b) {
            return a > b;
        }

        public static boolean lt(long a, long b) {
            return a < b;
        }
    }

    private static final FusionDescriptor ADD = new FusionDescriptor(
        Kernels.class,
        "add",
        "(JJ)J",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor GT = new FusionDescriptor(
        Kernels.class,
        "gt",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor LT = new FusionDescriptor(
        Kernels.class,
        "lt",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );

    /** A predicate tree, how many {@code long} columns it reads, and its non-baked {@code long} constants' values. */
    private record Predicate(String name, FusionNode tree, int arity, long... constants) {}

    private static FusionNode in(int index) {
        return new FusionNode.Input(index);
    }

    private static FusionNode kernel(FusionDescriptor descriptor, FusionNode... children) {
        return new FusionNode.Kernel(descriptor, List.of(children));
    }

    private static List<Predicate> predicates() {
        return List.of(
            new Predicate("(a + b) > c", kernel(GT, kernel(ADD, in(0), in(1)), in(2)), 3),
            new Predicate("(a + 1) > b", kernel(GT, kernel(ADD, in(0), new FusionNode.Constant(1L, 'J')), in(1)), 2, 1L),
            new Predicate(
                "a > b AND b < c",
                new FusionNode.Logical(FusionNode.BoolOp.AND, kernel(GT, in(0), in(1)), kernel(LT, in(1), in(2))),
                3
            ),
            new Predicate(
                "NOT(a > b) OR c < 2",
                new FusionNode.Logical(
                    FusionNode.BoolOp.OR,
                    new FusionNode.Not(kernel(GT, in(0), in(1))),
                    kernel(LT, in(2), new FusionNode.Constant(2L, 'J'))
                ),
                3,
                2L
            ),
            new Predicate(
                "NOT(a > b AND a < c)",
                new FusionNode.Not(new FusionNode.Logical(FusionNode.BoolOp.AND, kernel(GT, in(0), in(1)), kernel(LT, in(0), in(2)))),
                3
            )
        );
    }

    private enum Column {
        ARRAY_VECTOR,
        CONSTANT,
        NULLS,
        MULTIVALUES,
        OVERFLOW
    }

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private MockBigArrays bigArrays;
    private BlockFactory blockFactory;
    private Stitcher stitcher;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService);
        blockFactory = BlockFactory.builder(bigArrays).build();
        stitcher = new Stitcher(new TemplateRegistry());
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    /**
     * For every predicate and many random pages: block POSITIONS equals the value-output block's selection, positions
     * and warnings alike; on all-vector pages the RAW and INTERFACE vector POSITIONS methods agree too, or (only for an
     * overflow-checked tree) abandon the page with {@code -1}.
     */
    public void testPositionsMatchValueOutputSelection() throws Throwable {
        for (Predicate predicate : predicates()) {
            Handles handles = handles(predicate);
            for (int iteration = 0; iteration < 40; iteration++) {
                int positionCount = switch (iteration) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 1100;
                    default -> between(2, 300);
                };
                Column[] columns = new Column[predicate.arity()];
                for (int c = 0; c < columns.length; c++) {
                    columns[c] = randomFrom(Column.values());
                }
                if (iteration % 3 == 0) {
                    Arrays.fill(columns, Column.ARRAY_VECTOR);
                }
                Block[] blocks = new Block[columns.length];
                try {
                    for (int c = 0; c < columns.length; c++) {
                        blocks[c] = column(columns[c], positionCount);
                    }
                    assertSamePositions(predicate, handles, blocks, positionCount, predicate.name() + " " + Arrays.toString(columns));
                } finally {
                    Releasables.closeExpectNoException(blocks);
                }
            }
        }
    }

    /** The dense vector POSITIONS methods read and write only vectors and the positions array: no Block, no Builder. */
    public void testVectorPositionsLoopTouchesNoBlockOrBuilder() throws Exception {
        for (Predicate predicate : predicates()) {
            byte[] bytecode = new FusionTestEmitters().vectors.emitVectorPositionsLoop(MethodHandles.lookup(), predicate.tree());
            List<String> violations = new ArrayList<>();
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                            if (ForbiddenHotPathOwners.isBlockOwner(owner) || ForbiddenHotPathOwners.isBuilderOwner(owner)) {
                                violations.add(name + ": " + owner + "." + mName + mDesc);
                            }
                        }
                    };
                }
            }, 0);
            assertThat(predicate.name() + " vector positions must not touch Block/Builder: " + violations, violations, is(empty()));
        }
    }

    private void assertSamePositions(Predicate predicate, Handles handles, Block[] blocks, int positionCount, String ctx) throws Throwable {
        // Reference: the value-output block class, read the way FilterOperator reads any BooleanBlock.
        int[] expected = new int[positionCount];
        int expectedCount;
        DriverContext valueContext = new DriverContext(bigArrays, blockFactory, null);
        try (BooleanBlock mask = (BooleanBlock) handles.valueBlock.invokeWithArguments(blockArgs(predicate, valueContext, blocks, null))) {
            expectedCount = PositionsEvaluator.selectedPositions(mask, expected);
        }

        int[] actual = new int[positionCount];
        DriverContext positionsContext = new DriverContext(bigArrays, blockFactory, null);
        int actualCount = (int) handles.positionsBlock.invokeWithArguments(blockArgs(predicate, positionsContext, blocks, actual));
        assertPrefix(ctx + " block", expected, expectedCount, actual, actualCount);
        assertThat(ctx + " block warnings", warningsOf(positionsContext), equalTo(warningsOf(valueContext)));

        Vector[] vectors = new Vector[blocks.length];
        boolean exact = true;
        for (int c = 0; c < blocks.length; c++) {
            vectors[c] = blocks[c].asVector();
            if (vectors[c] == null) {
                return;
            }
            exact &= vectors[c] instanceof LongArrayVector;
        }
        boolean checked = FusionSignature.of(predicate.tree()).overflowExceptionInternalNames().isEmpty() == false;
        List<MethodHandle> vectorMethods = new ArrayList<>();
        vectorMethods.add(handles.positionsVectorInterface);
        if (exact && handles.positionsVectorRaw != null) {
            vectorMethods.add(handles.positionsVectorRaw);
        }
        for (MethodHandle method : vectorMethods) {
            Arrays.fill(actual, -7);
            List<Object> args = new ArrayList<>();
            args.add(blockFactory);
            args.add(positionCount);
            args.add(actual);
            args.addAll(Arrays.asList(vectors));
            for (long constant : predicate.constants()) {
                args.add(constant);
            }
            int count = (int) method.invokeWithArguments(args);
            if (count == -1) {
                assertThat(ctx + " only an overflow-checked tree may abandon a page", checked, is(true));
                continue;
            }
            assertPrefix(ctx + " vector", expected, expectedCount, actual, count);
        }
    }

    private static void assertPrefix(String ctx, int[] expected, int expectedCount, int[] actual, int actualCount) {
        assertThat(ctx + " count", actualCount, equalTo(expectedCount));
        assertThat(ctx + " positions", Arrays.copyOf(actual, actualCount), equalTo(Arrays.copyOf(expected, expectedCount)));
    }

    /** {@code [bf, Warnings[], positionCount, (positions,) blocks..., constants...]}. */
    private static List<Object> blockArgs(Predicate predicate, DriverContext context, Block[] blocks, int[] positions) {
        Warnings[] warnings = new Warnings[Stitcher.warningsSourceCount(predicate.tree())];
        for (int i = 0; i < warnings.length; i++) {
            warnings[i] = Warnings.createWarnings(context, new Location(i));
        }
        List<Object> args = new ArrayList<>();
        args.add(context.blockFactory());
        args.add(warnings);
        args.add(blocks.length == 0 ? 0 : blocks[0].getPositionCount());
        if (positions != null) {
            args.add(positions);
        }
        args.addAll(Arrays.asList(blocks));
        for (long constant : predicate.constants()) {
            args.add(constant);
        }
        return args;
    }

    private static List<String> warningsOf(DriverContext context) {
        context.finish();
        List<String> warnings = context.warnings();
        return warnings == null ? List.of() : warnings;
    }

    private record Location(int index) implements WarningSourceLocation {
        @Override
        public int lineNumber() {
            return 1;
        }

        @Override
        public int columnNumber() {
            return index + 1;
        }

        @Override
        public String viewName() {
            return null;
        }

        @Override
        public String text() {
            return "source" + index;
        }
    }

    private record Handles(
        MethodHandle valueBlock,
        MethodHandle positionsBlock,
        MethodHandle positionsVectorRaw,
        MethodHandle positionsVectorInterface
    ) {}

    private Handles handles(Predicate predicate) throws Exception {
        FusionNode tree = predicate.tree();
        boolean logical = FusionNode.isBooleanLogic(tree);
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> valueClass = logical ? stitcher.compileLogicalBlockLoop(lookup, tree) : stitcher.compileBlockLoop(lookup, tree);
        Class<?> positionsBlockClass = stitcher.compileBlockPositionsLoop(lookup, tree);
        Class<?> positionsVectorClass = stitcher.compileVectorPositionsLoop(lookup, tree);

        List<Class<?>> blockParams = new ArrayList<>(List.of(BlockFactory.class, Warnings[].class, int.class));
        List<Class<?>> positionsBlockParams = new ArrayList<>(List.of(BlockFactory.class, Warnings[].class, int.class, int[].class));
        List<Class<?>> positionsVectorParams = new ArrayList<>(List.of(BlockFactory.class, int.class, int[].class));
        for (int i = 0; i < predicate.arity(); i++) {
            blockParams.add(LongBlock.class);
            positionsBlockParams.add(LongBlock.class);
            positionsVectorParams.add(Vector.class);
        }
        for (int i = 0; i < predicate.constants().length; i++) {
            blockParams.add(long.class);
            positionsBlockParams.add(long.class);
            positionsVectorParams.add(long.class);
        }
        MethodType vectorType = MethodType.methodType(int.class, positionsVectorParams);
        return new Handles(
            lookup.findStatic(valueClass, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(BooleanBlock.class, blockParams)),
            lookup.findStatic(positionsBlockClass, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(int.class, positionsBlockParams)),
            logical ? null : lookup.findStatic(positionsVectorClass, Stitcher.FUSED_METHOD_NAME, vectorType),
            lookup.findStatic(positionsVectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, vectorType)
        );
    }

    private Block column(Column column, int positionCount) {
        return switch (column) {
            case ARRAY_VECTOR -> {
                long[] values = new long[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    values[p] = between(-3, 3);
                }
                yield blockFactory.newLongArrayVector(values, positionCount).asBlock();
            }
            case CONSTANT -> blockFactory.newConstantLongBlockWith(between(-3, 3), positionCount);
            case NULLS, MULTIVALUES, OVERFLOW -> {
                try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(positionCount)) {
                    for (int p = 0; p < positionCount; p++) {
                        if (column == Column.NULLS && randomInt(3) == 0) {
                            builder.appendNull();
                        } else if (column == Column.MULTIVALUES && randomInt(3) == 0) {
                            builder.beginPositionEntry();
                            builder.appendLong(between(-3, 3));
                            builder.appendLong(between(-3, 3));
                            builder.endPositionEntry();
                        } else if (column == Column.OVERFLOW) {
                            builder.appendLong(randomBoolean() ? Long.MAX_VALUE - between(0, 3) : between(-3, 3));
                        } else {
                            builder.appendLong(between(-3, 3));
                        }
                    }
                    yield builder.build();
                }
            }
        };
    }
}
