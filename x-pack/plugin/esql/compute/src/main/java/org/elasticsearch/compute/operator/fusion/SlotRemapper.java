/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Shifts every local-variable slot reference in a kernel method body by a fixed offset.
 *
 * <p><b>Why this exists.</b> The stitcher concatenates several fusable kernel bodies into a single
 * host method so the JIT sees one hot per-position body to inline and vectorize. Each kernel was
 * compiled independently and addresses its locals starting from slot 0 (e.g. {@code long process(long
 * lhs, long rhs)} reads slots 0/1). Splicing two such bodies verbatim would make them collide on the
 * same slots. To keep every kernel's locals in a disjoint slot range within the host frame, the
 * stitcher assigns each kernel a base offset and rewrites its slot references by that offset before
 * splicing. This class performs that rewrite.
 *
 * <p><b>What it rewrites.</b> Slot-bearing instructions are {@link VarInsnNode} (the {@code xLOAD}/
 * {@code xSTORE} family for int/long/float/double/reference) and {@link IincInsnNode}; their
 * {@code var} index is increased by {@code offset}. {@link LocalVariableNode} debug-table entries, if
 * present, have their {@code index} shifted to match so any emitted debug info stays consistent.
 * Note that a {@code long}/{@code double} occupies two consecutive slots — this shift preserves the
 * relative layout, so the second half of a wide value moves with its first half.
 *
 * <p><b>What it rejects.</b> The legacy {@code JSR}/{@code RET} subroutine opcodes are refused with an
 * {@link IllegalArgumentException}. {@code RET} takes a slot operand whose semantics (a return address
 * stored by {@code JSR}) are not plain local-variable data, and subroutine inlining is out of scope
 * for the stitcher. Modern {@code javac} (since Java 6) never emits these, so a kernel containing them
 * is unexpected and unsafe to blindly remap.
 *
 * <p>Stateless and side-effect-free apart from the in-place mutation of the supplied node — callers
 * pass a clone obtained from {@link TemplateRegistry}, never a shared template.
 */
public final class SlotRemapper {

    private SlotRemapper() {}

    /**
     * Shifts the local-variable slots of {@code method} (both its instructions and its
     * {@link MethodNode#localVariables} table) by {@code offset}.
     *
     * @param method the kernel method to rewrite in place
     * @param offset the non-negative amount to add to every local-variable slot
     * @throws IllegalArgumentException if {@code offset} is negative, or the body contains a
     *                                  {@code JSR}/{@code RET} instruction
     */
    public static void shift(MethodNode method, int offset) {
        shift(method.instructions, offset);
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                local.index += offset;
            }
        }
    }

    /**
     * Shifts the local-variable slots referenced by {@code instructions} by {@code offset}. This is the
     * instruction-list-only entry point; callers holding a full {@link MethodNode} should prefer
     * {@link #shift(MethodNode, int)} so the debug local-variable table is shifted in lockstep.
     *
     * @param instructions the instruction list to rewrite in place
     * @param offset       the non-negative amount to add to every local-variable slot
     * @throws IllegalArgumentException if {@code offset} is negative, or the list contains a
     *                                  {@code JSR}/{@code RET} instruction
     */
    public static void shift(InsnList instructions, int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("slot offset must be non-negative but was [" + offset + "]");
        }
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                throw new IllegalArgumentException(
                    "cannot remap slots for a method using legacy JSR/RET subroutine opcodes (opcode [" + opcode + "])"
                );
            }
            if (insn instanceof VarInsnNode varInsn) {
                varInsn.var += offset;
            } else if (insn instanceof IincInsnNode iincInsn) {
                iincInsn.var += offset;
            }
        }
    }
}
