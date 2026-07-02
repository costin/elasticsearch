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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashSet;
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
     * <p><b>Overflow.</b> This array-output fast path has no null bitmask, so it is only valid when no kernel can
     * throw on overflow (an escaping {@code ArithmeticException} would abort the whole method rather than nulling one
     * position). For a tree with overflow-checked kernels the caller must use {@link #compileVectorLoopChecked},
     * which appends through a builder and turns an overflow into a null + warning like the unfused evaluator.
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
     * Overflow-aware counterpart to {@link #compileVectorLoop}: fuses a tree of no-null single-valued primitive
     * {@code *ArrayVector} inputs whose kernels are <b>overflow-checked</b> into a single counted loop that reads
     * each input's {@code rawValues()} array (the same fast, guard-free read as the plain vector path) but writes
     * through a {@code *Block.Builder} so an overflowing position can become {@code null}. This is the "nulls-capable
     * output" the plain array-vector fast path cannot provide: an {@code ArrayVector} has no null bitmask, whereas
     * the unfused vector-path evaluator (e.g. {@code AddLongsEvaluator.eval(int, LongVector, LongVector)}) already
     * builds a {@code *Block} via a builder and appends {@code null} on a caught {@code ArithmeticException}. The
     * emitted {@link #FUSED_METHOD_NAME} has the shape
     * <pre>{@code
     * static LongBlock fused(BlockFactory bf, Warnings warnings, int positionCount, Vector in0, Vector in1) {
     *     long[] a0 = ((LongArrayVector) in0).rawValues();
     *     long[] a1 = ((LongArrayVector) in1).rawValues();
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
     * <p><b>Vector-path double linkage asymmetry (iter-11 carry-forward; note for the iter-13 caller).</b> Like
     * {@link #compileVectorLoop}, this path calls the package-private {@code rawValues()} accessor, so the hidden
     * class must be defined into the effective {@code compute.data} package via
     * {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)}. A hidden class defined there links only
     * against modules {@code compute} can read. For {@code long}/{@code int} overflow kernels (whose bodies call
     * only {@code java.base}'s {@code Math.*Exact}) that is fine. But a {@code double} overflow kernel body calls
     * {@code NumericUtils.asFiniteNumber} in {@code org.elasticsearch.xpack.esql.core}, which {@code compute.data}
     * cannot see — so {@code defineHiddenClass} fails with a {@code LinkageError} that this method converts to a
     * {@link StitchingException} (clean fallback, no crash). <b>The iter-13 caller must therefore route
     * overflow-checked {@code double} trees through {@link #compileBlockLoop} (which defines into the caller's own
     * module and links {@code NumericUtils}) or the unfused chain, not through this vector path.</b>
     *
     * @param caller the seed lookup (from the module that owns the kernels); narrowed into {@code compute.data}
     * @param tree   the expression tree to fuse; all inputs are homogeneous no-null single-valued {@code *ArrayVector}s
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller cannot be teleported into {@code compute.data}, or the emitted class
     *                            fails JVM verification/linking (e.g. a {@code double} kernel's non-{@code java.base}
     *                            callee) — the caller should fall back to the block path or unfused chain
     */
    public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        MethodHandles.Lookup dataLookup;
        try {
            dataLookup = MethodHandles.privateLookupIn(LongArrayVector.class, caller);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot be teleported into compute.data; falling back to unfused", caller, e);
            throw new StitchingException("caller lookup cannot access compute.data for fused checked vector loop", e);
        }
        byte[] bytecode = emitVectorLoopChecked(dataLookup, tree);
        try {
            return dataLookup.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            // A double kernel body's non-java.base callee (NumericUtils.asFiniteNumber) cannot link in compute.data.
            logger.warn("fused checked vector loop failed to link; falling back to unfused", e);
            throw new StitchingException("failed to define fused checked vector-loop class", e);
        } catch (IllegalAccessException e) {
            logger.warn("data lookup [{}] cannot define fused checked vector-loop class; falling back to unfused", dataLookup, e);
            throw new StitchingException("data lookup cannot define fused checked vector-loop class", e);
        }
    }

    /**
     * Builds the class bytes for the overflow-aware vector loop. Reads inputs from {@code rawValues()} arrays (no
     * per-position null/multi-value guard — a vector is dense and single-valued) but appends through a builder and
     * wraps the kernel evaluation in the overflow try/catch, so the only nulls it can produce are overflow nulls.
     */
    private byte[] emitVectorLoopChecked(MethodHandles.Lookup dataLookup, FusionNode tree) {
        int arity = arity(tree);
        int[] consumed = consumedInputs(tree);
        Element element = Element.of(rootReturnType(tree));
        int size = element.type().getSize();
        Set<String> overflowTypes = overflowExceptionInternalNames(tree);

        // Frame: [0] BlockFactory, [1] Warnings, [2] int positionCount, [3 .. 3+arity) input Vectors. The per-input
        // raw arrays, the builder, loop counter, per-input value slots, built result, and the two exception slots sit
        // above the parameters; each kernel body then gets a disjoint range above that.
        int warningsSlot = 1;
        int pcSlot = 2;
        int inputBase = 3;
        int rawArrayBase = inputBase + arity;
        int builderSlot = rawArrayBase + arity;
        int pSlot = builderSlot + 1;
        int valueBase = pSlot + 1;
        int resultSlot = valueBase + arity * size;
        int excSlot = resultSlot + 1;
        int overflowExcSlot = excSlot + 1;
        int kernelBase = overflowExcSlot + 1;

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";L").append(WARNINGS).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(VECTOR).append(";");
        }
        desc.append(")").append(element.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // Cast each CONSUMED input to its concrete *ArrayVector ONCE and read rawValues() into a local array.
        for (int i : consumed) {
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

        // out = bf.new*BlockBuilder(positionCount);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLOCK_FACTORY,
                element.newBlockBuilderMethod(),
                element.newBlockBuilderDescriptor(),
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

        // v_i = a_i[p]; no null guard — a vector is dense and single-valued.
        for (int i : consumed) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + i));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new InsnNode(element.type().getOpcode(Opcodes.IALOAD)));
            insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ISTORE), valueBase + i * size));
        }

        LabelNode tryStartOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode tryEndOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        if (tryStartOverflow != null) {
            insns.add(tryStartOverflow);
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        BlockLoopEmitter emitter = new BlockLoopEmitter(insns, element, valueBase, size, kernelBase);
        emitter.emit(tree);
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                element.builderInternalName(),
                element.appendMethod(),
                element.appendDescriptor(),
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
            insns.add(new VarInsnNode(Opcodes.ALOAD, warningsSlot));
            insns.add(new VarInsnNode(Opcodes.ALOAD, overflowExcSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
            insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    element.builderInternalName(),
                    "appendNull",
                    element.appendNullDescriptor(),
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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, element.builderInternalName(), "build", element.buildDescriptor(), true));
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
        classNode.name = dataLookup.lookupClass().getPackageName().replace('.', '/') + "/Fused$$CheckedVectorLoop";
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);

        ClassLoader loader = dataLookup.lookupClass().getClassLoader();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
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
     * <p><b>Why no {@code privateLookupIn} teleport (unlike {@link #compileVectorLoop}).</b> The vector path calls
     * the package-private {@code rawValues()} accessor and therefore must define its hidden class into the effective
     * {@code compute.data} package. The block path, by contrast, touches only <b>public exported</b> API —
     * {@code BlockFactory.new*BlockBuilder}, {@code Block.getValueCount}/{@code getFirstValueIndex}, the typed
     * {@code *Block.get*}, the {@code *Block.Builder} appenders/{@code build}, and {@code Releasable.close}. So the
     * fused class is defined directly into the <b>caller's</b> own package/module (as {@link #compile} does for a
     * depth-1 kernel): the caller's module already reads {@code compute} (for the public block types) and the module
     * that owns the kernels (for the spliced kernel-body call targets, e.g. {@code NumericUtils.asFiniteNumber} in
     * {@code Add.processDoubles}). This is precisely what lets an esql-proper caller fuse a non-{@code java.base}
     * kernel over {@code *Block}s and link it — {@code compute.data} is <i>exported</i> but not <i>opened</i>, so a
     * cross-module {@code privateLookupIn(compute.data, esqlLookup)} would have failed.
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
    private byte[] emitBlockLoop(MethodHandles.Lookup caller, FusionNode tree) {
        int arity = arity(tree);
        // Only the inputs the tree actually references are null-checked and read. The method still takes one *Block
        // parameter per index in [0, arity) (so callers bind positionally by leaf index), but guarding an input the
        // expression never consumes would over-nullify: a null/multi-value in an unused input would wrongly null the
        // whole position even though no kernel reads it. The unfused chain only inspects operands it actually uses,
        // so we mirror that by iterating the consumed-input set.
        int[] consumed = consumedInputs(tree);
        Element element = Element.of(rootReturnType(tree));
        int size = element.type().getSize();
        // Distinct overflow exception types the tree's kernels can throw (uniformly ArithmeticException in scope);
        // empty when no kernel is overflow-checked, in which case no per-position try/catch is emitted.
        Set<String> overflowTypes = overflowExceptionInternalNames(tree);

        // Frame layout: [0] BlockFactory, [1] Warnings, [2] int positionCount, [3 .. 3+arity) input *Blocks. The
        // builder, loop counter, a scratch value-count slot, per-input value slots (each `size` wide), the built
        // result slot, the caught-throwable slot (builder-safety) and the overflow-exception slot sit in the fixed
        // region above the parameters; each kernel body then gets a disjoint range above that.
        int warningsSlot = 1;
        int pcSlot = 2;
        int inputBase = 3;
        int builderSlot = inputBase + arity;
        int pSlot = builderSlot + 1;
        int countSlot = pSlot + 1;
        int valueBase = countSlot + 1;
        int resultSlot = valueBase + arity * size;
        int excSlot = resultSlot + 1;
        int overflowExcSlot = excSlot + 1;
        int kernelBase = overflowExcSlot + 1;

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";L").append(WARNINGS).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append(element.blockDescriptor());
        }
        desc.append(")").append(element.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            desc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

        // out = bf.new*BlockBuilder(positionCount);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcSlot));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLOCK_FACTORY,
                element.newBlockBuilderMethod(),
                element.newBlockBuilderDescriptor(),
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

        // Null / multi-value guard per CONSUMED input, matching the generated unfused evaluator's switch:
        // getValueCount(p) == 0 -> appendNull, no warning (a null propagates)
        // getValueCount(p) == 1 -> proceed to the next input's guard
        // getValueCount(p) > 1 -> register the single-value multi-value warning, then appendNull
        // Inputs the tree never references are neither guarded nor read.
        for (int i : consumed) {
            LabelNode proceed = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getValueCount", "(I)I", true));
            insns.add(new VarInsnNode(Opcodes.ISTORE, countSlot));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, appendNull));
            insns.add(new VarInsnNode(Opcodes.ILOAD, countSlot));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, proceed));
            emitMultiValueWarning(insns, warningsSlot);
            insns.add(new JumpInsnNode(Opcodes.GOTO, appendNull));
            insns.add(proceed);
        }

        // Read each consumed input's single value once: v_i = block_i.get*(block_i.getFirstValueIndex(p)).
        for (int i : consumed) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getFirstValueIndex", "(I)I", true));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    element.blockInternalName(),
                    element.getValueMethod(),
                    element.getValueDescriptor(),
                    true
                )
            );
            insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ISTORE), valueBase + i * size));
        }

        // out.append*(<stitched expression>); the builder ref is pushed first, then the fused kernel bodies leave
        // the computed value on top, so the append call consumes both; the fluent return is discarded. When the tree
        // can overflow, this region is guarded by a per-type try/catch that turns the overflow into null + warning.
        LabelNode tryStartOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        LabelNode tryEndOverflow = overflowTypes.isEmpty() ? null : new LabelNode();
        if (tryStartOverflow != null) {
            insns.add(tryStartOverflow);
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        BlockLoopEmitter emitter = new BlockLoopEmitter(insns, element, valueBase, size, kernelBase);
        emitter.emit(tree);
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                element.builderInternalName(),
                element.appendMethod(),
                element.appendDescriptor(),
                true
            )
        );
        insns.add(new InsnNode(Opcodes.POP));
        if (tryEndOverflow != null) {
            insns.add(tryEndOverflow);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, next));

        // Overflow handler(s): register the thrown exception and append null for this position. One handler per
        // distinct exception type keeps each handler's incoming frame a single concrete type (no supertype merge),
        // and they are added to the exception table BEFORE the builder-safety catch-all so overflow is matched first.
        for (String exType : overflowTypes) {
            LabelNode overflowHandler = new LabelNode();
            insns.add(overflowHandler);
            insns.add(new VarInsnNode(Opcodes.ASTORE, overflowExcSlot));
            insns.add(new VarInsnNode(Opcodes.ALOAD, warningsSlot));
            insns.add(new VarInsnNode(Opcodes.ALOAD, overflowExcSlot));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
            insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    element.builderInternalName(),
                    "appendNull",
                    element.appendNullDescriptor(),
                    true
                )
            );
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new JumpInsnNode(Opcodes.GOTO, next));
            host.tryCatchBlocks.add(new TryCatchBlockNode(tryStartOverflow, tryEndOverflow, overflowHandler, exType));
        }

        // out.appendNull();
        insns.add(appendNull);
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(
            new MethodInsnNode(Opcodes.INVOKEINTERFACE, element.builderInternalName(), "appendNull", element.appendNullDescriptor(), true)
        );
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(next);
        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
        // result = out.build(); build() transfers the builder's breaker accounting to the produced block.
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, element.builderInternalName(), "build", element.buildDescriptor(), true));
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
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Emits {@code warnings.registerException(new IllegalArgumentException("single-value function encountered
     * multi-value"))} — the carried-forward multi-value warning the generated unfused evaluator raises when a scalar
     * kernel meets a multi-valued position.
     */
    private static void emitMultiValueWarning(InsnList insns, int warningsSlot) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsSlot));
        insns.add(new TypeInsnNode(Opcodes.NEW, ILLEGAL_ARGUMENT_EXCEPTION));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode(MULTI_VALUE_MESSAGE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ILLEGAL_ARGUMENT_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_DESCRIPTOR, false));
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
     * Post-order emitter for one expression tree into the fused block loop. Identical in structure to
     * {@link VectorLoopEmitter} except an {@link FusionNode.Input} reads the per-position value already loaded into
     * its value local (rather than indexing a raw array), so the null/multi-value guard and single-value read done
     * once per position are shared by every reference to that input.
     */
    private final class BlockLoopEmitter {
        private final InsnList insns;
        private final Element element;
        private final int valueBase;
        private final int valueSize;
        private int kernelBase;

        BlockLoopEmitter(InsnList insns, Element element, int valueBase, int valueSize, int kernelBase) {
            this.insns = insns;
            this.element = element;
            this.valueBase = valueBase;
            this.valueSize = valueSize;
            this.kernelBase = kernelBase;
        }

        /**
         * Emits instructions that leave {@code node}'s value for the current position {@code p} on the operand
         * stack.
         */
        void emit(FusionNode node) {
            if (node instanceof FusionNode.Input input) {
                insns.add(new VarInsnNode(element.type().getOpcode(Opcodes.ILOAD), valueBase + input.index() * valueSize));
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
        } else {
            for (FusionNode child : ((FusionNode.Kernel) node).children()) {
                collectInputs(child, used);
            }
        }
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
                default -> throw new IllegalArgumentException("unsupported fused output element type: " + t.getClassName());
            };
        }

        /** JVM type descriptor of the concrete {@code *Block}, e.g. {@code Lorg/.../LongBlock;}. */
        String blockDescriptor() {
            return "L" + blockInternalName + ";";
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
