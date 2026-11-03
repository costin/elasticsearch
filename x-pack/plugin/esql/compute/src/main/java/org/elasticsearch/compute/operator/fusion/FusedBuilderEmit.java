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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Shared builder prologue and try/finally/build epilogue for fused loops that allocate a {@code *Block.Builder}.
 * Instruction order is the contract: callers must emit the same sequence they inlined before this helper existed,
 * so fused class bytes stay identical.
 */
final class FusedBuilderEmit {

    private FusedBuilderEmit() {}

    /**
     * {@code out = bf.new*BlockBuilder(positionCount);} — {@code bf} is slot 0. Used by the arithmetic block loop,
     * logical-block loop, and the checked-vector builder path.
     */
    static void emitNewBlockBuilder(InsnList insns, int pcSlot, int builderSlot, Element outputElement) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                Stitcher.BLOCK_FACTORY,
                outputElement.newBlockBuilderMethod(),
                outputElement.newBlockBuilderDescriptor(),
                false
            )
        );
        insns.add(new VarInsnNode(Opcodes.ASTORE, builderSlot));
    }

    /**
     * {@code result = out.build();} then close on the normal path and in the catch-all, rethrowing. Registers the
     * try/catch covering {@code [tryStart, tryEnd)}. Callers add {@code tryStart} before the loop body.
     */
    static void emitBuilderTryFinally(
        MethodNode host,
        InsnList insns,
        LabelNode tryStart,
        LabelNode tryEnd,
        LabelNode handler,
        int builderSlot,
        int resultSlot,
        int excSlot,
        Element outputElement
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(Opcodes.INVOKEINTERFACE, outputElement.builderInternalName(), "build", outputElement.buildDescriptor(), true)
        );
        insns.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        insns.add(tryEnd);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new InsnNode(Opcodes.ARETURN));

        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, excSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, excSlot));
        insns.add(new InsnNode(Opcodes.ATHROW));

        host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));
    }

    /**
     * Value is already on the stack (builder then typed value); fluent {@code *Block.Builder.append*(value)}
     * then {@code POP} the builder result.
     */
    static void emitAppend(InsnList insns, Element out) {
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, out.builderInternalName(), out.appendMethod(), out.appendDescriptor(), true));
        insns.add(new InsnNode(Opcodes.POP));
    }

    /**
     * {@code out.appendNull();} — {@code ALOAD} the builder, invoke fluent {@code appendNull()}, {@code POP} the result.
     */
    static void emitAppendNull(InsnList insns, int builderSlot, Element out) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, out.builderInternalName(), "appendNull", out.appendNullDescriptor(), true));
        insns.add(new InsnNode(Opcodes.POP));
    }

    /**
     * The append-or-null tail of a per-position builder loop: appends the value in {@code valueSlot} (the caller has
     * already emitted the guard that jumps to {@code nullLabel} when the position is null) then branches to
     * {@code nextLabel}; at {@code nullLabel} it appends a null instead. Instruction order is the byte-identity contract.
     */
    static void emitAppendOrNull(InsnList insns, int builderSlot, int valueSlot, Element out, LabelNode nullLabel, LabelNode nextLabel) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(out.type().getOpcode(Opcodes.ILOAD), valueSlot));
        emitAppend(insns, out);
        insns.add(new JumpInsnNode(Opcodes.GOTO, nextLabel));
        insns.add(nullLabel);
        emitAppendNull(insns, builderSlot, out);
    }
}
