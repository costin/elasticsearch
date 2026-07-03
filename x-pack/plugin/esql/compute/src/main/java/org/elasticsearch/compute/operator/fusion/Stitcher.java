/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * module that can read them. All emit shapes — block, plain-vector and checked-vector — define into the caller's
 * own module: the vector paths reach a dense {@code *ArrayVector}'s package-private backing array through the public
 * exported {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder (a plain {@code INVOKESTATIC}), so no
 * {@code privateLookupIn(compute.data, ...)} teleport is ever needed and every path can link non-{@code java.base}
 * kernel callees.
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
 *
 * <p><b>Trusted-generation contract (verification is test-time, not runtime).</b> The emitted per-position loop
 * carries only the <em>semantic</em> guards a correct result needs (null / multi-value short-circuiting, overflow
 * try/catch, div-by-zero) — never any defensive "is this bytecode well-formed / are the slots typed right" checks.
 * Correctness of the <em>generation</em> is proven exhaustively at test time — {@code StitcherBudgetTests} runs every
 * emit path through {@code CheckClassAdapter} and asserts no boxing / no {@code invokedynamic},
 * and the esql-proper differential suites define AND invoke the fused classes over live pages and assert value/null
 * parity with the unfused chain — plus the JVM's own bytecode verifier runs once per class at {@code
 * defineHiddenClass}. The few internal invariants the emitter itself relies on (e.g. a constant's element matching its
 * consuming kernel's) are Java {@code assert}s, enabled under {@code -ea} in tests and elided in production, so the
 * runtime generate-and-execute path stays fast and adds no per-stitch or per-row validation cost of its own.
 */
public final class Stitcher {

    private static final Logger logger = LogManager.getLogger(Stitcher.class);

    /**
     * Name of the fused static method emitted on the hidden class. Callers resolve it via the same
     * {@code Lookup} they passed to {@link #compile} and a {@code MethodType} matching the kernel's signature.
     */
    public static final String FUSED_METHOD_NAME = "fused";

    /**
     * HotSpot's default {@code FreqInlineSize} (criterion #6): a hot method above this many bytecodes is not inlined
     * into its caller, defeating the single-hot-body goal. The stitcher measures the emitted {@code fused} loop
     * method's {@code Code} length and refuses to fuse (throwing {@link StitchingException} so the caller falls back to
     * the unfused chain) if it would exceed this budget. In this scope depth-2..4 arithmetic/comparison trees stay far
     * under it; the guard is a safety net against a pathological tree, and keeps the emitted body inlinable by C2.
     */
    static final int MAX_FUSED_METHOD_BYTECODES = 325;

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
     * Internal name of {@code org.elasticsearch.compute.data.VectorUnsafe}, the public static seam through which the
     * fused vector loops read a dense {@code *ArrayVector}'s backing array. The emitted {@code INVOKESTATIC} to it
     * (rather than the package-private {@code INVOKEVIRTUAL rawValues()}) is what lets the vector-loop hidden class be
     * defined in the <b>caller's</b> module — unifying the lookup with the block path and letting non-{@code java.base}
     * kernels (e.g. {@code NumericUtils}) link on the vector path.
     */
    private static final String VECTOR_UNSAFE = "org/elasticsearch/compute/data/VectorUnsafe";

    /**
     * Internal name of the {@code compute.data} {@code Vector} base type. The fused method receives its inputs as
     * this common interface and narrows each once — outside the loop — to the concrete {@code *ArrayVector}
     * whose {@code rawValues()} it reads (JIT criterion #6: no per-position {@code checkcast}).
     */
    private static final String VECTOR = "org/elasticsearch/compute/data/Vector";

    /**
     * Internal name of the {@code compute.data} {@code Block} base type. The block-path fused method reads each
     * input's per-position null/multi-value state through this common interface ({@code getValueCount},
     * {@code getFirstValueIndex}) before narrowing to the concrete {@code *Block} for the typed value read.
     */
    private static final String BLOCK = "org/elasticsearch/compute/data/Block";

    /**
     * Internal name of {@code org.elasticsearch.core.Releasable}, the interface through which the block-path fused
     * method closes its output builder. The builder is a resource: {@code build()} transfers its breaker
     * accounting to the produced block, but on any abnormal exit (e.g. a {@code CircuitBreakingException} while
     * growing the builder) the try/finally the fused method emits closes it so no breaker bytes leak.
     */
    private static final String RELEASABLE = "org/elasticsearch/core/Releasable";

    /**
     * Internal name of {@code org.elasticsearch.compute.operator.Warnings}, the per-evaluator warning collector.
     * The fused block/checked-vector methods take one as a parameter and, on an overflowing or multi-valued
     * position, call {@link #WARNINGS_REGISTER_DESCRIPTOR registerException} on it — byte-for-byte the same call
     * the generated unfused evaluator makes ({@code warnings().registerException(...)}), so a fused query records
     * identical warnings.
     */
    private static final String WARNINGS = "org/elasticsearch/compute/operator/Warnings";

    /** Descriptor of {@code Warnings.registerException(Exception)}. */
    private static final String WARNINGS_REGISTER_DESCRIPTOR = "(Ljava/lang/Exception;)V";

    /**
     * JVM descriptor of a {@code Warnings[]} parameter. The fused block / checked-vector / logical methods take a
     * <b>per-warning-source</b> {@code Warnings} array (indexed by {@link #warningsSourceIndices}) rather than a single
     * {@code Warnings}, so each warning-emitting node registers on the {@code Warnings} built from <em>its own</em> node
     * {@link org.elasticsearch.compute.operator.WarningSourceLocation source} — byte-for-byte the header the generated
     * unfused evaluator for that node would emit (each generated {@code *Evaluator} carries its own node source). This
     * keeps the fused warning headers a <b>subset</b> of the unfused chain's (criterion #1): reached warnings are equal;
     * short-circuit only drops the never-reached ones.
     */
    private static final String WARNINGS_ARRAY_DESCRIPTOR = "[L" + WARNINGS + ";";

    /** Internal name of {@code java.lang.IllegalArgumentException}, the multi-value warning the unfused evaluator raises. */
    private static final String ILLEGAL_ARGUMENT_EXCEPTION = "java/lang/IllegalArgumentException";

    /**
     * The exact message the generated unfused evaluator uses when a scalar (single-value) function meets a
     * multi-valued position. Mirrored verbatim so the fused block path's carried-forward warning matches.
     */
    private static final String MULTI_VALUE_MESSAGE = "single-value function encountered multi-value";

    /**
     * Fuses an expression {@code tree} of primitive {@code *ArrayVector} inputs (no nulls) into a single counted
     * loop over {@code positionCount} and materialises it as a hidden class. The emitted {@link #FUSED_METHOD_NAME}
     * method has the shape
     * <pre>{@code
     * static LongVector fused(BlockFactory bf, int positionCount, Vector in0, Vector in1, Vector in2) {
     *     long[] a0 = VectorUnsafe.longs((LongArrayVector) in0);   // cast + read once, outside the loop
     *     long[] a1 = VectorUnsafe.longs((LongArrayVector) in1);
     *     long[] a2 = VectorUnsafe.longs((LongArrayVector) in2);
     *     long[] out = new long[positionCount];
     *     for (int p = 0; p < positionCount; p++) {
     *         out[p] = (a0[p] + a1[p]) * a2[p];              // stitched kernel bodies, inline
     *     }
     *     return bf.newLongArrayVector(out, positionCount);
     * }
     * }</pre>
     *
     * <p><b>Overflow.</b> This array-output fast path has no null bitmask, so it is only valid when no kernel can
     * throw on overflow (an escaping {@code ArithmeticException} would abort the whole method rather than nulling one
     * position). For a tree with overflow-checked kernels the caller must use {@link #compileVectorLoopChecked},
     * which appends through a builder and turns an overflow into a null + warning like the unfused evaluator.
     *
     * <p><b>Backing-array access via {@code VectorUnsafe} (no teleport).</b> {@code rawValues()} is package-private in
     * {@code org.elasticsearch.compute.data}, so the fused body cannot call it directly from another module. Rather
     * than teleport the hidden class into {@code compute.data} (which then could not link non-{@code java.base} kernel
     * callees), the emitter reads each input's backing array through the <b>public exported</b>
     * {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder with a plain {@code INVOKESTATIC}. Every symbol the
     * fused method touches ({@code VectorUnsafe}, {@code BlockFactory}, the kernel bodies' call targets) is therefore
     * resolvable from the <b>caller's</b> own module, so this method defines the hidden class directly with the
     * caller's {@link MethodHandles.Lookup} — exactly as {@link #compileBlockLoop} does. This unifies the block and
     * vector lookup and lets the vector path fuse esql-proper/esql-core kernels (e.g. {@code NumericUtils}).
     *
     * <p><b>Tree walk &amp; slot allocation.</b> The tree is emitted post-order. Each {@link FusionNode.Kernel}
     * node is assigned a <em>disjoint</em> local-slot range starting at a running base; the next node's base is
     * advanced by the kernel body's {@code maxLocals} ({@code base += body.maxLocals()}) so no two kernels' locals
     * collide. Each body is {@linkplain SlotRemapper#shift shifted} into its range and spliced via the
     * {@link BodyExtractor}. Intermediate results ride the operand stack — a child's value is stored into its
     * parent kernel's (shifted) argument slot immediately before the parent body runs — so no per-node result slot
     * is needed and the disjoint ranges guarantee a child's evaluation never clobbers a sibling's stored argument.
     *
     * @param caller the caller's lookup (from the module that owns the kernels); the fused class is defined here
     * @param tree   the expression tree to fuse; all inputs are homogeneous primitive {@code *ArrayVector}s
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, or the emitted class
     *                            fails JVM verification/linking — the caller should fall back to the unfused path
     */
    public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        byte[] bytecode = emitVectorLoop(caller, tree);
        try {
            return caller.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused vector loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused vector-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused vector-loop class; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot define fused vector-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the fused vector loop. Kept separate from {@link #compileVectorLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    private byte[] emitVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        int arity = arity(tree);
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the output
        // array + produced vector). They coincide for a homogeneous arithmetic root and differ for a comparison root
        // (numeric inputs feed a boolean output) or any cast-bridged input.
        Element[] inputElements = inputElements(tree, arity);
        Element outputElement = Element.of(rootReturnType(tree));

        // Frame layout: [0] BlockFactory, [1] int positionCount, [2 .. 2+arity) input Vectors, then one trailing
        // primitive parameter per embedded constant (in canonical emit order). The per-input raw arrays, the output
        // array, and the loop counter sit in the fixed region ABOVE the parameters — so their bases are all shifted up
        // by the constant parameters' total slot size; each kernel body then gets a disjoint slot range above that.
        List<FusionNode.Constant> constants = constantsInEmitOrder(tree);
        int inputBase = 2;
        int constParamBase = inputBase + arity;
        Map<FusionNode.Constant, Integer> constantSlots = constantParamSlots(constants, constParamBase);
        int rawArrayBase = constParamBase + constantParamSize(constants);
        int outSlot = rawArrayBase + arity;
        int pSlot = outSlot + 1;
        int kernelBase = pSlot + 1;

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(VECTOR).append(";");
        }
        appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.vectorDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // Cast each input to its concrete *ArrayVector ONCE and read its backing array into a local (JIT #6). The
        // array is fetched through the public VectorUnsafe forwarder (INVOKESTATIC) rather than the package-private
        // rawValues() (INVOKEVIRTUAL), so this hidden class can be defined in the caller's own module.
        for (int i = 0; i < arity; i++) {
            Element inputElement = inputElements[i];
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElement.arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    VECTOR_UNSAFE,
                    inputElement.vectorUnsafeMethod(),
                    inputElement.vectorUnsafeDescriptor(),
                    false
                )
            );
            insns.add(new VarInsnNode(Opcodes.ASTORE, rawArrayBase + i));
        }

        // out = new T[positionCount]; T is the OUTPUT element (boolean[] for a comparison root).
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, outputElement.newArrayType()));
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
        VectorLoopEmitter emitter = new VectorLoopEmitter(insns, inputElements, rawArrayBase, pSlot, kernelBase, constantSlots);
        emitter.emit(tree, outputElement);
        // The computed value (an int 0/1 for a comparison root) is stored with the OUTPUT element's array-store opcode
        // (BASTORE for boolean).
        insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IASTORE)));

        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);

        // return blockFactory.new*ArrayVector(out, positionCount);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLOCK_FACTORY,
                outputElement.newVectorMethod(),
                outputElement.newVectorDescriptor(),
                false
            )
        );
        insns.add(new InsnNode(Opcodes.ARETURN));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = caller.lookupClass().getPackageName().replace('.', '/') + "/Fused$$VectorLoop";
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        ClassLoader loader = caller.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            // The loop is control flow, so COMPUTE_FRAMES may need a common-superclass lookup at the back-edge
            // join. Resolve against the caller's class loader (which can see both the compute vector types and the
            // kernel callees) rather than ASM's own; our joins never actually merge incompatible reference types, so
            // this is only a safety net.
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        return serializeWithBudgetGuard(classNode, writer, "plain-vector loop", true);
    }

    /**
     * Overflow-aware counterpart to {@link #compileVectorLoop}: fuses a tree of no-null single-valued primitive
     * {@code *ArrayVector} inputs whose kernels are <b>overflow-checked</b> into a single counted loop that reads
     * each input's backing array (the same fast, guard-free read as the plain vector path) but writes
     * through a {@code *Block.Builder} so an overflowing position can become {@code null}. This is the "nulls-capable
     * output" the plain array-vector fast path cannot provide: an {@code ArrayVector} has no null bitmask, whereas
     * the unfused vector-path evaluator (e.g. {@code AddLongsEvaluator.eval(int, LongVector, LongVector)}) already
     * builds a {@code *Block} via a builder and appends {@code null} on a caught {@code ArithmeticException}. The
     * emitted {@link #FUSED_METHOD_NAME} has the shape
     * <pre>{@code
     * static LongBlock fused(BlockFactory bf, Warnings warnings, int positionCount, Vector in0, Vector in1) {
     *     long[] a0 = VectorUnsafe.longs((LongArrayVector) in0);
     *     long[] a1 = VectorUnsafe.longs((LongArrayVector) in1);
     *     LongBlock.Builder out = bf.newLongBlockBuilder(positionCount);
     *     try {
     *         for (int p = 0; p < positionCount; p++) {
     *             long v0 = a0[p], v1 = a1[p];
     *             try { out.appendLong(process(v0, v1)); }
     *             catch (ArithmeticException e) { warnings.registerException(e); out.appendNull(); }
     *         }
     *         return out.build();
     *     } finally { out.close(); }
     * }
     * }</pre>
     *
     * <p><b>Backing-array access via {@code VectorUnsafe} (no teleport; {@code double} now links).</b> Like
     * {@link #compileVectorLoop}, this path reads each input's backing array through the public exported
     * {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder ({@code INVOKESTATIC}) rather than the
     * package-private {@code rawValues()}, so the hidden class is defined with the <b>caller's</b> own
     * {@link MethodHandles.Lookup} (the module that owns the kernels), not teleported into {@code compute.data}. That
     * caller module reads both {@code compute} (for the vector/block API) and the kernel modules, so a {@code double}
     * overflow kernel body's {@code NumericUtils.asFiniteNumber} call (in {@code org.elasticsearch.xpack.esql.core})
     * links here just as it does on {@link #compileBlockLoop}. This removes the previous {@code double}+overflow hard
     * gate: overflow-checked {@code double} trees may now use this checked-vector fast path.
     *
     * @param caller the caller's lookup (from the module that owns the kernels); the fused class is defined here
     * @param tree   the expression tree to fuse; all inputs are homogeneous no-null single-valued {@code *ArrayVector}s
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, or the emitted class
     *                            fails JVM verification/linking — the caller should fall back to the block path or
     *                            unfused chain
     */
    public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        byte[] bytecode = emitVectorLoopChecked(caller, tree);
        try {
            return caller.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused checked vector loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused checked vector-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused checked vector-loop class; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot define fused checked vector-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the overflow-aware vector loop. Reads inputs from {@code rawValues()} arrays (no
     * per-position null/multi-value guard — a vector is dense and single-valued) but appends through a builder and
     * wraps the kernel evaluation in the overflow try/catch, so the only nulls it can produce are overflow nulls.
     */
    private byte[] emitVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        int arity = arity(tree);
        int[] consumed = consumedInputs(tree);
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the builder +
        // produced block). Each consumed input is read at its OWN element into a per-input value slot.
        Element[] inputElements = inputElements(tree, arity);
        Element outputElement = Element.of(rootReturnType(tree));
        Set<String> overflowTypes = overflowExceptionInternalNames(tree);

        // Frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input Vectors, then one
        // trailing primitive parameter per embedded constant (in canonical emit order). The per-input raw arrays, the
        // builder, loop counter, per-input value slots, built result, the two exception slots and the current-kernel
        // slot sit ABOVE the parameters (their bases shifted up by the constant parameters' total slot size); each
        // kernel body then gets a disjoint range above that.
        List<FusionNode.Constant> constants = constantsInEmitOrder(tree);
        int warningsArraySlot = 1;
        int pcSlot = 2;
        int inputBase = 3;
        int constParamBase = inputBase + arity;
        Map<FusionNode.Constant, Integer> constantSlots = constantParamSlots(constants, constParamBase);
        int rawArrayBase = constParamBase + constantParamSize(constants);
        int builderSlot = rawArrayBase + arity;
        int pSlot = builderSlot + 1;
        int valueBase = pSlot + 1;
        // Per-input value slots, sized by each input's own element (a long/double takes two slots). Densely populated
        // over [0, arity); an unused index (never expected) defaults to a one-slot reservation.
        int[] valueSlots = new int[arity];
        int valueCursor = valueBase;
        for (int i = 0; i < arity; i++) {
            valueSlots[i] = valueCursor;
            valueCursor += inputElements[i] != null ? inputElements[i].type().getSize() : 1;
        }
        int resultSlot = valueCursor;
        int excSlot = resultSlot + 1;
        int overflowExcSlot = excSlot + 1;
        // Names the overflow-checked kernel currently evaluating; the shared overflow handler reads it to attribute the
        // caught overflow to that kernel's own warning source.
        int currentKernelSlot = overflowExcSlot + 1;
        int kernelBase = currentKernelSlot + 1;

        // Each kernel's warning-source slot into the runtime Warnings[]: its overflow warning registers on
        // warnings[its slot], matching the unfused per-node evaluator source.
        Map<FusionNode, Integer> sourceIndices = warningsSourceIndices(tree);

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(VECTOR).append(";");
        }
        appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // Cast each CONSUMED input to its concrete *ArrayVector ONCE and read its backing array into a local. As in
        // the plain vector loop, the array is fetched through the public VectorUnsafe forwarder (INVOKESTATIC) so this
        // hidden class can be defined in the caller's own module and link non-java.base kernel callees.
        for (int i : consumed) {
            Element inputElement = inputElements[i];
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElement.arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    VECTOR_UNSAFE,
                    inputElement.vectorUnsafeMethod(),
                    inputElement.vectorUnsafeDescriptor(),
                    false
                )
            );
            insns.add(new VarInsnNode(Opcodes.ASTORE, rawArrayBase + i));
        }

        // out = bf.new*BlockBuilder(positionCount); the builder is the OUTPUT element (BooleanBlock.Builder for a
        // comparison root).
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLOCK_FACTORY,
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

        // v_i = a_i[p]; no null guard — a vector is dense and single-valued. Read with each input's OWN element opcodes.
        for (int i : consumed) {
            Element inputElement = inputElements[i];
            insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + i));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new InsnNode(inputElement.type().getOpcode(Opcodes.IALOAD)));
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
        BlockLoopEmitter emitter = new BlockLoopEmitter(
            insns,
            inputElements,
            valueSlots,
            sourceIndices,
            currentKernelSlot,
            kernelBase,
            constantSlots
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
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new InsnNode(Opcodes.ARETURN));

        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, excSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, excSlot));
        insns.add(new InsnNode(Opcodes.ATHROW));

        host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = caller.lookupClass().getPackageName().replace('.', '/') + "/Fused$$CheckedVectorLoop";
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        ClassLoader loader = caller.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        return serializeWithBudgetGuard(classNode, writer, "checked-vector loop", false);
    }

    /**
     * Post-order emitter for one expression tree into the fused loop body. Holds the running kernel-slot base so
     * each {@link FusionNode.Kernel} node claims a disjoint range (advanced by the body's {@code maxLocals}).
     */
    private final class VectorLoopEmitter {
        private final InsnList insns;
        private final Element[] inputElements;
        private final int rawArrayBase;
        private final int pSlot;
        private int kernelBase;
        private final Map<FusionNode.Constant, Integer> constantSlots;

        VectorLoopEmitter(
            InsnList insns,
            Element[] inputElements,
            int rawArrayBase,
            int pSlot,
            int kernelBase,
            Map<FusionNode.Constant, Integer> constantSlots
        ) {
            this.insns = insns;
            this.inputElements = inputElements;
            this.rawArrayBase = rawArrayBase;
            this.pSlot = pSlot;
            this.kernelBase = kernelBase;
            this.constantSlots = constantSlots;
        }

        /**
         * Emits instructions that leave {@code node}'s value for the current position {@code p} on the operand stack.
         * {@code expected} is the element {@code node} must produce (its consuming kernel's argument element, or the
         * output element at the root) — used to type a leaf read / constant push under per-node typing.
         */
        void emit(FusionNode node, Element expected) {
            if (node instanceof FusionNode.Input input) {
                // a<index>[p], read at this leaf's own element (== expected).
                Element element = inputElements[input.index()];
                insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + input.index()));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
                insns.add(new InsnNode(element.type().getOpcode(Opcodes.IALOAD)));
                return;
            }
            if (node instanceof FusionNode.Constant constant) {
                // Embedded literal: load it from its trailing method-parameter slot (no array read, no input slot).
                loadConstantParam(insns, expected, constant, constantSlots.get(constant));
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
                // Each child produces this kernel's argument element (a cast child bridges a differing leaf element).
                emit(children.get(i), Element.of(argTypes[i]));
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), nodeBase + offset));
                offset += argTypes[i].getSize();
            }
            // The spliced body reads its inputs from the shifted argument slots and leaves the result on the stack.
            insns.add(body.computation());
        }
    }

    /**
     * Fuses an expression {@code tree} whose inputs are primitive {@code *Block}s that may carry nulls and/or
     * multi-valued positions into a single position-by-position loop, and materialises it as a hidden class. This
     * is the null-tolerant fallback to {@link #compileVectorLoop}: the caller uses the vector fast path when every
     * input is a no-null single-valued {@code *ArrayVector}, and this block path otherwise. The emitted
     * {@link #FUSED_METHOD_NAME} method has the shape
     * <pre>{@code
     * static LongBlock fused(BlockFactory bf, Warnings warnings, int positionCount,
     *                        LongBlock b0, LongBlock b1, LongBlock b2) {
     *     LongBlock.Builder out = bf.newLongBlockBuilder(positionCount);
     *     try {
     *         position: for (int p = 0; p < positionCount; p++) {
     *             for (LongBlock b : {b0, b1, b2}) {          // each CONSUMED input, in leaf order
     *                 int c = b.getValueCount(p);
     *                 if (c == 0) { out.appendNull(); continue position; }              // null -> null, no warning
     *                 if (c > 1)  { warnings.registerException(                          // multi-value -> null + warning
     *                                   new IllegalArgumentException("single-value function encountered multi-value"));
     *                               out.appendNull(); continue position; }
     *             }
     *             long v0 = b0.getLong(b0.getFirstValueIndex(p));
     *             long v1 = b1.getLong(b1.getFirstValueIndex(p));
     *             long v2 = b2.getLong(b2.getFirstValueIndex(p));
     *             try {
     *                 out.appendLong((v0 + v1) * v2);         // stitched kernel bodies, inline (Math.*Exact)
     *             } catch (ArithmeticException e) {            // overflow -> null + warning (per overflowExceptionType)
     *                 warnings.registerException(e);
     *                 out.appendNull();
     *             }
     *         }
     *         return out.build();
     *     } finally {
     *         out.close();                                    // no-op after build(); frees breaker bytes on throw
     *     }
     * }
     * }</pre>
     *
     * <p><b>Null / multi-value semantics.</b> This mirrors the generated unfused evaluator's {@code *Block}-path
     * exactly (e.g. {@code AddLongsEvaluator.eval(int, LongBlock, LongBlock)}): for each consumed input a position is
     * emitted as {@code null} when {@code getValueCount(p) == 0} (a null, with <em>no</em> warning) or when
     * {@code getValueCount(p) > 1} (multi-value, which a scalar binary kernel cannot accept — with a
     * {@code IllegalArgumentException("single-value function encountered multi-value")} warning registered, the
     * carried-forward single-value warning); otherwise the single value at {@code getFirstValueIndex(p)} is read and
     * the kernel bodies run. In an unfused chain every leaf null/multi-value propagates up through each kernel to the
     * root, so the composed result is null at {@code p} iff any leaf is null/multi-value at {@code p} — which is what
     * the per-input guard here reproduces (intermediate kernel results are always single-valued, so only the leaves'
     * value counts matter).
     *
     * <p><b>Overflow semantics (binding rule #3).</b> When the tree contains any overflow-checked kernel, the
     * per-position value computation + append is wrapped in a try/catch for each distinct
     * {@link FusionDescriptor#overflowExceptionType()} present (uniformly {@code ArithmeticException} in scope). A
     * caught overflow registers the thrown exception on the {@code Warnings} and appends {@code null} for that
     * position — exactly the {@code try { result.append*(process(...)); } catch (ArithmeticException e) {
     * warnings().registerException(e); result.appendNull(); }} the generated evaluator emits. A single try/catch
     * around the whole spliced expression is observably identical to wrapping each kernel individually: the kernels
     * are evaluated post-order, so the first kernel to overflow throws out of the expression and yields one null +
     * one warning at that position, just as the unfused chain (where that kernel's evaluator nulls the position and
     * the null then short-circuits every enclosing kernel) does. The narrower overflow handler is registered ahead
     * of the builder-safety catch-all in the exception table so overflow is matched first and any other throwable
     * (e.g. a {@code CircuitBreakingException} from the builder) still closes the builder.
     *
     * <p><b>Defined in the caller's module (no teleport).</b> The block path touches only <b>public exported</b> API —
     * {@code BlockFactory.new*BlockBuilder}, {@code Block.getValueCount}/{@code getFirstValueIndex}, the typed
     * {@code *Block.get*}, the {@code *Block.Builder} appenders/{@code build}, and {@code Releasable.close}. So the
     * fused class is defined directly into the <b>caller's</b> own package/module (as {@link #compile} does for a
     * depth-1 kernel): the caller's module already reads {@code compute} (for the public block types) and the module
     * that owns the kernels (for the spliced kernel-body call targets, e.g. {@code NumericUtils.asFiniteNumber} in
     * {@code Add.processDoubles}). The vector paths ({@link #compileVectorLoop}/{@link #compileVectorLoopChecked}) now
     * define into the caller's module too — they reach the package-private backing array through the public
     * {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder rather than teleporting into {@code compute.data}
     * (which is <i>exported</i> but not <i>opened</i>, so a {@code privateLookupIn(compute.data, esqlLookup)} would
     * have failed) — so all three paths share one caller-module lookup.
     *
     * <p><b>Failure surface (rule #5).</b> Any verification/linkage failure at {@code defineHiddenClass} is rethrown
     * as a {@link StitchingException} for the caller to fall back to the unfused chain.
     *
     * <p><b>JIT discipline (criterion #6).</b> The inner work is primitive-only (values read into
     * {@code long}/{@code int}/{@code double} locals, kernel bodies spliced verbatim). The per-position null-check
     * branch is unavoidable for null propagation but is a single compare per <em>consumed</em> input; the concrete
     * {@code *Block} type is fixed by the method descriptor so there is no per-position {@code checkcast}.
     *
     * @param caller the caller's lookup (from the module that owns the kernels); the fused class is defined here
     * @param tree   the expression tree to fuse; all inputs are homogeneous primitive {@code *Block}s
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, or the emitted class
     *                            fails JVM verification/linking — the caller should fall back to the unfused path
     */
    public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        byte[] bytecode = emitBlockLoop(caller, tree);
        try {
            return caller.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused block loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused block-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused block-loop class; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot define fused block-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the fused block loop. Kept separate from {@link #compileBlockLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    private byte[] emitBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        int arity = arity(tree);
        // Only the inputs the tree actually references are null-checked and read. The method still takes one *Block
        // parameter per index in [0, arity) (so callers bind positionally by leaf index), but guarding an input the
        // expression never consumes would over-nullify: a null/multi-value in an unused input would wrongly null the
        // whole position even though no kernel reads it. The unfused chain only inspects operands it actually uses,
        // so we mirror that by iterating the consumed-input set.
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs OUTPUT element (the builder +
        // produced block). Each *Block parameter is typed to its input's own element.
        Element[] inputElements = inputElements(tree, arity);
        Element outputElement = Element.of(rootReturnType(tree));
        // Frame layout: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input *Blocks. The
        // builder, loop counter, a scratch value-count slot, the built result slot, the caught-throwable slot
        // (builder-safety), the per-kernel overflow-exception slot and the value-path {@code live} flag sit in the
        // fixed region above the parameters; each node then gets a disjoint present/value/body range above that
        // (allocated by the DFS emitter from {@code workBase}). The DFS emitter (see below) reads each consumed leaf
        // inline into the enclosing kernel's operand slots, so there are no separate pre-read leaf value slots.
        // One trailing primitive parameter per embedded constant (canonical emit order) sits between the input *Blocks
        // and the scratch region, so every scratch base is shifted up by the constant parameters' total slot size.
        List<FusionNode.Constant> constants = constantsInEmitOrder(tree);
        int warningsArraySlot = 1;
        int pcSlot = 2;
        int inputBase = 3;
        int constParamBase = inputBase + arity;
        Map<FusionNode.Constant, Integer> constantSlots = constantParamSlots(constants, constParamBase);
        int builderSlot = constParamBase + constantParamSize(constants);
        int pSlot = builderSlot + 1;
        int countSlot = pSlot + 1;
        int resultSlot = countSlot + 1;
        int excSlot = resultSlot + 1;
        // A scratch slot for the exception caught by any single kernel's overflow handler (handlers never overlap in
        // execution, so one slot is shared).
        int overflowExcSlot = excSlot + 1;
        // The per-position value-path flag: {@code true} until the whole-tree short-circuit fires (the first null /
        // multi-value / overflow the value DFS meets), then {@code false}. It gates ONLY the value-dependent overflow /
        // div-by-zero warning registration to the ratified short-circuit subset — an off-value-path kernel body still
        // runs (its result feeds a sibling's per-kernel multi-value gate) but its overflow is caught silently. Leaf
        // multi-value warnings are NOT gated by {@code live}: each kernel emits them in its own operand order so they
        // match the unfused chain (which runs every kernel's loop fully), independent of ancestor short-circuits.
        int liveSlot = overflowExcSlot + 1;
        int workBase = liveSlot + 1;

        // Each kernel's warning-source slot into the runtime Warnings[]: its overflow warning and its direct leaf
        // operands' multi-value warnings register on warnings[its slot], matching the unfused per-node evaluator source.
        Map<FusionNode, Integer> sourceIndices = warningsSourceIndices(tree);

        // Each input *Block param uses its OWN element; the returned block uses the OUTPUT element (BooleanBlock for a
        // comparison root).
        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        for (int i = 0; i < arity; i++) {
            desc.append(inputElements[i].blockDescriptor());
        }
        appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
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
                BLOCK_FACTORY,
                outputElement.newBlockBuilderMethod(),
                outputElement.newBlockBuilderDescriptor(),
                false
            )
        );
        insns.add(new VarInsnNode(Opcodes.ASTORE, builderSlot));

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
        BlockDfsEmitter emitter = new BlockDfsEmitter(
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
            workBase,
            constantSlots
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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new InsnNode(Opcodes.ARETURN));

        // Catch-all: close the builder to release its breaker bytes, then rethrow.
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, excSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, excSlot));
        insns.add(new InsnNode(Opcodes.ATHROW));

        host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = caller.lookupClass().getPackageName().replace('.', '/') + "/Fused$$BlockLoop";
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        ClassLoader loader = caller.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            // The loop and the try/finally are control flow, so COMPUTE_FRAMES may need a common-superclass lookup
            // at a join. Resolve against the caller's class loader (which can see the public block types and, via
            // java.base, java/lang/Throwable) rather than ASM's own.
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        return serializeWithBudgetGuard(classNode, writer, "block loop", false);
    }

    /**
     * Fuses a <b>logical</b> tree — a three-valued-logic (3VL) {@code AND}/{@code OR} tree ({@link FusionNode.Logical})
     * whose operands are boolean-producing comparison {@link FusionNode.Kernel}s (from S3.0) or nested
     * {@link FusionNode.Logical}s — into a single nullable-boolean block loop, materialised as a hidden class
     * (iter 20, S3.1). The emitted {@link #FUSED_METHOD_NAME} has the shape
     * <pre>{@code
     * static BooleanBlock fused(BlockFactory bf, Warnings warnings, int positionCount,
     *                           LongBlock b0, LongBlock b1, LongBlock b2, LongBlock b3) {
     *     BooleanBlock.Builder out = bf.newBooleanBlockBuilder(positionCount);
     *     try {
     *         for (int p = 0; p < positionCount; p++) {
     *             int l = cmp0(warnings, p, b0, b1);       // a > b  -> 0 false / 1 true / 2 null
     *             int t;
     *             if (l == 0) { t = 0; }                    // AND short-circuit: left false -> false (skip right)
     *             else {
     *                 int r = cmp1(warnings, p, b2, b3);    // c < d
     *                 if (r == 0)      t = 0;               // right false -> false
     *                 else if (l == 2 || r == 2) t = 2;     // 3VL null
     *                 else             t = 1;               // both true -> true
     *             }
     *             if (t == 2) out.appendNull(); else out.appendBoolean(t != 0);
     *         }
     *         return out.build();
     *     } finally { out.close(); }
     * }
     * }</pre>
     *
     * <p><b>Nullable-boolean 3VL (matches {@code BinaryLogicOperation}).</b> Each operand yields a tri-state
     * {@code int} — {@code 0} false, {@code 1} true, {@code 2} null — from a {@code private static} helper
     * ({@code cmpN}). A comparison helper returns {@code 2} (null, <em>no</em> warning) when either numeric input is
     * {@code null} at {@code p}, or {@code 2} after registering the single-value multi-value warning when an input is
     * multi-valued (byte-for-byte the generated comparison evaluator's block path); otherwise it returns the
     * comparison's {@code 0}/{@code 1}. The {@link FusionNode.Logical} node then combines the two tri-states with the
     * exact truth tables of {@code BinaryLogicOperation}: {@code AND} yields false if either side is false, else null if
     * either side is null, else true; {@code OR} yields true if either side is true, else null if either side is null,
     * else false.
     *
     * <p><b>Short-circuit.</b> The left operand is evaluated first; when it alone decides the result — {@code AND} with
     * a false left, {@code OR} with a true left — the right operand helper is <b>not called</b>, so any multi-value
     * warning the right subtree would have registered is suppressed. The unfused {@code BooleanLogicExpressionEvaluator}
     * evaluates both sides eagerly (over the whole page), so the fused warning set is always a <b>subset</b> of the
     * unfused set (ratified rule), while the 3VL <em>values and null positions remain identical</em> (short-circuit only
     * skips work whose result cannot change the answer).
     *
     * <p><b>Multi-value on a logical operand → null, no warning.</b> The operands here are single-valued by
     * construction (a comparison/logical output is one boolean or null), so the logical node itself never sees a
     * multi-valued operand and never emits a logical-level multi-value warning — matching
     * {@code BooleanLogicExpressionEvaluator}, which turns a multi-valued operand into null without a warning. The only
     * multi-value warnings come from the sub-comparisons' own numeric inputs.
     *
     * <p><b>Why {@code private static} helpers (criterion #6 budget guard).</b> Inlining the two per-operand
     * null/multi-value guards (with their multi-value-warning construction) into the loop body pushes even a
     * single-level {@code a>b AND c<d} over HotSpot's 325-bytecode {@code FreqInlineSize}. Splitting each comparison
     * operand into a tiny {@code private static} helper keeps the top-level {@code fused} loop method small and
     * inlinable for arbitrarily nested logical trees; each helper is itself well under the budget. The top-level method
     * is still measured against {@link #MAX_FUSED_METHOD_BYTECODES} and refuses to fuse (→ unfused fallback) if it ever
     * exceeds it.
     *
     * <p><b>Block path only.</b> A 3VL result is nullable, so — like {@link #compileBlockLoop} — this defines the
     * hidden class into the caller's own module (only public exported {@code compute} API and the same-class helper
     * calls are touched; no {@code rawValues()} teleport) and there is no vector fast path.
     *
     * @param caller the caller's lookup (from the module that owns the comparison kernels); the fused class is defined here
     * @param tree   the logical tree to fuse; its root is a {@link FusionNode.Logical}
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, the emitted class fails JVM
     *                            verification/linking, or the top-level loop exceeds the inlining budget — the caller
     *                            should fall back to the unfused path
     */
    public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        byte[] bytecode = emitLogicalBlockLoop(caller, tree);
        try {
            return caller.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused logical block loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused logical block-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused logical block-loop class; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot define fused logical block-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the fused logical block loop: one {@code private static} {@code cmpN} helper per
     * comparison operand plus the top-level {@code fused} loop that calls them and combines their tri-states with the
     * short-circuit 3VL truth tables.
     */
    private byte[] emitLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        int arity = arity(tree);
        // INPUT element: the numeric kind of the comparison operands' columns (long/int/double). OUTPUT: nullable boolean.
        Element inputElement = Element.of(logicalInputType(tree));
        Element outputElement = Element.of(Type.BOOLEAN_TYPE);

        String internalName = caller.lookupClass().getPackageName().replace('.', '/') + "/Fused$$LogicalBlockLoop";

        // One helper per comparison operand, in DFS order; the map lets the loop emitter resolve each comparison's call.
        Map<FusionNode, String> helperNames = new IdentityHashMap<>();
        List<MethodNode> helpers = new ArrayList<>();
        assignComparisonHelpers(tree, inputElement, helperNames, helpers);
        // Each comparison's warning-source slot into the runtime Warnings[]; a comparison registers its multi-value
        // warning on warnings[its slot] so the header matches the unfused comparison evaluator's node source.
        Map<FusionNode, Integer> sourceIndices = warningsSourceIndices(tree);

        // Frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input *Blocks, then one
        // trailing primitive parameter per embedded constant (canonical emit order). Builder, loop counter, built
        // result, and caught-throwable slots sit above (their bases shifted up by the constant parameters' slot size);
        // the tri-state node slots start at workBase. The top-level loop loads each constant from its parameter slot
        // and threads it into the relevant cmpN helper call.
        List<FusionNode.Constant> constants = constantsInEmitOrder(tree);
        int warningsArraySlot = 1;
        int pcSlot = 2;
        int inputBase = 3;
        int constParamBase = inputBase + arity;
        Map<FusionNode.Constant, Integer> constantSlots = constantParamSlots(constants, constParamBase);
        int builderSlot = constParamBase + constantParamSize(constants);
        int pSlot = builderSlot + 1;
        int resultSlot = pSlot + 1;
        int excSlot = resultSlot + 1;
        int workBase = excSlot + 1;

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        for (int i = 0; i < arity; i++) {
            desc.append(inputElement.blockDescriptor());
        }
        appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
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
                BLOCK_FACTORY,
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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new InsnNode(Opcodes.ARETURN));

        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, excSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, RELEASABLE, "close", "()V", true));
        insns.add(new VarInsnNode(Opcodes.ALOAD, excSlot));
        insns.add(new InsnNode(Opcodes.ATHROW));

        host.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));

        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = internalName;
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);
        classNode.methods.addAll(helpers);

        ClassLoader loader = caller.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        // Enforce the inlining budget on the top-level loop method (criterion #6). The helpers keep it small even for
        // nested trees; if it somehow exceeds the budget we refuse to fuse (→ unfused fallback) rather than emit an
        // un-inlinable hot body.
        return serializeWithBudgetGuard(classNode, writer, "logical block loop", true);
    }

    /**
     * Assigns each comparison operand ({@link FusionNode.Kernel}) in {@code tree} a {@code private static} helper method
     * (named {@code cmp0}, {@code cmp1}, … in DFS order) and records the name so the loop emitter can call it.
     */
    private void assignComparisonHelpers(FusionNode node, Element inputElement, Map<FusionNode, String> names, List<MethodNode> helpers) {
        if (node instanceof FusionNode.Logical logical) {
            assignComparisonHelpers(logical.left(), inputElement, names, helpers);
            assignComparisonHelpers(logical.right(), inputElement, names, helpers);
            return;
        }
        FusionNode.Kernel comparison = (FusionNode.Kernel) node;
        String name = "cmp" + helpers.size();
        names.put(comparison, name);
        helpers.add(emitComparisonHelper(comparison, inputElement, name));
    }

    /**
     * Builds a {@code private static int cmpN(Warnings w, int p, <Block> b0, <Block> b1)} helper that reproduces the
     * generated comparison evaluator's block path for one position and returns a tri-state: {@code 0} false, {@code 1}
     * true, {@code 2} null. A {@code null} input ({@code getValueCount == 0}) returns {@code 2} with no warning; a
     * multi-valued input registers the single-value multi-value warning and returns {@code 2}; otherwise the single
     * value at {@code getFirstValueIndex(p)} is read and the spliced comparison kernel body's {@code 0}/{@code 1} is
     * returned.
     */
    private MethodNode emitComparisonHelper(FusionNode.Kernel comparison, Element inputElement, String methodName) {
        MethodNode template = templates.methodNode(comparison.descriptor());
        Body body = BodyExtractor.extract(template);
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
        List<FusionNode.Constant> helperConstants = constantsInEmitOrder(comparison);

        StringBuilder desc = new StringBuilder("(L").append(WARNINGS).append(";I");
        for (int i = 0; i < columnOperands; i++) {
            desc.append(inputElement.blockDescriptor());
        }
        appendConstantParamDescriptors(desc, helperConstants);
        desc.append(")I");

        MethodNode helper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, methodName, desc.toString(), null, null);
        InsnList insns = helper.instructions;

        int warningsSlot = 0;
        int pSlot = 1;
        int blockBase = 2;                                   // block params occupy [2 .. 2+columnOperands)
        int constParamBase = blockBase + columnOperands;     // then the trailing constant params
        Map<FusionNode.Constant, Integer> helperConstantSlots = constantParamSlots(helperConstants, constParamBase);
        int countSlot = constParamBase + constantParamSize(helperConstants); // scratch for getValueCount
        int bodyBase = countSlot + 1;                        // the comparison body's shifted locals start here
        SlotRemapper.shift(body.computation(), bodyBase);

        LabelNode retNull = new LabelNode();
        int offset = 0;         // running offset into the comparison body's argument slots (one per operand, in order)
        int blockParam = 0;     // running index of the next *Block parameter (column operands only)
        for (FusionNode child : children) {
            if (child instanceof FusionNode.Constant constant) {
                // Constant operand: load it from its trailing helper parameter into the body's shifted argument slot;
                // no null / multi-value guard (a constant is always present and single-valued).
                loadConstantParam(insns, inputElement, constant, helperConstantSlots.get(constant));
                insns.add(new VarInsnNode(inputElement.type().getOpcode(Opcodes.ISTORE), bodyBase + offset));
                offset += size;
                continue;
            }
            int block = blockBase + blockParam;
            blockParam++;
            LabelNode read = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getValueCount", "(I)I", true));
            insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, retNull)); // count == 0 -> null, no warning
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, read)); // count == 1 -> single value
            // count > 1 -> register the multi-value warning on this comparison's own Warnings (the caller resolved
            // warnings[this-comparison's-source-slot] and passed it as the single w parameter), then null.
            insns.add(new VarInsnNode(Opcodes.ALOAD, warningsSlot));
            emitMultiValueWarningOn(insns);
            insns.add(new JumpInsnNode(Opcodes.GOTO, retNull));
            insns.add(read);
            // v = block.get*(block.getFirstValueIndex(p)); store into the comparison body's shifted arg slot.
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getFirstValueIndex", "(I)I", true));
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
        insns.add(body.computation());
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(retNull);
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
        insns.add(new InsnNode(Opcodes.IRETURN));
        return helper;
    }

    /**
     * The numeric input element of a logical tree: the argument kind of the first comparison operand reached by
     * descending left spines. Every comparison in a fusable logical tree is homogeneous in the same primitive, so any
     * one determines the element.
     */
    private static Type logicalInputType(FusionNode tree) {
        if (tree instanceof FusionNode.Kernel kernel) {
            return Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes()[0];
        }
        return logicalInputType(((FusionNode.Logical) tree).left());
    }

    /**
     * Post-order emitter for the fused logical block loop. Leaves each node's tri-state ({@code 0} false / {@code 1}
     * true / {@code 2} null) in an assigned local slot; comparison operands call their {@code private static} helper,
     * and {@link FusionNode.Logical} nodes combine two child tri-states with the short-circuit 3VL truth tables of
     * {@code BinaryLogicOperation}.
     */
    private final class LogicalDfsEmitter {
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
            } else {
                emitComparisonCall((FusionNode.Kernel) node, tSlot);
            }
        }

        private void emitComparisonCall(FusionNode.Kernel comparison, int tSlot) {
            String name = helperNames.get(comparison);
            // Column ({@link FusionNode.Input}) operands are passed as *Block arguments; constant operands follow as
            // trailing primitive arguments (in canonical emit order), loaded from the top-level fused method's own
            // constant parameter slots — so the helper is byte-identical for every constant value.
            List<FusionNode.Constant> callConstants = constantsInEmitOrder(comparison);
            StringBuilder callDesc = new StringBuilder("(L").append(WARNINGS).append(";I");
            for (FusionNode child : comparison.children()) {
                if (child instanceof FusionNode.Input) {
                    callDesc.append(inputElement.blockDescriptor());
                }
            }
            appendConstantParamDescriptors(callDesc, callConstants);
            callDesc.append(")I");

            // Pass this comparison's OWN Warnings (warnings[its source slot]) so its multi-value warning is attributed
            // to the comparison's node source — the same header the unfused comparison evaluator would emit.
            loadWarnings(insns, warningsArraySlot, sourceIndices.get(comparison));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            for (FusionNode child : comparison.children()) {
                if (child instanceof FusionNode.Input input) {
                    insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + input.index()));
                }
            }
            // Then the constant arguments, loaded from the top-level fused method's trailing parameter slots in child
            // order (which equals canonical emit order for a single comparison's operands).
            for (FusionNode child : comparison.children()) {
                if (child instanceof FusionNode.Constant constant) {
                    insns.add(new VarInsnNode(inputElement.type().getOpcode(Opcodes.ILOAD), constantSlots.get(constant)));
                }
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, name, callDesc.toString(), false));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tSlot));
        }

        private void emitLogical(FusionNode.Logical logical, int tOut) {
            boolean and = logical.op() == FusionNode.BoolOp.AND;
            int tLeft = alloc(1);
            emit(logical.left(), tLeft);
            int tRight = alloc(1);

            LabelNode setDecisive = new LabelNode(); // AND: result false; OR: result true
            LabelNode setNull = new LabelNode();
            LabelNode done = new LabelNode();

            if (and) {
                // Short-circuit: left == false (0) -> result false, skip the right operand entirely.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, setDecisive));
                emit(logical.right(), tRight);
                // right == false (0) -> result false.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, setDecisive));
                // else 3VL: either side null -> null; otherwise both true -> true.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
                insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
                insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
                insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
                insns.add(new JumpInsnNode(Opcodes.GOTO, done));
                insns.add(setDecisive);
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
                insns.add(new JumpInsnNode(Opcodes.GOTO, done));
            } else {
                // Short-circuit: left == true (1) -> result true, skip the right operand entirely.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setDecisive));
                emit(logical.right(), tRight);
                // right == true (1) -> result true.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setDecisive));
                // else 3VL: either side null -> null; otherwise both false -> false.
                insns.add(new VarInsnNode(Opcodes.ILOAD, tLeft));
                insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
                insns.add(new VarInsnNode(Opcodes.ILOAD, tRight));
                insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, setNull));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
                insns.add(new JumpInsnNode(Opcodes.GOTO, done));
                insns.add(setDecisive);
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
                insns.add(new JumpInsnNode(Opcodes.GOTO, done));
            }
            insns.add(setNull);
            insns.add(new IntInsnNode(Opcodes.BIPUSH, 2));
            insns.add(new VarInsnNode(Opcodes.ISTORE, tOut));
            insns.add(done);
        }
    }

    /**
     * Emits {@code <warnings>.registerException(new IllegalArgumentException("single-value function encountered
     * multi-value"))} — the carried-forward multi-value warning the generated unfused evaluator raises when a scalar
     * kernel meets a multi-valued position. The target {@code Warnings} must already be on top of the operand stack
     * (pushed by {@link #loadWarnings} for the node's own warning-source slot, or a bare {@code ALOAD} of a single
     * {@code Warnings} local in a {@code cmpN} helper); this method consumes it and leaves the stack unchanged
     * otherwise.
     */
    private static void emitMultiValueWarningOn(InsnList insns) {
        insns.add(new TypeInsnNode(Opcodes.NEW, ILLEGAL_ARGUMENT_EXCEPTION));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode(MULTI_VALUE_MESSAGE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ILLEGAL_ARGUMENT_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
    }

    /**
     * Pushes {@code warnings[index]} onto the operand stack: {@code ALOAD warningsArraySlot; <push index>; AALOAD}. The
     * {@code index} is the node's {@linkplain #warningsSourceIndices structural warning-source slot}, so the loaded
     * {@code Warnings} is the one built from that node's own source.
     */
    private static void loadWarnings(InsnList insns, int warningsArraySlot, int index) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
        pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.AALOAD));
    }

    /**
     * Emits a type-correct {@code xLOAD} of a {@link FusionNode.Constant} from its trailing <b>method-parameter</b>
     * slot, typed to {@code element}, so a constant leaf reads no array and occupies no {@link FusionNode.Input} slot on
     * any emit path. This replaces the former embedded-literal push ({@code LDC}/{@code ICONST}/…): the value now
     * arrives as a runtime argument in {@code slot} (a single-assignment, loop-invariant parameter local), so one
     * stitched class serves every constant value — {@code a > 5} and {@code a > 6} share it and differ only in the
     * argument supplied at evaluation time.
     *
     * <p>{@code element} is the ambient element of the tree the constant sits in (the consuming kernel's argument
     * kind). It <b>must</b> equal {@code constant.element()} — the planner guarantees this, and it types the parameter
     * — so a defensive assertion fails loud here rather than silently emitting a wrong-category load (e.g. an
     * {@code LLOAD} of a {@code long} where the kernel wants an {@code int}), which would only surface downstream as an
     * opaque {@link VerifyError}.
     */
    private static void loadConstantParam(InsnList insns, Element element, FusionNode.Constant constant, int slot) {
        assert elementSortMatches(element, constant.element())
            : "constant element ["
                + constant.element()
                + "] does not match the ambient tree element ["
                + element.type().getClassName()
                + "]";
        insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ILOAD), slot));
    }

    /**
     * The {@link FusionNode.Constant} leaves of {@code tree} in the SINGLE canonical order every emit path visits them:
     * a stable pre-order DFS (a {@link FusionNode.Kernel}'s children left-to-right; a {@link FusionNode.Logical}'s left
     * subtree before its right). This is the one source of truth for the trailing constant-<b>parameter</b> order that
     * the three parties which must agree byte-for-byte all consult: (a) the {@link Stitcher} assigns each constant a
     * parameter slot in this order and loads it at each use site, (b) {@code FusionPlanner} collects the constant values
     * in this order, and (c) {@code FusedExpressionEvaluatorFactory} both appends the parameter types to the resolved
     * {@code MethodType}s and marshals the values into the invoke args in this order. Every emitter walks the tree in
     * exactly this DFS order, so the i-th constant it reaches is the i-th trailing parameter.
     */
    public static List<FusionNode.Constant> constantsInEmitOrder(FusionNode tree) {
        List<FusionNode.Constant> constants = new ArrayList<>();
        collectConstantsInEmitOrder(tree, constants);
        return constants;
    }

    private static void collectConstantsInEmitOrder(FusionNode node, List<FusionNode.Constant> out) {
        if (node instanceof FusionNode.Constant constant) {
            out.add(constant);
        } else if (node instanceof FusionNode.Kernel kernel) {
            for (FusionNode child : kernel.children()) {
                collectConstantsInEmitOrder(child, out);
            }
        } else if (node instanceof FusionNode.Logical logical) {
            collectConstantsInEmitOrder(logical.left(), out);
            collectConstantsInEmitOrder(logical.right(), out);
        }
        // FusionNode.Input: not a constant, contributes no trailing parameter.
    }

    /**
     * Maps each constant (by identity) to the absolute local slot of its trailing parameter, assigned in
     * {@link #constantsInEmitOrder canonical order} starting at {@code base} (a {@code long}/{@code double} takes two
     * slots). Distinct {@link FusionNode.Constant} instances get distinct slots even when their values are equal (two
     * equal-valued literals are still separate leaves in the tree), so an {@link IdentityHashMap} keys on the node
     * instance rather than on record value-equality.
     */
    private static Map<FusionNode.Constant, Integer> constantParamSlots(List<FusionNode.Constant> constants, int base) {
        Map<FusionNode.Constant, Integer> slots = new IdentityHashMap<>();
        int cursor = base;
        for (FusionNode.Constant constant : constants) {
            slots.put(constant, cursor);
            cursor += constantSlotSize(constant);
        }
        return slots;
    }

    /** Total local slots the trailing constant parameters occupy (a {@code long}/{@code double} takes two). */
    private static int constantParamSize(List<FusionNode.Constant> constants) {
        int size = 0;
        for (FusionNode.Constant constant : constants) {
            size += constantSlotSize(constant);
        }
        return size;
    }

    private static int constantSlotSize(FusionNode.Constant constant) {
        return constant.element() == 'J' || constant.element() == 'D' ? 2 : 1;
    }

    /** Appends one JVM descriptor char per constant parameter; the element char {@code J}/{@code I}/{@code D} is it. */
    private static void appendConstantParamDescriptors(StringBuilder desc, List<FusionNode.Constant> constants) {
        for (FusionNode.Constant constant : constants) {
            desc.append(constant.element());
        }
    }

    /** Whether {@code element}'s JVM sort matches a {@link FusionNode.Constant#element()} descriptor char (J/I/D). */
    private static boolean elementSortMatches(Element element, char constantElement) {
        int sort = element.type().getSort();
        return switch (constantElement) {
            case 'J' -> sort == Type.LONG;
            case 'I' -> sort == Type.INT;
            case 'D' -> sort == Type.DOUBLE;
            default -> false;
        };
    }

    /** Pushes a non-negative {@code int} constant using the tightest of {@code ICONST}/{@code BIPUSH}/{@code SIPUSH}/{@code LDC}. */
    private static void pushInt(InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    /**
     * Assigns every {@link FusionNode.Kernel} in {@code tree} a distinct <b>warning-source slot</b> (an index into the
     * runtime {@code Warnings[]}), via a stable pre-order walk (a node before its children; a {@link FusionNode.Logical}'s
     * left subtree before its right). {@link FusionNode.Input} leaves and {@link FusionNode.Logical} nodes are not
     * warning sources and get no slot. Each kernel's slot is where <em>its</em> overflow warning and <em>its</em> direct
     * leaf operands' multi-value warnings are registered — mirroring the unfused chain, where every generated
     * {@code *Evaluator} carries its own node source.
     *
     * <p>The assignment is purely structural (it depends only on the tree shape, never on the actual sources or
     * columns), so the {@link Stitcher} bakes these indices into the emitted bytecode while the plan-time caller builds a
     * matching {@code Warnings[]} of the same size from each kernel's real {@code Source}. Two trees of the same shape
     * therefore reuse one cached hidden class and each supplies its own per-source {@code Warnings[]}.
     */
    public static Map<FusionNode, Integer> warningsSourceIndices(FusionNode tree) {
        Map<FusionNode, Integer> indices = new IdentityHashMap<>();
        assignWarningsSourceIndices(tree, indices);
        return indices;
    }

    /** The number of distinct warning-source slots {@code tree} needs, i.e. the size of the runtime {@code Warnings[]}. */
    public static int warningsSourceCount(FusionNode tree) {
        return warningsSourceIndices(tree).size();
    }

    private static void assignWarningsSourceIndices(FusionNode node, Map<FusionNode, Integer> indices) {
        if (node instanceof FusionNode.Logical logical) {
            assignWarningsSourceIndices(logical.left(), indices);
            assignWarningsSourceIndices(logical.right(), indices);
        } else if (node instanceof FusionNode.Kernel kernel) {
            indices.put(kernel, indices.size());
            for (FusionNode child : kernel.children()) {
                assignWarningsSourceIndices(child, indices);
            }
        }
        // FusionNode.Input: not a warning source, no slot.
    }

    /** Distinct JVM internal names of the overflow exceptions the {@code tree}'s kernels can throw (may be empty). */
    private static Set<String> overflowExceptionInternalNames(FusionNode tree) {
        Set<String> types = new LinkedHashSet<>();
        collectOverflowTypes(tree, types);
        return types;
    }

    private static void collectOverflowTypes(FusionNode node, Set<String> types) {
        if (node instanceof FusionNode.Kernel kernel) {
            if (kernel.descriptor().hasOverflowException()) {
                types.add(kernel.descriptor().overflowExceptionType().replace('.', '/'));
            }
            for (FusionNode child : kernel.children()) {
                collectOverflowTypes(child, types);
            }
        }
    }

    /**
     * Post-order emitter for the fused <b>checked-vector</b> loop. Identical in structure to {@link VectorLoopEmitter}
     * except an {@link FusionNode.Input} reads the per-position value already loaded into its value local (rather than
     * indexing a raw array). It carries no null/multi-value guards because the checked-vector path operates on dense,
     * single-valued vectors; the nullable/multi-valued block path uses {@link BlockDfsEmitter} instead, which inlines
     * those guards in DFS order.
     */
    private final class BlockLoopEmitter {
        private final InsnList insns;
        private final Element[] inputElements;
        private final int[] valueSlots;
        private final Map<FusionNode, Integer> sourceIndices;
        private final int currentKernelSlot;
        private int kernelBase;
        private final Map<FusionNode.Constant, Integer> constantSlots;

        BlockLoopEmitter(
            InsnList insns,
            Element[] inputElements,
            int[] valueSlots,
            Map<FusionNode, Integer> sourceIndices,
            int currentKernelSlot,
            int kernelBase,
            Map<FusionNode.Constant, Integer> constantSlots
        ) {
            this.insns = insns;
            this.inputElements = inputElements;
            this.valueSlots = valueSlots;
            this.sourceIndices = sourceIndices;
            this.currentKernelSlot = currentKernelSlot;
            this.kernelBase = kernelBase;
            this.constantSlots = constantSlots;
        }

        /**
         * Emits instructions that leave {@code node}'s value for the current position {@code p} on the operand stack.
         * {@code expected} is the element {@code node} must produce (its consuming kernel's argument element, or the
         * output element at the root) — used to type a constant push; a leaf reads its own pre-loaded value slot.
         */
        void emit(FusionNode node, Element expected) {
            if (node instanceof FusionNode.Input input) {
                Element element = inputElements[input.index()];
                insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ILOAD), valueSlots[input.index()]));
                return;
            }
            if (node instanceof FusionNode.Constant constant) {
                // Embedded literal: load it from its trailing method-parameter slot (no pre-read value slot).
                loadConstantParam(insns, expected, constant, constantSlots.get(constant));
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
                emit(children.get(i), Element.of(argTypes[i]));
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), nodeBase + offset));
                offset += argTypes[i].getSize();
            }
            if (kernel.descriptor().hasOverflowException()) {
                // Record which kernel is about to run so the shared overflow handler attributes a caught overflow to
                // THIS kernel's own source (see emitVectorLoopChecked's handler).
                pushInt(insns, sourceIndices.get(kernel));
                insns.add(new VarInsnNode(Opcodes.ISTORE, currentKernelSlot));
            }
            insns.add(body.computation());
        }
    }

    /** The disjoint local slots a {@link BlockDfsEmitter} node evaluates into: a {@code present} flag and its value. */
    private record NodeSlots(int present, int value) {}

    /**
     * Depth-first emitter for the nullable/multi-valued <b>block</b> loop. Each node evaluates into two disjoint local
     * slots for the current position {@code p}: a {@code present} flag ({@code 1} iff the node produced a value) and,
     * when present, its value. The tree's VALUE / null position is exactly today's whole-tree short-circuit — the root
     * is not present (⇒ {@code appendNull}) iff any consumed leaf is null / multi-value or any kernel on the value path
     * overflows — so values and null positions are byte-for-byte unchanged.
     *
     * <p><b>Per-kernel multi-value warnings (match the unfused chain).</b> The unfused chain runs every generated
     * evaluator's loop FULLY, so a leaf's {@code single-value function encountered multi-value} warning fires iff,
     * within its own consuming kernel's operand order, that kernel's earlier operands are present at {@code p} —
     * INDEPENDENT of whether an ancestor kernel already short-circuited the position. This emitter reproduces that
     * exactly: it does NOT stop the DFS at the first not-present operand. Each kernel walks its operands in order,
     * tracking its OWN running {@code present} flag; a leaf operand's multi-value warning is emitted iff that kernel's
     * earlier operands were present (its own {@code present} still {@code true}), attributed to that kernel's warning
     * source. A nested-kernel operand is always emitted (so its own subtree's per-kernel warnings fire regardless of an
     * ancestor short-circuit), and every kernel body whose own operands are present is still run — its result feeds a
     * consuming kernel's per-kernel gate even when that consumer is off the value path.
     *
     * <p><b>Value-dependent warnings stay the ratified subset.</b> A kernel body's overflow / div-by-zero warning is
     * registered only while {@code live} (the value path has not yet short-circuited); a body that overflows off the
     * value path is caught silently (its {@code present} still goes {@code false}, so a consuming kernel's per-kernel
     * gate matches the unfused chain, but no warning is emitted — exactly today's behaviour).
     *
     * <p><b>Slots.</b> Each node claims a {@code present} slot, a value slot (its element's width) and a disjoint body
     * range (the kernel body's {@code maxLocals}); the value + argument slots are pre-initialised to a typed zero so
     * the verifier sees them definitely assigned (they are read only on the present path, but that correlation is
     * value-level, not type-level, so an explicit zero-init is required).
     */
    private final class BlockDfsEmitter {
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
        private final Map<FusionNode.Constant, Integer> constantSlots;
        private int next;

        BlockDfsEmitter(
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
            int workBase,
            Map<FusionNode.Constant, Integer> constantSlots
        ) {
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
            MethodNode template = templates.methodNode(kernel.descriptor());
            Body body = BodyExtractor.extract(template);

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
            int kernelSlot = sourceIndices.get(kernel);

            // Pre-init value slot + argument slots to a typed zero for verifier definite-assignment (they are read only
            // on the present path, but the verifier cannot prove that value-level correlation).
            storeZero(valueSlot, expected);
            int initOffset = 0;
            for (Type argType : argTypes) {
                storeZero(nodeBase + initOffset, Element.of(argType));
                initOffset += argType.getSize();
            }

            // present = true; each not-present operand (in this kernel's own order) sets it false.
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));

            int offset = 0;
            for (int i = 0; i < argTypes.length; i++) {
                Type argType = argTypes[i];
                Element argElem = Element.of(argType);
                int argSlot = nodeBase + offset;
                FusionNode child = children.get(i);
                if (child instanceof FusionNode.Constant constant) {
                    // A constant is always present and single-valued: load it into the argument slot unconditionally
                    // (harmless when this kernel already short-circuited — the body will not run).
                    loadConstantParam(insns, argElem, constant, constantSlots.get(constant));
                    insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ISTORE), argSlot));
                } else if (child instanceof FusionNode.Input input) {
                    emitLeaf(input, argElem, argSlot, presentSlot, kernelSlot);
                } else {
                    // Nested kernel: ALWAYS emit it (so its own subtree's per-kernel warnings fire even when THIS
                    // kernel already short-circuited), then fold its presence into this kernel's own present flag.
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
                loadWarnings(insns, warningsArraySlot, kernelSlot);
                insns.add(new VarInsnNode(Opcodes.ALOAD, overflowExcSlot));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
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
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getValueCount", "(I)I", true));
            insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, leafFail)); // count == 0 -> null, no warning
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, leafSingle)); // count == 1 -> read the single value
            // count > 1 -> register the multi-value warning on THIS kernel's own Warnings, then fail.
            loadWarnings(insns, warningsArraySlot, kernelSlot);
            emitMultiValueWarningOn(insns);
            insns.add(leafFail);
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new VarInsnNode(Opcodes.ISTORE, presentSlot));
            insns.add(new JumpInsnNode(Opcodes.GOTO, afterOperand));
            insns.add(leafSingle);
            // v = block.get*(block.getFirstValueIndex(p)); store into this kernel's argument slot.
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ALOAD, block));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getFirstValueIndex", "(I)I", true));
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
                default -> {
                    // int / boolean (one slot, int category).
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
                }
            }
        }
    }

    /**
     * The per-input {@link Element}, indexed by {@link FusionNode.Input#index()}. With per-node typing a tree may mix
     * element kinds — a leaf reads its column at the element the kernel that consumes it expects (its descriptor's
     * argument type at that operand position), which the planner guarantees equals the column's own element (bridging
     * any mismatch with an explicit cast kernel). So {@code doubleCol + castIntToDouble(intCol)} yields
     * {@code [double, int]}: the fused method reads channel 0 as a {@code double} array and channel 1 as an
     * {@code int} array (the cast body then widens it). Every index in {@code [0, arity)} is populated (the planner
     * assigns input indices densely and each is consumed exactly once).
     */
    private static Element[] inputElements(FusionNode tree, int arity) {
        Element[] elements = new Element[arity];
        collectInputElements(tree, elements);
        // A tree may declare an input index in [0, arity) that no leaf actually consumes (the over-nullify guard: the
        // method still takes one parameter per index, but an unused input is never read). Such a gap has no natural
        // element, so bind it to the first populated one — its parameter type is arbitrary since the fused body never
        // reads it, and this keeps the parameter list homogeneous with the consumed inputs.
        Element fallback = null;
        for (Element e : elements) {
            if (e != null) {
                fallback = e;
                break;
            }
        }
        for (int i = 0; i < elements.length; i++) {
            if (elements[i] == null) {
                elements[i] = fallback;
            }
        }
        return elements;
    }

    private static void collectInputElements(FusionNode node, Element[] elements) {
        if (node instanceof FusionNode.Kernel kernel) {
            Type[] argTypes = Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes();
            List<FusionNode> children = kernel.children();
            for (int i = 0; i < children.size(); i++) {
                FusionNode child = children.get(i);
                if (child instanceof FusionNode.Input input) {
                    elements[input.index()] = Element.of(argTypes[i]);
                } else {
                    // A Constant is embedded (no input slot); a nested Kernel carries its own argument elements.
                    collectInputElements(child, elements);
                }
            }
        } else if (node instanceof FusionNode.Logical logical) {
            collectInputElements(logical.left(), elements);
            collectInputElements(logical.right(), elements);
        }
        // FusionNode.Input at the root is impossible (depth ≥ 2); a bare Constant contributes no input slot.
    }

    /** Number of distinct input vectors the tree references, i.e. one past the maximum {@code Input} index. */
    private static int arity(FusionNode tree) {
        if (tree instanceof FusionNode.Input input) {
            return input.index() + 1;
        }
        if (tree instanceof FusionNode.Constant) {
            // A constant is embedded in the bytecode, so it references no input vector and does not widen the arity.
            return 0;
        }
        if (tree instanceof FusionNode.Logical logical) {
            return Math.max(arity(logical.left()), arity(logical.right()));
        }
        int max = 0;
        for (FusionNode child : ((FusionNode.Kernel) tree).children()) {
            max = Math.max(max, arity(child));
        }
        return max;
    }

    /**
     * The distinct input indices the {@code tree} actually references, ascending. Used by the block path to guard and
     * read only the inputs the expression consumes (rule against over-nullifying on an unreferenced input).
     */
    private static int[] consumedInputs(FusionNode tree) {
        TreeSet<Integer> used = new TreeSet<>();
        collectInputs(tree, used);
        int[] out = new int[used.size()];
        int i = 0;
        for (int index : used) {
            out[i++] = index;
        }
        return out;
    }

    private static void collectInputs(FusionNode node, Set<Integer> used) {
        if (node instanceof FusionNode.Input input) {
            used.add(input.index());
        } else if (node instanceof FusionNode.Constant) {
            // Embedded in the bytecode: consumes no input vector, so it contributes nothing to the consumed-input set.
        } else if (node instanceof FusionNode.Logical logical) {
            collectInputs(logical.left(), used);
            collectInputs(logical.right(), used);
        } else {
            for (FusionNode child : ((FusionNode.Kernel) node).children()) {
                collectInputs(child, used);
            }
        }
    }

    /** The tree's output element type — the return type of the root kernel. May be boolean for a comparison root. */
    private Type rootReturnType(FusionNode tree) {
        if (tree instanceof FusionNode.Kernel kernel) {
            return Type.getMethodType(kernel.descriptor().kernelType()).getReturnType();
        }
        throw new IllegalArgumentException("a fused vector loop requires a kernel at the tree root, not a bare input");
    }

    /**
     * Serializes {@code classNode} through {@code writer} and applies the JIT inlining budget (criterion #6) to the
     * emitted {@code fused} loop method's {@code Code} length, measured against {@link #MAX_FUSED_METHOD_BYTECODES}
     * (HotSpot's default {@code FreqInlineSize}).
     *
     * <p><b>Enforcement scope.</b> {@code enforce} is {@code true} for the tight <b>plain-vector fast path</b> — the
     * dense, control-flow-light body criterion #6 targets as the single hot per-position body the JIT should inline and
     * vectorize; an oversized plain-vector body is refused with a {@link StitchingException} so the caller falls back
     * (the plan permits "split or refuse-to-fuse"; refusing keeps the guard simple). It is <b>also</b> {@code true} for
     * the <b>logical block loop</b> ({@link #emitLogicalBlockLoop}): there the per-comparison null/multi-value guards
     * and their multi-value-warning construction are factored into {@code private static} {@code cmpN} helpers, so the
     * top-level {@code fused} loop that only calls them and combines their tri-states with the 3VL truth tables stays
     * small and inlinable even for deeply nested {@code AND}/{@code OR} trees; if it ever exceeds the budget we refuse
     * to fuse (→ unfused fallback) rather than emit an un-inlinable hot body (the {@code cmpN} helpers are themselves
     * comfortably under the budget). It is {@code false} for the
     * <b>checked-vector</b> and <b>block</b> paths, whose mandatory overflow try/catch (and, for the block path,
     * per-position null/multi-value guards + builder try/finally) push even a pre-existing depth-4 <em>arithmetic</em>
     * body over 325 bytecodes today. Those paths are correctness fallbacks invoked via {@code MethodHandle} (not a
     * normal inlinable call site); refusing them would regress the existing differential suite (deep arithmetic trees
     * would stop fusing) with no JIT benefit, so their sizes are measured and logged for visibility but not enforced.
     * Splitting oversize bodies into {@code private static} helpers is the planned follow-up for the larger combined
     * bodies of the filter+expression mega-fusion iters.
     */
    private static byte[] serializeWithBudgetGuard(ClassNode classNode, ClassWriter writer, String pathName, boolean enforce)
        throws StitchingException {
        classNode.accept(writer);
        byte[] bytecode = writer.toByteArray();
        int size = fusedMethodCodeLength(bytecode);
        if (size > MAX_FUSED_METHOD_BYTECODES) {
            if (enforce) {
                logger.warn(
                    "fused {} method is [{}] bytecodes, over the inlining budget of [{}]; refusing to fuse (falling back to unfused)",
                    pathName,
                    size,
                    MAX_FUSED_METHOD_BYTECODES
                );
                throw new StitchingException(
                    "fused "
                        + pathName
                        + " method ["
                        + size
                        + " bytecodes] exceeds the inlining budget ["
                        + MAX_FUSED_METHOD_BYTECODES
                        + "]",
                    null
                );
            }
            logger.debug(
                "fused {} method is [{}] bytecodes, over the inlining budget of [{}] (fallback path — not enforced)",
                pathName,
                size,
                MAX_FUSED_METHOD_BYTECODES
            );
        }
        return bytecode;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Test seams (package-private): the raw fused class bytes for each emit path, BEFORE defineHiddenClass verifies
    // them. They let same-package tests (StitcherBudgetTests) measure the emitted fused method's Code length against
    // MAX_FUSED_METHOD_BYTECODES and run the bytes through CheckClassAdapter for a structural-validity check on top of
    // the JVM's defineHiddenClass verifier. The enforced paths (plain-vector, logical block) still throw a
    // StitchingException here if they exceed the budget — exactly as the compile* entry points would.
    // -----------------------------------------------------------------------------------------------------------------

    byte[] vectorLoopBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitVectorLoop(caller, tree);
    }

    byte[] vectorLoopCheckedBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitVectorLoopChecked(caller, tree);
    }

    byte[] blockLoopBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitBlockLoop(caller, tree);
    }

    byte[] logicalBlockLoopBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitLogicalBlockLoop(caller, tree);
    }

    /**
     * Reads the {@code Code} attribute length (the bytecode count C2 measures against {@code FreqInlineSize}) of the
     * {@link #FUSED_METHOD_NAME} method in a just-emitted class. A minimal class-file walk over our own freshly written
     * bytes (no external dependency, no class loading): parse the constant pool for the UTF-8 entries, then scan the
     * method table for {@code fused}'s {@code Code} attribute and return its {@code code_length}. Returns 0 if the
     * method or its {@code Code} attribute is absent (never expected for an emitted loop).
     */
    static int fusedMethodCodeLength(byte[] classBytes) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(classBytes); // class files are big-endian, ByteBuffer's default
        buf.getInt();   // magic
        buf.getShort(); // minor_version
        buf.getShort(); // major_version
        int cpCount = buf.getShort() & 0xFFFF;
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = buf.get() & 0xFF;
            switch (tag) {
                case 1 -> { // CONSTANT_Utf8
                    int len = buf.getShort() & 0xFFFF;
                    byte[] b = new byte[len];
                    buf.get(b);
                    utf8[i] = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 7, 8, 16, 19, 20 -> buf.getShort();          // Class, String, MethodType, Module, Package
                case 15 -> {
                    buf.get();       // MethodHandle: reference_kind
                    buf.getShort();  // reference_index
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> buf.getInt(); // Integer/Float/Fieldref/Methodref/IMethodref/NameAndType/(Invoke)Dynamic
                case 5, 6 -> {                                    // Long/Double occupy two constant-pool slots
                    buf.getLong();
                    i++;
                }
                default -> throw new IllegalStateException("unexpected constant-pool tag [" + tag + "] measuring fused method");
            }
        }
        buf.getShort(); // access_flags
        buf.getShort(); // this_class
        buf.getShort(); // super_class
        int interfaces = buf.getShort() & 0xFFFF;
        buf.position(buf.position() + interfaces * 2);
        int fields = buf.getShort() & 0xFFFF;
        for (int f = 0; f < fields; f++) {
            buf.getShort(); // access_flags
            buf.getShort(); // name_index
            buf.getShort(); // descriptor_index
            skipAttributes(buf);
        }
        int methods = buf.getShort() & 0xFFFF;
        for (int m = 0; m < methods; m++) {
            buf.getShort(); // access_flags
            int nameIndex = buf.getShort() & 0xFFFF;
            buf.getShort(); // descriptor_index
            int attributeCount = buf.getShort() & 0xFFFF;
            boolean isFused = FUSED_METHOD_NAME.equals(utf8[nameIndex]);
            for (int a = 0; a < attributeCount; a++) {
                int attrNameIndex = buf.getShort() & 0xFFFF;
                int attrLength = buf.getInt();
                int attrStart = buf.position();
                if (isFused && "Code".equals(utf8[attrNameIndex])) {
                    buf.getShort(); // max_stack
                    buf.getShort(); // max_locals
                    return buf.getInt(); // code_length
                }
                buf.position(attrStart + attrLength);
            }
        }
        return 0;
    }

    private static void skipAttributes(java.nio.ByteBuffer buf) {
        int attributeCount = buf.getShort() & 0xFFFF;
        for (int a = 0; a < attributeCount; a++) {
            buf.getShort(); // attribute_name_index
            int attrLength = buf.getInt();
            buf.position(buf.position() + attrLength);
        }
    }

    /**
     * The concrete {@code compute.data} types and opcodes for one supported primitive element kind. Resolving these
     * once keeps the emit code free of per-type branching and confines the {@code long}/{@code int}/{@code double}/
     * {@code boolean} differences to a single {@link #of(Type)} table — the emit paths never hardcode a specific
     * primitive, they read everything (array-vector class, raw-array descriptor, block/builder types, get/append
     * methods, the {@code Type} that derives every load/store/return opcode) from here, so a new primitive is a
     * pure data-add.
     *
     * <p><b>float:</b> deliberately absent. It would be one more {@code case Type.FLOAT} here (plus a
     * {@code FloatArrayVector.rawValues()} exposed through {@code VectorUnsafe.floats}), but no {@code float} arithmetic
     * kernel is {@code @Fusable} today, so the planner never types a node {@code float} and this table is never asked
     * for it — adding it now would be untested dead code. When a fusable {@code float} op lands, add those two data
     * entries and it flows through every path unchanged.
     */
    private record Element(
        String arrayVectorInternalName,
        String rawValuesDescriptor,
        String vectorDescriptor,
        String newVectorMethod,
        String newVectorDescriptor,
        int newArrayType,
        String blockInternalName,
        String builderInternalName,
        String newBlockBuilderMethod,
        String getValueMethod,
        String appendMethod,
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
                    "org/elasticsearch/compute/data/LongBlock",
                    "org/elasticsearch/compute/data/LongBlock$Builder",
                    "newLongBlockBuilder",
                    "getLong",
                    "appendLong",
                    t
                );
                case Type.INT -> new Element(
                    "org/elasticsearch/compute/data/IntArrayVector",
                    "()[I",
                    "Lorg/elasticsearch/compute/data/IntVector;",
                    "newIntArrayVector",
                    "([II)Lorg/elasticsearch/compute/data/IntVector;",
                    Opcodes.T_INT,
                    "org/elasticsearch/compute/data/IntBlock",
                    "org/elasticsearch/compute/data/IntBlock$Builder",
                    "newIntBlockBuilder",
                    "getInt",
                    "appendInt",
                    t
                );
                case Type.DOUBLE -> new Element(
                    "org/elasticsearch/compute/data/DoubleArrayVector",
                    "()[D",
                    "Lorg/elasticsearch/compute/data/DoubleVector;",
                    "newDoubleArrayVector",
                    "([DI)Lorg/elasticsearch/compute/data/DoubleVector;",
                    Opcodes.T_DOUBLE,
                    "org/elasticsearch/compute/data/DoubleBlock",
                    "org/elasticsearch/compute/data/DoubleBlock$Builder",
                    "newDoubleBlockBuilder",
                    "getDouble",
                    "appendDouble",
                    t
                );
                // BOOLEAN is an OUTPUT-only element in this scope: it is the return kind of a comparison-rooted tree
                // (e.g. a > b). Its input reads (rawValues()[Z, getBoolean, BALOAD) are wired for completeness/symmetry,
                // but fusion leaves are Attribute-only numeric columns today, so a boolean element is never an INPUT.
                // A boolean occupies one slot and rides the operand stack as an int (0/1), so BALOAD/BASTORE and the
                // ILOAD/ISTORE the emitters derive via Type#getOpcode all resolve to the int-category boolean opcodes.
                case Type.BOOLEAN -> new Element(
                    "org/elasticsearch/compute/data/BooleanArrayVector",
                    "()[Z",
                    "Lorg/elasticsearch/compute/data/BooleanVector;",
                    "newBooleanArrayVector",
                    "([ZI)Lorg/elasticsearch/compute/data/BooleanVector;",
                    Opcodes.T_BOOLEAN,
                    "org/elasticsearch/compute/data/BooleanBlock",
                    "org/elasticsearch/compute/data/BooleanBlock$Builder",
                    "newBooleanBlockBuilder",
                    "getBoolean",
                    "appendBoolean",
                    t
                );
                default -> throw new IllegalArgumentException("unsupported fused output element type: " + t.getClassName());
            };
        }

        /** JVM type descriptor of the concrete {@code *Block}, e.g. {@code Lorg/.../LongBlock;}. */
        String blockDescriptor() {
            return "L" + blockInternalName + ";";
        }

        /**
         * Name of the {@link org.elasticsearch.compute.data.VectorUnsafe} static forwarder that returns this element's
         * backing array, e.g. {@code longs} for {@code long[]}. The fused vector loop calls it via {@code INVOKESTATIC}
         * (instead of the package-private {@code INVOKEVIRTUAL rawValues()}), so the hidden class can be defined in the
         * caller's own module rather than teleported into {@code compute.data}.
         */
        String vectorUnsafeMethod() {
            return switch (type.getSort()) {
                case Type.LONG -> "longs";
                case Type.INT -> "ints";
                case Type.DOUBLE -> "doubles";
                case Type.BOOLEAN -> "booleans";
                default -> throw new IllegalArgumentException("no VectorUnsafe accessor for element type: " + type.getClassName());
            };
        }

        /**
         * Descriptor of the {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder for this element, e.g.
         * {@code (Lorg/.../LongArrayVector;)[J}: it takes the concrete {@code *ArrayVector} (narrowed once by the
         * emitted {@code CHECKCAST}) and returns the backing primitive array. Derived from {@link #rawValuesDescriptor}
         * (a {@code ()[X}) by threading the {@code *ArrayVector} in as the sole argument.
         */
        String vectorUnsafeDescriptor() {
            return "(L" + arrayVectorInternalName + ";)" + rawValuesDescriptor.substring(2);
        }

        /** JVM type descriptor of the concrete {@code *Block.Builder}, e.g. {@code Lorg/.../LongBlock$Builder;}. */
        String builderDescriptor() {
            return "L" + builderInternalName + ";";
        }

        /** Descriptor of {@code BlockFactory.new*BlockBuilder(int)} -> {@code *Block.Builder}. */
        String newBlockBuilderDescriptor() {
            return "(I)" + builderDescriptor();
        }

        /** Descriptor of {@code *Block.get*(int)} -> primitive, e.g. {@code (I)J} for {@code getLong}. */
        String getValueDescriptor() {
            return "(I)" + type.getDescriptor();
        }

        /** Descriptor of the fluent {@code *Block.Builder.append*(primitive)} -> {@code *Block.Builder}. */
        String appendDescriptor() {
            return "(" + type.getDescriptor() + ")" + builderDescriptor();
        }

        /** Descriptor of the fluent {@code *Block.Builder.appendNull()} -> {@code *Block.Builder}. */
        String appendNullDescriptor() {
            return "()" + builderDescriptor();
        }

        /** Descriptor of {@code *Block.Builder.build()} -> {@code *Block}. */
        String buildDescriptor() {
            return "()" + blockDescriptor();
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
