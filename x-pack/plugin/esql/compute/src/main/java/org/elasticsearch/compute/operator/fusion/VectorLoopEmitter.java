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
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Emits the dense vector-loop fused classes: the plain-vector loop (ALL-RAW {@code fused} plus ALL-INTERFACE
 * {@code fusedInterface}), the overflow-checked vector loop (builder {@code fused}/{@code fusedInterface} plus
 * raw-array {@code fusedRaw}/{@code fusedRawInterface}), the dense logical-vector INTERFACE loop, and the POSITIONS
 * loop of a boolean predicate ({@link #emitVectorPositionsLoop}).
 * {@link Stitcher} owns the public {@code compile*} façade and define boundary; this class is the vector-path
 * seam those entry points delegate to.
 */
final class VectorLoopEmitter {

    private final TemplateRegistry templates;
    private final Consumer<MethodNode> hostMethodInterceptor;

    VectorLoopEmitter(TemplateRegistry templates, Consumer<MethodNode> hostMethodInterceptor) {
        this.templates = templates;
        this.hostMethodInterceptor = Objects.requireNonNull(hostMethodInterceptor);
    }

    private Body extractKernelBody(FusionNode.Kernel kernel) {
        return BodyExtractor.extract(templates.methodNode(kernel.descriptor()));
    }

    /**
     * The surviving vector-loop combinations: how leaves are read, how the result is
     * materialised, whether the loop is overflow-checked, and the output element. Nested enums are
     * the record's components, not a strategy hierarchy.
     */
    record LoopSpec(Reads reads, Output output, boolean checked, Element outElement) {
        enum Reads {
            RAW,
            INTERFACE
        }

        enum Output {
            VECTOR,
            BLOCK,
            BUILDER,
            /**
             * A boolean predicate writes the positions where it is {@code true} into an {@code int[] positions}
             * parameter and returns their count, instead of materialising a {@code BooleanBlock}. A checked
             * (overflow-capable) tree abandons the page with {@code -1} on the first overflow, like the raw-array
             * output's {@code null}; the caller re-runs it on the block path, which nulls the row and warns.
             */
            POSITIONS
        }

        boolean raw() {
            return reads == Reads.RAW;
        }

        /**
         * Whether the method takes the {@code Warnings[]} parameter. Every checked value output does, so the raw-array
         * and builder siblings share one descriptor; POSITIONS never warns (it abandons on overflow instead).
         */
        boolean withWarnings() {
            return checked && output != Output.POSITIONS;
        }
    }

    /**
     * Builds the class bytes for the fused vector loop. One hidden class carries both the ALL-RAW {@code fused}
     * method ({@code VectorUnsafe} + {@code xALOAD}) and the ALL-INTERFACE {@code fusedInterface} method (typed
     * {@code *Vector.getX(p)}), same signature, so the factory picks per page without a second
     * {@code defineHiddenClass}. Kept separate from {@link Stitcher#compileVectorLoop} so the verification boundary is
     * exactly the {@code defineHiddenClass} call — everything here is pure ASM tree manipulation on cloned kernel
     * bodies.
     */
    byte[] emitVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        // One walk derives every structural fact this emit path needs (arity, constants-in-emit-order, ...).
        FusionSignature signature = FusionSignature.of(tree);
        int[] consumed = signature.consumedInputs();
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the output
        // array + produced vector). They coincide for a homogeneous arithmetic root and differ for a comparison root
        // (numeric inputs feed a boolean output) or any cast-bridged input.
        Element[] inputElements = Stitcher.inputElements(signature);
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();

        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$VectorLoop", tree);
        List<LoopSpec> specs = List.of(
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.VECTOR, false, outputElement),
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.VECTOR, false, outputElement)
        );
        List<MethodNode> methods = new ArrayList<>(specs.size());
        for (LoopSpec spec : specs) {
            methods.add(emitVectorMethod(tree, spec, inputElements, constants, consumed, Set.of(), Map.of(), debug));
        }
        // The plain-vector fast path enforces the HugeMethodLimit huge-method guard on every method of the class.
        // An over-limit body is refused (→ unfused fallback) rather than split: 8000 is the size at which HotSpot
        // will not JIT-compile the method at all, which is strictly worse than the unfused chain.
        return Stitcher.finishFusedClass(
            caller,
            Stitcher.fusedClassName(caller, "Fused$$VectorLoop"),
            debug,
            "plain-vector loop",
            methods,
            List.of(),
            hostMethodInterceptor
        );
    }

    /**
     * One {@link LoopSpec}-parameterised vector-loop method. Unifies the four per-combination copies
     * ({@code emitPlainVectorMethod}, {@code emitCheckedVectorRawMethod}, {@code emitCheckedVectorBuilderMethod},
     * {@code emitLogicalVectorMethod}). {@link LoopSpec.Output#BUILDER} keeps the builder body; the remaining
     * combinations share the array-output loop (write {@code T[]}, then {@code new*ArrayVector} and optionally
     * {@code asBlock()}). Logical trees use {@code outElement=BOOLEAN} — {@link Stitcher#rootReturnType} already
     * maps a Logical/Not root to boolean, and {@link StackKernelEmitter} already walks Logical/Not nodes.
     */
    private MethodNode emitVectorMethod(
        FusionNode tree,
        LoopSpec spec,
        Element[] inputElements,
        List<FusionNode.Constant> constants,
        int[] consumed,
        Set<String> overflowTypes,
        Map<FusionNode, Integer> sourceIndices,
        FusedClassDebugInfo debug
    ) {
        assert spec.checked() == (overflowTypes.isEmpty() == false)
            : "LoopSpec.checked [" + spec.checked() + "] must agree with overflowTypes " + overflowTypes;
        if (spec.output() == LoopSpec.Output.BUILDER) {
            return emitCheckedVectorBuilderMethod(
                tree,
                inputElements,
                spec.outElement(),
                constants,
                consumed,
                overflowTypes,
                sourceIndices,
                vectorMethodDescriptor(spec, inputElements.length, constants),
                spec.raw(),
                debug
            );
        }
        return emitArrayVectorMethod(tree, spec, inputElements, constants, consumed, overflowTypes, debug);
    }

    static String vectorMethodName(LoopSpec spec) {
        boolean raw = spec.raw();
        return switch (spec.output()) {
            case BUILDER, VECTOR, POSITIONS -> raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME;
            case BLOCK -> spec.checked()
                ? (raw ? Stitcher.FUSED_RAW_METHOD_NAME : Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME)
                : (raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME);
        };
    }

    static String vectorMethodDescriptor(LoopSpec spec, int arity, List<FusionNode.Constant> constants) {
        String vector = "L" + Stitcher.VECTOR + ";";
        String[] inputDescriptors = new String[arity];
        for (int i = 0; i < arity; i++) {
            inputDescriptors[i] = vector;
        }
        if (spec.output() == LoopSpec.Output.POSITIONS) {
            return Stitcher.fusedMethodDescriptor(spec.withWarnings(), "[I", inputDescriptors, constants, "I");
        }
        String ret = spec.output() == LoopSpec.Output.VECTOR ? spec.outElement().vectorDescriptor() : spec.outElement().blockDescriptor();
        return Stitcher.fusedMethodDescriptor(spec.withWarnings(), "", inputDescriptors, constants, ret);
    }

    /**
     * Array-output vector loop: write {@code T[]}, return {@code new*ArrayVector} ({@link LoopSpec.Output#VECTOR})
     * or {@code new*ArrayVector.asBlock()} ({@link LoopSpec.Output#BLOCK}). Covers the plain-vector methods, the
     * checked-raw methods (overflow → {@code return null}) and the logical-vector INTERFACE method. With
     * {@link LoopSpec.Output#POSITIONS} the same loop instead writes {@code positions[n] = p; n += <predicate>} and
     * returns {@code n} (overflow → {@code return -1}).
     */
    private MethodNode emitArrayVectorMethod(
        FusionNode tree,
        LoopSpec spec,
        Element[] inputElements,
        List<FusionNode.Constant> constants,
        int[] consumed,
        Set<String> overflowTypes,
        FusedClassDebugInfo debug
    ) {
        boolean raw = spec.raw();
        boolean positions = spec.output() == LoopSpec.Output.POSITIONS;
        Element outputElement = spec.outElement();
        int arity = inputElements.length;
        // Frame layout: [0] BlockFactory, then an unused Warnings[] when {@code spec.withWarnings()} (uniform binding
        // with the builder sibling), then int positionCount, then int[] positions for POSITIONS, then input Vectors, then
        // trailing constant parameters. The per-input extracted refs (raw arrays OR typed *Vector), the output array
        // (the selected-position count for POSITIONS), and the loop counter sit ABOVE the parameters.
        FrameLayout frame = new FrameLayout();
        FrameLayout.FusedParams params = frame.fusedParams(spec.withWarnings(), positions, arity, constants);
        int pcSlot = params.pcSlot();
        int inputBase = params.inputBase();
        Map<FusionNode.Constant, Integer> constantSlots = params.constantSlots();
        int extractedBase = frame.slots(arity);                             // per-input raw array OR typed *Vector
        int outSlot = frame.slot();                                         // out[], or the POSITIONS count n
        int pSlot = frame.slot();
        int kernelBase = frame.mark();

        MethodNode host = Stitcher.newFusedMethod(vectorMethodName(spec), vectorMethodDescriptor(spec, arity, constants));
        InsnList insns = host.instructions;

        // Narrow each CONSUMED input once, outside the loop (no per-position checkcast).
        for (int i : consumed) {
            emitExtractConsumedInput(insns, inputElements[i], inputBase + i, extractedBase + i, raw);
        }

        LabelNode tryStart = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode tryEnd = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode handler = overflowTypes.isEmpty() ? null : new LabelNode();
        if (tryStart != null) {
            insns.add(tryStart);
        }

        if (positions) {
            // n = 0;
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, outSlot));
        } else {
            // out = new T[positionCount]; T is the OUTPUT element (boolean[] for a comparison/logical root).
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
            insns.add(new IntInsnNode(Opcodes.NEWARRAY, outputElement.newArrayType()));
            insns.add(new VarInsnNode(Opcodes.ASTORE, outSlot));
        }

        // int p = 0;
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        if (positions) {
            // positions[n] = p; n = n + <stitched predicate>; — branch-free: every position is recorded and the count
            // only moves past a true one (the predicate leaves an int 0/1 on top of n).
            PositionLoop.emitRecordPosition(insns, params.positionsSlot(), outSlot, pSlot);
            insns.add(new VarInsnNode(Opcodes.ILOAD, outSlot));
        } else {
            // out[p] = <stitched expression>; the array ref and index are pushed first, then the fused kernel bodies
            // leave the computed value on top, so a single array-store consumes all three.
            insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        }
        StackKernelEmitter emitter = new StackKernelEmitter(
            insns,
            constantSlots,
            kernelBase,
            input -> emitReadExtractedAtP(insns, inputElements[input.index()], extractedBase + input.index(), pSlot, raw),
            // Array paths have no per-kernel overflow bookkeeping (the checked-raw path abandons the whole page).
            kernel -> {},
            debug
        );
        emitter.emit(tree, outputElement);
        if (positions) {
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new VarInsnNode(Opcodes.ISTORE, outSlot));
        } else {
            // The computed value (an int 0/1 for a comparison/logical root) is stored with the OUTPUT element's
            // array-store opcode (BASTORE for boolean).
            insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IASTORE)));
        }

        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);

        if (tryEnd != null) {
            insns.add(tryEnd);
        }

        if (positions) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, outSlot));
            insns.add(new InsnNode(Opcodes.IRETURN));
        } else if (spec.output() == LoopSpec.Output.BLOCK) {
            emitReturnNewArrayVectorAsBlock(insns, outSlot, pcSlot, outputElement);
        } else {
            emitNewArrayVector(insns, outSlot, pcSlot, outputElement);
            insns.add(new InsnNode(Opcodes.ARETURN));
        }

        if (handler != null) {
            // Overflow abandons the page: null for an array output, -1 for POSITIONS (whose partially written
            // positions the caller's block-path re-run overwrites).
            insns.add(handler);
            insns.add(new InsnNode(Opcodes.POP));
            if (positions) {
                insns.add(new InsnNode(Opcodes.ICONST_M1));
                insns.add(new InsnNode(Opcodes.IRETURN));
            } else {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
            }
            for (String exType : overflowTypes) {
                host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, exType));
            }
        }
        return host;
    }

    /**
     * Builds the class bytes for the overflow-aware vector loop. One hidden class carries four methods of the same
     * descriptor so the factory picks ALL-RAW vs ALL-INTERFACE per page without a second {@code defineHiddenClass}:
     * {@link Stitcher#FUSED_METHOD_NAME}/{@link Stitcher#FUSED_INTERFACE_METHOD_NAME} append through a builder (null + warning on
     * overflow) and {@link Stitcher#FUSED_RAW_METHOD_NAME}/{@link Stitcher#FUSED_RAW_INTERFACE_METHOD_NAME} write a dense
     * {@code *ArrayVector}, returning {@code null} on the first declared overflow so the caller can re-run the
     * matching builder method. No per-position null/multi-value guard — a vector is dense and single-valued.
     */
    byte[] emitVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        // One walk derives arity, consumed inputs, constants-in-emit-order, warning-source indices and overflow types.
        FusionSignature signature = FusionSignature.of(tree);
        int[] consumed = signature.consumedInputs();
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the builder +
        // produced block). Each consumed input is read at its OWN element into a per-input value slot.
        Element[] inputElements = Stitcher.inputElements(signature);
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        Set<String> overflowTypes = signature.overflowExceptionInternalNames();
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        Map<FusionNode, Integer> sourceIndices = signature.warningSourceIndices();
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$CheckedVectorLoop", tree);

        List<LoopSpec> specs = List.of(
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.BUILDER, true, outputElement),
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.BUILDER, true, outputElement),
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.BLOCK, true, outputElement),
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.BLOCK, true, outputElement)
        );
        List<MethodNode> methods = new ArrayList<>(specs.size());
        for (LoopSpec spec : specs) {
            methods.add(emitVectorMethod(tree, spec, inputElements, constants, consumed, overflowTypes, sourceIndices, debug));
        }

        String internalName = Stitcher.fusedClassName(caller, "Fused$$CheckedVectorLoop");
        return Stitcher.finishFusedClass(caller, internalName, debug, "checked-vector loop", methods, List.of(), hostMethodInterceptor);
    }

    /**
     * Builder-based checked-vector method: {@code fused} when {@code raw} (VectorUnsafe + xALOAD into value slots)
     * or {@code fusedInterface} (typed {@code *Vector.getX(p)} into the same value slots). Overflow → null + warning
     * on the currently-evaluating kernel's source. Output is always a {@code *Block.Builder}.
     */
    private MethodNode emitCheckedVectorBuilderMethod(
        FusionNode tree,
        Element[] inputElements,
        Element outputElement,
        List<FusionNode.Constant> constants,
        int[] consumed,
        Set<String> overflowTypes,
        Map<FusionNode, Integer> sourceIndices,
        String descriptor,
        boolean raw,
        FusedClassDebugInfo debug
    ) {
        int arity = inputElements.length;
        // Frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input Vectors, then one
        // trailing primitive parameter per embedded constant (in canonical emit order). The per-input extracted refs,
        // the builder, loop counter, per-input value slots, built result, the two exception slots and the
        // current-kernel slot sit ABOVE the parameters.
        FrameLayout frame = new FrameLayout();
        FrameLayout.FusedParams params = frame.fusedParams(true, arity, constants);
        // Scratch region above the parameters. Per-input value slots are sized by each input's own element (a
        // long/double takes two slots); an unused index (never expected) reserves one. The FrameLayout guarantees the
        // widths never collide.
        int extractedBase = frame.slots(arity);
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int[] valueSlots = new int[arity];
        for (int i = 0; i < arity; i++) {
            valueSlots[i] = inputElements[i] != null ? frame.slot(inputElements[i].type()) : frame.slot();
        }
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int overflowExcSlot = frame.slot();
        // Names the overflow-checked kernel currently evaluating; the shared overflow handler reads it to attribute the
        // caught overflow to that kernel's own warning source.
        int currentKernelSlot = frame.slot();
        int kernelBase = frame.mark();

        MethodNode host = Stitcher.newFusedMethod(raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME, descriptor);
        InsnList insns = host.instructions;

        // Narrow each CONSUMED input once, outside the loop (no per-position checkcast).
        for (int i : consumed) {
            emitExtractConsumedInput(insns, inputElements[i], params.inputBase() + i, extractedBase + i, raw);
        }

        // out = bf.new*BlockBuilder(positionCount); the builder is the OUTPUT element (BooleanBlock.Builder for a
        // comparison root).
        PositionLoop loop = PositionLoop.open(insns, params.pcSlot(), pSlot, builderSlot, outputElement);

        // v_i = extracted_i[p]; no null guard — a vector is dense and single-valued.
        for (int i : consumed) {
            Element inputElement = inputElements[i];
            emitReadExtractedAtP(insns, inputElement, extractedBase + i, pSlot, raw);
            insns.add(new VarInsnNode(inputElement.type().getOpcode(Opcodes.ISTORE), valueSlots[i]));
        }

        LabelNode tryStartOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode tryEndOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        if (tryStartOverflow != null) {
            // Seed currentKernelSlot (0) OUTSIDE the overflow try so it is definitely int-typed at the handler's frame
            // merge; the emitter overwrites it with the real kernel slot immediately before each overflow-checked body.
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, currentKernelSlot));
            insns.add(tryStartOverflow);
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        StackKernelEmitter emitter = new StackKernelEmitter(
            insns,
            params.constantSlots(),
            kernelBase,
            // Checked-vector leaf: the per-position value already read (no null/mv guard — the vector is dense).
            input -> {
                Element element = inputElements[input.index()];
                insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ILOAD), valueSlots[input.index()]));
            },
            // Before an overflow-checked body, record which kernel is running so the shared overflow handler
            // attributes a caught overflow to THIS kernel's own source (see the handler below).
            kernel -> {
                if (kernel.descriptor().hasOverflowException()) {
                    Stitcher.pushInt(insns, sourceIndices.get(kernel));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, currentKernelSlot));
                }
            },
            debug
        );
        emitter.emit(tree, outputElement);
        FusedBuilderEmit.emitAppend(insns, outputElement);
        if (tryEndOverflow != null) {
            insns.add(tryEndOverflow);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop.next()));

        for (String exType : overflowTypes) {
            LabelNode overflowHandler = new LabelNode();
            insns.add(overflowHandler);
            insns.add(new VarInsnNode(Opcodes.ASTORE, overflowExcSlot));
            // Register the overflow on the currently-evaluating kernel's OWN Warnings (warnings[currentKernelSlot]) so
            // the header matches the unfused chain's per-node evaluator source.
            Stitcher.emitRegisterException(insns, params.warningsSlot(), currentKernelSlot, true, overflowExcSlot);
            FusedBuilderEmit.emitAppendNull(insns, builderSlot, outputElement);
            insns.add(new JumpInsnNode(Opcodes.GOTO, loop.next()));
            host.tryCatchBlocks.add(new TryCatchBlockNode(tryStartOverflow, tryEndOverflow, overflowHandler, exType));
        }

        loop.close(host, insns, resultSlot, excSlot, outputElement);
        return host;
    }

    /**
     * Pushes {@code blockFactory.new*ArrayVector(out, positionCount)} onto the stack. {@code new*ArrayVector} charges
     * the breaker; the {@code out[]} written above is a transient uncharged array. Leaves the vector on the stack
     * (no {@code asBlock}, no {@code ARETURN}).
     */
    static void emitNewArrayVector(InsnList insns, int outSlot, int pcSlot, Element outputElement) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                Stitcher.BLOCK_FACTORY,
                outputElement.newVectorMethod(),
                outputElement.newVectorDescriptor(),
                false
            )
        );
    }

    /**
     * {@code return blockFactory.new*ArrayVector(out, positionCount).asBlock();} — the dense-output epilogue shared by
     * {@link #emitArrayVectorMethod} when {@link LoopSpec.Output#BLOCK}. {@code new*ArrayVector} charges the
     * breaker; the {@code out[]} written above is a transient uncharged array (same as {@link #emitVectorLoop}).
     */
    static void emitReturnNewArrayVectorAsBlock(InsnList insns, int outSlot, int pcSlot, Element outputElement) {
        emitNewArrayVector(insns, outSlot, pcSlot, outputElement);
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                outputElement.vectorInternalName(),
                "asBlock",
                "()" + outputElement.blockDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.ARETURN));
    }

    /**
     * Narrows one consumed input once, outside the loop (no per-position checkcast). ALL-RAW: CHECKCAST to
     * {@code *ArrayVector} and {@code VectorUnsafe} extract into {@code destSlot}. ALL-INTERFACE: CHECKCAST to the
     * typed {@code *Vector} interface and store that reference — {@code Constant*Vector}/{@code *BigArrayVector} then
     * read through {@code getX(p)} at the hidden class's own call sites.
     */
    static void emitExtractConsumedInput(InsnList insns, Element inputElement, int inputSlot, int destSlot, boolean raw) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, inputSlot));
        if (raw) {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElement.arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Stitcher.VECTOR_UNSAFE,
                    inputElement.vectorUnsafeMethod(),
                    inputElement.vectorUnsafeDescriptor(),
                    false
                )
            );
        } else {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElement.vectorInternalName()));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, destSlot));
    }

    /**
     * Pushes {@code extracted[p]} onto the stack. ALL-RAW: {@code xALOAD} from the backing array. ALL-INTERFACE:
     * {@code INVOKEINTERFACE *Vector.getX(I)}.
     */
    static void emitReadExtractedAtP(InsnList insns, Element element, int extractedSlot, int pSlot, boolean raw) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, extractedSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        if (raw) {
            insns.add(new InsnNode(element.type().getOpcode(Opcodes.IALOAD)));
        } else {
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    element.vectorInternalName(),
                    element.getValueMethod(),
                    element.getValueDescriptor(),
                    true
                )
            );
        }
    }

    /**
     * Emits an {@link FusionNode.Input} leaf's value for the current position onto the operand stack, typed to the
     * leaf's own element. The two stack-based paths differ only here: the plain-vector and checked-raw fast paths
     * inject {@link #emitReadExtractedAtP} after an outside-the-loop {@link #emitExtractConsumedInput} (ALL-RAW
     * {@code xALOAD} or ALL-INTERFACE {@code *Vector.getX(p)}), while the checked-vector builder path reads a
     * value already loaded into the leaf's value local.
     */
    @FunctionalInterface
    private interface LeafReader {
        void read(FusionNode.Input input);
    }

    /**
     * A hook the {@link StackKernelEmitter} runs immediately before splicing a kernel's body (after its operands are
     * stored into the body's argument slots). No-op for the plain-vector path; the checked-vector path uses it to
     * record which overflow-checked kernel is about to run so the shared handler attributes a caught overflow to that
     * kernel's own warning source.
     */
    @FunctionalInterface
    private interface KernelPreBody {
        void beforeBody(FusionNode.Kernel kernel);
    }

    /**
     * Post-order emitter that leaves a tree's value for the current position on the operand stack — the shared core of
     * the two <b>stack-based</b> fast paths (plain-vector {@link #emitVectorLoop} and checked-vector
     * {@link #emitVectorLoopChecked}). Both walk the tree identically — a {@link FusionNode.Constant} loads from its
     * trailing parameter slot; a {@link FusionNode.Kernel} claims a disjoint slot range (advanced by the body's
     * {@code maxLocals}), shifts and splices its body, storing each child's value into the body's argument slots — and
     * differ ONLY in two injected strategies: how an {@link FusionNode.Input} leaf is read ({@link LeafReader}) and
     * what runs just before a kernel body ({@link KernelPreBody}). Sharing this walk keeps the two paths from drifting.
     *
     * <p>The dense-logical vector path ({@link #emitLogicalVectorLoop}) reuses this walk for comparison kernels
     * and adds a 2VL {@link FusionNode.Logical} branch (plain boolean short-circuit, no tri-state) plus a
     * {@link FusionNode.Not} flip ({@code ICONST_1}/{@code IXOR}). It is NOT used by the nullable/multi-valued
     * {@link BlockDfsEmitter} or the 3VL {@link BlockLoopEmitter.LogicalDfsEmitter}: those interleave present/live/warning control
     * flow with the walk (they are real semantic forks), though they reuse the same
     * {@link #extractKernelBody}/{@link Stitcher#checkKernelArity}/{@link Stitcher#loadConstantParam} primitives.
     */
    final class StackKernelEmitter {
        private final InsnList insns;
        private final Map<FusionNode.Constant, Integer> constantSlots;
        private final LeafReader leafReader;
        private final KernelPreBody preBody;
        private final FusedClassDebugInfo debug;
        private int kernelBase;

        StackKernelEmitter(
            InsnList insns,
            Map<FusionNode.Constant, Integer> constantSlots,
            int kernelBase,
            LeafReader leafReader,
            KernelPreBody preBody,
            FusedClassDebugInfo debug
        ) {
            this.insns = insns;
            this.constantSlots = constantSlots;
            this.kernelBase = kernelBase;
            this.leafReader = leafReader;
            this.preBody = preBody;
            this.debug = debug;
        }

        /**
         * Emits instructions that leave {@code node}'s value for the current position {@code p} on the operand stack.
         * {@code expected} is the element {@code node} must produce (its consuming kernel's argument element, or the
         * output element at the root) — used to type a leaf read / constant push under per-node typing.
         */
        void emit(FusionNode node, Element expected) {
            if (node instanceof FusionNode.Input input) {
                // A leaf reads at its OWN element (inputElements[index], == expected under per-node typing), via the
                // injected strategy.
                leafReader.read(input);
                return;
            }
            if (node instanceof FusionNode.Constant constant) {
                // Embedded literal: a baked constant is an immediate; otherwise load from its trailing param slot.
                Stitcher.loadConstantParam(insns, expected, constant, constantSlots.get(constant));
                return;
            }
            if (node instanceof FusionNode.Logical logical) {
                // Dense 2VL: no nulls, so AND/OR are plain boolean short-circuit. Comparisons are spliced inline
                // (C1 will not inline a >35-byte helper); the 3VL null machinery lives only on the block path.
                emitLogical2vl(logical);
                return;
            }
            if (node instanceof FusionNode.Not not) {
                // Dense 2VL Not: child boolean on the stack, then ICONST_1 + IXOR. No null lane.
                emit(not.child(), Element.of(Type.BOOLEAN_TYPE));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IXOR));
                return;
            }
            FusionNode.Kernel kernel = (FusionNode.Kernel) node;
            Body body = extractKernelBody(kernel);

            // Claim this node's disjoint slot range, then shift the body's locals into it. Argument loads inside the
            // body now read [nodeBase + argOffset]; the stores below feed exactly those slots.
            int nodeBase = kernelBase;
            kernelBase += body.maxLocals();
            SlotRemapper.shift(body.computation(), nodeBase);

            Type[] argTypes = Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes();
            var children = kernel.children();
            Stitcher.checkKernelArity(kernel, argTypes.length, children.size());
            int offset = 0;
            for (int i = 0; i < argTypes.length; i++) {
                // Each child produces this kernel's argument element (a cast child bridges a differing leaf element).
                emit(children.get(i), Element.of(argTypes[i]));
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), nodeBase + offset));
                offset += argTypes[i].getSize();
            }
            preBody.beforeBody(kernel);
            // The spliced body reads its inputs from the shifted argument slots and leaves the result on the stack.
            Stitcher.spliceKernelBody(insns, body.computation(), kernel, debug);
        }

        /**
         * Emits a dense 2VL {@code AND}/{@code OR}: evaluate the left operand, skip the right when it cannot change
         * the result ({@code false AND *}, {@code true OR *}), otherwise replace the left with the right. No tri-state
         * and no warnings — dense vectors have no nulls, and comparisons do not warn.
         */
        void emitLogical2vl(FusionNode.Logical logical) {
            boolean and = logical.op() == FusionNode.BoolOp.AND;
            LabelNode skipRight = new LabelNode();
            emit(logical.left(), Element.of(Type.BOOLEAN_TYPE));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new JumpInsnNode(and ? Opcodes.IFEQ : Opcodes.IFNE, skipRight));
            insns.add(new InsnNode(Opcodes.POP));
            emit(logical.right(), Element.of(Type.BOOLEAN_TYPE));
            insns.add(skipRight);
        }
    }

    /**
     * Builds the class bytes for the dense logical vector loop: INTERFACE-only {@link Stitcher#FUSED_INTERFACE_METHOD_NAME}.
     * The RAW sibling is not emitted.
     */
    byte[] emitLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        if (FusionNode.isBooleanLogic(tree) == false) {
            throw new IllegalArgumentException("fused logical vector loop requires a Logical or Not root, not " + tree);
        }
        FusionSignature signature = FusionSignature.of(tree);
        int[] consumed = signature.consumedInputs();
        Element[] inputElements = Stitcher.inputElements(signature);
        // Logical/Not roots map to boolean via rootReturnType; StackKernelEmitter already walks those nodes.
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();

        String internalName = Stitcher.fusedClassName(caller, "Fused$$LogicalVectorLoop");
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$LogicalVectorLoop", tree);
        MethodNode iface = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.BLOCK, false, outputElement),
            inputElements,
            constants,
            consumed,
            Set.of(),
            Map.of(),
            debug
        );
        return Stitcher.finishFusedClass(
            caller,
            internalName,
            debug,
            "logical vector loop",
            List.of(iface),
            List.of(),
            hostMethodInterceptor
        );
    }

    /**
     * Builds the class bytes for the dense POSITIONS loop of a boolean predicate {@code tree}: each method writes the
     * positions where the predicate is {@code true} into its {@code int[] positions} parameter and returns their count
     * (see {@link LoopSpec.Output#POSITIONS}). The body is branch-free per position — {@code positions[n] = p;
     * n += <predicate 0/1>} — so a page pays no BooleanBlock and no second scan over it. A comparison-rooted tree gets
     * the ALL-RAW {@code fused} and ALL-INTERFACE {@code fusedInterface} methods like {@link #emitVectorLoop}; a logical
     * tree gets {@code fusedInterface} only, like {@link #emitLogicalVectorLoop}. An overflow-checked tree returns
     * {@code -1} on the first overflow so the caller re-runs the page on the block path, which nulls the row and warns.
     */
    byte[] emitVectorPositionsLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        if (outputElement.type().getSort() != Type.BOOLEAN) {
            throw new IllegalArgumentException("fused positions loop requires a boolean predicate root, not " + tree);
        }
        FusionSignature signature = FusionSignature.of(tree);
        int[] consumed = signature.consumedInputs();
        Element[] inputElements = Stitcher.inputElements(signature);
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        Set<String> overflowTypes = signature.overflowExceptionInternalNames();
        boolean checked = overflowTypes.isEmpty() == false;
        boolean logical = FusionNode.isBooleanLogic(tree);

        String simpleName = logical ? "Fused$$LogicalVectorPositionsLoop" : "Fused$$VectorPositionsLoop";
        FusedClassDebugInfo debug = FusedClassDebugInfo.of(simpleName, tree);
        List<LoopSpec> specs = logical
            ? List.of(new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.POSITIONS, checked, outputElement))
            : List.of(
                new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.POSITIONS, checked, outputElement),
                new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.POSITIONS, checked, outputElement)
            );
        List<MethodNode> methods = new ArrayList<>(specs.size());
        for (LoopSpec spec : specs) {
            methods.add(emitVectorMethod(tree, spec, inputElements, constants, consumed, overflowTypes, Map.of(), debug));
        }
        return Stitcher.finishFusedClass(
            caller,
            Stitcher.fusedClassName(caller, simpleName),
            debug,
            logical ? "logical vector positions loop" : "vector positions loop",
            methods,
            List.of(),
            hostMethodInterceptor
        );
    }
}
