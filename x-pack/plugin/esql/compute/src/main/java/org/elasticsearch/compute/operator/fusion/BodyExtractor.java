/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a fusable kernel method into the reusable "computation body" the stitcher needs to splice
 * into a host loop.
 *
 * <p><b>Why this exists.</b> Each kernel (e.g. {@code static long processLongs(long lhs, long rhs)
 * { return Math.addExact(lhs, rhs); }}) is a complete method: it loads its arguments, computes a
 * value, and hands that value back with a single terminal {@code xRETURN}. When the stitcher fuses
 * several kernels into one per-position body, the individual {@code xRETURN} opcodes must not
 * survive — a return in the middle of the fused loop would abort the whole method. This extractor
 * therefore returns the kernel's instructions with that single terminal return removed, and reports
 * the removed opcode separately so the stitcher knows the produced value's category (a
 * {@code long}/{@code double} occupies two slots, an {@code int}/{@code boolean}/reference one) when
 * it decides where to route the result.
 *
 * <p><b>What is kept.</b> The returned {@link Body#computation()} retains the kernel's
 * argument-loading instructions ({@code xLOAD}s) rather than stripping them. This is deliberate and
 * pairs with {@link SlotRemapper}: the stitcher assigns each kernel a disjoint local-slot range,
 * {@linkplain SlotRemapper#shift(MethodNode, int) shifts} the body's slots into that range, stores
 * the kernel's inputs into the (shifted) argument slots, then runs the body — which reads those
 * slots back exactly as the original method did. Stripping the loads would defeat the slot remap and
 * force a separate stack-plumbing protocol. Only the terminal return is removed; every label, line
 * number, and computation instruction is preserved so the debug-info {@link LocalVariableNode}
 * entries (whose scope labels may sit after the return) still reference live nodes.
 *
 * <p><b>What is rejected.</b> Only straight-line kernels — those that reach their single terminal
 * return with no other way for control to leave the body — can be spliced verbatim. An
 * {@link IllegalArgumentException} is raised when the body:
 * <ul>
 *   <li>uses the legacy {@code JSR}/{@code RET} subroutine opcodes (modern {@code javac} never
 *       emits these; they are unsafe to relocate);</li>
 *   <li>does not have exactly one return instruction, or its single return is not the terminal
 *       instruction (multiple or early returns are extra exits the fused loop cannot honour);</li>
 *   <li>contains an {@code ATHROW} reachable inside a loop (a back-edge). A throw from within a loop
 *       is an abnormal exit that straight-line splicing cannot reproduce. Forward branches (as
 *       emitted by comparison kernels for their {@code boolean} result) and internal method calls
 *       that may themselves throw (e.g. {@code Math.addExact}) are fine — those are ordinary
 *       instructions, not body-level {@code ATHROW}s.</li>
 * </ul>
 *
 * <p><b>Non-mutating.</b> The supplied node is only read. The returned computation is a deep clone
 * (instructions and their label references are rebuilt via {@link AbstractInsnNode#clone(Map)}), and
 * the returned locals are fresh {@link LocalVariableNode}s pointing at the cloned labels, so callers
 * may remap and splice the result without aliasing the input (typically a cached
 * {@link TemplateRegistry} template clone).
 *
 * <p>This runs on the cold plan-time path (once per expression-shape stitch), so the clone cost is
 * not in the per-row inlining budget.
 */
public final class BodyExtractor {

    private BodyExtractor() {}

    /**
     * The straight-line computation lifted out of a kernel method.
     *
     * @param computation  the kernel's instructions with the single terminal return removed; a deep
     *                      clone, safe to mutate and splice
     * @param returnOpcode the opcode of the removed terminal return ({@code IRETURN}, {@code LRETURN},
     *                      {@code FRETURN}, {@code DRETURN}, {@code ARETURN}, or {@code RETURN}),
     *                      telling the stitcher the category of the produced value
     * @param locals       fresh copies of the kernel's local-variable debug table, referencing the
     *                      cloned labels; empty if the kernel carried no local-variable table
     * @param maxStack     the kernel's original max operand-stack depth, carried through so the
     *                      stitcher can size the host frame
     * @param maxLocals    the kernel's original max local-slot count, carried through so the stitcher
     *                      can allocate a disjoint slot range for the next kernel
     */
    public record Body(InsnList computation, int returnOpcode, List<LocalVariableNode> locals, int maxStack, int maxLocals) {}

    /**
     * Extracts the {@link Body} of {@code method} without mutating it.
     *
     * @param method the kernel method to lift the computation body from
     * @return the return-free computation plus the terminal return opcode and frame sizing
     * @throws IllegalArgumentException if the body is not straight-line to a single terminal return
     *                                  (see the class javadoc for the exact rejection rules)
     */
    public static Body extract(MethodNode method) {
        InsnList source = method.instructions;

        // Index every node so branch targets can be classified as forward or backward (a loop).
        Map<AbstractInsnNode, Integer> positions = new HashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        AbstractInsnNode terminalReturn = null;
        AbstractInsnNode lastRealInsn = null;
        int returnCount = 0;
        boolean hasAthrow = false;
        boolean hasBackwardBranch = false;

        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode < 0) {
                // Pseudo-nodes (labels, line numbers, frames) carry no opcode; they are not real
                // instructions and never form an exit from the body.
                continue;
            }
            lastRealInsn = insn;

            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                throw new IllegalArgumentException(
                    "cannot extract body from a kernel using legacy JSR/RET subroutine opcodes (opcode [" + opcode + "])"
                );
            }
            if (opcode == Opcodes.ATHROW) {
                hasAthrow = true;
            }
            if (isReturn(opcode)) {
                returnCount++;
                terminalReturn = insn;
            }
            if (isBackwardBranch(insn, positions)) {
                hasBackwardBranch = true;
            }
        }

        if (returnCount != 1) {
            throw new IllegalArgumentException(
                "expected exactly one terminal return in the kernel body but found ["
                    + returnCount
                    + "]; multiple or early returns are extra exits the fused loop cannot honour"
            );
        }
        if (terminalReturn != lastRealInsn) {
            throw new IllegalArgumentException(
                "the kernel body's return is not its terminal instruction (early return); control must reach the single "
                    + "terminal return with no other exit"
            );
        }
        if (hasAthrow && hasBackwardBranch) {
            throw new IllegalArgumentException(
                "the kernel body contains an ATHROW reachable inside a loop; a throw from within a loop is an abnormal exit "
                    + "that straight-line splicing cannot reproduce"
            );
        }

        int returnOpcode = terminalReturn.getOpcode();
        Map<LabelNode, LabelNode> clonedLabels = cloneLabels(source);
        InsnList computation = cloneWithoutTerminalReturn(source, terminalReturn, clonedLabels);
        List<LocalVariableNode> locals = cloneLocals(method.localVariables, clonedLabels);

        return new Body(computation, returnOpcode, locals, method.maxStack, method.maxLocals);
    }

    private static boolean isReturn(int opcode) {
        return switch (opcode) {
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN -> true;
            default -> false;
        };
    }

    /**
     * Reports whether {@code insn} is a branch (conditional/unconditional jump or switch) whose target
     * sits at an earlier position than the branch itself, i.e. a back-edge that forms a loop.
     */
    private static boolean isBackwardBranch(AbstractInsnNode insn, Map<AbstractInsnNode, Integer> positions) {
        int here = positions.get(insn);
        if (insn instanceof JumpInsnNode jump) {
            return positions.get(jump.label) < here;
        }
        if (insn instanceof TableSwitchInsnNode tableSwitch) {
            if (positions.get(tableSwitch.dflt) < here) {
                return true;
            }
            for (LabelNode label : tableSwitch.labels) {
                if (positions.get(label) < here) {
                    return true;
                }
            }
            return false;
        }
        if (insn instanceof LookupSwitchInsnNode lookupSwitch) {
            if (positions.get(lookupSwitch.dflt) < here) {
                return true;
            }
            for (LabelNode label : lookupSwitch.labels) {
                if (positions.get(label) < here) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Builds a fresh {@link LabelNode} for every label in {@code source}. The map spans the whole
     * instruction list (including any labels after the terminal return) so that both the cloned
     * computation and the cloned local-variable table resolve their label references consistently.
     */
    private static Map<LabelNode, LabelNode> cloneLabels(InsnList source) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        return labels;
    }

    private static InsnList cloneWithoutTerminalReturn(
        InsnList source,
        AbstractInsnNode terminalReturn,
        Map<LabelNode, LabelNode> clonedLabels
    ) {
        InsnList computation = new InsnList();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn == terminalReturn) {
                continue;
            }
            computation.add(insn.clone(clonedLabels));
        }
        return computation;
    }

    private static List<LocalVariableNode> cloneLocals(List<LocalVariableNode> source, Map<LabelNode, LabelNode> clonedLabels) {
        List<LocalVariableNode> locals = new ArrayList<>();
        if (source == null) {
            return locals;
        }
        for (LocalVariableNode local : source) {
            locals.add(
                new LocalVariableNode(
                    local.name,
                    local.desc,
                    local.signature,
                    clonedLabels.get(local.start),
                    clonedLabels.get(local.end),
                    local.index
                )
            );
        }
        return locals;
    }
}
