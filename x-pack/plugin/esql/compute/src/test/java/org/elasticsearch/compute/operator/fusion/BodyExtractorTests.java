/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.operator.fusion.BodyExtractor.Body;
import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class BodyExtractorTests extends ESTestCase {

    /**
     * The real fusable kernels ({@code Add.processLongs}, {@code Mul.processLongs}) live in the
     * {@code :x-pack:plugin:esql} module, which depends on this ({@code :...:compute}) module — so
     * they cannot be on this module's test classpath without introducing a dependency cycle. To keep
     * the extraction assertions on <b>real compiled kernel bytecode read through the real
     * {@link TemplateRegistry}</b> (rather than mocks or synthetic {@link MethodNode}s), this fixture
     * mirrors those kernels byte-for-byte: {@code Math.addExact}/{@code Math.multiplyExact} delegates
     * with the identical {@code (long, long) -> long} shape. {@code javac} compiles these to the same
     * instruction sequence as the production kernels, so the {@code MethodNode} the registry hands
     * back is representative of what the stitcher will see for {@code Add}/{@code Mul}.
     */
    public static final class KernelFixtures {
        public static long processLongsAdd(long lhs, long rhs) {
            return Math.addExact(lhs, rhs);
        }

        public static long processLongsMul(long lhs, long rhs) {
            return Math.multiplyExact(lhs, rhs);
        }

        /**
         * A straight-line {@code BytesRef -> BytesRef} kernel (identity) for exercising the fused block path's
         * BytesRef-OUTPUT support (B3b): a single terminal {@code ARETURN}, no loops, so {@link BodyExtractor} can
         * splice it. Returns the (reused input spare) reference — the block builder's {@code appendBytesRef} copies its
         * bytes into block storage, so reuse across positions is safe.
         */
        public static org.apache.lucene.util.BytesRef processBytesRefIdentity(org.apache.lucene.util.BytesRef v) {
            return v;
        }
    }

    private static final String LONG_BINARY_TYPE = "(JJ)J";

    public void testExtractsAddProcessLongsBody() {
        assertLongKernelBody("processLongsAdd");
    }

    public void testExtractsMulProcessLongsBody() {
        assertLongKernelBody("processLongsMul");
    }

    private void assertLongKernelBody(String kernelMethod) {
        TemplateRegistry registry = new TemplateRegistry();
        MethodNode kernel = registry.methodNode(descriptor(kernelMethod));
        int originalSize = kernel.instructions.size();

        Body body = BodyExtractor.extract(kernel);

        assertThat(body.returnOpcode(), equalTo(Opcodes.LRETURN));
        assertThat("computation must be non-empty", body.computation().size(), greaterThan(0));
        assertThat("exactly the terminal return is removed", body.computation().size(), equalTo(originalSize - 1));
        assertNoReturnInstruction(body);

        // Extraction must not mutate the source node handed in by the registry.
        assertThat(kernel.instructions.size(), equalTo(originalSize));
    }

    public void testRejectsMultipleReturns() {
        // Two terminal-shaped returns: an extra exit the fused loop cannot honour.
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "twoReturns", LONG_BINARY_TYPE, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.LADD));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> BodyExtractor.extract(method));
        assertThat(e.getMessage(), containsString("exactly one terminal return"));
    }

    public void testRejectsEarlyReturn() {
        // A single return that is not the terminal instruction (an early return before a trailing tail).
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "earlyReturn", "(J)J", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.LCONST_1));
        method.instructions.add(new InsnNode(Opcodes.LADD));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> BodyExtractor.extract(method));
        // Two returns? No — one return, but it is not terminal, so it fails the terminal-instruction check.
        assertThat(e.getMessage(), containsString("early return"));
    }

    public void testRejectsAthrowInsideLoop() {
        // A back-edge (loop) plus an ATHROW: an abnormal exit from within a loop.
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "athrowInLoop", "()I", null, null);
        LabelNode loopTop = new LabelNode();
        method.instructions.add(loopTop);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopTop)); // back-edge => loop
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> BodyExtractor.extract(method));
        assertThat(e.getMessage(), containsString("ATHROW reachable inside a loop"));
    }

    public void testRejectsJsr() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "legacy", "()V", null, null);
        LabelNode subroutine = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.JSR, subroutine));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(subroutine);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> BodyExtractor.extract(method));
        assertThat(e.getMessage(), containsString("JSR/RET"));
    }

    private static void assertNoReturnInstruction(Body body) {
        for (AbstractInsnNode insn = body.computation().getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            boolean isReturn = opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN
                || opcode == Opcodes.RETURN;
            assertFalse("computation must not contain a return instruction", isReturn);
        }
    }

    private static FusionDescriptor descriptor(String kernelMethod) {
        return new FusionDescriptor(KernelFixtures.class, kernelMethod, LONG_BINARY_TYPE, true, true);
    }
}
