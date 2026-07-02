/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.operator.fusion.BodyExtractor.Body;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.objectweb.asm.ClassWriter;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.function.Consumer;

/**
 * Fuses one or more compiled kernel bodies into a single host method and materialises it as a hidden
 * class the JIT can inline and vectorize as one hot per-position body.
 *
 * <p><b>Why a caller-provided {@link MethodHandles.Lookup}.</b> {@link MethodHandles.Lookup#defineHiddenClass}
 * defines the new class into the <i>lookup's</i> module and runtime package. This class ({@code Stitcher})
 * lives in the named {@code :...:compute} module, but fusable kernel bodies routinely call targets outside
 * {@code java.base} — e.g. the {@code double} arithmetic kernels inline {@code NumericUtils.asFiniteNumber},
 * which lives in {@code org.elasticsearch.xpack.esql.core}. A hidden class defined with {@code :compute}'s own
 * lookup could not link those targets ({@code IllegalAccessError}/{@code NoClassDefFoundError} at link time).
 * Therefore {@link #compile} takes the <b>caller's</b> {@code Lookup} (from the module that owns the kernels,
 * i.e. esql-proper) and defines the fused class there, so every call the kernel bodies make resolves against a
 * module that can read them. When later stages need package-private access into another package (e.g.
 * {@code compute.data} for {@code rawValues()}), the caller — or this class — will teleport via
 * {@link MethodHandles#privateLookupIn} before defining; depth-1 arithmetic kernels need no such teleport
 * because they only call public targets.
 *
 * <p><b>Depth-1 scope.</b> This iteration stitches a single kernel. The seam is post-order-ready for real
 * expression trees (iter 10): fetch the kernel {@link MethodNode} from the {@link TemplateRegistry}, lift its
 * straight-line {@link Body} with the {@link BodyExtractor}, {@linkplain SlotRemapper#shift shift} the body's
 * local slots into a disjoint range above the host's parameters, then concatenate on a clean stack —
 * <em>push host inputs into the kernel's (shifted) argument slots, run the body, return the result</em>. A tree
 * simply repeats this per node, routing each subtree's result into the next free slot; nothing here is
 * depth-1-specific except that a single node is stitched.
 *
 * <p><b>Failure surface (binding rule #5).</b> A bytecode defect only surfaces when the JVM verifies the class
 * at {@code defineHiddenClass} time, as a {@link LinkageError} (of which {@link VerifyError} is a subtype). This
 * method catches those (and the access failure) and rethrows a typed {@link StitchingException} after a WARN,
 * so the plan-time caller (iter 13's {@code EvalMapper}) can catch it and fall back to the unfused evaluator
 * chain without the query path ever seeing an {@code Error}.
 *
 * <p><b>JIT discipline (criterion #6).</b> The emitted {@code fused} method is primitive-only: host inputs are
 * moved into the kernel's argument slots with type-correct {@code xLOAD}/{@code xSTORE} and the kernel body is
 * spliced verbatim, so there is no autoboxing and no per-position {@code checkcast}. For a depth-1 binary kernel
 * the whole method is a handful of instructions — far below HotSpot's default {@code FreqInlineSize} (325
 * bytecodes).
 */
public final class Stitcher {

    private static final Logger logger = LogManager.getLogger(Stitcher.class);

    /**
     * Name of the fused static method emitted on the hidden class. Callers resolve it via the same
     * {@code Lookup} they passed to {@link #compile} and a {@code MethodType} matching the kernel's signature.
     */
    public static final String FUSED_METHOD_NAME = "fused";

    private final TemplateRegistry templates;

    /**
     * Test-only seam (the seed of {@code IMPLEMENTATION_PLAN} iter 18's {@code failureInjector}). When non-null,
     * this hook is invoked on the fully assembled host {@link MethodNode} immediately before it is serialized and
     * defined, letting a test corrupt the emitted bytecode to exercise the {@link StitchingException}
     * verification-failure fallback (binding rule #5). It is <b>never</b> set in production: the field defaults to
     * {@code null} and adds only a single null-check to the cold plan-time path, leaving the hot path untouched.
     */
    Consumer<MethodNode> hostMethodInterceptor = null;

    public Stitcher(TemplateRegistry templates) {
        this.templates = templates;
    }

    /**
     * Fuses the kernel identified by {@code descriptor} into a hidden class defined in {@code caller}'s module,
     * exposing a {@code public static} {@link #FUSED_METHOD_NAME} method whose descriptor matches the kernel's
     * (e.g. {@code (JJ)J}) and whose body is the kernel's computation.
     *
     * @param caller     the lookup of the module that owns the kernel; the fused class is defined here so the
     *                   kernel body's call targets link. Must have {@code PRIVATE} access (as returned by
     *                   {@link MethodHandles#lookup()} or {@link MethodHandles#privateLookupIn}).
     * @param descriptor the fusable kernel to stitch
     * @return the defined hidden {@link Class}
     * @throws StitchingException if the emitted class fails JVM verification/linking, or the caller lacks the
     *                            access needed to define the hidden class — the caller should fall back to the
     *                            unfused path
     */
    public Class<?> compile(MethodHandles.Lookup caller, FusionDescriptor descriptor) throws StitchingException {
        byte[] bytecode = emit(caller, descriptor);
        try {
            MethodHandles.Lookup defined = caller.defineHiddenClass(bytecode, true);
            return defined.lookupClass();
        } catch (LinkageError e) {
            // VerifyError is a LinkageError: a stitched body the verifier rejects lands here.
            logger.warn("fused kernel [{}] failed to link; falling back to unfused", descriptor, e);
            throw new StitchingException("failed to define fused class for kernel " + descriptor, e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused class for kernel [{}]; falling back to unfused", caller, descriptor, e);
            throw new StitchingException("caller lookup cannot define fused class for kernel " + descriptor, e);
        }
    }

    /**
     * Builds the class bytes for the fused host method. Split out from {@link #compile} so the verification
     * boundary is exactly the {@code defineHiddenClass} call: everything here is pure ASM tree manipulation on
     * cloned nodes.
     */
    private byte[] emit(MethodHandles.Lookup caller, FusionDescriptor descriptor) {
        MethodNode kernel = templates.methodNode(descriptor);
        Body body = BodyExtractor.extract(kernel);

        Type kernelType = Type.getMethodType(descriptor.kernelType());
        Type[] argTypes = kernelType.getArgumentTypes();

        // The host method reuses the kernel's descriptor, so its parameters occupy slots [0, base). The kernel's
        // own locals are shifted up by `base` into a disjoint range so its (unshifted) argument loads read back
        // exactly the inputs we store below rather than colliding with the host parameters.
        int base = 0;
        for (Type arg : argTypes) {
            base += arg.getSize();
        }
        SlotRemapper.shift(body.computation(), base);

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            descriptor.kernelType(),
            null,
            null
        );

        // Clean-stack input protocol: copy each host parameter into the kernel's shifted argument slot. Because
        // the host descriptor mirrors the kernel's, parameter i sits at the same offset in both frames, so the
        // shifted destination is simply `base + offset`.
        int offset = 0;
        for (Type arg : argTypes) {
            host.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), offset));
            host.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ISTORE), base + offset));
            offset += arg.getSize();
        }

        // The kernel body now reads its inputs from the shifted slots and leaves the result on the stack; the
        // terminal return the extractor stripped is re-emitted here so the host method returns that value.
        host.instructions.add(body.computation());
        host.instructions.add(new InsnNode(body.returnOpcode()));

        // Test-only: corrupt the assembled method to drive the verification-failure fallback. No-op in production.
        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        String internalName = hiddenClassInternalName(caller, descriptor);
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = internalName;
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        // COMPUTE_FRAMES recomputes maxStack, maxLocals, and stack-map frames. Straight-line kernel bodies have
        // no control-flow joins, so getCommonSuperClass (which would need the caller's class loader) is never
        // consulted.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * A hidden class must be defined in the same runtime package as the lookup class, so its internal name is
     * derived from the caller's package. The JVM appends a unique suffix to this name at define time.
     */
    private static String hiddenClassInternalName(MethodHandles.Lookup caller, FusionDescriptor descriptor) {
        String pkg = caller.lookupClass().getPackageName().replace('.', '/');
        String simpleName = descriptor.kernelClass().getSimpleName() + "$$Fused";
        return pkg.isEmpty() ? simpleName : pkg + "/" + simpleName;
    }

    /**
     * Internal name of the {@code compute.data} package's {@code BlockFactory}, whose
     * {@code new*ArrayVector} factory the fused loop calls to wrap its output array.
     */
    private static final String BLOCK_FACTORY = "org/elasticsearch/compute/data/BlockFactory";

    /**
     * Internal name of the {@code compute.data} {@code Vector} base type. The fused method receives its inputs as
     * this common interface and narrows each once — outside the loop — to the concrete {@code *ArrayVector}
     * whose {@code rawValues()} it reads (JIT criterion #6: no per-position {@code checkcast}).
     */
    private static final String VECTOR = "org/elasticsearch/compute/data/Vector";

    /**
     * Fuses an expression {@code tree} of primitive {@code *ArrayVector} inputs (no nulls) into a single counted
     * loop over {@code positionCount} and materialises it as a hidden class. The emitted {@link #FUSED_METHOD_NAME}
     * method has the shape
     * <pre>{@code
     * static LongVector fused(BlockFactory bf, int positionCount, Vector in0, Vector in1, Vector in2) {
     *     long[] a0 = ((LongArrayVector) in0).rawValues();   // cast + read once, outside the loop
     *     long[] a1 = ((LongArrayVector) in1).rawValues();
     *     long[] a2 = ((LongArrayVector) in2).rawValues();
     *     long[] out = new long[positionCount];
     *     for (int p = 0; p < positionCount; p++) {
     *         out[p] = (a0[p] + a1[p]) * a2[p];              // stitched kernel bodies, inline
     *     }
     *     return bf.newLongArrayVector(out, positionCount);
     * }
     * }</pre>
     *
     * <p><b>Binding rule #2 — {@code rawValues()} access.</b> {@code rawValues()} is package-private in
     * {@code org.elasticsearch.compute.data}. For the fused body to call it, the hidden class must live in that
     * effective runtime package. This method teleports the caller's {@link MethodHandles.Lookup} into
     * {@code compute.data} via {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)} anchored on
     * {@link LongArrayVector} (a {@code compute.data} class) and defines the hidden class there, so the emitted
     * {@code INVOKEVIRTUAL rawValues} and the {@code BlockFactory} factory calls all link with package access. The
     * caller-provided-{@code Lookup} contract from iter 9 is preserved: the caller still supplies the seed lookup;
     * this method only narrows it to the data package.
     *
     * <p><b>Tree walk &amp; slot allocation.</b> The tree is emitted post-order. Each {@link FusionNode.Kernel}
     * node is assigned a <em>disjoint</em> local-slot range starting at a running base; the next node's base is
     * advanced by the kernel body's {@code maxLocals} ({@code base += body.maxLocals()}) so no two kernels' locals
     * collide. Each body is {@linkplain SlotRemapper#shift shifted} into its range and spliced via the
     * {@link BodyExtractor}. Intermediate results ride the operand stack — a child's value is stored into its
     * parent kernel's (shifted) argument slot immediately before the parent body runs — so no per-node result slot
     * is needed and the disjoint ranges guarantee a child's evaluation never clobbers a sibling's stored argument.
     *
     * @param caller the seed lookup (from the module that owns the kernels); narrowed into {@code compute.data}
     * @param tree   the expression tree to fuse; all inputs are homogeneous primitive {@code *ArrayVector}s
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller cannot be teleported into {@code compute.data}, or the emitted class
     *                            fails JVM verification/linking — the caller should fall back to the unfused path
     */
    public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        MethodHandles.Lookup dataLookup;
        try {
            dataLookup = MethodHandles.privateLookupIn(LongArrayVector.class, caller);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot be teleported into compute.data; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot access compute.data for fused vector loop", e);
        }
        byte[] bytecode = emitVectorLoop(dataLookup, tree);
        try {
            return dataLookup.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused vector loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused vector-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("data lookup [{}] cannot define fused vector-loop class; falling back to unfused", dataLookup, e);
            throw new StitchingException("data lookup cannot define fused vector-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the fused vector loop. Kept separate from {@link #compileVectorLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    private byte[] emitVectorLoop(MethodHandles.Lookup dataLookup, FusionNode tree) {
        int arity = arity(tree);
        Element element = Element.of(rootReturnType(tree));

        // Frame layout: [0] BlockFactory, [1] int positionCount, [2 .. 2+arity) input Vectors. The per-input raw
        // arrays, the output array, and the loop counter sit in the fixed region above the parameters; each kernel
        // body then gets a disjoint slot range above that.
        int inputBase = 2;
        int rawArrayBase = inputBase + arity;
        int outSlot = rawArrayBase + arity;
        int pSlot = outSlot + 1;
        int kernelBase = pSlot + 1;

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(VECTOR).append(";");
        }
        desc.append(")").append(element.vectorDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // Cast each input to its concrete *ArrayVector ONCE and read rawValues() into a local array (JIT #6).
        for (int i = 0; i < arity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, element.arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    element.arrayVectorInternalName(),
                    "rawValues",
                    element.rawValuesDescriptor(),
                    false
                )
            );
            insns.add(new VarInsnNode(Opcodes.ASTORE, rawArrayBase + i));
        }

        // out = new T[positionCount];
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, element.newArrayType()));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outSlot));

        // int p = 0;
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // out[p] = <stitched expression>; the array ref and index are pushed first, then the fused kernel bodies
        // leave the computed value on top, so a single array-store consumes all three.
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        VectorLoopEmitter emitter = new VectorLoopEmitter(insns, element, rawArrayBase, pSlot, kernelBase);
        emitter.emit(tree);
        insns.add(new InsnNode(element.type().getOpcode(Opcodes.IASTORE)));

        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);

        // return blockFactory.new*ArrayVector(out, positionCount);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_FACTORY, element.newVectorMethod(), element.newVectorDescriptor(), false)
        );
        insns.add(new InsnNode(Opcodes.ARETURN));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = dataLookup.lookupClass().getPackageName().replace('.', '/') + "/Fused$$VectorLoop";
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        ClassLoader loader = dataLookup.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            // The loop is control flow, so COMPUTE_FRAMES may need a common-superclass lookup at the back-edge
            // join. Resolve against the compute.data class loader (which can see the vector types) rather than
            // ASM's own; our joins never actually merge incompatible reference types, so this is only a safety net.
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Post-order emitter for one expression tree into the fused loop body. Holds the running kernel-slot base so
     * each {@link FusionNode.Kernel} node claims a disjoint range (advanced by the body's {@code maxLocals}).
     */
    private final class VectorLoopEmitter {
        private final InsnList insns;
        private final Element element;
        private final int rawArrayBase;
        private final int pSlot;
        private int kernelBase;

        VectorLoopEmitter(InsnList insns, Element element, int rawArrayBase, int pSlot, int kernelBase) {
            this.insns = insns;
            this.element = element;
            this.rawArrayBase = rawArrayBase;
            this.pSlot = pSlot;
            this.kernelBase = kernelBase;
        }

        /**
         * Emits instructions that leave {@code node}'s value for the current position {@code p} on the operand
         * stack.
         */
        void emit(FusionNode node) {
            if (node instanceof FusionNode.Input input) {
                // a<index>[p]
                insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + input.index()));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
                insns.add(new InsnNode(element.type().getOpcode(Opcodes.IALOAD)));
                return;
            }
            FusionNode.Kernel kernel = (FusionNode.Kernel) node;
            MethodNode template = templates.methodNode(kernel.descriptor());
            Body body = BodyExtractor.extract(template);

            // Claim this node's disjoint slot range, then shift the body's locals into it. Argument loads inside the
            // body now read [nodeBase + argOffset]; the stores below feed exactly those slots.
            int nodeBase = kernelBase;
            kernelBase += body.maxLocals();
            SlotRemapper.shift(body.computation(), nodeBase);

            Type[] argTypes = Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes();
            var children = kernel.children();
            if (children.size() != argTypes.length) {
                throw new IllegalArgumentException(
                    "kernel ["
                        + kernel.descriptor().kernelMethod()
                        + "] expects "
                        + argTypes.length
                        + " operands but the tree supplied "
                        + children.size()
                );
            }
            int offset = 0;
            for (int i = 0; i < argTypes.length; i++) {
                emit(children.get(i));
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), nodeBase + offset));
                offset += argTypes[i].getSize();
            }
            // The spliced body reads its inputs from the shifted argument slots and leaves the result on the stack.
            insns.add(body.computation());
        }
    }

    /** Number of distinct input vectors the tree references, i.e. one past the maximum {@code Input} index. */
    private static int arity(FusionNode tree) {
        if (tree instanceof FusionNode.Input input) {
            return input.index() + 1;
        }
        int max = 0;
        for (FusionNode child : ((FusionNode.Kernel) tree).children()) {
            max = Math.max(max, arity(child));
        }
        return max;
    }

    /** The tree's output element type — the return type of the root kernel. Inputs are homogeneous in this scope. */
    private Type rootReturnType(FusionNode tree) {
        if (tree instanceof FusionNode.Kernel kernel) {
            return Type.getMethodType(kernel.descriptor().kernelType()).getReturnType();
        }
        throw new IllegalArgumentException("a fused vector loop requires a kernel at the tree root, not a bare input");
    }

    /**
     * The concrete {@code compute.data} types and opcodes for one supported primitive element kind. Resolving these
     * once keeps the emit code free of per-type branching and confines the {@code long}/{@code int}/{@code double}
     * differences to a single table.
     */
    private record Element(
        String arrayVectorInternalName,
        String rawValuesDescriptor,
        String vectorDescriptor,
        String newVectorMethod,
        String newVectorDescriptor,
        int newArrayType,
        Type type
    ) {
        static Element of(Type t) {
            return switch (t.getSort()) {
                case Type.LONG -> new Element(
                    "org/elasticsearch/compute/data/LongArrayVector",
                    "()[J",
                    "Lorg/elasticsearch/compute/data/LongVector;",
                    "newLongArrayVector",
                    "([JI)Lorg/elasticsearch/compute/data/LongVector;",
                    Opcodes.T_LONG,
                    t
                );
                case Type.INT -> new Element(
                    "org/elasticsearch/compute/data/IntArrayVector",
                    "()[I",
                    "Lorg/elasticsearch/compute/data/IntVector;",
                    "newIntArrayVector",
                    "([II)Lorg/elasticsearch/compute/data/IntVector;",
                    Opcodes.T_INT,
                    t
                );
                case Type.DOUBLE -> new Element(
                    "org/elasticsearch/compute/data/DoubleArrayVector",
                    "()[D",
                    "Lorg/elasticsearch/compute/data/DoubleVector;",
                    "newDoubleArrayVector",
                    "([DI)Lorg/elasticsearch/compute/data/DoubleVector;",
                    Opcodes.T_DOUBLE,
                    t
                );
                default -> throw new IllegalArgumentException("unsupported fused output element type: " + t.getClassName());
            };
        }
    }

    /**
     * Typed, catchable failure for a stitch that could not be verified or defined. Callers catch this to fall
     * back to the unfused evaluator chain (binding rule #5); it wraps the underlying {@link LinkageError} or
     * access failure so the cause is preserved for diagnostics.
     */
    public static final class StitchingException extends Exception {
        public StitchingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
