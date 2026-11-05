/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Huge-method guard (HotSpot {@code -XX:HugeMethodLimit}, 8000): every emit path must refuse a class whose
 * <em>largest</em> root method {@code Code} length exceeds {@link Stitcher#maxFusedMethodBytecodes}, falling back
 * rather than defining a method the JIT will never compile. Per-row helpers ({@code cmpN}) are held to the much smaller
 * inlining limit {@link Stitcher#maxFusedHelperBytecodes} ({@code -XX:FreqInlineSize}): a root above 325 bytecodes is
 * fine, a helper above it is refused.
 *
 * <p>The per-path tests drop the live limit to {@link #TINY_LIMIT} so a representative tree is over-budget
 * without walking to depth thousands. Previously, LOGICAL_VECTOR measures only {@code fused} (absent → size 0) and
 * CHECKED/block compile with {@code enforce=false}, so those cases must fail on code.
 * Plain-vector and logical-block already enforced the named {@code fused} method. The POSITIONS paths of a WHERE
 * predicate go through the same guard.
 */
public class StitcherHugeMethodGuardTests extends ESTestCase {

    /** Small enough that every real fused method exceeds it; {@code resolveMaxFusedMethodBytecodes} rejects {@code <= 0}. */
    private static final int TINY_LIMIT = 50;

    /** Safety net: restore the default limits even if a test flips them outside its own try/finally. */
    @After
    public void restoreDefaultBudget() {
        Stitcher.maxFusedMethodBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES);
        Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
    }

    private static final FusionDescriptor ADD = new FusionDescriptor(
        ArithmeticKernels.class,
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
    private static final FusionDescriptor MUL = new FusionDescriptor(
        ArithmeticKernels.class,
        "mul",
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
        ComparisonKernels.class,
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
        ComparisonKernels.class,
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

    /** {@code long} arithmetic kernels shaped like the production {@code Add}/{@code Mul}. */
    public static final class ArithmeticKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    }

    /**
     * A comparison whose straight-line body alone is past {@code FreqInlineSize} yet far below {@code HugeMethodLimit}:
     * twenty mixing rounds (about 20 bytecodes each) before the compare. Spliced into a logical block loop's
     * {@code cmpN} helper it makes that helper too big for C2 to inline.
     */
    public static final class MixingKernels {
        public static boolean mixedGt(long a, long b) {
            long x = a;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            x = x * 31 + b;
            x ^= x >>> 7;
            return x > b;
        }
    }

    private static final FusionDescriptor MIXED_GT = new FusionDescriptor(
        MixingKernels.class,
        "mixedGt",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );

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

    @Before
    public void setUpStitcher() throws Exception {
        emitters = new FusionTestEmitters();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Unit: maxMethodCodeLength returns the largest Code length, not a named-method match.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Roles come from the access flag, not the name: a {@code private} method is a helper, a {@code public} one a root,
     * and {@code largest} answers per role even when the largest method overall is a helper.
     */
    public void testLargestIsPerRoleByAccessFlag() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "Measure$$Roles", null, "java/lang/Object", null);
        emitNopsAndReturn(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fused", 40);
        emitNopsAndReturn(cw, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "cmp0", 400);
        emitNopsAndReturn(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cmp1", 90);
        emitNopsAndReturn(cw, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "fusedRaw", 12);
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        assertThat(
            FusedMethodCodeLengths.methodSizes(bytes),
            is(
                List.of(
                    new FusedMethodCodeLengths.MethodSize("fused", false, 40),
                    new FusedMethodCodeLengths.MethodSize("cmp0", true, 400),
                    new FusedMethodCodeLengths.MethodSize("cmp1", false, 90),
                    new FusedMethodCodeLengths.MethodSize("fusedRaw", true, 12)
                )
            )
        );
        assertThat(FusedMethodCodeLengths.largest(bytes, false), is(new FusedMethodCodeLengths.MethodSize("cmp1", false, 90)));
        assertThat(FusedMethodCodeLengths.largest(bytes, true), is(new FusedMethodCodeLengths.MethodSize("cmp0", true, 400)));
    }

    /** A class without helpers has no largest helper. */
    public void testLargestHelperIsNullWithoutHelpers() throws Exception {
        byte[] bytecode = emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree());
        assertThat(FusedMethodCodeLengths.largest(bytecode, true), nullValue());
        assertThat(FusedMethodCodeLengths.largest(bytecode, false).codeLength(), is(FusedMethodCodeLengths.maxMethodCodeLength(bytecode)));
    }

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
    // Per-path refusal under a tiny limit. LOGICAL-VECTOR and CHECKED-RAW must fail on code.
    // -----------------------------------------------------------------------------------------------------------------

    /** Passing-before: the plain-vector path already enforced the named {@code fused} method. */
    public void testTinyLimitRefusesPlainVector() throws Exception {
        assertTinyLimitRefuses("plain-vector loop", () -> emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree()));
    }

    /**
     * FAIL-before: checked-vector compiled with {@code enforce=false}, so an over-limit {@code fused}/{@code fusedRaw}
     * class was still defined. After the largest of the four methods is refused.
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
     * size 0 and never refused. After {@code maxMethodCodeLength} sees {@code fusedInterface}.
     */
    public void testTinyLimitRefusesLogicalVector() throws Exception {
        assertTinyLimitRefuses("logical vector loop", () -> emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), andGtLt()));
    }

    /** The POSITIONS block and vector paths of a comparison and a logical predicate are refused like their siblings. */
    public void testTinyLimitRefusesPositions() throws Exception {
        FusionNode comparison = new FusionNode.Kernel(GT, List.of(arithmeticTree(), new FusionNode.Input(3)));
        assertTinyLimitRefuses("block positions loop", () -> emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), comparison));
        assertTinyLimitRefuses("vector positions loop", () -> emitters.vectors.emitVectorPositionsLoop(MethodHandles.lookup(), comparison));
        assertTinyLimitRefuses(
            "logical block positions loop",
            () -> emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), andGtLt())
        );
        assertTinyLimitRefuses(
            "logical vector positions loop",
            () -> emitters.vectors.emitVectorPositionsLoop(MethodHandles.lookup(), andGtLt())
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
     * a 16-comparison left-nested AND (the same shape {@code FusionHugeMethodGuardTests} evaluates end-to-end)
     * emits a LOGICAL_VECTOR class whose largest method is between 325 and {@link Stitcher#DEFAULT_MAX_FUSED_METHOD_BYTECODES}.
     * That method is a root, invoked once per page, so being past {@code FreqInlineSize} is fine: it is accepted.
     */
    public void testDeepLogicalVectorLargestMethodBetween325And8000() throws Exception {
        byte[] bytecode = emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), leftAndChain(16));
        int size = FusedMethodCodeLengths.maxMethodCodeLength(bytecode);
        logger.info("16-AND logical-vector max method = {} bytecodes", size);
        assertThat("LOGICAL_VECTOR has no helpers, only roots", FusedMethodCodeLengths.largest(bytecode, true), nullValue());
        assertThat(
            "a deep logical-vector root past FreqInlineSize is accepted",
            size,
            greaterThan(Stitcher.DEFAULT_MAX_FUSED_HELPER_BYTECODES)
        );
        assertThat(
            "deep logical-vector body must stay within HugeMethodLimit so it fuses",
            size,
            lessThanOrEqualTo(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES)
        );
    }

    /**
     * a comparison over a 16-add chain emits a block POSITIONS class whose {@code fused} method is between 325 and
     * 8000: past FreqInlineSize, but a root is only JIT-compiled, never inlined, so a deep WHERE predicate fuses.
     */
    public void testDeepPositionsLargestMethodBetween325And8000() throws Exception {
        FusionNode predicate = new FusionNode.Kernel(GT, List.of(leftChain(ADD, 16), new FusionNode.Input(1)));
        byte[] bytecode = emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), predicate);
        int size = FusedMethodCodeLengths.maxMethodCodeLength(bytecode);
        logger.info("gt(16-add) block positions max method = {} bytecodes", size);
        assertThat("the only method is the fused positions loop", size, is(FusedMethodCodeLengths.fusedMethodCodeLength(bytecode)));
        assertThat("a comparison-rooted positions class has no helpers", FusedMethodCodeLengths.largest(bytecode, true), nullValue());
        assertThat("a deep positions root past FreqInlineSize is accepted", size, greaterThan(Stitcher.DEFAULT_MAX_FUSED_HELPER_BYTECODES));
        assertThat(
            "deep positions body must stay within HugeMethodLimit so it fuses",
            size,
            lessThanOrEqualTo(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES)
        );
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helper (cmpN) inlining limit: FreqInlineSize, not HugeMethodLimit.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * The helper limit is the running JVM's {@code -XX:FreqInlineSize}. Read here through the platform MBean server, an
     * independent route to the same HotSpot diagnostic bean the stitcher reads.
     */
    public void testHelperLimitIsLiveFreqInlineSize() throws Exception {
        CompositeData option = (CompositeData) ManagementFactory.getPlatformMBeanServer()
            .invoke(
                new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                "getVMOption",
                new Object[] { "FreqInlineSize" },
                new String[] { String.class.getName() }
            );
        int live = Integer.parseInt((String) option.get("value"));
        assertThat(Stitcher.FREQ_INLINE_SIZE, is(live));
        assertThat(Stitcher.maxFusedHelperBytecodes(), is(live));
        assertThat("far below the root limit", live, lessThan(Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES));
    }

    /** The representative logical block loop's {@code cmpN} helpers fit the live inlining limit, so it is emitted. */
    public void testLogicalBlockHelpersWithinInliningLimit() throws Exception {
        byte[] bytecode = emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), andGtLt());
        int helpers = 0;
        for (FusedMethodCodeLengths.MethodSize size : FusedMethodCodeLengths.methodSizes(bytecode)) {
            if (size.helper()) {
                helpers++;
                assertThat(
                    size.name() + " fits the inlining limit",
                    size.codeLength(),
                    lessThanOrEqualTo(Stitcher.maxFusedHelperBytecodes())
                );
            }
        }
        assertThat("one cmpN helper per comparison", helpers, is(2));
    }

    /**
     * A {@code cmpN} helper between 325 and 8000 bytecodes is refused. The old guard measured only the largest method
     * against HugeMethodLimit, so this class was emitted and its per-row helper call was never inlined.
     */
    public void testLogicalBlockHelperBetween325And8000IsRefused() throws Exception {
        FusionNode tree = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(MIXED_GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        Stitcher.maxFusedHelperBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_HELPER_BYTECODES);
        try {
            Stitcher.StitchingException refused = expectThrows(
                Stitcher.StitchingException.class,
                () -> emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), tree)
            );
            assertHelperRefusal(refused, "logical block loop", "cmp0");
            int size = parseReportedSize(refused.getMessage());
            logger.info("mixedGt cmp0 helper = {} bytecodes", size);
            assertThat("the helper is within HugeMethodLimit: only the inlining limit refuses it", size, lessThanOrEqualTo(8000));
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
        }
    }

    /**
     * The helper limit applies to helpers only: under a tiny helper limit the logical block loop (which has helpers) is
     * refused, while every root-only path still emits, roots past FreqInlineSize included.
     */
    public void testTinyHelperLimitRefusesOnlyClassesWithHelpers() throws Exception {
        Stitcher.maxFusedHelperBytecodesForTests(1);
        try {
            Stitcher.StitchingException refused = expectThrows(
                Stitcher.StitchingException.class,
                () -> emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), andGtLt())
            );
            assertHelperRefusal(refused, "logical block loop", "cmp");
            emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree());
            emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree());
            emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree());
            emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), leftAndChain(16));
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
        }
    }

    /** A logical WHERE predicate's block POSITIONS class carries the same {@code cmpN} helpers and the same limit. */
    public void testLogicalBlockPositionsHelperBetween325And8000IsRefused() throws Exception {
        FusionNode tree = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(MIXED_GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        Stitcher.maxFusedHelperBytecodesForTests(Stitcher.DEFAULT_MAX_FUSED_HELPER_BYTECODES);
        try {
            Stitcher.StitchingException refused = expectThrows(
                Stitcher.StitchingException.class,
                () -> emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), tree)
            );
            assertHelperRefusal(refused, "logical block positions loop", "cmp0");
            // The dense POSITIONS loop inlines the comparison into its root, which may exceed FreqInlineSize.
            emitters.vectors.emitVectorPositionsLoop(MethodHandles.lookup(), tree);
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
        }
    }

    /** Under a tiny helper limit only the logical block POSITIONS class (it has helpers) is refused. */
    public void testTinyHelperLimitRefusesOnlyPositionsClassesWithHelpers() throws Exception {
        FusionNode comparison = new FusionNode.Kernel(GT, List.of(arithmeticTree(), new FusionNode.Input(3)));
        Stitcher.maxFusedHelperBytecodesForTests(1);
        try {
            Stitcher.StitchingException refused = expectThrows(
                Stitcher.StitchingException.class,
                () -> emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), andGtLt())
            );
            assertHelperRefusal(refused, "logical block positions loop", "cmp");
            emitters.blocks.emitBlockPositionsLoop(MethodHandles.lookup(), comparison);
            emitters.vectors.emitVectorPositionsLoop(MethodHandles.lookup(), comparison);
            emitters.vectors.emitVectorPositionsLoop(MethodHandles.lookup(), andGtLt());
        } finally {
            Stitcher.maxFusedHelperBytecodesForTests(Stitcher.FREQ_INLINE_SIZE);
        }
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

    private static void assertHelperRefusal(Stitcher.StitchingException refused, String pathName, String helperName) {
        String message = refused.getMessage();
        assertThat("refusal must be the inlining limit: " + message, message, containsString("exceeds the inlining limit"));
        assertThat("refusal must name the path [" + pathName + "]: " + message, message, containsString(pathName));
        assertThat("refusal must name the helper [" + helperName + "]: " + message, message, containsString("helper [" + helperName));
        assertThat(
            "the measured helper must actually exceed the inlining limit",
            parseReportedSize(message),
            greaterThan(Stitcher.maxFusedHelperBytecodes())
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
        emitNopsAndReturn(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "small", smallLen);
        emitNopsAndReturn(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "medium", mediumLen);
        emitNopsAndReturn(cw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "large", largeLen);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitNopsAndReturn(ClassWriter cw, int access, String name, int codeLength) {
        MethodVisitor mv = cw.visitMethod(access, name, "()V", null, null);
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
