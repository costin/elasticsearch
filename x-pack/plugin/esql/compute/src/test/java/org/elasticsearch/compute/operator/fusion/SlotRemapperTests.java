/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class SlotRemapperTests extends ESTestCase {

    /**
     * Builds a real kernel-shaped {@link MethodNode} with a known slot layout — two {@code long}
     * inputs read from slots 0/2, a sum stored into slot 4, an {@code IINC} on slot 6, and a
     * local-variable debug table — then shifts everything by a fixed offset and asserts every slot
     * reference (instructions plus the debug table) moved by exactly that offset.
     */
    public void testShiftsVarInsnIincAndLocalVariableSlots() {
        int offset = 4;
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "kernel", "(JJ)J", null, null);

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList insns = method.instructions;
        insns.add(start);
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 2));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 4));
        insns.add(new IincInsnNode(6, 3));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 4));
        insns.add(new InsnNode(Opcodes.LRETURN));
        insns.add(end);

        method.localVariables = new ArrayList<>();
        method.localVariables.add(new LocalVariableNode("lhs", "J", null, start, end, 0));
        method.localVariables.add(new LocalVariableNode("rhs", "J", null, start, end, 2));

        SlotRemapper.shift(method, offset);

        assertThat(varSlots(method.instructions), equalTo(new int[] { 0 + offset, 2 + offset, 4 + offset, 4 + offset }));
        assertThat(iincSlot(method.instructions), equalTo(6 + offset));
        assertThat(method.localVariables.get(0).index, equalTo(0 + offset));
        assertThat(method.localVariables.get(1).index, equalTo(2 + offset));
    }

    public void testRejectsJsr() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "legacy", "()V", null, null);
        LabelNode subroutine = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.JSR, subroutine));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(subroutine);

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> SlotRemapper.shift(method, 4));
        assertThat(e.getMessage(), containsString("JSR/RET"));
    }

    public void testRejectsRet() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "legacy", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.RET, 1));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> SlotRemapper.shift(method, 4));
        assertThat(e.getMessage(), containsString("JSR/RET"));
    }

    public void testRejectsNegativeOffset() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "kernel", "(J)J", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> SlotRemapper.shift(method, -1));
        assertThat(e.getMessage(), containsString("non-negative"));
    }

    private static int[] varSlots(InsnList instructions) {
        var slots = new ArrayList<Integer>();
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode varInsn) {
                slots.add(varInsn.var);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int iincSlot(InsnList instructions) {
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof IincInsnNode iincInsn) {
                return iincInsn.var;
            }
        }
        throw new AssertionError("no IINC instruction present");
    }
}
