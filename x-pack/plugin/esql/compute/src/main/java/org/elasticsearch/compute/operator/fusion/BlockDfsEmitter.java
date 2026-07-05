/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.operator.fusion.BodyExtractor.Body;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Map;

/**
 * The per-position, post-order DFS emitter for the fused <b>block</b> loop — the most intricate of the emit paths, so
 * it lives in its own file (extracted from {@link Stitcher}; behaviour unchanged). Each tree node computes a
 * {@code present} flag (did the node produce a value at position {@code p}?) and, when present, its value, into freshly
 * claimed disjoint local slots; a position is null iff the root is not present (any consumed leaf is null/multi-value
 * or any value-path kernel overflows). The subtle contract this preserves — WARNING fidelity — is spelled out below.
 *
 * <p><b>{@code present} vs {@code live}.</b> The unfused chain runs every generated evaluator's loop FULLY, so a leaf's
 * multi-value warning fires iff, WITHIN its own consuming kernel's operand order, that kernel's earlier operands are
 * present — INDEPENDENT of whether an ancestor kernel already short-circuited the position. So each kernel emits its
 * own leaf operands' multi-value warnings gated only by its OWN running {@code present} flag (never an ancestor's), and
 * every kernel body whose own operands are present still runs (its result may feed a sibling's per-kernel gate) even
 * off the value path. Value-DEPENDENT warnings (overflow / div-by-zero) are the ratified short-circuit SUBSET: a body's
 * overflow is registered only while {@code live} (the value path has not short-circuited) and caught silently
 * otherwise. This dual contract is pinned by the differential regressions (values/nulls identical; MV warnings
 * per-kernel operand-order, ancestor-independent; overflow/div-zero live-gated subset).
 *
 * <p>Shared emit primitives (arity check, constant load, warnings load, multi-value warning, int push) and the shared
 * constants live on {@link Stitcher} and are referenced qualified; the reusable kernel-body template is read through
 * the {@link TemplateRegistry} passed in.
 */
final class BlockDfsEmitter {
    private final TemplateRegistry templates;
    private final InsnList insns;
    private final MethodNode host;
    private final Element[] inputElements;
    private final int inputBase;
    private final int pSlot;
    private final int countSlot;
    private final int warningsArraySlot;
    private final Map<FusionNode, Integer> sourceIndices;
    private final int liveSlot;
    private final int overflowExcSlot;
    /**
     * Slot of the reusable {@code BytesRef} "spare" a {@code BYTES_REF} leaf reads into via
     * {@code getBytesRef(int, BytesRef)}. Allocated once by the host BEFORE the loop and passed in here; {@code -1}
     * when the tree has no reference input (then no leaf ever consults it). Reusing one spare across positions is
     * safe: the returned {@code BytesRef} is consumed by its kernel within the same position, exactly as the
     * generated unfused evaluator's per-eval {@code valScratch} does.
     */
    private final int spareBytesRefSlot;
    private final Map<FusionNode.Constant, Integer> constantSlots;
    private int next;

    BlockDfsEmitter(
        TemplateRegistry templates,
        InsnList insns,
        MethodNode host,
        Element[] inputElements,
        int inputBase,
        int pSlot,
        int countSlot,
        int warningsArraySlot,
        Map<FusionNode, Integer> sourceIndices,
        int liveSlot,
        int overflowExcSlot,
        int spareBytesRefSlot,
        int workBase,
        Map<FusionNode.Constant, Integer> constantSlots
    ) {
        this.templates = templates;
        this.insns = insns;
        this.host = host;
        this.inputElements = inputElements;
        this.inputBase = inputBase;
        this.pSlot = pSlot;
        this.countSlot = countSlot;
        this.warningsArraySlot = warningsArraySlot;
        this.sourceIndices = sourceIndices;
        this.liveSlot = liveSlot;
        this.overflowExcSlot = overflowExcSlot;
        this.spareBytesRefSlot = spareBytesRefSlot;
        this.constantSlots = constantSlots;
        this.next = workBase;
    }

    /**
     * Emits the DFS evaluation of the kernel {@code node} for the current position {@code p} into freshly claimed
     * present/value slots, and returns them. {@code expected} is the element the node must produce (its consuming
     * kernel's argument element, or the output element at the root); it types the value slot.
     */
    NodeSlots emit(FusionNode node, Element expected) {
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        Body body = BodyExtractor.extract(templates.methodNode(kernel.descriptor()));

        // Claim this node's disjoint slots: a present flag, an expected-wide value slot, then the body's local
        // range (arguments + temps). Children recurse and claim strictly higher slots, so no ranges collide.
        int presentSlot = next++;
        int valueSlot = next;
        next += expected.type().getSize();
        int nodeBase = next;
        next += body.maxLocals();
        SlotRemapper.shift(body.computation(), nodeBase);

        Type[] argTypes = Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes();
        var children = kernel.children();
        // A variadic kernel's LAST parameter is an array gathering the trailing children (e.g. CONCAT's BytesRef[]);
        // it has one child per FIXED arg plus zero-or-more varargs elements. A non-variadic kernel is 1:1.
        boolean variadic = kernel.descriptor().variadic();
        int fixedArgs = variadic ? argTypes.length - 1 : argTypes.length;
        if (variadic) {
            if (children.size() < fixedArgs) {
                throw new IllegalArgumentException(
                    "variadic kernel [" + kernel.descriptor().kernelMethod() + "] needs at least " + fixedArgs + " operands"
                );
            }
        } else {
            Stitcher.checkKernelArity(kernel, argTypes.length, children.size());
        }
        int kernelSlot = sourceIndices.get(kernel);

        // Pre-init value slot + argument slots to a typed zero for verifier definite-assignment (they are read only
        // on the present path, but the verifier cannot prove that value-level correlation). The variadic array arg
        // is a reference, so storeZeroForType nulls it.
        storeZero(valueSlot, expected);
        int initOffset = 0;
        for (Type argType : argTypes) {
            // Type-based (not Element-based): an object @Fixed argument (e.g. Locale) has no Element, so seed it via
            // the raw-type helper which nulls any reference type.
            storeZeroForType(nodeBase + initOffset, argType);
            initOffset += argType.getSize();
        }

        // present = true; each not-present operand (in this kernel's own order) sets it false.
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));

        int offset = 0;
        for (int i = 0; i < fixedArgs; i++) {
            Type argType = argTypes[i];
            int argSlot = nodeBase + offset;
            FusionNode child = children.get(i);
            if (child instanceof FusionNode.Constant constant) {
                // A constant is always present and single-valued: load it into the argument slot unconditionally
                // (harmless when this kernel already short-circuited — the body will not run). A reference (object)
                // @Fixed constant has no primitive Element, so only a primitive constant resolves one.
                Stitcher.loadConstantParam(
                    insns,
                    constant.isReference() ? null : Element.of(argType),
                    constant,
                    constantSlots.get(constant)
                );
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ISTORE), argSlot));
            } else if (child instanceof FusionNode.Input input) {
                // A leaf input is always a supported primitive/BytesRef element (a reference @Fixed value is a
                // Constant, never an Input), so Element.of is safe here.
                emitLeaf(input, Element.of(argType), argSlot, presentSlot, kernelSlot);
            } else {
                // Nested kernel: ALWAYS emit it (so its own subtree's per-kernel warnings fire even when THIS
                // kernel already short-circuited), then fold its presence into this kernel's own present flag. A
                // nested kernel always produces a supported element, so Element.of is safe.
                Element argElem = Element.of(argType);
                NodeSlots childSlots = emit(child, argElem);
                LabelNode afterChild = new LabelNode();
                LabelNode childFail = new LabelNode();
                insns.add(new VarInsnNode(Opcodes.ILOAD, presentSlot));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, afterChild)); // this kernel already short-circuited: ignore
                insns.add(new VarInsnNode(Opcodes.ILOAD, childSlots.present()));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, childFail));
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), childSlots.value()));
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ISTORE), argSlot));
                insns.add(new JumpInsnNode(Opcodes.GOTO, afterChild));
                insns.add(childFail);
                // A not-present operand on the value path short-circuits the position (live=false); it is idempotent
                // otherwise. present=false stops later operands of THIS kernel from warning.
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));
                insns.add(afterChild);
            }
            offset += argType.getSize();
        }

        // Variadic kernel: build the trailing array argument from the remaining children (each a BytesRef column),
        // gathering them into a fresh array at the array parameter's slot. Any null/multi-value element short-
        // circuits the whole kernel (present=false), exactly like a fixed operand.
        if (variadic) {
            Element elementElem = Element.of(argTypes[argTypes.length - 1].getElementType());
            emitVarargsGather(nodeBase + offset, elementElem, children.subList(fixedArgs, children.size()), presentSlot, kernelSlot);
        }

        // Run the body iff every operand of THIS kernel was present. An overflow-checked body is wrapped so an
        // overflow (a) registers its warning only while live (ratified subset), (b) marks this kernel not present
        // and short-circuits the value path.
        LabelNode kDone = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, presentSlot));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, kDone));
        if (kernel.descriptor().hasOverflowException()) {
            String exType = kernel.descriptor().overflowExceptionType().replace('.', '/');
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode handler = new LabelNode();
            LabelNode skipRegister = new LabelNode();
            insns.add(tryStart);
            insns.add(body.computation());
            insns.add(new VarInsnNode(expected.type().getOpcode(Opcodes.ISTORE), valueSlot));
            insns.add(tryEnd);
            insns.add(new JumpInsnNode(Opcodes.GOTO, kDone));
            insns.add(handler);
            insns.add(new VarInsnNode(Opcodes.ASTORE, overflowExcSlot));
            // Register on THIS kernel's own Warnings only while the value path is still live (matches the ratified
            // short-circuit subset for value-dependent warnings); off the value path the overflow is caught
            // silently but still marks the kernel not present.
            insns.add(new VarInsnNode(Opcodes.ILOAD, liveSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, skipRegister));
            Stitcher.loadWarnings(insns, warningsArraySlot, kernelSlot);
            insns.add(new VarInsnNode(Opcodes.ALOAD, overflowExcSlot));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    Stitcher.WARNINGS,
                    "registerException",
                    Stitcher.WARNINGS_REGISTER_DESCRIPTOR,
                    false
                )
            );
            insns.add(skipRegister);
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));
            // fall through to kDone
            host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, exType));
        } else {
            insns.add(body.computation());
            insns.add(new VarInsnNode(expected.type().getOpcode(Opcodes.ISTORE), valueSlot));
        }
        insns.add(kDone);
        return new NodeSlots(presentSlot, valueSlot);
    }

    /**
     * Emits the null / multi-value guard for a leaf {@link FusionNode.Input} consumed by the kernel whose warning
     * source is {@code kernelSlot} and whose running present flag is {@code presentSlot}. When the leaf is single-
     * valued its value is read into {@code argSlot}; a multi-value ({@code getValueCount > 1}) leaf registers the
     * single-value multi-value warning on {@code kernelSlot} — but ONLY when this kernel's earlier operands were
     * present (its {@code presentSlot} still {@code true}), matching the unfused kernel's own operand-order
     * short-circuit — and a null ({@code getValueCount == 0}) leaf registers no warning; both mark the kernel not
     * present and short-circuit the value path ({@code live=false}).
     */
    private void emitLeaf(FusionNode.Input input, Element argElem, int argSlot, int presentSlot, int kernelSlot) {
        Element inputElement = inputElements[input.index()];
        int block = inputBase + input.index();
        LabelNode afterOperand = new LabelNode();
        LabelNode leafFail = new LabelNode();
        LabelNode leafSingle = new LabelNode();
        // If this kernel already short-circuited, skip the leaf entirely: the unfused kernel never inspects (nor
        // warns about) an operand past its own short-circuit.
        insns.add(new VarInsnNode(Opcodes.ILOAD, presentSlot));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, afterOperand));
        insns.add(new VarInsnNode(Opcodes.ALOAD, block));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getValueCount", "(I)I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, leafFail)); // count == 0 -> null, no warning
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, leafSingle)); // count == 1 -> read the single value
        // count > 1 -> register the multi-value warning on THIS kernel's own Warnings, then fail.
        Stitcher.loadWarnings(insns, warningsArraySlot, kernelSlot);
        Stitcher.emitMultiValueWarningOn(insns);
        insns.add(leafFail);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterOperand));
        insns.add(leafSingle);
        // Primitive: v = block.get*(block.getFirstValueIndex(p)). Reference (BytesRef): v =
        // block.getBytesRef(block.getFirstValueIndex(p), spare) — the reusable spare is pushed as the extra arg
        // before the call. Either way the value is stored into this kernel's argument slot (ASTORE for a reference,
        // derived by Type#getOpcode).
        insns.add(new VarInsnNode(Opcodes.ALOAD, block));
        insns.add(new VarInsnNode(Opcodes.ALOAD, block));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getFirstValueIndex", "(I)I", true));
        if (inputElement.reference()) {
            assert spareBytesRefSlot >= 0 : "a BytesRef leaf requires a spare slot allocated by the host";
            insns.add(new VarInsnNode(Opcodes.ALOAD, spareBytesRefSlot));
        }
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                inputElement.blockInternalName(),
                inputElement.getValueMethod(),
                inputElement.getValueDescriptor(),
                true
            )
        );
        insns.add(new VarInsnNode(argElem.type().getOpcode(Opcodes.ISTORE), argSlot));
        insns.add(afterOperand);
    }

    /**
     * Builds a variadic kernel's trailing array argument: {@code arraySlot = new E[elements.size()]}, then reads each
     * element (a BytesRef column) with the same null / multi-value guard as {@link #emitLeaf} and stores it into the
     * array. Any null or multi-value element short-circuits the whole kernel ({@code present=false}) and a
     * multi-value element registers the single-value warning on {@code kernelSlot} — matching the unfused evaluator's
     * per-argument semantics. Each element is read into a <b>fresh</b> {@code new E()} (not the shared reusable
     * spare) because all elements coexist in the array; a shared spare would alias every slot to the last value.
     */
    private void emitVarargsGather(int arraySlot, Element elementElem, List<FusionNode> elements, int presentSlot, int kernelSlot) {
        String elementInternalName = elementElem.type().getInternalName();
        // array = new E[count]
        Stitcher.pushInt(insns, elements.size());
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, elementInternalName));
        insns.add(new VarInsnNode(Opcodes.ASTORE, arraySlot));
        for (int k = 0; k < elements.size(); k++) {
            FusionNode element = elements.get(k);
            if ((element instanceof FusionNode.Input) == false) {
                // The planner only builds variadic trees whose elements are BytesRef column inputs.
                throw new IllegalArgumentException("a variadic array element must be a column input but was [" + element + "]");
            }
            FusionNode.Input input = (FusionNode.Input) element;
            Element inputElement = inputElements[input.index()];
            int block = inputBase + input.index();
            LabelNode after = new LabelNode();
            LabelNode fail = new LabelNode();
            LabelNode single = new LabelNode();
            // If the kernel already short-circuited, skip this element entirely.
            insns.add(new VarInsnNode(Opcodes.ILOAD, presentSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, after));
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getValueCount", "(I)I", true));
            insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, fail)); // count == 0 -> null, no warning
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, single)); // count == 1 -> read
            // count > 1 -> single-value multi-value warning on THIS kernel's source, then fail.
            Stitcher.loadWarnings(insns, warningsArraySlot, kernelSlot);
            Stitcher.emitMultiValueWarningOn(insns);
            insns.add(fail);
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));
            insns.add(new JumpInsnNode(Opcodes.GOTO, after));
            insns.add(single);
            // array[k] = block.getBytesRef(block.getFirstValueIndex(p), new E()) — a fresh spare per element.
            insns.add(new VarInsnNode(Opcodes.ALOAD, arraySlot));
            Stitcher.pushInt(insns, k);
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getFirstValueIndex", "(I)I", true));
            insns.add(new TypeInsnNode(Opcodes.NEW, elementInternalName));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, elementInternalName, "<init>", "()V", false));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    inputElement.blockInternalName(),
                    inputElement.getValueMethod(),
                    inputElement.getValueDescriptor(),
                    true
                )
            );
            insns.add(new InsnNode(Opcodes.AASTORE));
            insns.add(after);
        }
    }

    /**
     * Stores a type-correct zero into {@code slot} for a raw JVM {@link Type} (verifier definite-assignment). Unlike
     * {@link #storeZero(int, Element)} this handles any reference/array type by seeding {@code null} directly, so it
     * works for an object {@code @Fixed} argument type (e.g. {@code Locale}) that has no {@link Element}.
     */
    private void storeZeroForType(int slot, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
        } else {
            storeZero(slot, Element.of(type));
        }
    }

    /** Stores a type-correct zero into {@code slot} (verifier definite-assignment for the present-path-only reads). */
    private void storeZero(int slot, Element element) {
        switch (element.type().getSort()) {
            case Type.LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
            }
            case Type.DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
            }
            case Type.OBJECT -> {
                // A reference (BytesRef) argument slot: seed with null so the verifier sees it definitely assigned
                // (it is read only on the present path, where emitLeaf has stored the real BytesRef).
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            default -> {
                // int / boolean (one slot, int category).
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
            }
        }
    }
}
