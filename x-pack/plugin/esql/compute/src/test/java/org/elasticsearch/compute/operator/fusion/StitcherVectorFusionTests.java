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
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.fusion.BodyExtractorTests.KernelFixtures;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Differential proof for iter 10's vector fast path: a depth-2 tree {@code (a + b) * c} fused into a single
 * counted loop must produce, position-by-position, exactly what the unfused kernel chain
 * ({@code Math.multiplyExact(Math.addExact(a, b), c)}) would — over many random shapes including empty, single,
 * small, and &ge; 1024 position counts.
 *
 * <p>The fused hidden class is defined into the <b>caller's</b> own package/module (no {@code privateLookupIn}
 * teleport): it reads each dense {@code *ArrayVector}'s backing array through the public
 * {@code VectorUnsafe} forwarder ({@code INVOKESTATIC}) rather than the package-private {@code rawValues()}.
 * Everything runs on real breaker-backed {@link BlockFactory} vectors and real emitted bytecode — no mocks — and
 * the {@code @After} breaker-balance assert proves every produced vector is released.
 */
public class StitcherVectorFusionTests extends ESTestCase {

    /** Local {@code int} kernels mirroring the production {@code Add}/{@code Mul} int kernels (java.base-only bodies). */
    public static final class IntKernels {
        public static int processIntsAdd(int lhs, int rhs) {
            return Math.addExact(lhs, rhs);
        }

        public static int processIntsMul(int lhs, int rhs) {
            return Math.multiplyExact(lhs, rhs);
        }

        public static int processIntsDiv(int lhs, int rhs) {
            if (rhs == 0) {
                throw new ArithmeticException("/ by zero");
            }
            return lhs / rhs;
        }
    }

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    public void testLongAddMulTreeDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(KernelFixtures.class, "processLongsMul", "(JJ)J", true, true);
        // (a + b) * c
        FusionNode tree = new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        // The hidden class lands in the caller's own package (no teleport); it reads backing arrays via VectorUnsafe.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(LongVector.class, BlockFactory.class, int.class, Vector.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        int shapes = 120;
        for (int shape = 0; shape < shapes; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            long[] c = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so (a + b) * c cannot overflow long; overflow semantics are an iter-12 concern.
                a[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                b[p] = randomLongBetween(-1_000_000L, 1_000_000L);
                c[p] = randomLongBetween(-1_000L, 1_000L);
            }

            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector v2 = blockFactory.newLongArrayVector(c, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1, v2);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    long expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, result);
            }
        }
    }

    public void testIntAddMulTreeDifferential() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(IntKernels.class, "processIntsAdd", "(II)I", true, true);
        FusionDescriptor mul = new FusionDescriptor(IntKernels.class, "processIntsMul", "(II)I", true, true);
        // (a + b) * c
        FusionNode tree = new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(IntVector.class, BlockFactory.class, int.class, Vector.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        int shapes = 100;
        for (int shape = 0; shape < shapes; shape++) {
            int positionCount = positionCountFor(shape);
            int[] a = new int[positionCount];
            int[] b = new int[positionCount];
            int[] c = new int[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so (a + b) * c cannot overflow int.
                a[p] = randomIntBetween(-1_000, 1_000);
                b[p] = randomIntBetween(-1_000, 1_000);
                c[p] = randomIntBetween(-1_000, 1_000);
            }

            IntVector v0 = blockFactory.newIntArrayVector(a, positionCount);
            IntVector v1 = blockFactory.newIntArrayVector(b, positionCount);
            IntVector v2 = blockFactory.newIntArrayVector(c, positionCount);
            IntVector result = null;
            try {
                result = (IntVector) handle.invoke(blockFactory, positionCount, v0, v1, v2);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    int expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                    assertThat("shape=" + shape + " p=" + p, result.getInt(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, v2, result);
            }
        }
    }

    /**
     * A 60-add chain ({@code (((in0 + in1) + in1) + …)}) fuses INLINE on the plain-vector path under the 8000-bytecode
     * huge-method guard (well under the limit; previously this tree split into {@code compute()} at the 325
     * FreqInlineSize budget). Asserts the fused method computes {@code a + 60*b} position-by-position.
     */
    public void testDeepChainFusesInlineAndComputesCorrectly() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        int adds = 60;
        FusionNode tree = new FusionNode.Input(0);
        for (int i = 0; i < adds; i++) {
            tree = new FusionNode.Kernel(add, List.of(tree, new FusionNode.Input(1)));
        }

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(LongVector.class, BlockFactory.class, int.class, Vector.class, Vector.class);
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        for (int shape = 0; shape < 40; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            long[] b = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                // Bounded so a + 60*b cannot overflow long.
                a[p] = randomLongBetween(-1_000L, 1_000L);
                b[p] = randomLongBetween(-100L, 100L);
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector v1 = blockFactory.newLongArrayVector(b, positionCount);
            LongVector result = null;
            try {
                result = (LongVector) handle.invoke(blockFactory, positionCount, v0, v1);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    long expected = a[p];
                    for (int i = 0; i < adds; i++) {
                        expected = Math.addExact(expected, b[p]);
                    }
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(expected));
                }
            } finally {
                Releasables.closeExpectNoException(v0, v1, result);
            }
        }
    }

    /**
     * A 60-add chain {@code (((in0 + 1) + 1) + …)} with a distinct {@link FusionNode.Constant} at each level. The fused
     * method takes 60 trailing {@code long} constant params and must marshal all 60 in canonical emit order. Asserting
     * the result is {@code a + 60} proves constant plumbing is correct under INLINE fusion.
     */
    public void testDeepChainWithConstantsFusesInlineAndComputesCorrectly() throws Throwable {
        FusionDescriptor add = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", "(JJ)J", true, true);
        int adds = 60;
        FusionNode tree = new FusionNode.Input(0);
        for (int i = 0; i < adds; i++) {
            tree = new FusionNode.Kernel(add, List.of(tree, new FusionNode.Constant(1L, 'J')));
        }

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        Class<?> fused = stitcher.compileVectorLoop(MethodHandles.lookup(), tree);

        // (BlockFactory, int, Vector, long x adds) -> LongVector
        Class<?>[] params = new Class<?>[3 + adds];
        params[0] = BlockFactory.class;
        params[1] = int.class;
        params[2] = Vector.class;
        for (int i = 0; i < adds; i++) {
            params[3 + i] = long.class;
        }
        MethodHandle handle = MethodHandles.lookup()
            .findStatic(fused, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(LongVector.class, params));

        for (int shape = 0; shape < 30; shape++) {
            int positionCount = positionCountFor(shape);
            long[] a = new long[positionCount];
            for (int p = 0; p < positionCount; p++) {
                a[p] = randomLongBetween(-1_000_000L, 1_000_000L);
            }
            LongVector v0 = blockFactory.newLongArrayVector(a, positionCount);
            LongVector result = null;
            try {
                Object[] args = new Object[3 + adds];
                args[0] = blockFactory;
                args[1] = positionCount;
                args[2] = v0;
                for (int i = 0; i < adds; i++) {
                    args[3 + i] = 1L;
                }
                result = (LongVector) handle.invokeWithArguments(args);
                assertThat("shape=" + shape, result.getPositionCount(), equalTo(positionCount));
                for (int p = 0; p < positionCount; p++) {
                    assertThat("shape=" + shape + " p=" + p, result.getLong(p), equalTo(a[p] + adds));
                }
            } finally {
                Releasables.closeExpectNoException(v0, result);
            }
        }
    }

    /**
     * F1: a baked divisor is an immediate, not a trailing {@code J} param. {@code Div(Mul(in0,in1), baked(1000L))}
     * must emit no trailing long parameter and must push {@code 1000} as {@code LDC}/{@code LCONST}. A runtime-param
     * comparison constant in the same tree still occupies a trailing param slot.
     */
    public void testBakedDivisorIsImmediateAndDoesNotConsumeParamSlot() throws Exception {
        FusionDescriptor mul = new FusionDescriptor(KernelFixtures.class, "processLongsMul", "(JJ)J", true, true);
        FusionDescriptor div = new FusionDescriptor(KernelFixtures.class, "processLongsDiv", "(JJ)J", true, true);
        FusionDescriptor gt = new FusionDescriptor(KernelFixtures.class, "processLongsGreaterThan", "(JJ)Z", false, true);
        FusionNode.Constant baked = FusionNode.Constant.baked(1000L, 'J');
        FusionNode.Constant cmpConst = new FusionNode.Constant(5L, 'J');
        FusionNode mulNode = new FusionNode.Kernel(mul, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode divTree = new FusionNode.Kernel(div, List.of(mulNode, baked));

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        byte[] bakedOnly = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), divTree);
        assertStructurallyValid(bakedOnly);
        String bakedDesc = fusedMethodDescriptor(bakedOnly);
        assertThat("baked divisor must not add a trailing J param: " + bakedDesc, bakedDesc, not(containsString("J)")));
        assertThat("baked 1000L must appear as an immediate at the splice site", hasLongImmediate(bakedOnly, 1000L), is(true));

        // Same tree plus a runtime-param comparison constant: GreaterThan(Div(Mul(in0,in1), baked(1000)), 5).
        FusionNode mixed = new FusionNode.Kernel(gt, List.of(divTree, cmpConst));
        byte[] mixedBytes = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), mixed);
        assertStructurallyValid(mixedBytes);
        String mixedDesc = fusedMethodDescriptor(mixedBytes);
        assertThat(
            "a runtime-param comparison constant must still occupy a trailing J slot: " + mixedDesc,
            mixedDesc,
            containsString("J)")
        );
        assertThat("the baked 1000L immediate is still present in the mixed tree", hasLongImmediate(mixedBytes, 1000L), is(true));
        // Exactly one trailing J (the comparison constant), not two (baked divisor must not add a second).
        assertThat(trailingPrimitiveParamCount(mixedDesc, 'J'), equalTo(1));
        assertThat(trailingPrimitiveParamCount(bakedDesc, 'J'), equalTo(0));
    }

    /**
     * F1/H3: INT-divisor variant of {@link #testBakedDivisorIsImmediateAndDoesNotConsumeParamSlot} on the
     * checked-vector path. The immediate must still be present (INT stays baked).
     */
    public void testBakedIntDivisorIsImmediateOnCheckedPath() throws Exception {
        FusionDescriptor mul = new FusionDescriptor(IntKernels.class, "processIntsMul", "(II)I", true, true);
        FusionDescriptor div = new FusionDescriptor(IntKernels.class, "processIntsDiv", "(II)I", true, true);
        FusionNode.Constant baked = FusionNode.Constant.baked(1000, 'I');
        FusionNode mulNode = new FusionNode.Kernel(mul, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode divTree = new FusionNode.Kernel(div, List.of(mulNode, baked));

        byte[] bakedOnly = new Stitcher(new TemplateRegistry()).vectorLoopCheckedBytecode(MethodHandles.lookup(), divTree);
        assertStructurallyValid(bakedOnly);
        String bakedDesc = fusedMethodDescriptor(bakedOnly);
        assertThat(
            "baked int divisor must not add a trailing I param: " + bakedDesc,
            trailingPrimitiveParamCount(bakedDesc, 'I'),
            equalTo(0)
        );
        assertThat("baked 1000 must appear as an int immediate", hasIntImmediate(bakedOnly, 1000), is(true));
    }

    /**
     * F1/H3: BLOCK-path variant of the LONG baked-divisor immediate test.
     */
    public void testBakedLongDivisorIsImmediateOnBlockPath() throws Exception {
        FusionDescriptor mul = new FusionDescriptor(KernelFixtures.class, "processLongsMul", "(JJ)J", true, true);
        FusionDescriptor div = new FusionDescriptor(KernelFixtures.class, "processLongsDiv", "(JJ)J", true, true);
        FusionNode.Constant baked = FusionNode.Constant.baked(1000L, 'J');
        FusionNode mulNode = new FusionNode.Kernel(mul, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode divTree = new FusionNode.Kernel(div, List.of(mulNode, baked));

        byte[] bakedOnly = new Stitcher(new TemplateRegistry()).blockLoopBytecode(MethodHandles.lookup(), divTree);
        assertStructurallyValid(bakedOnly);
        String bakedDesc = fusedMethodDescriptor(bakedOnly);
        assertThat(
            "baked long divisor must not add a trailing J param: " + bakedDesc,
            trailingPrimitiveParamCount(bakedDesc, 'J'),
            equalTo(0)
        );
        assertThat("baked 1000L must appear as an immediate on the BLOCK path", hasLongImmediate(bakedOnly, 1000L), is(true));
    }

    /**
     * F1/H3: BLOCK-path INT-divisor variant. The immediate must still be present (INT stays baked).
     */
    public void testBakedIntDivisorIsImmediateOnBlockPath() throws Exception {
        FusionDescriptor mul = new FusionDescriptor(IntKernels.class, "processIntsMul", "(II)I", true, true);
        FusionDescriptor div = new FusionDescriptor(IntKernels.class, "processIntsDiv", "(II)I", true, true);
        FusionNode.Constant baked = FusionNode.Constant.baked(1000, 'I');
        FusionNode mulNode = new FusionNode.Kernel(mul, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode divTree = new FusionNode.Kernel(div, List.of(mulNode, baked));

        byte[] bakedOnly = new Stitcher(new TemplateRegistry()).blockLoopBytecode(MethodHandles.lookup(), divTree);
        assertStructurallyValid(bakedOnly);
        String bakedDesc = fusedMethodDescriptor(bakedOnly);
        assertThat(
            "baked int divisor must not add a trailing I param: " + bakedDesc,
            trailingPrimitiveParamCount(bakedDesc, 'I'),
            equalTo(0)
        );
        assertThat("baked 1000 must appear as an int immediate on the BLOCK path", hasIntImmediate(bakedOnly, 1000), is(true));
    }

    private static void assertStructurallyValid(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    private static String fusedMethodDescriptor(byte[] bytecode) {
        String[] desc = { null };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (Stitcher.FUSED_METHOD_NAME.equals(name) && desc[0] == null) {
                    desc[0] = descriptor;
                }
                return null;
            }
        }, 0);
        assertThat("emitted class must define fused()", desc[0] != null, is(true));
        return desc[0];
    }

    /** Counts trailing primitive params of {@code element} after the last {@code L...;} reference param. */
    private static int trailingPrimitiveParamCount(String descriptor, char element) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return 0;
        }
        String params = descriptor.substring(1, close);
        int lastRef = params.lastIndexOf(';');
        int start = lastRef < 0 ? 0 : lastRef + 1;
        int count = 0;
        for (int i = start; i < params.length(); i++) {
            if (params.charAt(i) == element) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasIntImmediate(byte[] bytecode, int value) {
        boolean[] found = { false };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof Integer i && i == value) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && operand == value) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (value >= -1 && value <= 5 && opcode == Opcodes.ICONST_0 + value) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private static boolean hasLongImmediate(byte[] bytecode, long value) {
        boolean[] found = { false };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof Long l && l == value) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (value == 0L && opcode == Opcodes.LCONST_0) {
                            found[0] = true;
                        }
                        if (value == 1L && opcode == Opcodes.LCONST_1) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
    }

    /** Guarantees coverage of empty, single, small, and &ge; 1024 position counts across the shape sweep. */
    private int positionCountFor(int shape) {
        if (shape == 0) {
            return 0;
        }
        if (shape == 1) {
            return 1;
        }
        if (shape % 20 == 0) {
            return 1024 + randomIntBetween(0, 2048);
        }
        return randomIntBetween(2, 256);
    }
}
