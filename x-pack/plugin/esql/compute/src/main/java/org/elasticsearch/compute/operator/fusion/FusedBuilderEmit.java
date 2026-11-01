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
     * logical-block loop, filter-eval loop, and the checked-vector builder path.
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
}
