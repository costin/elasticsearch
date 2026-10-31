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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Emits the dense vector-loop fused classes: the plain-vector loop (ALL-RAW {@code fused} plus ALL-INTERFACE
 * {@code fusedInterface}), the overflow-checked vector loop (builder {@code fused}/{@code fusedInterface} plus
 * raw-array {@code fusedRaw}/{@code fusedRawInterface}), the Panama Vector-API SIMD comparison loop, and the
 * dense logical-vector INTERFACE loop. {@link Stitcher} owns the public {@code compile*} façade and define
 * boundary; this class is the vector-path seam those entry points delegate to.
 */
final class VectorLoopEmitter {

    private final TemplateRegistry templates;
    private final Consumer<MethodNode> hostMethodInterceptor;

    VectorLoopEmitter(TemplateRegistry templates, Consumer<MethodNode> hostMethodInterceptor) {
        this.templates = templates;
        this.hostMethodInterceptor = hostMethodInterceptor;
    }

    private Body extractKernelBody(FusionNode.Kernel kernel) {
        return BodyExtractor.extract(templates.methodNode(kernel.descriptor()));
    }

    /**
     * The surviving vector-loop combinations after E2/E3: how leaves are read, how the result is
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
            BUILDER
        }

        boolean raw() {
            return reads == Reads.RAW;
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
        MethodNode raw = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.VECTOR, false, outputElement),
            inputElements,
            constants,
            consumed,
            Set.of(),
            Map.of(),
            debug
        );
        MethodNode iface = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.VECTOR, false, outputElement),
            inputElements,
            constants,
            consumed,
            Set.of(),
            Map.of(),
            debug
        );
        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(raw);
            hostMethodInterceptor.accept(iface);
        }
        ClassNode classNode = Stitcher.newFusedClass(Stitcher.fusedClassName(caller, "Fused$$VectorLoop"), raw, debug);
        classNode.methods.add(iface);
        // The plain-vector fast path enforces the DontCompileHugeMethods huge-method guard on the inline body.
        // An over-limit body is refused (→ unfused fallback) rather than split: 8000 is the size at which HotSpot
        // will not JIT-compile the method at all, which is strictly worse than the unfused chain.
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "plain-vector loop", true);
    }

    /**
     * One {@link LoopSpec}-parameterised vector-loop method. Replaces the four pre-E6 copies
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
            case BUILDER, VECTOR -> raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME;
            case BLOCK -> spec.checked()
                ? (raw ? Stitcher.FUSED_RAW_METHOD_NAME : Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME)
                : (raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME);
        };
    }

    static String vectorMethodDescriptor(LoopSpec spec, int arity, List<FusionNode.Constant> constants) {
        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY).append(";");
        if (spec.checked()) {
            desc.append(Stitcher.WARNINGS_ARRAY_DESCRIPTOR);
        }
        desc.append("I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(Stitcher.VECTOR).append(";");
        }
        Stitcher.appendConstantParamDescriptors(desc, constants);
        desc.append(")")
            .append(spec.output() == LoopSpec.Output.VECTOR ? spec.outElement().vectorDescriptor() : spec.outElement().blockDescriptor());
        return desc.toString();
    }

    /**
     * Array-output vector loop: write {@code T[]}, return {@code new*ArrayVector} ({@link LoopSpec.Output#VECTOR})
     * or {@code new*ArrayVector.asBlock()} ({@link LoopSpec.Output#BLOCK}). Covers the plain-vector methods, the
     * checked-raw methods (overflow → {@code return null}) and the logical-vector INTERFACE method.
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
        Element outputElement = spec.outElement();
        int arity = inputElements.length;
        // Frame layout: [0] BlockFactory, then an unused Warnings[] when {@code checked} (uniform binding with
        // the builder sibling), then int positionCount, then input Vectors, then trailing constant parameters.
        // The per-input extracted refs (raw arrays OR typed *Vector), the output array, and the loop counter
        // sit ABOVE the parameters.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        int pcSlot;
        if (spec.checked()) {
            frame.slot();                                                   // [1] Warnings[] (unused on the raw path)
            pcSlot = frame.slot();                                          // [2] int positionCount
        } else {
            pcSlot = frame.slot();                                          // [1] int positionCount
        }
        int inputBase = frame.slots(arity);                                 // input Vectors
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(constants, frame);
        int extractedBase = frame.slots(arity);                             // per-input raw array OR typed *Vector
        int outSlot = frame.slot();
        int pSlot = frame.slot();
        int kernelBase = frame.mark();

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            vectorMethodName(spec),
            vectorMethodDescriptor(spec, arity, constants),
            null,
            null
        );
        InsnList insns = host.instructions;

        // Narrow each CONSUMED input once, outside the loop (JIT #6: no per-position checkcast).
        for (int i : consumed) {
            emitExtractConsumedInput(insns, inputElements[i], inputBase + i, extractedBase + i, raw);
        }

        LabelNode tryStart = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode tryEnd = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode handler = overflowTypes.isEmpty() ? null : new LabelNode();
        if (tryStart != null) {
            insns.add(tryStart);
        }

        // out = new T[positionCount]; T is the OUTPUT element (boolean[] for a comparison/logical root).
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, outputElement.newArrayType()));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outSlot));

        // int p = 0;
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // out[p] = <stitched expression>; the array ref and index are pushed first, then the fused kernel bodies
        // leave the computed value on top, so a single array-store consumes all three.
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
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
        // The computed value (an int 0/1 for a comparison/logical root) is stored with the OUTPUT element's
        // array-store opcode (BASTORE for boolean).
        insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IASTORE)));

        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);

        if (tryEnd != null) {
            insns.add(tryEnd);
        }

        if (spec.output() == LoopSpec.Output.BLOCK) {
            emitReturnNewArrayVectorAsBlock(insns, outSlot, pcSlot, outputElement);
        } else {
            // return blockFactory.new*ArrayVector(out, positionCount);
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
            insns.add(new InsnNode(Opcodes.ARETURN));
        }

        if (handler != null) {
            insns.add(handler);
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new InsnNode(Opcodes.ARETURN));
            for (String exType : overflowTypes) {
                host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, exType));
            }
        }
        return host;
    }

    /** The {@code jdk.incubator.vector.*Vector} internal name for a primitive operand element (int/long/double). */
    static String simdVectorClass(Element operand) {
        return switch (operand.type().getSort()) {
            case Type.INT -> FusionSimd.INT_VECTOR;
            case Type.LONG -> FusionSimd.LONG_VECTOR;
            case Type.DOUBLE -> FusionSimd.DOUBLE_VECTOR;
            default -> throw new IllegalArgumentException("no SIMD vector class for element " + operand.type().getClassName());
        };
    }

    /**
     * Emits the SIMD comparison loop:
     * <pre>{@code
     * static BooleanVector fused(BlockFactory bf, int positionCount, Vector in0, Vector in1) {
     *     int[] a = VectorUnsafe.ints((IntArrayVector) in0);
     *     int[] b = VectorUnsafe.ints((IntArrayVector) in1);
     *     boolean[] out = new boolean[positionCount];
     *     VectorSpecies species = IntVector.SPECIES_PREFERRED;
     *     int bound = species.loopBound(positionCount), len = species.length(), i = 0;
     *     for (; i < bound; i += len) {                                   // SIMD body
     *         IntVector.fromArray(species, a, i).compare(GT, IntVector.fromArray(species, b, i)).intoArray(out, i);
     *     }
     *     for (; i < positionCount; i++) out[i] = a[i] > b[i];            // scalar tail (spliced comparison kernel)
     *     return bf.newBooleanArrayVector(out, positionCount);
     * }
     * }</pre>
     */
    byte[] emitVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison) throws Stitcher.StitchingException {
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        Element[] inputElements = Stitcher.inputElements(signature);
        Element operand = inputElements[0];
        // Defensive shape guard (the planner only routes eligible trees here): two same-primitive column operands,
        // no constants, boolean output. Anything else is a planner bug — refuse rather than emit wrong code.
        int operandSort = operand.type().getSort();
        if (arity != 2
            || signature.constantsInEmitOrder().isEmpty() == false
            || inputElements[1].type().getSort() != operandSort
            || (operandSort != Type.INT && operandSort != Type.LONG && operandSort != Type.DOUBLE)) {
            // int/long/double only. Double is included because the Vector-API lane compare uses IEEE-754 unordered
            // semantics identical to the Java relational/equality operators the ES double-comparison kernels use
            // (NaN compares false for </<=/>/>=/==, true for !=; -0.0 == +0.0), so the SIMD mask matches the scalar
            // tail exactly (proven by the NaN/-0.0/Inf differential). BytesRef/boolean operands are excluded.
            throw new Stitcher.StitchingException("SIMD path requires two same-int/long/double column operands with no constants", null);
        }
        Element outputElement = Element.of(Stitcher.rootReturnType(tree));
        String eVector = simdVectorClass(operand);
        String arrayDescriptor = operand.rawValuesDescriptor().substring(2);            // "()[I" -> "[I"
        String speciesDescriptor = "L" + FusionSimd.VECTOR_SPECIES + ";";
        String fromArrayDescriptor = "(" + speciesDescriptor + arrayDescriptor + "I)L" + eVector + ";";
        String compareDescriptor = "(L" + FusionSimd.COMPARISON + ";L" + FusionSimd.VECTOR + ";)L" + FusionSimd.VECTOR_MASK + ";";

        FrameLayout frame = new FrameLayout();
        frame.slot();                                    // [0] BlockFactory
        frame.slot();                                    // [1] int positionCount
        int inputBase = frame.slots(arity);              // [2 .. 2+arity) input Vectors
        int rawArrayBase = frame.slots(arity);           // per-input backing arrays
        int outSlot = frame.slot();                      // boolean[]
        int speciesSlot = frame.slot();                  // VectorSpecies
        int boundSlot = frame.slot();                    // int loop bound
        int lenSlot = frame.slot();                      // int species length
        int iSlot = frame.slot();                        // int cursor (shared by SIMD body + scalar tail)
        int vaSlot = frame.slot();                       // *Vector
        int vbSlot = frame.slot();                       // *Vector
        int maskSlot = frame.slot();                     // VectorMask
        int kernelBase = frame.mark();                   // scalar-tail spliced comparison body

        StringBuilder desc = new StringBuilder("(L").append(Stitcher.BLOCK_FACTORY).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(Stitcher.VECTOR).append(";");
        }
        desc.append(")").append(outputElement.vectorDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Stitcher.FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // a = VectorUnsafe.<E>s((EArrayVector) inN); read each backing array once.
        for (int i = 0; i < arity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElements[i].arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Stitcher.VECTOR_UNSAFE,
                    inputElements[i].vectorUnsafeMethod(),
                    inputElements[i].vectorUnsafeDescriptor(),
                    false
                )
            );
            insns.add(new VarInsnNode(Opcodes.ASTORE, rawArrayBase + i));
        }
        // out = new boolean[positionCount]
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, outputElement.newArrayType()));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outSlot));
        // species = EVector.SPECIES_PREFERRED
        insns.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, eVector, "SPECIES_PREFERRED", speciesDescriptor));
        insns.add(new VarInsnNode(Opcodes.ASTORE, speciesSlot));
        // bound = species.loopBound(positionCount)
        insns.add(new VarInsnNode(Opcodes.ALOAD, speciesSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FusionSimd.VECTOR_SPECIES, "loopBound", "(I)I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, boundSlot));
        // len = species.length()
        insns.add(new VarInsnNode(Opcodes.ALOAD, speciesSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FusionSimd.VECTOR_SPECIES, "length", "()I", true));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lenSlot));
        // i = 0
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, iSlot));

        LabelNode simdStart = new LabelNode();
        LabelNode tailStart = new LabelNode();
        LabelNode tailEnd = new LabelNode();
        insns.add(simdStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, boundSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, tailStart));
        // va = EVector.fromArray(species, a, i)
        insns.add(new VarInsnNode(Opcodes.ALOAD, speciesSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase));
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, eVector, "fromArray", fromArrayDescriptor, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, vaSlot));
        // vb = EVector.fromArray(species, b, i)
        insns.add(new VarInsnNode(Opcodes.ALOAD, speciesSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, eVector, "fromArray", fromArrayDescriptor, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, vbSlot));
        // mask = va.compare(VectorOperators.<OP>, vb)
        insns.add(new VarInsnNode(Opcodes.ALOAD, vaSlot));
        insns.add(
            new org.objectweb.asm.tree.FieldInsnNode(
                Opcodes.GETSTATIC,
                FusionSimd.VECTOR_OPERATORS,
                comparison,
                "L" + FusionSimd.COMPARISON + ";"
            )
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, vbSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, eVector, "compare", compareDescriptor, false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, maskSlot));
        // mask.intoArray(out, i)
        insns.add(new VarInsnNode(Opcodes.ALOAD, maskSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FusionSimd.VECTOR_MASK, "intoArray", "([ZI)V", false));
        // i += len
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lenSlot));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, iSlot));
        insns.add(new JumpInsnNode(Opcodes.GOTO, simdStart));

        // Scalar tail: for (; i < positionCount; i++) out[i] = <comparison>(a[i], b[i]).
        insns.add(tailStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, tailEnd));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
        FusedClassDebugInfo debug = FusedClassDebugInfo.of("Fused$$SimdVectorLoop", tree);
        StackKernelEmitter tail = new StackKernelEmitter(insns, java.util.Map.of(), kernelBase, input -> {
            Element e = inputElements[input.index()];
            insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + input.index()));
            insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
            insns.add(new InsnNode(e.type().getOpcode(Opcodes.IALOAD)));
        }, kernel -> {}, debug);
        tail.emit(tree, outputElement);
        insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IASTORE)));
        insns.add(new IincInsnNode(iSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, tailStart));
        insns.add(tailEnd);

        // return bf.newBooleanArrayVector(out, positionCount)
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                Stitcher.BLOCK_FACTORY,
                outputElement.newVectorMethod(),
                outputElement.newVectorDescriptor(),
                false
            )
        );
        insns.add(new InsnNode(Opcodes.ARETURN));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }
        ClassNode classNode = Stitcher.newFusedClass(Stitcher.fusedClassName(caller, "Fused$$SimdVectorLoop"), host, debug);
        // Not budget-enforced: the SIMD body is a fixed small sequence, but it is only used for the eligible
        // single-comparison shape, so it never approaches the limit; measure/log only.
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "SIMD vector loop", false);
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

        MethodNode host = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.BUILDER, true, outputElement),
            inputElements,
            constants,
            consumed,
            overflowTypes,
            sourceIndices,
            debug
        );
        MethodNode hostIface = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.BUILDER, true, outputElement),
            inputElements,
            constants,
            consumed,
            overflowTypes,
            sourceIndices,
            debug
        );
        MethodNode raw = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.RAW, LoopSpec.Output.BLOCK, true, outputElement),
            inputElements,
            constants,
            consumed,
            overflowTypes,
            sourceIndices,
            debug
        );
        MethodNode rawIface = emitVectorMethod(
            tree,
            new LoopSpec(LoopSpec.Reads.INTERFACE, LoopSpec.Output.BLOCK, true, outputElement),
            inputElements,
            constants,
            consumed,
            overflowTypes,
            sourceIndices,
            debug
        );
        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
            hostMethodInterceptor.accept(hostIface);
            hostMethodInterceptor.accept(raw);
            hostMethodInterceptor.accept(rawIface);
        }

        String internalName = Stitcher.fusedClassName(caller, "Fused$$CheckedVectorLoop");
        ClassNode classNode = Stitcher.newFusedClass(internalName, host, debug);
        classNode.methods.add(hostIface);
        classNode.methods.add(raw);
        classNode.methods.add(rawIface);
        // Checked-vector stays enforce=false: size is measured and DEBUG-logged, never refused. Deep arithmetic
        // over the huge-method limit is astronomically deep and must not regress the differential suite; this
        // path is a MethodHandle correctness fallback rather than a JIT-inline hot body.
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "checked-vector loop", false);
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
        frame.slot();                                                       // [0] BlockFactory
        int warningsArraySlot = frame.slot();                               // [1] Warnings[]
        int pcSlot = frame.slot();                                          // [2] int positionCount
        int inputBase = frame.slots(arity);                                 // [3 .. 3+arity) input Vectors
        Map<FusionNode.Constant, Integer> constantSlots = Stitcher.allocConstantSlots(constants, frame);
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

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            raw ? Stitcher.FUSED_METHOD_NAME : Stitcher.FUSED_INTERFACE_METHOD_NAME,
            descriptor,
            null,
            null
        );
        InsnList insns = host.instructions;

        // Narrow each CONSUMED input once, outside the loop (JIT #6).
        for (int i : consumed) {
            emitExtractConsumedInput(insns, inputElements[i], inputBase + i, extractedBase + i, raw);
        }

        // out = bf.new*BlockBuilder(positionCount); the builder is the OUTPUT element (BooleanBlock.Builder for a
        // comparison root).
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
        LabelNode next = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

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
            constantSlots,
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
        if (tryEndOverflow != null) {
            insns.add(tryEndOverflow);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, next));

        for (String exType : overflowTypes) {
            LabelNode overflowHandler = new LabelNode();
            insns.add(overflowHandler);
            insns.add(new VarInsnNode(Opcodes.ASTORE, overflowExcSlot));
            // Register the overflow on the currently-evaluating kernel's OWN Warnings (warnings[currentKernelSlot]) so
            // the header matches the unfused chain's per-node evaluator source.
            insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, currentKernelSlot));
            insns.add(new InsnNode(Opcodes.AALOAD));
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
            insns.add(new JumpInsnNode(Opcodes.GOTO, next));
            host.tryCatchBlocks.add(new TryCatchBlockNode(tryStartOverflow, tryEndOverflow, overflowHandler, exType));
        }

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
        return host;
    }

    /**
     * {@code return blockFactory.new*ArrayVector(out, positionCount).asBlock();} — the dense-output epilogue shared by
     * {@link #emitArrayVectorMethod} when {@link LoopSpec.Output#BLOCK}. {@code new*ArrayVector} charges the
     * breaker; the {@code out[]} written above is a transient uncharged array (same as {@link #emitVectorLoop}).
     */
    static void emitReturnNewArrayVectorAsBlock(InsnList insns, int outSlot, int pcSlot, Element outputElement) {
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
     * Narrows one consumed input once, outside the loop (JIT #6: no per-position checkcast). ALL-RAW: CHECKCAST to
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
     * Builds the class bytes for the dense logical vector loop: INTERFACE-only {@link Stitcher#FUSED_INTERFACE_METHOD_NAME}
     * (B2). The RAW sibling is not emitted.
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
        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(iface);
        }
        ClassNode classNode = Stitcher.newFusedClass(internalName, iface, debug);
        // Not budget-enforced: a representative AND of two comparisons is a handful of bytecodes, and refusing a
        // deep tree would drop the dense path (the whole point of this emit) back to the 3VL builder loop. Measure
        // via the test seam; log-only like the checked-vector / block fallbacks. serializeWithBudgetGuard looks up
        // fused (absent here); size 0 skips the log — the INTERFACE body is the only method and is not refused.
        return Stitcher.serializeWithBudgetGuard(classNode, Stitcher.frameComputingWriter(caller), "logical vector loop", false);
    }

}
