/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Huge-method guard (HotSpot {@code -XX:HugeMethodLimit}, 8000): every emit path must refuse a class whose
 * <em>largest</em> method {@code Code} length exceeds {@link Stitcher#maxFusedMethodBytecodes}, falling back
 * rather than defining a method the JIT will never compile.
 *
 * <p>The per-path tests drop the live limit to {@link #TINY_LIMIT} so a representative tree is over-budget
 * without walking to depth thousands. Pre-H2, LOGICAL_VECTOR measures only {@code fused} (absent → size 0) and
 * CHECKED/block compile with {@code enforce=false}, so those cases must fail on pre-H2 code.
 * Plain-vector, logical-block and filter-eval already enforced the named {@code fused} method.
 */
public class StitcherHugeMethodGuardTests extends ESTestCase {

    /** Small enough that every real fused method exceeds it; {@code resolveMaxFusedMethodBytecodes} rejects {@code <= 0}. */
    private static final int TINY_LIMIT = 50;

    /** Safety net: restore the default limit even if a test flips it outside its own try/finally. */
    @After
    public void restoreDefaultBudget() {
        Stitcher.maxFusedMethodBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES);
    }

    private static final FusionDescriptor ADD = new FusionDescriptor(ArithmeticKernels.class, "add", "(JJ)J", true, true);
    private static final FusionDescriptor MUL = new FusionDescriptor(ArithmeticKernels.class, "mul", "(JJ)J", true, true);
    private static final FusionDescriptor GT = new FusionDescriptor(ComparisonKernels.class, "gt", "(JJ)Z", false, true);
    private static final FusionDescriptor LT = new FusionDescriptor(ComparisonKernels.class, "lt", "(JJ)Z", false, true);

    /** {@code long} arithmetic kernels shaped like the production {@code Add}/{@code Mul}. */
    public static final class ArithmeticKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    }

    /** {@code long} comparison kernels shaped like the production comparisons. */
    public static final class ComparisonKernels {
        public static boolean gt(long a, long b) {
            return a > b;
        }

        public static boolean lt(long a, long b) {
            return a < b;
        }
    }

    private FusionTestEmitters emitters;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        emitters = new FusionTestEmitters();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Unit: maxMethodCodeLength returns the largest Code length, not a named-method match.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * A hand-written class with three methods of different {@code Code} lengths: {@code maxMethodCodeLength}
     * returns the largest, and {@code methodCodeLength} still answers per name.
     */
    public void testMaxMethodCodeLengthReturnsLargestOfThreeMethods() {
        byte[] bytes = classWithThreeMethods(1, 7, 21);
        assertThat("small", FusedMethodCodeLengths.methodCodeLength(bytes, "small"), is(1));
        assertThat("medium", FusedMethodCodeLengths.methodCodeLength(bytes, "medium"), is(7));
        assertThat("large", FusedMethodCodeLengths.methodCodeLength(bytes, "large"), is(21));
        assertThat("max is the large method, not the first or a name match", FusedMethodCodeLengths.maxMethodCodeLength(bytes), is(21));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Per-path refusal under a tiny limit. LOGICAL-VECTOR and CHECKED-RAW must fail on pre-H2 code.
    // -----------------------------------------------------------------------------------------------------------------

    /** Passing-before: the plain-vector path already enforced the named {@code fused} method. */
    public void testTinyLimitRefusesPlainVector() throws Exception {
        assertTinyLimitRefuses("plain-vector loop", () -> emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree()));
    }

    /**
     * FAIL-before: checked-vector compiled with {@code enforce=false}, so an over-limit {@code fused}/{@code fusedRaw}
     * class was still defined. After H2 the largest of the four methods is refused.
     */
    public void testTinyLimitRefusesCheckedVector() throws Exception {
        assertTinyLimitRefuses(
            "checked-vector loop",
            () -> emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree())
        );
    }

    /** FAIL-before: the block loop compiled with {@code enforce=false}. */
    public void testTinyLimitRefusesBlock() throws Exception {
        assertTinyLimitRefuses("block loop", () -> emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree()));
    }

    /** Passing-before: the logical-block path already enforced the named {@code fused} method. */
    public void testTinyLimitRefusesLogicalBlock() throws Exception {
        assertTinyLimitRefuses("logical block loop", () -> emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), andGtLt()));
    }

    /**
     * FAIL-before: LOGICAL_VECTOR emits only {@code fusedInterface} (no {@code fused}), so the old guard measured
     * size 0 and never refused. After H2 {@code maxMethodCodeLength} sees {@code fusedInterface}.
     */
    public void testTinyLimitRefusesLogicalVector() throws Exception {
        assertTinyLimitRefuses("logical vector loop", () -> emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), andGtLt()));
    }

    /** Passing-before: the filter-eval path already enforced the named {@code fused} method. */
    public void testTinyLimitRefusesFilterEval() throws Exception {
        FusionNode predicate = new FusionNode.Kernel(
            GT,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        FusionNode projection = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        assertTinyLimitRefuses(
            "filter-eval block loop",
            () -> emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection)
        );
    }

    /**
     * LOGICAL_VECTOR's only loop method is {@code fusedInterface}: {@link FusedMethodCodeLengths#fusedMethodCodeLength} is 0,
     * {@link FusedMethodCodeLengths#maxMethodCodeLength} is the INTERFACE body (the size the huge-method guard must use).
     */
    public void testLogicalVectorMaxMethodCodeLengthSeesFusedInterface() throws Exception {
        byte[] bytecode = emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), andGtLt());
        assertThat("LOGICAL_VECTOR does not emit fused", FusedMethodCodeLengths.fusedMethodCodeLength(bytecode), is(0));
        int iface = FusedMethodCodeLengths.methodCodeLength(bytecode, Stitcher.FUSED_INTERFACE_METHOD_NAME);
        assertThat("LOGICAL_VECTOR fusedInterface is a real method", iface, greaterThan(0));
        assertThat(
            "maxMethodCodeLength must report fusedInterface, not 0",
            FusedMethodCodeLengths.maxMethodCodeLength(bytecode),
            is(iface)
        );
    }

    /**
     * CHECKED-VECTOR carries four loop methods; the guard must refuse based on the largest, which may be
     * {@code fusedRaw} rather than the builder {@code fused} the old walk named.
     */
    public void testCheckedVectorMaxMethodCodeLengthIsLargestOfFour() throws Exception {
        byte[] bytecode = emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree());
        int fused = FusedMethodCodeLengths.methodCodeLength(bytecode, Stitcher.FUSED_METHOD_NAME);
        int fusedIface = FusedMethodCodeLengths.methodCodeLength(bytecode, Stitcher.FUSED_INTERFACE_METHOD_NAME);
        int fusedRaw = FusedMethodCodeLengths.methodCodeLength(bytecode, Stitcher.FUSED_RAW_METHOD_NAME);
        int fusedRawIface = FusedMethodCodeLengths.methodCodeLength(bytecode, Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME);
        int expected = Math.max(Math.max(fused, fusedIface), Math.max(fusedRaw, fusedRawIface));
        assertThat("each checked-vector sibling is a real method", fused, greaterThan(0));
        assertThat(fusedIface, greaterThan(0));
        assertThat(fusedRaw, greaterThan(0));
        assertThat(fusedRawIface, greaterThan(0));
        assertThat(FusedMethodCodeLengths.maxMethodCodeLength(bytecode), is(expected));
    }

    /**
     * E4: a 16-comparison left-nested AND (the same shape {@code FusionHugeMethodGuardTests} evaluates end-to-end)
     * emits a LOGICAL_VECTOR class whose largest method is between 325 and {@link Stitcher#DEFAULT_MAX_FUSED_METHOD_BYTECODES}.
     */
    public void testDeepLogicalVectorLargestMethodBetween325And8000() throws Exception {
        byte[] bytecode = emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), leftAndChain(16));
        int size = FusedMethodCodeLengths.maxMethodCodeLength(bytecode);
        logger.info("16-AND logical-vector max method = {} bytecodes", size);
        assertThat("deep logical-vector body must be past the old 325 FreqInlineSize split", size, greaterThan(325));
        assertThat(
            "deep logical-vector body must stay within HugeMethodLimit so it fuses",
            size,
            lessThanOrEqualTo(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES)
        );
    }

    /**
     * E4: a 16-add projection under a comparison predicate emits a filter-eval class whose largest method
     * ({@code pred} or {@code projAppend}, not the tiny top-level {@code fused} loop) is between 325 and 8000.
     */
    public void testDeepFilterEvalLargestMethodBetween325And8000() throws Exception {
        FusionNode predicate = new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode projection = leftChain(ADD, 16);
        byte[] bytecode = emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection);
        int fused = FusedMethodCodeLengths.fusedMethodCodeLength(bytecode);
        int pred = FusedMethodCodeLengths.methodCodeLength(bytecode, "pred");
        int projAppend = FusedMethodCodeLengths.methodCodeLength(bytecode, "projAppend");
        int size = FusedMethodCodeLengths.maxMethodCodeLength(bytecode);
        logger.info("16-add filter-eval fused={} pred={} projAppend={} max={}", fused, pred, projAppend, size);
        assertThat("max must see pred/projAppend, not just the tiny fused loop", size, greaterThan(fused));
        assertThat("deep filter-eval body must be past the old 325 FreqInlineSize split", size, greaterThan(325));
        assertThat(
            "deep filter-eval body must stay within HugeMethodLimit so it fuses",
            size,
            lessThanOrEqualTo(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES)
        );
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    private static FusionNode arithmeticTree() {
        return new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    private static FusionNode andGtLt() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
    }

    /** A left-nested AND of {@code comparisons} column-column comparisons, matching the e2e 16-AND tree. */
    private static FusionNode leftAndChain(int comparisons) {
        FusionNode node = new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        for (int i = 1; i < comparisons; i++) {
            FusionDescriptor cmp = i % 2 == 0 ? GT : LT;
            FusionNode next = new FusionNode.Kernel(cmp, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
            node = new FusionNode.Logical(FusionNode.BoolOp.AND, node, next);
        }
        return node;
    }

    /** A left-leaning chain of {@code depth} binary kernels reusing two inputs. */
    private static FusionNode leftChain(FusionDescriptor descriptor, int depth) {
        FusionNode node = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        for (int i = 1; i < depth; i++) {
            node = new FusionNode.Kernel(descriptor, List.of(node, new FusionNode.Input(1)));
        }
        return node;
    }

    private void assertTinyLimitRefuses(String pathName, ThrowingBytecode emit) throws Exception {
        int previous = Stitcher.maxFusedMethodBytecodes;
        try {
            Stitcher.maxFusedMethodBytecodesForTests(TINY_LIMIT);
            Stitcher.StitchingException refused = expectThrows(Stitcher.StitchingException.class, emit::get);
            assertBudgetRefusal(refused, pathName);
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
        }
    }

    private static void assertBudgetRefusal(Stitcher.StitchingException refused, String pathName) {
        String message = refused.getMessage();
        assertThat(
            "refusal must be the huge-method guard, not some other stitch failure: " + message,
            message,
            containsString("exceeds the huge-method limit")
        );
        assertThat("refusal must name the enforced path [" + pathName + "]: " + message, message, containsString(pathName));
        int reportedSize = parseReportedSize(message);
        assertThat(
            "the size the huge-method guard measured must actually exceed the limit (the method WOULD not be JIT-compiled)",
            reportedSize,
            greaterThan(Stitcher.maxFusedMethodBytecodes)
        );
    }

    private static int parseReportedSize(String message) {
        Matcher matcher = Pattern.compile("\\[(\\d+) bytecodes\\]").matcher(message);
        assertThat("budget message must report the measured method size: " + message, matcher.find(), is(true));
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * A minimal class file with three static void methods whose {@code Code} lengths are {@code smallLen},
     * {@code mediumLen} and {@code largeLen} (NOP padding plus RETURN).
     */
    private static byte[] classWithThreeMethods(int smallLen, int mediumLen, int largeLen) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "Measure$$Three", null, "java/lang/Object", null);
        emitNopsAndReturn(cw, "small", smallLen);
        emitNopsAndReturn(cw, "medium", mediumLen);
        emitNopsAndReturn(cw, "large", largeLen);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitNopsAndReturn(ClassWriter cw, String name, int codeLength) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < codeLength - 1; i++) {
            mv.visitInsn(Opcodes.NOP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @FunctionalInterface
    private interface ThrowingBytecode {
        byte[] get() throws Exception;
    }
}
