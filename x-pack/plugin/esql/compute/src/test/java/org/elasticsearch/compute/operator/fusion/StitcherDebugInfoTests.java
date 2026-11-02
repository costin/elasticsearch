/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Structural proof that every fused hidden class carries always-on, zero-runtime-cost debug metadata:
 * a {@code SourceFile} naming the fused shape, a {@code LineNumberTable} entry at each spliced kernel
 * (line = kernel index in post-order), and a {@code public static final String KERNEL_MAP} ConstantValue
 * mapping those indices to {@code kernelClass#kernelMethod}. Fail-first: pre-C3 classes have neither
 * {@code SourceFile} nor {@code KERNEL_MAP}.
 */
public class StitcherDebugInfoTests extends ESTestCase {

    public static final class ArithmeticKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    }

    public static final class ComparisonKernels {
        public static boolean gt(long a, long b) {
            return a > b;
        }

        public static boolean lt(long a, long b) {
            return a < b;
        }
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

    private static final String ADD_ENTRY = ArithmeticKernels.class.getName() + "#add";
    private static final String MUL_ENTRY = ArithmeticKernels.class.getName() + "#mul";
    private static final String GT_ENTRY = ComparisonKernels.class.getName() + "#gt";
    private static final String LT_ENTRY = ComparisonKernels.class.getName() + "#lt";

    private FusionTestEmitters emitters;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        emitters = new FusionTestEmitters();
    }

    /**
     * Plain-vector {@code (a + b) * c}: two kernels, post-order {@code add} then {@code mul}. The RAW
     * {@code fused} method splices both bodies, so its LineNumberTable is {@code 0, 1}.
     */
    public void testPlainVectorArithmeticEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        byte[] bytecode = emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree());
        assertDebugInfo(
            bytecode,
            "Fused$$VectorLoop",
            List.of("add", "mul"),
            "0=" + ADD_ENTRY + ";1=" + MUL_ENTRY,
            Stitcher.FUSED_METHOD_NAME
        );
        assertCodeLengthUninflatedByLineNumbers(bytecode, Stitcher.FUSED_METHOD_NAME);
    }

    /**
     * Dense logical VECTOR {@code (a > b) AND (c < d)}: Logical nodes are not numbered; the two comparison
     * kernels are indices {@code 0, 1}. Splices live in {@code fusedInterface}.
     */
    public void testLogicalVectorEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        byte[] bytecode = emitters.vectors.emitLogicalVectorLoop(MethodHandles.lookup(), andGtLt());
        assertDebugInfo(
            bytecode,
            "Fused$$LogicalVectorLoop",
            List.of("AND", "gt", "lt"),
            "0=" + GT_ENTRY + ";1=" + LT_ENTRY,
            Stitcher.FUSED_INTERFACE_METHOD_NAME
        );
    }

    /** Block-path {@code (a + b) * c}: same kernel order as the vector path; splices live in {@code fused}. */
    public void testBlockArithmeticEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        byte[] bytecode = emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree());
        assertDebugInfo(
            bytecode,
            "Fused$$BlockLoop",
            List.of("add", "mul"),
            "0=" + ADD_ENTRY + ";1=" + MUL_ENTRY,
            Stitcher.FUSED_METHOD_NAME
        );
        assertCodeLengthUninflatedByLineNumbers(bytecode, Stitcher.FUSED_METHOD_NAME);
    }

    /**
     * Logical block {@code (a > b) AND (c < d)}: comparison bodies are spliced in {@code cmp0}/{@code cmp1}
     * helpers (not the tiny top-level {@code fused} loop). Each helper's LineNumberTable line is that
     * kernel's global index.
     */
    public void testLogicalBlockEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        byte[] bytecode = emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), andGtLt());
        ClassNode classNode = readClass(bytecode);
        assertSourceFile(classNode, "Fused$$LogicalBlockLoop", List.of("AND", "gt", "lt"));
        assertKernelMap(classNode, "0=" + GT_ENTRY + ";1=" + LT_ENTRY);
        assertLineNumbers(method(classNode, "cmp0"), List.of(0));
        assertLineNumbers(method(classNode, "cmp1"), List.of(1));
    }

    /** Checked-vector {@code (a + b) * c}: builder {@code fused} splices both overflow-checked kernels. */
    public void testCheckedVectorEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        byte[] bytecode = emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree());
        assertDebugInfo(
            bytecode,
            "Fused$$CheckedVectorLoop",
            List.of("add", "mul"),
            "0=" + ADD_ENTRY + ";1=" + MUL_ENTRY,
            Stitcher.FUSED_METHOD_NAME
        );
    }

    /**
     * Filter-eval with a comparison-over-arithmetic predicate and an arithmetic projection: kernels are
     * numbered predicate-first (post-order {@code add}, {@code gt}) then projection (post-order {@code add},
     * {@code mul}). Splices live in {@code pred} and {@code projAppend}.
     */
    public void testFilterEvalEmitsSourceFileLineNumbersAndKernelMap() throws Exception {
        FusionNode predicate = new FusionNode.Kernel(
            GT,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        FusionNode projection = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        byte[] bytecode = emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection);
        ClassNode classNode = readClass(bytecode);
        assertSourceFile(classNode, "Fused$$FilterEvalBlockLoop", List.of("gt", "add", "mul"));
        assertKernelMap(classNode, "0=" + ADD_ENTRY + ";1=" + GT_ENTRY + ";2=" + ADD_ENTRY + ";3=" + MUL_ENTRY);
        assertLineNumbers(method(classNode, "pred"), List.of(0, 1));
        assertLineNumbers(method(classNode, "projAppend"), List.of(2, 3));
        assertCodeLengthUninflatedByLineNumbers(bytecode, Stitcher.FUSED_METHOD_NAME);
    }

    /**
     * Filter-eval with a logical predicate: comparison splices are in {@code cmpN} (global indices 0, 1);
     * the projection splice is in {@code projAppend} (index 2).
     */
    public void testFilterEvalLogicalPredicateEmitsKernelIndicesAcrossHelpers() throws Exception {
        FusionNode predicate = andGtLt();
        FusionNode projection = new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        byte[] bytecode = emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection);
        ClassNode classNode = readClass(bytecode);
        assertKernelMap(classNode, "0=" + GT_ENTRY + ";1=" + LT_ENTRY + ";2=" + ADD_ENTRY);
        assertLineNumbers(method(classNode, "cmp0"), List.of(0));
        assertLineNumbers(method(classNode, "cmp1"), List.of(1));
        assertLineNumbers(method(classNode, "projAppend"), List.of(2));
    }

    /**
     * H7: SourceFile and KERNEL_MAP ConstantValue strings are capped at 4 KB so a huge shape cannot make ASM
     * throw ({@code UTF8 string too large}, 64 KB class-file limit) and lose fusion. The cap must engage, and the
     * truncated class must still {@code defineHiddenClass}.
     */
    public void testOversizedSourceFileAndKernelMapAreCappedAndStillDefine() throws Exception {
        FusionNode hugeTree = nestedAdds(100);
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("x".repeat(100_000), hugeTree);
        assertCappedUtf8("SourceFile", debug.sourceFile());
        assertCappedUtf8("KERNEL_MAP", debug.kernelMap());

        MethodNode host = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fused", "()V", null, null);
        host.instructions.add(new InsnNode(Opcodes.RETURN));
        host.maxStack = 0;
        host.maxLocals = 0;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassNode classNode = Stitcher.newFusedClass(Stitcher.fusedClassName(lookup, "Fused$$Utf8Cap"), host, debug);
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        byte[] bytecode = writer.toByteArray();
        Class<?> defined = lookup.defineHiddenClass(bytecode, true).lookupClass();
        String kernelMap = (String) defined.getDeclaredField(Stitcher.KERNEL_MAP_FIELD_NAME).get(null);
        assertCappedUtf8("defined KERNEL_MAP", kernelMap);
    }

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

    /** Left-deep chain of {@code kernelCount} {@code Add} nodes so KERNEL_MAP exceeds 4 KB. */
    private static FusionNode nestedAdds(int kernelCount) {
        FusionNode tree = new FusionNode.Input(0);
        for (int i = 0; i < kernelCount; i++) {
            tree = new FusionNode.Kernel(ADD, List.of(tree, new FusionNode.Input(0)));
        }
        return tree;
    }

    private static void assertCappedUtf8(String label, String value) {
        assertThat(label + " must be present", value, notNullValue());
        int utf8Bytes = value.getBytes(StandardCharsets.UTF_8).length;
        assertThat(label + " UTF-8 bytes must be capped at 4 KB (was " + utf8Bytes + ")", utf8Bytes, lessThanOrEqualTo(4096));
        assertThat(label + " must carry the truncation marker", value, endsWith("...[truncated]"));
    }

    private static void assertDebugInfo(
        byte[] bytecode,
        String pathToken,
        List<String> shapeTokens,
        String expectedKernelMap,
        String spliceMethodName
    ) {
        ClassNode classNode = readClass(bytecode);
        assertSourceFile(classNode, pathToken, shapeTokens);
        assertKernelMap(classNode, expectedKernelMap);
        int kernelCount = expectedKernelMap.split(";").length;
        List<Integer> expected = new ArrayList<>(kernelCount);
        for (int i = 0; i < kernelCount; i++) {
            expected.add(i);
        }
        assertLineNumbers(method(classNode, spliceMethodName), expected);
    }

    private static void assertSourceFile(ClassNode classNode, String pathToken, List<String> shapeTokens) {
        assertThat("SourceFile must be present", classNode.sourceFile, notNullValue());
        assertThat("SourceFile must be non-empty", classNode.sourceFile, not(emptyString()));
        assertThat("SourceFile names the fused path", classNode.sourceFile, containsString(pathToken));
        for (String token : shapeTokens) {
            assertThat(
                "SourceFile reflects shape token [" + token + "]: " + classNode.sourceFile,
                classNode.sourceFile,
                containsString(token)
            );
        }
        assertThat("SourceFile is filename-safe (no slashes)", classNode.sourceFile.contains("/"), is(false));
    }

    private static void assertKernelMap(ClassNode classNode, String expected) {
        FieldNode field = null;
        for (FieldNode candidate : classNode.fields) {
            if (Stitcher.KERNEL_MAP_FIELD_NAME.equals(candidate.name)) {
                field = candidate;
                break;
            }
        }
        assertThat("KERNEL_MAP field must exist", field, notNullValue());
        assertThat("KERNEL_MAP is public static final", field.access, equalTo(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL));
        assertThat("KERNEL_MAP is String", field.desc, equalTo("Ljava/lang/String;"));
        assertThat("KERNEL_MAP ConstantValue lists kernels in index order", field.value, equalTo(expected));
    }

    private static void assertLineNumbers(MethodNode method, List<Integer> expected) {
        assertThat("method [" + method.name + "] must exist", method, notNullValue());
        assertThat("LineNumberTable in [" + method.name + "]", lineNumbers(method), equalTo(expected));
    }

    private static List<Integer> lineNumbers(MethodNode method) {
        List<Integer> lines = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode lineNumber) {
                lines.add(lineNumber.line);
            }
        }
        return lines;
    }

    private static MethodNode method(ClassNode classNode, String name) {
        for (MethodNode method : classNode.methods) {
            if (name.equals(method.name)) {
                return method;
            }
        }
        fail("emitted class has no method [" + name + "]; methods=" + classNode.methods.stream().map(m -> m.name).toList());
        return null;
    }

    /**
     * {@link FusedMethodCodeLengths#fusedMethodCodeLength} reads {@code code_length}, which LineNumberTable does not inflate
     * (it lives in the Code attribute's attributes). The budget guard must still see a positive, real bytecode
     * count — and the set of actual opcodes in the method must be non-empty.
     */
    private static void assertCodeLengthUninflatedByLineNumbers(byte[] bytecode, String methodName) {
        int codeLength = FusedMethodCodeLengths.methodCodeLength(bytecode, methodName);
        assertThat("budget still measures code_length of [" + methodName + "]", codeLength, greaterThan(0));
        MethodNode method = method(readClass(bytecode), methodName);
        int opcodes = 0;
        int metadata = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode || insn instanceof LabelNode) {
                assertThat(
                    "[" + methodName + "] " + insn.getClass().getSimpleName() + " is metadata, not an opcode",
                    insn.getOpcode(),
                    lessThan(0)
                );
                metadata++;
            }
            if (insn.getOpcode() >= 0) {
                opcodes++;
            }
        }
        assertThat("[" + methodName + "] has LabelNode/LineNumberNode metadata to check (assertion not vacuous)", metadata, greaterThan(0));
        assertThat("[" + methodName + "] still has real opcodes (labels/line numbers are not instructions)", opcodes, greaterThan(0));
        // LineNumberTable marks existing offsets; it cannot make code_length smaller than the opcode count
        // (each opcode is at least one byte).
        assertThat("code_length covers the opcodes", codeLength, greaterThan(opcodes - 1));
    }

    private static ClassNode readClass(byte[] bytecode) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytecode).accept(classNode, 0);
        new ClassReader(bytecode).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        return classNode;
    }
}
