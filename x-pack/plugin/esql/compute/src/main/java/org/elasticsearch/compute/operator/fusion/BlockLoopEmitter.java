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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Emits the nullable/multi-valued block-loop fused classes: the arithmetic block loop, the mapping block loop,
 * and the 3VL logical-block loop (with one {@code cmpN} helper per comparison operand). {@link Stitcher} owns
 * the public {@code compile*} façade and define boundary; this class is the block-path seam those entry points
 * delegate to. {@link BlockDfsEmitter} lives beside it for the arithmetic DFS walk.
 */
final class BlockLoopEmitter {

    private final TemplateRegistry templates;
    private final Consumer<MethodNode> hostMethodInterceptor;

    BlockLoopEmitter(TemplateRegistry templates, Consumer<MethodNode> hostMethodInterceptor) {
        this.templates = templates;
        this.hostMethodInterceptor = hostMethodInterceptor;
    }

    private Body extractKernelBody(FusionNode.Kernel kernel) {
        return BodyExtractor.extract(templates.methodNode(kernel.descriptor()));
    }

    /**
     * Builds the class bytes for the fused block loop. Kept separate from {@link Stitcher#compileBlockLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    byte[] emitBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        // One walk derives arity, constants-in-emit-order and warning-source indices for this emit path.
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        // Only the inputs the tree actually references are null-checked and read. The method still takes one *Block
        // parameter per index in [0, arity) (so callers bind positionally by leaf index), but guarding an input the
        // expression never consumes would over-nullify: a null/multi-value in an unused input would wrongly null the
        // whole position even though no kernel reads it. The unfused chain only inspects operands it actually uses,
        // so we mirror that by iterating the consumed-input set.
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs OUTPUT element (the builder +
        // produced block). Each *Block parameter is typed to its input's own element.
        Element[] inputElements = Stitcher.inputElements(signature);
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        // Frame layout: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input *Blocks. The
        // builder, loop counter, a scratch value-count slot, the built result slot, the caught-throwable slot
        // (builder-safety), the per-kernel overflow-exception slot and the value-path {@code live} flag sit in the
        // fixed region above the parameters; each node then gets a disjoint present/value/body range above that
        // (allocated by the DFS emitter from {@code workBase}). The DFS emitter (see below) reads each consumed leaf
        // inline into the enclosing kernel's operand slots, so there are no separate pre-read leaf value slots.
        // One trailing primitive parameter per embedded constant (canonical emit order) sits between the input *Blocks
        // and the scratch region, so every scratch base is shifted up by the constant parameters' total slot size.
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        // Parameter region [0..): BlockFactory, Warnings[], positionCount, one *Block per input, one primitive per constant.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        int warningsArraySlot = frame.slot();                               // [1] Warnings[]
        int pcSlot = frame.slot();                                          // [2] int positionCount
        int inputBase = frame.slots(arity);                                 // [3 .. 3+arity) input *Blocks
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(constants, frame);
        // Scratch region above the parameters: the builder, loop counter, a scratch value-count slot, the built result
        // slot, the caught-throwable slot (builder-safety), the per-kernel overflow-exception slot, then:
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int countSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        // A scratch slot for the exception caught by any single kernel's overflow handler (handlers never overlap in
        // execution, so one slot is shared).
        int overflowExcSlot = frame.slot();
        // The per-position value-path flag: {@code true} until the whole-tree short-circuit fires (the first null /
        // multi-value / overflow the value DFS meets), then {@code false}. It gates ONLY the value-dependent overflow /
        // div-by-zero warning registration to the ratified short-circuit subset — an off-value-path kernel body still
        // runs (its result feeds a sibling's per-kernel multi-value gate) but its overflow is caught silently. Leaf
        // multi-value warnings are NOT gated by {@code live}: each kernel emits them in its own operand order so they
        // match the unfused chain (which runs every kernel's loop fully), independent of ancestor short-circuits.
        int liveSlot = frame.slot();
        // One reusable BytesRef spare per BYTES_REF (keyword/text) column, allocated below the DFS work region and
        // initialised before the loop. The allocator always reserves the historical one-slot gap (so 0- and
        // 1-reference trees stay byte-identical); a 2nd+ reference input gets a new slot just below workBase.
        int[] spareByInput = Stitcher.allocBytesRefSpareSlots(frame, inputElements);
        int workBase = frame.mark();

        // Each kernel's warning-source slot into the runtime Warnings[]: its overflow warning and its direct leaf
        // operands' multi-value warnings register on warnings[its slot], matching the unfused per-node evaluator source.
        Map<FusionNode, Integer> sourceIndices = signature.warningSourceIndices();

        // Each input *Block param uses its OWN element; the returned block uses the OUTPUT element (BooleanBlock for a
        // comparison root).
        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY)
            .append(";")
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        for (int i = 0; i < arity; i++) {
            desc.append(inputElements[i].blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Stitcher.FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // out = bf.new*BlockBuilder(positionCount); the builder is the OUTPUT element.
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

        // Allocate one reusable BytesRef spare per reference column, before the loop. Plain locals (no breaker
        // accounting), so they live outside the builder's try region. No-op when the tree has no reference input.
        Stitcher.emitBytesRefSpareInits(insns, spareByInput);

        // The builder is a resource: everything up to and including build() runs inside a try whose catch-all
        // handler closes it before rethrowing, so a break part-way through does not leak breaker bytes.
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        insns.add(tryStart);

        // int p = 0;
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode appendNull = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // Per-position DFS evaluation. Each node computes a {@code present} flag (did this node produce a value at
        // {@code p}?) and, when present, its value, both into disjoint local slots. The tree VALUE / null position is
        // exactly today's whole-tree short-circuit — a position is null iff the root is not present, i.e. iff any
        // consumed leaf is null / multi-value or any kernel on the value path overflows.
        //
        // The difference from a single unified short-circuit DFS is WARNING fidelity: the unfused chain runs every
        // generated evaluator's loop FULLY, so a leaf's multi-value warning fires iff, WITHIN its own consuming
        // kernel's operand order, that kernel's earlier operands are present — INDEPENDENT of whether an ancestor
        // kernel already short-circuited the position. So each kernel here emits its own leaf operands' multi-value
        // warnings gated only by its OWN running {@code present} flag (never by an ancestor's), and every kernel body
        // whose own operands are present is still run (its result feeds a sibling's per-kernel gate) even off the
        // value path. Value-DEPENDENT warnings (overflow / div-by-zero) stay the ratified short-circuit subset: a
        // body's overflow is registered only while {@code live} is true (the value path has not yet short-circuited),
        // and caught silently otherwise. {@code live} is reset true here for each position.
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$BlockLoop", tree);
        BlockDfsEmitter emitter = new BlockDfsEmitter(
            templates,
            insns,
            host,
            inputElements,
            inputBase,
            pSlot,
            countSlot,
            warningsArraySlot,
            sourceIndices,
            liveSlot,
            overflowExcSlot,
            spareByInput,
            workBase,
            constantSlots,
            debug
        );
        NodeSlots root = emitter.emit(tree, outputElement);
        // if (root not present) appendNull; else append the root value.
        insns.add(new VarInsnNode(Opcodes.ILOAD, root.present()));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, appendNull));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(outputElement.type().getOpcode(Opcodes.ILOAD), root.value()));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                outputElement.appendMethod(),
                outputElement.appendDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, next));

        // out.appendNull(); reached when the root is not present (a null / multi-value leaf or an overflow on the value
        // path short-circuited the position; any warning was already registered inline by the emitter).
        insns.add(appendNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                "appendNull",
                outputElement.appendNullDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
        // result = out.build(); build() transfers the builder's breaker accounting to the produced block.
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(Opcodes.INVOKEINTERFACE, outputElement.builderInternalName(), "build", outputElement.buildDescriptor(), true)
        );
        insns.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        insns.add(tryEnd);
        // Normal exit: close the builder (a no-op after build()) and return the block.
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new InsnNode(Opcodes.ARETURN));

        // Catch-all: close the builder to release its breaker bytes, then rethrow.
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, excSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, excSlot));
        insns.add(new InsnNode(Opcodes.ATHROW));

        host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = Stitcher.newFusedClass(Stitcher.fusedClassName(caller, "Fused$$BlockLoop"), host, debug);
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "block loop");
    }

    byte[] emitMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        if (tree instanceof FusionNode.Kernel == false) {
            throw new Stitcher.StitchingException("mapping path requires a single convert kernel root", null);
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        Element[] inputElements = Stitcher.inputElements(signature);
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        // Shape guard: a unary reference (BytesRef) input, a reference output, a NON-throwing body. The planner only
        // routes such convert kernels here; anything else is a planner bug — refuse rather than emit wrong code. The
        // hasOverflowException() clause deliberately rejects any warn-exception convert kernel: this loop opens the
        // multi-value positionEntry EAGERLY (before the value loop), which is only correct when no value can fail; a
        // warn-exception kernel (where some values null out and an all-fail position must become null) would need the
        // lazy open-on-first-append the generated evalBlock uses, so such kernels must not reach here.
        if (arity != 1
            || inputElements[0].reference() == false
            || outputElement.reference() == false
            || root.descriptor().hasOverflowException()) {
            throw new Stitcher.StitchingException("mapping path requires a unary BytesRef->BytesRef non-throwing convert kernel", null);
        }
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        Type[] argTypes = Type.getMethodType(root.descriptor().kernelType()).getArgumentTypes();
        List<FusionNode> children = root.children();
        Body body = extractKernelBody(root);

        // Frame: [0] BlockFactory, [1] Warnings[] (unused; kept so the signature matches the factory's blockType), [2]
        // positionCount, [3] the single input BytesRefBlock, then one reference param per @Fixed constant, then scratch.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                    // [0] BlockFactory
        frame.slot();                                    // [1] Warnings[]
        int pcSlot = frame.slot();                       // [2] int positionCount
        int inputBase = frame.slots(arity);              // [3] input BytesRefBlock
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(constants, frame);
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int countSlot = frame.slot();
        int fviSlot = frame.slot();
        int iSlot = frame.slot();
        int spareSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int bodyBase = frame.mark();
        SlotRemapper.shift(body.computation(), bodyBase);

        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY)
            .append(";")
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        desc.append(inputElements[0].blockDescriptor());
        Stitcher.appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Stitcher.FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // builder = bf.new*BlockBuilder(positionCount); spare = new BytesRef();
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
        Stitcher.emitSpareBytesRefInit(insns, spareSlot);

        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        insns.add(tryStart);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode appendNull = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        // count = in0.getValueCount(p)
        insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getValueCount", "(I)I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, appendNull)); // count == 0 -> appendNull

        // if (count > 1) builder.beginPositionEntry();
        LabelNode afterBegin = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, afterBegin));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                "beginPositionEntry",
                "()" + outputElement.builderDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(afterBegin);

        // fvi = in0.getFirstValueIndex(p); i = 0;
        insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getFirstValueIndex", "(I)I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, fviSlot));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, iSlot));

        LabelNode valLoop = new LabelNode();
        LabelNode valLoopEnd = new LabelNode();
        insns.add(valLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, valLoopEnd));
        // Store each operand into the body's argument slot: the single Input reads value (fvi+i) into the reusable
        // spare; each @Fixed reference constant is loaded from its parameter slot.
        int off = 0;
        for (int j = 0; j < children.size(); j++) {
            Type argType = argTypes[j];
            int argSlot = bodyBase + off;
            FusionNode child = children.get(j);
            if (child instanceof FusionNode.Input) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase));
                insns.add(new VarInsnNode(Opcodes.ILOAD, fviSlot));
                insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new VarInsnNode(Opcodes.ALOAD, spareSlot));
                insns.add(
                    new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        inputElements[0].blockInternalName(),
                        inputElements[0].getValueMethod(),
                        inputElements[0].getValueDescriptor(),
                        true
                    )
                );
                insns.add(new VarInsnNode(Opcodes.ASTORE, argSlot));
            } else {
                FusionNode.Constant constant = (FusionNode.Constant) child;
                Stitcher.loadConstantParam(
                    insns,
                    constant.isReference() ? null : Element.of(argType),
                    constant,
                    constantSlots.get(constant)
                );
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ISTORE), argSlot));
            }
            off += argType.getSize();
        }
        // Run the (non-throwing) convert body; it leaves the converted value on the stack, then append it.
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$MappingBlockLoop", tree);
        Stitcher.spliceKernelBody(insns, body.computation(), root, debug);
        insns.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                outputElement.appendMethod(),
                outputElement.appendDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new IincInsnNode(iSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, valLoop));
        insns.add(valLoopEnd);

        // if (count > 1) builder.endPositionEntry();
        insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, next));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                "endPositionEntry",
                "()" + outputElement.builderDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, next));

        insns.add(appendNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                "appendNull",
                outputElement.appendNullDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
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

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }
        ClassNode classNode = Stitcher.newFusedClass(Stitcher.fusedClassName(caller, "Fused$$MappingBlockLoop"), host, debug);
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "mapping block loop");
    }

    /**
     * Builds the class bytes for the fused logical block loop: one {@code private static} {@code cmpN} helper per
     * comparison operand plus the top-level {@code fused} loop that calls them and combines their tri-states with the
     * short-circuit 3VL truth tables.
     */
    byte[] emitLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        if (FusionNode.isBooleanLogic(tree) == false) {
            throw new IllegalArgumentException("fused logical block loop requires a Logical or Not root, not " + tree);
        }
        // One walk derives arity, warning-source indices and constants-in-emit-order for this emit path.
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        // INPUT element: the numeric kind of the comparison operands' columns (long/int/double). OUTPUT: nullable boolean.
        Element inputElement = Element.of(logicalInputType(tree));
        Element outputElement = Element.of(Type.BOOLEAN_TYPE);

        String internalName = Stitcher.fusedClassName(caller, "Fused$$LogicalBlockLoop");
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$LogicalBlockLoop", tree);

        // One helper per comparison operand, in DFS order; the map lets the loop emitter resolve each comparison's call.
        Map<FusionNode, String> helperNames = new IdentityHashMap<>();
        List<MethodNode> helpers = new ArrayList<>();
        assignComparisonHelpers(tree, inputElement, helperNames, helpers, debug);
        // Each comparison's warning-source slot into the runtime Warnings[]; a comparison registers its multi-value
        // warning on warnings[its slot] so the header matches the unfused comparison evaluator's node source.
        Map<FusionNode, Integer> sourceIndices = signature.warningSourceIndices();

        // Frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input *Blocks, then one
        // trailing primitive parameter per embedded constant (canonical emit order). Builder, loop counter, built
        // result, and caught-throwable slots sit above (their bases shifted up by the constant parameters' slot size);
        // the tri-state node slots start at workBase. The top-level loop loads each constant from its parameter slot
        // and threads it into the relevant cmpN helper call.
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        // Parameter region [0..): BlockFactory, Warnings[], positionCount, one *Block per input, one primitive per constant.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        int warningsArraySlot = frame.slot();                               // [1] Warnings[]
        int pcSlot = frame.slot();                                          // [2] int positionCount
        int inputBase = frame.slots(arity);                                 // [3 .. 3+arity) input *Blocks
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(constants, frame);
        // Scratch region above the parameters: builder, loop counter, built result, caught-throwable; the tri-state
        // node slots start at workBase.
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int workBase = frame.mark();

        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY)
            .append(";")
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        for (int i = 0; i < arity; i++) {
            desc.append(inputElement.blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Stitcher.FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // out = bf.newBooleanBlockBuilder(positionCount);
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

        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        insns.add(tryStart);

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode doNull = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // Evaluate the logical tree for position p into a tri-state (0 false / 1 true / 2 null) in tRoot.
        LogicalDfsEmitter emitter = new LogicalDfsEmitter(
            insns,
            inputElement,
            inputBase,
            pSlot,
            warningsArraySlot,
            sourceIndices,
            internalName,
            helperNames,
            workBase,
            constantSlots
        );
        int tRoot = emitter.alloc(1);
        emitter.emit(tree, tRoot);

        insns.add(new VarInsnNode(Opcodes.ILOAD, tRoot));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, doNull));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, tRoot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                outputElement.appendMethod(),
                outputElement.appendDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, next));

        insns.add(doNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.builderInternalName(),
                "appendNull",
                outputElement.appendNullDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
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

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = Stitcher.newFusedClass(internalName, host, debug);
        classNode.methods.addAll(helpers);
        // Enforce the huge-method limit on every method (criterion #6). The helpers keep the top-level loop small even
        // for nested trees; if any method exceeds the limit we refuse to fuse (→ unfused fallback) rather than emit
        // a method HotSpot will not JIT-compile.
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "logical block loop");
    }

    /**
     * Assigns each comparison operand ({@link FusionNode.Kernel}) in {@code tree} a {@code private static} helper method
     * (named {@code cmp0}, {@code cmp1}, … in DFS order) and records the name so the loop emitter can call it.
     */
    void assignComparisonHelpers(
        FusionNode node,
        Element inputElement,
        Map<FusionNode, String> names,
        List<MethodNode> helpers,
        FusedClassDebugInfo debug
    ) {
        if (node instanceof FusionNode.Logical logical) {
            assignComparisonHelpers(logical.left(), inputElement, names, helpers, debug);
            assignComparisonHelpers(logical.right(), inputElement, names, helpers, debug);
            return;
        }
        if (node instanceof FusionNode.Not not) {
            assignComparisonHelpers(not.child(), inputElement, names, helpers, debug);
            return;
        }
        FusionNode.Kernel comparison = (FusionNode.Kernel) node;
        String name = "cmp" + helpers.size();
        names.put(comparison, name);
        helpers.add(emitComparisonHelper(comparison, inputElement, name, debug));
    }

    /**
     * Builds a {@code private static int cmpN(Warnings w, int p, <Block> b0, <Block> b1)} helper that reproduces the
     * generated comparison evaluator's block path for one position and returns a tri-state: {@code 0} false, {@code 1}
     * true, {@code 2} null. A {@code null} input ({@code getValueCount == 0}) returns {@code 2} with no warning; a
     * multi-valued input registers the single-value multi-value warning and returns {@code 2}; otherwise the single
     * value at {@code getFirstValueIndex(p)} is read and the spliced comparison kernel body's {@code 0}/{@code 1} is
     * returned.
     */
    MethodNode emitComparisonHelper(FusionNode.Kernel comparison, Element inputElement, String methodName, FusedClassDebugInfo debug) {
        Body body = extractKernelBody(comparison);
        int size = inputElement.type().getSize();
        List<FusionNode> children = comparison.children();
        // Only column ({@link FusionNode.Input}) operands become *Block parameters; a {@link FusionNode.Constant}
        // operand is passed as a trailing primitive parameter (after the *Blocks, in canonical emit order) and loaded
        // into the body's argument slot with no null / multi-value guard.
        int columnOperands = 0;
        for (FusionNode child : children) {
            if (child instanceof FusionNode.Input) {
                columnOperands++;
            }
        }
        List<FusionNode.Constant> helperConstants = Stitcher.constantsInEmitOrder(comparison);

        StringBuilder desc = new StringBuilder("(L").append(Stitcher.WARNINGS).append(";I");
        for (int i = 0; i < columnOperands; i++) {
            desc.append(inputElement.blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, helperConstants);
        desc.append(")I");

        MethodNode helper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, methodName, desc.toString(), null, null);
        InsnList insns = helper.instructions;

        // Params [0..): Warnings, int p, one *Block per column operand, one primitive per constant. Then scratch + body.
        FrameLayout frame = new FrameLayout();
        int warningsSlot = frame.slot();
        int pSlot = frame.slot();
        int blockBase = frame.slots(columnOperands);         // block params occupy [2 .. 2+columnOperands)
        Map<FusionNode.Constant, Integer> helperConstantSlots = Stitcher.allocConstantSlots(helperConstants, frame);
        int countSlot = frame.slot();                        // scratch for getValueCount
        int bodyBase = frame.mark();                         // the comparison body's shifted locals start here
        SlotRemapper.shift(body.computation(), bodyBase);

        LabelNode retNull = new LabelNode();
        int offset = 0;         // running offset into the comparison body's argument slots (one per operand, in order)
        int blockParam = 0;     // running index of the next *Block parameter (column operands only)
        for (FusionNode child : children) {
            if (child instanceof FusionNode.Constant constant) {
                // Constant operand: load it from its trailing helper parameter into the body's shifted argument slot;
                // no null / multi-value guard (a constant is always present and single-valued).
                Stitcher.loadConstantParam(insns, inputElement, constant, helperConstantSlots.get(constant));
                insns.add(new VarInsnNode(inputElement.type().getOpcode(Opcodes.ISTORE), bodyBase + offset));
                offset += size;
                continue;
            }
            int block = blockBase + blockParam;
            blockParam++;
            LabelNode read = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getValueCount", "(I)I", true));
            insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, retNull)); // count == 0 -> null, no warning
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, read)); // count == 1 -> single value
            // count > 1 -> register the multi-value warning on this comparison's own Warnings (the caller resolved
            // warnings[this-comparison's-source-slot] and passed it as the single w parameter), then null.
            insns.add(new VarInsnNode(Opcodes.ALOAD, warningsSlot));
            Stitcher.emitMultiValueWarningOn(insns);
            insns.add(new JumpInsnNode(Opcodes.GOTO, retNull));
            insns.add(read);
            // v = block.get*(block.getFirstValueIndex(p)); store into the comparison body's shifted arg slot.
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, Stitcher.BLOCK, "getFirstValueIndex", "(I)I", true));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    inputElement.blockInternalName(),
                    inputElement.getValueMethod(),
                    inputElement.getValueDescriptor(),
                    true
                )
            );
            insns.add(new VarInsnNode(inputElement.type().getOpcode(Opcodes.ISTORE), bodyBase + offset));
            offset += size;
        }
        // All operands present and single-valued: run the comparison body (leaves int 0/1) and return it.
        Stitcher.spliceKernelBody(insns, body.computation(), comparison, debug);
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(retNull);
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
        insns.add(new InsnNode(Opcodes.IRETURN));
        return helper;
    }

    /**
     * The numeric input element of a logical tree: the argument kind of the first comparison operand reached by
     * descending left spines and {@link FusionNode.Not} children. Every comparison in a fusable logical tree is
     * homogeneous in the same primitive, so any one determines the element.
     */
    static Type logicalInputType(FusionNode tree) {
        if (tree instanceof FusionNode.Kernel kernel) {
            return Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes()[0];
        }
        if (tree instanceof FusionNode.Not not) {
            return logicalInputType(not.child());
        }
        return logicalInputType(((FusionNode.Logical) tree).left());
    }

    /**
     * Post-order emitter for the fused logical block loop. Leaves each node's tri-state ({@code 0} false / {@code 1}
     * true / {@code 2} null) in an assigned local slot; comparison operands call their {@code private static} helper,
     * {@link FusionNode.Logical} nodes combine two child tri-states with the short-circuit 3VL truth tables of
     * {@code BinaryLogicOperation}, and {@link FusionNode.Not} flips {@code 0↔1} keeping {@code 2}.
     */
    static final class LogicalDfsEmitter {
        private final InsnList insns;
        private final Element inputElement;
        private final int inputBase;
        private final int pSlot;
        private final int warningsArraySlot;
        private final Map<FusionNode, Integer> sourceIndices;
        private final String internalName;
        private final Map<FusionNode, String> helperNames;
        private int nextSlot;
        private final Map<FusionNode.Constant, Integer> constantSlots;

        LogicalDfsEmitter(
            InsnList insns,
            Element inputElement,
            int inputBase,
            int pSlot,
            int warningsArraySlot,
            Map<FusionNode, Integer> sourceIndices,
            String internalName,
            Map<FusionNode, String> helperNames,
            int workBase,
            Map<FusionNode.Constant, Integer> constantSlots
        ) {
            this.insns = insns;
            this.inputElement = inputElement;
            this.inputBase = inputBase;
            this.pSlot = pSlot;
            this.warningsArraySlot = warningsArraySlot;
            this.sourceIndices = sourceIndices;
            this.internalName = internalName;
            this.helperNames = helperNames;
            this.nextSlot = workBase;
            this.constantSlots = constantSlots;
        }

        /** Claims {@code size} fresh, disjoint local slots and returns the base. */
        int alloc(int size) {
            int slot = nextSlot;
            nextSlot += size;
            return slot;
        }

        /** Emits code leaving {@code node}'s tri-state value in {@code tSlot}. */
        void emit(FusionNode node, int tSlot) {
            if (node instanceof FusionNode.Logical logical) {
                emitLogical(logical, tSlot);
            } else if (node instanceof FusionNode.Not not) {
                emitNot(not, tSlot);
            } else {
                emitComparisonCall((FusionNode.Kernel) node, tSlot);
            }
        }

        /**
         * Kleene 3VL negation: flip 0↔1, keep 2. The child tri-state is evaluated first (and its comparison
         * warnings fire); Not itself is not a warning source.
         */
        void emitNot(FusionNode.Not not, int tOut) {
            int tChild = alloc(1);
            emit(not.child(), tChild);
            LabelNode setNull = new LabelNode();
            LabelNode done = new LabelNode();
            // if (child == 2) -> 2; else -> child XOR 1
            insns.add(new VarInsnNode(Opcodes.ILOAD, tChild));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
            insns.add(new VarInsnNode(Opcodes.ILOAD, tChild));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(new JumpInsnNode(Opcodes.GOTO, done));
            insns.add(setNull);
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(done);
        }

        void emitComparisonCall(FusionNode.Kernel comparison, int tSlot) {
            String name = helperNames.get(comparison);
            // Column ({@link FusionNode.Input}) operands are passed as *Block arguments; constant operands follow as
            // trailing primitive arguments (in canonical emit order), loaded from the top-level fused method's own
            // constant parameter slots — so the helper is byte-identical for every constant value.
            List<FusionNode.Constant> callConstants = Stitcher.constantsInEmitOrder(comparison);
            StringBuilder callDesc = new StringBuilder("(L").append(Stitcher.WARNINGS).append(";I");
            for (FusionNode child : comparison.children()) {
                if (child instanceof FusionNode.Input) {
                    callDesc.append(inputElement.blockDescriptor());
                }
            }
            Stitcher.appendConstantParamDescriptors(callDesc, callConstants);
            callDesc.append(")I");

            // Pass this comparison's OWN Warnings (warnings[its source slot]) so its multi-value warning is attributed
            // to the comparison's node source — the same header the unfused comparison evaluator would emit.
            Stitcher.loadWarnings(insns, warningsArraySlot, sourceIndices.get(comparison));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            for (FusionNode child : comparison.children()) {
                if (child instanceof FusionNode.Input input) {
                    insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + input.index()));
                }
            }
            // Then the non-baked constant arguments, loaded from the top-level fused method's trailing parameter slots
            // in canonical emit order. Baked constants are not helper params (the helper body pushes the immediate).
            for (FusionNode.Constant constant : callConstants) {
                Stitcher.pushConstantArg(insns, constant.element(), constantSlots.get(constant));
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, name, callDesc.toString(), false));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tSlot));
        }

        void emitLogical(FusionNode.Logical logical, int tOut) {
            boolean and = logical.op() == FusionNode.BoolOp.AND;
            // Evaluate BOTH operands unconditionally so each sub-comparison's multi-value warning fires — matching the
            // unfused BooleanLogicExpressionEvaluator (which evaluates both sides over the whole page) and the
            // arithmetic block path's per-kernel warning emission. The 3VL VALUE is then a pure function of the two
            // tri-states, so dropping the eval-time short-circuit changes only WHICH warnings fire (now exact parity,
            // still ⊆ unfused), never the value/null result.
            int tLeft = alloc(1);
            emit(logical.left(), tLeft);
            int tRight = alloc(1);
            emit(logical.right(), tRight);

            // The tri-state that, on either operand, decides the result — and equals it: false (0) for AND, true (1)
            // for OR. When neither side is decisive nor null, the result is the "otherwise" value: true for AND (both
            // true), false for OR (both false).
            int decisive = and ? 0 : 1;
            int otherwise = and ? 1 : 0;

            LabelNode setDecisive = new LabelNode();
            LabelNode setNull = new LabelNode();
            LabelNode done = new LabelNode();

            // if (left == decisive || right == decisive) -> decisive
            insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
            insns.add(new InsnNode(Opcodes.ICONST_0 + decisive));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setDecisive));
            insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
            insns.add(new InsnNode(Opcodes.ICONST_0 + decisive));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setDecisive));
            // else if (left == null(2) || right == null(2)) -> null
            insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
            insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
            // else -> otherwise (both non-null, non-decisive)
            insns.add(new InsnNode(Opcodes.ICONST_0 + otherwise));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(new JumpInsnNode(Opcodes.GOTO, done));
            insns.add(setDecisive);
            insns.add(new InsnNode(Opcodes.ICONST_0 + decisive));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(new JumpInsnNode(Opcodes.GOTO, done));
            insns.add(setNull);
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(done);
        }
    }

}
