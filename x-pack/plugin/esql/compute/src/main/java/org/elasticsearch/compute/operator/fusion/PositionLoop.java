/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Shared per-position builder-loop skeleton for the four fused loops that allocate a {@code *Block.Builder}.
 * {@link #open} allocates the builder, opens the builder-safety try, and emits {@code for (p = 0; p < positionCount;)}
 * up through the bound check; the caller then emits its own per-position body; {@link #close} steps {@code p},
 * closes the loop, and builds/closes the builder. Instruction order is the byte-identity contract: the four
 * builder loops differ only in that body.
 *
 * <p>The POSITIONS output of a fused predicate allocates nothing, so {@link #openPositions}/{@link #closePositions}
 * emit the same counted loop without a builder or its try/finally (the try labels are {@code null}, the builder slot
 * {@code -1}), counting the selected positions in a local and returning that count.
 */
record PositionLoop(
    LabelNode tryStart,
    LabelNode tryEnd,
    LabelNode handler,
    LabelNode loopStart,
    LabelNode loopEnd,
    LabelNode next,
    int builderSlot,
    int pSlot
) {
    /**
     * Emits: out = bf.new*BlockBuilder(pc); try { int p = 0; loopStart: if (p >= pc) goto loopEnd; ...
     * Returns the loop's labels so the caller emits its per-position body, then calls {@link #close}.
     */
    static PositionLoop open(InsnList insns, int pcSlot, int pSlot, int builderSlot, Element out) {
        FusedBuilderEmit.emitNewBlockBuilder(insns, pcSlot, builderSlot, out);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        insns.add(tryStart);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        return new PositionLoop(tryStart, tryEnd, handler, loopStart, loopEnd, next, builderSlot, pSlot);
    }

    /**
     * POSITIONS output: emits {@code n = 0; int p = 0; loopStart: if (p >= pc) goto loopEnd;}. The caller emits the
     * per-position body (typically {@link #emitRecordPosition} then advancing {@code n} by the selection), then
     * {@link #closePositions}.
     */
    static PositionLoop openPositions(InsnList insns, int pcSlot, int pSlot, int countSlot) {
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        return new PositionLoop(null, null, null, loopStart, loopEnd, next, -1, pSlot);
    }

    /**
     * Emits {@code positions[n] = p;}. Every reached position is written; the caller then adds the 0/1 selection to
     * {@code n}, so the store stays branch-free and an unselected position is simply overwritten by the next one.
     * In bounds because {@code n <= p < positionCount <= positions.length}.
     */
    static void emitRecordPosition(InsnList insns, int positionsSlot, int countSlot, int pSlot) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, positionsSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    /** POSITIONS output: emits {@code next: p++; goto loopStart; loopEnd: return n;}. */
    void closePositions(InsnList insns, int countSlot) {
        assert builderSlot == -1 : "closePositions on a builder loop";
        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new InsnNode(Opcodes.IRETURN));
    }

    /**
     * Emits: next: p++; goto loopStart; loopEnd: result = out.build(); close()/rethrow try-finally.
     */
    void close(MethodNode host, InsnList insns, int resultSlot, int excSlot, Element out) {
        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);
        FusedBuilderEmit.emitBuilderTryFinally(host, insns, tryStart, tryEnd, handler, builderSlot, resultSlot, excSlot, out);
    }
}
