/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

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
 * Emits the fused filter+eval block-loop class: a {@code pred} helper (predicate tri-state), any {@code cmpN}
 * comparison helpers a logical predicate needs, a {@code projAppend} helper (projection value-or-null), and the
 * top-level {@code fused} loop that calls them and records surviving positions. {@link Stitcher} owns the public
 * {@link Stitcher#compileFilterEvalBlockLoop} façade and define boundary; this class is that path's emit seam.
 */
final class FilterEvalEmitter {

    private final TemplateRegistry templates;
    private final Consumer<MethodNode> hostMethodInterceptor;
    private final BlockLoopEmitter blocks;

    FilterEvalEmitter(TemplateRegistry templates, Consumer<MethodNode> hostMethodInterceptor, BlockLoopEmitter blocks) {
        this.templates = templates;
        this.hostMethodInterceptor = hostMethodInterceptor;
        this.blocks = blocks;
    }

    /**
     * Builds the class bytes for the fused filter+eval block loop: the {@code pred} helper (predicate tri-state), any
     * {@code cmpN} comparison helpers a logical predicate needs, the {@code projAppend} helper (projection value/null
     * append) and the top-level {@code fused} loop that calls them and records surviving positions. Kept separate from
     * {@link Stitcher#compileFilterEvalBlockLoop} so the verification boundary is exactly the {@code defineHiddenClass} call.
     */
    byte[] emitFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws Stitcher.StitchingException {
        // Shape guards: the projection root must be a kernel (a bare column/constant EVAL is not fused here), and the
        // predicate must be a boolean-returning comparison kernel or a 3VL logical tree. An ill-shaped tree throws a
        // bare RuntimeException before the define boundary, which the plan-time caller treats as a fusion failure and
        // falls back to the unfused chain (criterion #5).
        if ((projectionTree instanceof FusionNode.Kernel) == false) {
            throw new IllegalArgumentException("fused filter-eval requires a kernel projection root, not " + projectionTree);
        }
        boolean predicateIsLogical = FusionNode.isBooleanLogic(predicateTree);
        if (predicateIsLogical == false) {
            if ((predicateTree instanceof FusionNode.Kernel) == false) {
                throw new IllegalArgumentException("fused filter-eval requires a kernel/logical/Not predicate root, not " + predicateTree);
            }
            if (Stitcher.rootReturnType(predicateTree).getSort() != Type.BOOLEAN) {
                throw new IllegalArgumentException("fused filter-eval predicate root must return boolean");
            }
        }

        // One walk per sub-tree derives its arity, constants-in-emit-order and warning-source indices.
        FusionSignature predSignature = FusionSignature.of(predicateTree);
        FusionSignature projSignature = FusionSignature.of(projectionTree);
        int predArity = predSignature.arity();
        int projArity = projSignature.arity();
        Element[] predInputElements = Stitcher.inputElements(predSignature);
        Element predLogicalElement = predicateIsLogical ? Element.of(BlockLoopEmitter.logicalInputType(predicateTree)) : null;
        Element[] projInputElements = Stitcher.inputElements(projSignature);
        Element projOutputElement = Element.of(Stitcher.rootReturnType(projectionTree));

        List<FusionNode.Constant> predConstants = predSignature.constantsInEmitOrder();
        List<FusionNode.Constant> projConstants = projSignature.constantsInEmitOrder();

        // Per-source warning slots: the predicate's sources first, then the projection's (offset by predWarnCount), so
        // the single runtime Warnings[] holds both and each kernel registers against its own node source.
        Map<FusionNode, Integer> predSources = predSignature.warningSourceIndices();
        int predWarnCount = predSources.size();
        Map<FusionNode, Integer> projSourcesLocal = projSignature.warningSourceIndices();
        Map<FusionNode, Integer> mergedProjSources = new IdentityHashMap<>();
        for (Map.Entry<FusionNode, Integer> e : projSourcesLocal.entrySet()) {
            mergedProjSources.put(e.getKey(), predWarnCount + e.getValue());
        }

        String internalName = Stitcher.fusedClassName(caller, "Fused$$FilterEvalBlockLoop");
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$FilterEvalBlockLoop", predicateTree, projectionTree);

        // Build the helper methods. A logical predicate additionally needs one cmpN helper per comparison operand.
        List<MethodNode> methods = new ArrayList<>();
        Map<FusionNode, String> comparisonHelperNames = new IdentityHashMap<>();
        if (predicateIsLogical) {
            List<MethodNode> comparisonHelpers = new ArrayList<>();
            blocks.assignComparisonHelpers(predicateTree, predLogicalElement, comparisonHelperNames, comparisonHelpers, debug);
            methods.addAll(comparisonHelpers);
        }
        MethodNode predHelper = buildPredicateHelper(
            predicateTree,
            predArity,
            predInputElements,
            predLogicalElement,
            predConstants,
            predSources,
            internalName,
            comparisonHelperNames,
            debug
        );
        MethodNode projHelper = buildProjectionAppendHelper(
            projectionTree,
            projArity,
            projInputElements,
            projOutputElement,
            projConstants,
            mergedProjSources,
            debug
        );
        methods.add(predHelper);
        methods.add(projHelper);

        // Top-level fused loop frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3] int[] positions,
        // [4 .. 4+predArity) predicate input *Blocks, then the projection input *Blocks, then the predicate constant
        // params, then the projection constant params, then the scratch region.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        int warningsArraySlot = frame.slot();                               // [1] Warnings[]
        int pcSlot = frame.slot();                                          // [2] int positionCount
        int positionsSlot = frame.slot();                                   // [3] int[] positions
        int predInputBase = frame.slots(predArity);                         // predicate input *Blocks
        int projInputBase = frame.slots(projArity);                         // projection input *Blocks
        Map<FusionNode.Constant, Integer> topPredConstantSlots = Stitcher.allocConstantSlots(predConstants, frame);
        Map<FusionNode.Constant, Integer> topProjConstantSlots = Stitcher.allocConstantSlots(projConstants, frame);
        // Scratch region above the parameters.
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int rowCountSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int tSlot = frame.slot();

        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY)
            .append(";")
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I[I");
        for (int i = 0; i < predArity; i++) {
            desc.append(predInputElements[i].blockDescriptor());
        }
        for (int i = 0; i < projArity; i++) {
            desc.append(projInputElements[i].blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, predConstants);
        Stitcher.appendConstantParamDescriptors(desc, projConstants);
        desc.append(")").append(projOutputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Stitcher.FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // out = bf.new<Proj>BlockBuilder(positionCount) — a COMPACT builder that receives one entry per surviving row.
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                Stitcher.BLOCK_FACTORY,
                projOutputElement.newBlockBuilderMethod(),
                projOutputElement.newBlockBuilderDescriptor(),
                false
            )
        );
        insns.add(new VarInsnNode(Opcodes.ASTORE, builderSlot));

        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        insns.add(tryStart);

        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, rowCountSlot));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // t = pred(warnings, p, <predBlocks...>, <predConsts...>);
        StringBuilder predCallDesc = new StringBuilder("(").append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR).append("I");
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        for (int i = 0; i < predArity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, predInputBase + i));
            predCallDesc.append(predInputElements[i].blockDescriptor());
        }
        for (FusionNode.Constant constant : predConstants) {
            Stitcher.pushConstantArg(insns, constant.element(), topPredConstantSlots.get(constant));
        }
        Stitcher.appendConstantParamDescriptors(predCallDesc, predConstants);
        predCallDesc.append(")I");
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, "pred", predCallDesc.toString(), false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, tSlot));

        // if (t != 1) skip this position entirely (false OR null ⇒ dropped, no projection evaluated).
        insns.add(new VarInsnNode(Opcodes.ILOAD, tSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));

        // projAppend(out, warnings, p, <projBlocks...>, <projConsts...>); — evaluates + appends the projection value/null.
        StringBuilder projCallDesc = new StringBuilder("(").append(projOutputElement.builderDescriptor())
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        for (int i = 0; i < projArity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, projInputBase + i));
            projCallDesc.append(projInputElements[i].blockDescriptor());
        }
        for (FusionNode.Constant constant : projConstants) {
            Stitcher.pushConstantArg(insns, constant.element(), topProjConstantSlots.get(constant));
        }
        Stitcher.appendConstantParamDescriptors(projCallDesc, projConstants);
        projCallDesc.append(")V");
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, "projAppend", projCallDesc.toString(), false));

        // positions[rowCount] = p; rowCount++;
        insns.add(new VarInsnNode(Opcodes.ALOAD, positionsSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rowCountSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new IincInsnNode(rowCountSlot, 1));

        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
        // result = out.build(); the compact projection block's positionCount is exactly the surviving row count.
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                projOutputElement.builderInternalName(),
                "build",
                projOutputElement.buildDescriptor(),
                true
            )
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
        classNode.methods.addAll(methods);
        // The two per-position bodies are factored into the pred/projAppend (and cmpN) helpers, so the top-level fused
        // loop stays small; the huge-method guard measures every method (→ unfused fallback if any exceeds the limit).
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "filter-eval block loop");
    }

    /**
     * Builds the {@code private static int pred(Warnings[] w, int p, <input *Blocks>, <constants>)} helper that
     * evaluates the WHERE predicate for one position and returns its tri-state: {@code 0} false, {@code 1} true,
     * {@code 2} null. A {@link FusionNode.Logical} or {@link FusionNode.Not} predicate reuses {@link BlockLoopEmitter.LogicalDfsEmitter}
     * (calling the {@code cmpN} comparison helpers); a comparison {@link FusionNode.Kernel} (incl.
     * comparison-over-arithmetic) reuses {@link BlockDfsEmitter}, returning its boolean value when present and {@code 2}
     * otherwise. Predicate warnings are attributed to each kernel's own source (indices {@code [0, predWarnCount)} of
     * the shared {@code Warnings[]}).
     */
    private MethodNode buildPredicateHelper(
        FusionNode predicateTree,
        int predArity,
        Element[] predInputElements,
        Element predLogicalElement,
        List<FusionNode.Constant> predConstants,
        Map<FusionNode, Integer> predSources,
        String internalName,
        Map<FusionNode, String> comparisonHelperNames,
        FusedClassDebugInfo debug
    ) {
        StringBuilder desc = new StringBuilder("(").append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR).append("I");
        for (int i = 0; i < predArity; i++) {
            desc.append(predInputElements[i].blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, predConstants);
        desc.append(")I");

        MethodNode pred = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "pred", desc.toString(), null, null);
        InsnList insns = pred.instructions;

        // Params [0..): Warnings[], int p, one *Block per input, one primitive per constant. Then the scratch region.
        FrameLayout frame = new FrameLayout();
        int warningsArraySlot = frame.slot();
        int pSlot = frame.slot();
        int inputBase = frame.slots(predArity);
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(predConstants, frame);
        int countSlot = frame.slot();
        int liveSlot = frame.slot();
        int overflowExcSlot = frame.slot();
        int[] spareByInput = Stitcher.allocBytesRefSpareSlots(frame, predInputElements);
        int workBase = frame.mark();

        if (FusionNode.isBooleanLogic(predicateTree)) {
            BlockLoopEmitter.LogicalDfsEmitter emitter = new BlockLoopEmitter.LogicalDfsEmitter(
                insns,
                predLogicalElement,
                inputBase,
                pSlot,
                warningsArraySlot,
                predSources,
                internalName,
                comparisonHelperNames,
                workBase,
                constantSlots
            );
            int tRoot = emitter.alloc(1);
            emitter.emit(predicateTree, tRoot);
            insns.add(new VarInsnNode(Opcodes.ILOAD, tRoot));
            insns.add(new InsnNode(Opcodes.IRETURN));
        } else {
            // A comparison kernel (possibly over arithmetic): reset the value-path live flag, run the block DFS, and
            // map its present/value to a tri-state (present ? value(0/1) : null(2)).
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
            Stitcher.emitBytesRefSpareInits(insns, spareByInput);
            BlockDfsEmitter emitter = new BlockDfsEmitter(
                templates,
                insns,
                pred,
                predInputElements,
                inputBase,
                pSlot,
                countSlot,
                warningsArraySlot,
                predSources,
                liveSlot,
                overflowExcSlot,
                spareByInput,
                workBase,
                constantSlots,
                debug
            );
            NodeSlots root = emitter.emit(predicateTree, Element.of(Type.BOOLEAN_TYPE));
            LabelNode retNull = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, root.present()));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, retNull));
            insns.add(new VarInsnNode(Opcodes.ILOAD, root.value()));
            insns.add(new InsnNode(Opcodes.IRETURN));
            insns.add(retNull);
            insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
            insns.add(new InsnNode(Opcodes.IRETURN));
        }
        return pred;
    }

    /**
     * Builds the {@code static void projAppend(<Proj>Block.Builder out, Warnings[] w, int p, <input *Blocks>,
     * <constants>)} helper that evaluates the EVAL projection for one (surviving) position with the arithmetic
     * {@link BlockDfsEmitter} kernel DFS and appends its value — or {@code appendNull()} when the projection is not
     * present at {@code p} (a null / multi-value leaf or an overflow short-circuited it), exactly as the unfused
     * {@code Eval} would for that row. Projection warnings are attributed to each kernel's own source, offset into the
     * shared {@code Warnings[]} after the predicate's sources.
     */
    private MethodNode buildProjectionAppendHelper(
        FusionNode projectionTree,
        int projArity,
        Element[] projInputElements,
        Element projOutputElement,
        List<FusionNode.Constant> projConstants,
        Map<FusionNode, Integer> mergedProjSources,
        FusedClassDebugInfo debug
    ) {
        StringBuilder desc = new StringBuilder("(").append(projOutputElement.builderDescriptor())
            .append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        for (int i = 0; i < projArity; i++) {
            desc.append(projInputElements[i].blockDescriptor());
        }
        Stitcher.appendConstantParamDescriptors(desc, projConstants);
        desc.append(")V");

        MethodNode proj = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "projAppend", desc.toString(), null, null);
        InsnList insns = proj.instructions;

        // Params [0..): builder, Warnings[], int p, one *Block per input, one primitive per constant. Then scratch.
        FrameLayout frame = new FrameLayout();
        int builderSlot = frame.slot();
        int warningsArraySlot = frame.slot();
        int pSlot = frame.slot();
        int inputBase = frame.slots(projArity);
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(projConstants, frame);
        int countSlot = frame.slot();
        int liveSlot = frame.slot();
        int overflowExcSlot = frame.slot();
        int[] spareByInput = Stitcher.allocBytesRefSpareSlots(frame, projInputElements);
        int workBase = frame.mark();

        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
        Stitcher.emitBytesRefSpareInits(insns, spareByInput);
        BlockDfsEmitter emitter = new BlockDfsEmitter(
            templates,
            insns,
            proj,
            projInputElements,
            inputBase,
            pSlot,
            countSlot,
            warningsArraySlot,
            mergedProjSources,
            liveSlot,
            overflowExcSlot,
            spareByInput,
            workBase,
            constantSlots,
            debug
        );
        NodeSlots root = emitter.emit(projectionTree, projOutputElement);
        LabelNode doNull = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, root.present()));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, doNull));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(projOutputElement.type().getOpcode(Opcodes.ILOAD), root.value()));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                projOutputElement.builderInternalName(),
                projOutputElement.appendMethod(),
                projOutputElement.appendDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(doNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                projOutputElement.builderInternalName(),
                "appendNull",
                projOutputElement.appendNullDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(done);
        insns.add(new InsnNode(Opcodes.RETURN));
        return proj;
    }

}
