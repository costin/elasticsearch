/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Shared block-leaf read primitives: the per-position null / multi-value guard and the first-value read.
 * The block comparison helper and the DFS leaf / varargs paths all inspect a column the same way
 * (count at {@code p}, then the single first value); folding those sequences keeps fused class bytes
 * identical while the three call sites keep their own warning tails and fail labels.
 */
final class BlockLeafEmit {

    private BlockLeafEmit() {}

    /**
     * Emits a block's getValue INVOKEINTERFACE for {@code inputElement} (the typed element accessor, itf=true).
     * The value is left on the stack.
     */
    static void emitGetValue(InsnList insns, Element inputElement) {
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                inputElement.blockInternalName(),
                inputElement.getValueMethod(),
                inputElement.getValueDescriptor(),
                true
            )
        );
    }

    /**
     * Per-position null / multi-value guard: {@code count = block.getValueCount(p)}; if {@code count==0} jump
     * {@code nullLabel}; if {@code count==1} jump {@code singleLabel}; fall through on {@code count>1} (caller
     * registers the multi-value warning + its own fail path).
     */
    static void emitSingleValueGuard(InsnList insns, int blockSlot, int pSlot, int countSlot, LabelNode nullLabel, LabelNode singleLabel) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, blockSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getValueCount", "(I)I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, nullLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, singleLabel));
    }

    /**
     * Reads the first value of a single-valued block: {@code v = block.get*(block.getFirstValueIndex(p))}; for a
     * BytesRef element ({@code spareSlot >= 0}) the reusable spare is pushed as the trailing arg. The value is left
     * on the stack; the caller stores it with the correct typed opcode.
     */
    static void emitReadFirstValue(InsnList insns, int blockSlot, int pSlot, Element inputElement, int spareSlot) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, blockSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, blockSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getFirstValueIndex", "(I)I", true));
        if (spareSlot >= 0) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, spareSlot));
        }
        emitGetValue(insns, inputElement);
    }
}
