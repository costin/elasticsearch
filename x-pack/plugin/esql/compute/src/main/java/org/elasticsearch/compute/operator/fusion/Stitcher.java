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
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Defines {@code bytecode} as a hidden class in the {@code caller}'s module and returns it, mapping the two
     * failure modes of {@link MethodHandles.Lookup#defineHiddenClass} — a {@link LinkageError} (a {@link VerifyError}
     * from the JVM's own verifier is a subtype) and an {@link IllegalAccessException} — to a typed
     * {@link StitchingException} after a WARN, so the plan-time caller falls back to the unfused chain without the
     * query path ever seeing an {@code Error}. Every {@code compile*} entry point funnels through here, so the
     * define-and-map-failure contract (binding rule #5's failure surface) lives once rather than being copied per path.
     *
     * @param what names the emit path in the log/message (e.g. {@code "block-loop class"})
     */
    private Class<?> defineOrThrow(MethodHandles.Lookup caller, byte[] bytecode, String what) throws StitchingException {
        // Opt-in debug/profiler visibility into the generated (hidden, on-disk-invisible) class; a no-op unless
        // -Desql.fusion.dump=true or a sink is installed (cold plan-time path, once per fused class).
        FusionDump.emit(bytecode);
        try {
            return caller.defineHiddenClass(bytecode, true).lookupClass();
        } catch (LinkageError e) {
            logger.warn("fused {} failed to link; falling back to unfused", what, e);
            throw new StitchingException("failed to define fused " + what, e);
        } catch (IllegalAccessException e) {
            logger.warn("caller lookup [{}] cannot define fused {}; falling back to unfused", caller, what, e);
            throw new StitchingException("caller lookup cannot define fused " + what, e);
        }
    }

    /**
     * Fails loud when a {@link FusionNode.Kernel}'s operand count does not match its descriptor's arity — a
     * planner/tree construction bug, not a runtime input condition (so an unchecked exception, caught by no one, is
     * correct). Shared by every emitter that splices a kernel body so the check reads identically on all paths.
     */
    static void checkKernelArity(FusionNode.Kernel kernel, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                "kernel [" + kernel.descriptor().kernelMethod() + "] expects " + expected + " operands but the tree supplied " + actual
            );
        }
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
        return defineOrThrow(caller, emit(caller, descriptor), "class for kernel " + descriptor);
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

        ClassNode classNode = newFusedClass(hiddenClassInternalName(caller, descriptor), host);

        // COMPUTE_FRAMES recomputes maxStack, maxLocals, and stack-map frames. Straight-line kernel bodies have
        // no control-flow joins, so getCommonSuperClass (which would need the caller's class loader) is never
        // consulted — a plain writer suffices here (the looped paths use frameComputingWriter).
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * A hidden class must be defined in the same runtime package as the lookup class, so its internal name is
     * derived from the caller's package. The JVM appends a unique suffix to this name at define time.
     */
    private static String hiddenClassInternalName(MethodHandles.Lookup caller, FusionDescriptor descriptor) {
        return fusedClassName(caller, descriptor.kernelClass().getSimpleName() + "$$Fused");
    }

    /**
     * The internal (slash-separated) name for a fused hidden class: {@code simpleName} qualified by the
     * <b>caller's</b> package, so {@code defineHiddenClass} places it in the caller's runtime package (the JVM then
     * appends a unique suffix). Every emit path derives its host class name here.
     */
    private static String fusedClassName(MethodHandles.Lookup caller, String simpleName) {
        String pkg = caller.lookupClass().getPackageName().replace('.', '/');
        return pkg.isEmpty() ? simpleName : pkg + "/" + simpleName;
    }

    /**
     * A fused hidden-class {@link ClassNode}: {@code public final}, class-file {@code V21}, {@code Object}
     * superclass, carrying {@code host} as its (initial) method. The two helper-emitting paths (logical, filter-eval)
     * add their {@code cmpN}/{@code pred}/{@code projAppend} helper methods afterwards.
     */
    private static ClassNode newFusedClass(String internalName, MethodNode host) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = internalName;
        classNode.superName = "java/lang/Object";
        classNode.methods.add(host);
        return classNode;
    }

    /**
     * A {@link ClassWriter#COMPUTE_FRAMES} writer whose {@code getCommonSuperClass} resolves against the
     * <b>caller's</b> class loader rather than ASM's own. The looped/branching emit paths have control-flow joins at
     * which {@code COMPUTE_FRAMES} may need a common-superclass lookup; the caller's loader can see both the public
     * {@code compute} vector/block types and the spliced kernel bodies' call targets. Our joins never actually merge
     * incompatible reference types, so this is a safety net rather than a hot dependency. (The depth-1 straight-line
     * {@link #emit} has no joins and uses a plain writer.)
     */
    private static ClassWriter frameComputingWriter(MethodHandles.Lookup caller) {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
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
    static final String BLOCK = "org/elasticsearch/compute/data/Block";

    /**
     * Internal name of {@code org.apache.lucene.util.BytesRef}, the reference type a {@code BYTES_REF} (keyword/text)
     * input leaf reads into. Unlike the primitive elements a {@code BytesRef} is read into a reusable {@code BytesRef}
     * "spare" local (allocated once outside the loop) via {@code BytesRefBlock.getBytesRef(int, BytesRef)}, then rides
     * the fused body's reference slots ({@code ALOAD}/{@code ASTORE}). It is INPUT-only in this slice: a leaf/kernel-arg
     * element, never a kernel's output element (no {@code BytesRef}-returning kernels are fused yet).
     */
    // Package-private so the extracted Element record (its own file) can reference the one definition.
    static final String BYTES_REF = "org/apache/lucene/util/BytesRef";

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
    static final String WARNINGS = "org/elasticsearch/compute/operator/Warnings";

    /** Descriptor of {@code Warnings.registerException(Exception)} — used for an already-thrown overflow exception. */
    static final String WARNINGS_REGISTER_DESCRIPTOR = "(Ljava/lang/Exception;)V";

    /**
     * Descriptor of {@code Warnings.registerException(Class<? extends Exception>, String)} — the allocation-free
     * overload. The multi-value warning has a constant class and message, so the fused loop registers it through this
     * overload (a {@code ldc} of the class + the message string) instead of constructing an
     * {@code IllegalArgumentException} per multi-valued position. {@link org.elasticsearch.compute.operator.Warnings}
     * caps at {@link org.elasticsearch.compute.operator.Warnings#MAX_ADDED_WARNINGS} and builds the identical header
     * ({@code registerException(Exception)} itself delegates to {@code registerException(getClass(), getMessage())}),
     * so this is byte-for-byte the same warning with no per-row allocation in the hot loop.
     */
    private static final String WARNINGS_REGISTER_CLASS_DESCRIPTOR = "(Ljava/lang/Class;Ljava/lang/String;)V";

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
        return defineOrThrow(caller, emitVectorLoop(caller, tree), "vector-loop class");
    }

    /**
     * Builds the class bytes for the fused vector loop. Kept separate from {@link #compileVectorLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    private byte[] emitVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        // One walk derives every structural fact this emit path needs (arity, constants-in-emit-order, ...).
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        int[] consumed = signature.consumedInputs();
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the output
        // array + produced vector). They coincide for a homogeneous arithmetic root and differ for a comparison root
        // (numeric inputs feed a boolean output) or any cast-bridged input.
        Element[] inputElements = inputElements(signature);
        Element outputElement = Element.of(rootReturnType(tree));

        // Frame layout: [0] BlockFactory, [1] int positionCount, [2 .. 2+arity) input Vectors, then one trailing
        // primitive parameter per embedded constant (in canonical emit order). The per-input raw arrays, the output
        // array, and the loop counter sit in the fixed region ABOVE the parameters — so their bases are all shifted up
        // by the constant parameters' total slot size; each kernel body then gets a disjoint slot range above that.
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        // Parameter region [0..): BlockFactory, positionCount, one Vector ref per input, one primitive per constant.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        frame.slot();                                                       // [1] int positionCount
        int inputBase = frame.slots(arity);                                 // [2 .. 2+arity) input Vectors
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
        // Scratch region above the parameters: per-input raw arrays, the output array, the loop counter.
        int rawArrayBase = frame.slots(arity);
        int outSlot = frame.slot();
        int pSlot = frame.slot();
        int kernelBase = frame.mark();

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

        // Cast each CONSUMED input to its concrete *ArrayVector ONCE and read its backing array into a local (JIT #6).
        // The array is fetched through the public VectorUnsafe forwarder (INVOKESTATIC) rather than the package-private
        // rawValues() (INVOKEVIRTUAL), so this hidden class can be defined in the caller's own module. Only inputs the
        // tree actually reads are cast/extracted (matching the checked-vector path): a declared-but-unread index (a
        // gap in [0,arity)) is never touched, so no wasted CHECKCAST/extract and no ClassCastException on a vector no
        // leaf consumes.
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
        StackKernelEmitter emitter = new StackKernelEmitter(
            insns,
            constantSlots,
            kernelBase,
            // Plain-vector leaf: a<index>[p], read at this leaf's own element.
            input -> {
                Element element = inputElements[input.index()];
                insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + input.index()));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
                insns.add(new InsnNode(element.type().getOpcode(Opcodes.IALOAD)));
            },
            // Plain vector has no overflow-checked kernels, so nothing runs before a body.
            kernel -> {}
        );
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

        ClassNode classNode = newFusedClass(fusedClassName(caller, "Fused$$VectorLoop"), host);
        // Measure the inline body WITHOUT enforcing: an oversized inline loop is not refused (which would degrade to
        // block-only), it is split into a private static compute() helper (B1) so the fast path survives.
        byte[] inline = serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "plain-vector loop", false);
        if (fusedMethodCodeLength(inline) <= MAX_FUSED_METHOD_BYTECODES) {
            return inline;
        }
        return emitVectorLoopSplit(caller, tree, inputElements, outputElement, constants);
    }

    /**
     * Over-budget counterpart to the plain-vector fast path (B1): when the inline per-position expression would exceed
     * the {@link #MAX_FUSED_METHOD_BYTECODES} inlining budget, the whole stitched expression is lifted into a
     * {@code private static compute(v0, v1, …, c0, …)} helper (the same {@link StackKernelEmitter} output, but reading
     * its inputs from parameters instead of {@code a_i[p]}). The top-level {@code fused} loop then only reads each
     * {@code a_i[p]}, calls {@code compute}, and stores {@code out[p]} — a tiny, inlinable body that keeps the dense
     * fast path (raw-array reads, no null/multi-value guards) instead of degrading to the block path. The small-tree
     * common case never reaches here (it stays fully inline, so C2 can vectorize it); only a pathologically deep tree
     * is split, and the top-level loop is still budget-enforced (so a genuinely un-inlinable loop shell — not expected —
     * still refuses to fuse).
     */
    private byte[] emitVectorLoopSplit(
        MethodHandles.Lookup caller,
        FusionNode tree,
        Element[] inputElements,
        Element outputElement,
        List<FusionNode.Constant> constants
    ) throws StitchingException {
        int arity = inputElements.length;
        String internalName = fusedClassName(caller, "Fused$$VectorLoopSplit");

        // The compute helper: (v0, v1, …, c0, c1, …) -> result. Value params first (one per input, its own element),
        // then one primitive per constant in canonical emit order.
        MethodNode compute = emitVectorComputeHelper(tree, inputElements, outputElement, constants);

        // Top-level loop frame: [0] BlockFactory, [1] positionCount, [2 .. 2+arity) input Vectors, constants, then the
        // per-input raw arrays, output array, loop counter (no kernel-body range — the body is a single compute call).
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        frame.slot();                                                       // [1] int positionCount
        int inputBase = frame.slots(arity);                                 // [2 .. 2+arity) input Vectors
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
        int rawArrayBase = frame.slots(arity);
        int outSlot = frame.slot();
        int pSlot = frame.slot();

        StringBuilder loopDesc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";I");
        for (int i = 0; i < arity; i++) {
            loopDesc.append("L").append(VECTOR).append(";");
        }
        appendConstantParamDescriptors(loopDesc, constants);
        loopDesc.append(")").append(outputElement.vectorDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
            loopDesc.toString(),
            null,
            null
        );
        InsnList insns = host.instructions;

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
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, outputElement.newArrayType()));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outSlot));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pSlot));

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        insns.add(loopStart);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // out[p] = compute(a0[p], a1[p], …, c0, c1, …)
        insns.add(new VarInsnNode(Opcodes.ALOAD, outSlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        for (int i = 0; i < arity; i++) {
            Element inputElement = inputElements[i];
            insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + i));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
            insns.add(new InsnNode(inputElement.type().getOpcode(Opcodes.IALOAD)));
        }
        for (FusionNode.Constant constant : constants) {
            insns.add(new VarInsnNode(constantType(constant).getOpcode(Opcodes.ILOAD), constantSlots.get(constant)));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, "compute", compute.desc, false));
        insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IASTORE)));

        insns.add(new IincInsnNode(pSlot, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        insns.add(loopEnd);

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

        ClassNode classNode = newFusedClass(internalName, host);
        classNode.methods.add(compute);
        // Enforce on the TOP-LEVEL loop only: it is now a handful of instructions (reads + one call + store), so this
        // never trips for a real tree; the (unenforced) compute helper carries the large body.
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "plain-vector loop (split)", true);
    }

    /**
     * Builds the {@code private static compute(v0, …, c0, …)} helper for {@link #emitVectorLoopSplit}: the stitched
     * expression reading its inputs from value parameters (rather than {@code a_i[p]}), returning the root result.
     */
    private MethodNode emitVectorComputeHelper(
        FusionNode tree,
        Element[] inputElements,
        Element outputElement,
        List<FusionNode.Constant> constants
    ) {
        int arity = inputElements.length;
        FrameLayout frame = new FrameLayout();
        int[] valueSlots = new int[arity];
        for (int i = 0; i < arity; i++) {
            valueSlots[i] = frame.slot(inputElements[i].type());
        }
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
        int kernelBase = frame.mark();

        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) {
            desc.append(inputElements[i].type().getDescriptor());
        }
        appendConstantParamDescriptors(desc, constants);
        desc.append(")").append(outputElement.type().getDescriptor());

        MethodNode compute = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "compute", desc.toString(), null, null);
        InsnList insns = compute.instructions;
        StackKernelEmitter emitter = new StackKernelEmitter(
            insns,
            constantSlots,
            kernelBase,
            // Split-vector leaf: the input value passed as a parameter (read at this leaf's own element).
            input -> insns.add(new VarInsnNode(inputElements[input.index()].type().getOpcode(Opcodes.ILOAD), valueSlots[input.index()])),
            kernel -> {}
        );
        emitter.emit(tree, outputElement);
        insns.add(new InsnNode(outputElement.type().getOpcode(Opcodes.IRETURN)));
        return compute;
    }

    /** The JVM {@link Type} of a constant's element: a primitive ({@code J}/{@code I}/{@code D}) or a reference type. */
    private static Type constantType(FusionNode.Constant constant) {
        if (constant.isReference()) {
            return Type.getType(constant.refType());
        }
        return switch (constant.element()) {
            case 'J' -> Type.LONG_TYPE;
            case 'D' -> Type.DOUBLE_TYPE;
            default -> Type.INT_TYPE;
        };
    }

    /** The {@code jdk.incubator.vector.*Vector} internal name for a primitive operand element (int/long/double). */
    private static String simdVectorClass(Element operand) {
        return switch (operand.type().getSort()) {
            case Type.INT -> FusionSimd.INT_VECTOR;
            case Type.LONG -> FusionSimd.LONG_VECTOR;
            case Type.DOUBLE -> FusionSimd.DOUBLE_VECTOR;
            default -> throw new IllegalArgumentException("no SIMD vector class for element " + operand.type().getClassName());
        };
    }

    /**
     * Compiles the opt-in Panama Vector-API (SIMD) variant of the plain-vector fast path (B2) for a boolean COMPARISON
     * of two same-primitive column operands (e.g. {@code a > b}), materialised as a hidden class. {@code comparison}
     * is the {@code jdk.incubator.vector.VectorOperators.Comparison} constant name (e.g. {@code "GT"}) supplied by the
     * planner from the comparison {@code Expression}. The caller must have {@linkplain FusionSimd#enableReadsFrom
     * granted its module a read edge} to {@code jdk.incubator.vector} first.
     *
     * @throws StitchingException on verification/link/access failure — the caller falls back to the scalar path
     */
    public Class<?> compileVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison) throws StitchingException {
        return defineOrThrow(caller, emitVectorLoopSimd(caller, tree, comparison), "SIMD vector-loop class");
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
    private byte[] emitVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison) throws StitchingException {
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        Element[] inputElements = inputElements(signature);
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
            throw new StitchingException("SIMD path requires two same-int/long/double column operands with no constants", null);
        }
        Element outputElement = Element.of(rootReturnType(tree));
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

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";I");
        for (int i = 0; i < arity; i++) {
            desc.append("L").append(VECTOR).append(";");
        }
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

        // a = VectorUnsafe.<E>s((EArrayVector) inN); read each backing array once.
        for (int i = 0; i < arity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, inputBase + i));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, inputElements[i].arrayVectorInternalName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    VECTOR_UNSAFE,
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
        StackKernelEmitter tail = new StackKernelEmitter(insns, java.util.Map.of(), kernelBase, input -> {
            Element e = inputElements[input.index()];
            insns.add(new VarInsnNode(Opcodes.ALOAD, rawArrayBase + input.index()));
            insns.add(new VarInsnNode(Opcodes.ILOAD, iSlot));
            insns.add(new InsnNode(e.type().getOpcode(Opcodes.IALOAD)));
        }, kernel -> {});
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
        ClassNode classNode = newFusedClass(fusedClassName(caller, "Fused$$SimdVectorLoop"), host);
        // Not budget-enforced: the SIMD body is a fixed small sequence, but it is only used for the eligible
        // single-comparison shape, so it never approaches the limit; measure/log only.
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "SIMD vector loop", false);
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
        return defineOrThrow(caller, emitVectorLoopChecked(caller, tree), "checked vector-loop class");
    }

    /**
     * Builds the class bytes for the overflow-aware vector loop. Reads inputs from {@code rawValues()} arrays (no
     * per-position null/multi-value guard — a vector is dense and single-valued) but appends through a builder and
     * wraps the kernel evaluation in the overflow try/catch, so the only nulls it can produce are overflow nulls.
     */
    private byte[] emitVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        // One walk derives arity, consumed inputs, constants-in-emit-order, warning-source indices and overflow types.
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        int[] consumed = signature.consumedInputs();
        // Per-input leaf elements (a tree may mix int/long/double via cast kernels) vs the OUTPUT element (the builder +
        // produced block). Each consumed input is read at its OWN element into a per-input value slot.
        Element[] inputElements = inputElements(signature);
        Element outputElement = Element.of(rootReturnType(tree));
        Set<String> overflowTypes = signature.overflowExceptionInternalNames();

        // Frame: [0] BlockFactory, [1] Warnings[], [2] int positionCount, [3 .. 3+arity) input Vectors, then one
        // trailing primitive parameter per embedded constant (in canonical emit order). The per-input raw arrays, the
        // builder, loop counter, per-input value slots, built result, the two exception slots and the current-kernel
        // slot sit ABOVE the parameters (their bases shifted up by the constant parameters' total slot size); each
        // kernel body then gets a disjoint range above that.
        List<FusionNode.Constant> constants = signature.constantsInEmitOrder();
        // Parameter region [0..): BlockFactory, Warnings[], positionCount, one Vector per input, one primitive per constant.
        FrameLayout frame = new FrameLayout();
        frame.slot();                                                       // [0] BlockFactory
        int warningsArraySlot = frame.slot();                               // [1] Warnings[]
        int pcSlot = frame.slot();                                          // [2] int positionCount
        int inputBase = frame.slots(arity);                                 // [3 .. 3+arity) input Vectors
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
        // Scratch region above the parameters. Per-input value slots are sized by each input's own element (a
        // long/double takes two slots); an unused index (never expected) reserves one. The FrameLayout guarantees the
        // widths never collide.
        int rawArrayBase = frame.slots(arity);
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

        // Each kernel's warning-source slot into the runtime Warnings[]: its overflow warning registers on
        // warnings[its slot], matching the unfused per-node evaluator source.
        Map<FusionNode, Integer> sourceIndices = signature.warningSourceIndices();

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
                    pushInt(insns, sourceIndices.get(kernel));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, currentKernelSlot));
                }
            }
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

        ClassNode classNode = newFusedClass(fusedClassName(caller, "Fused$$CheckedVectorLoop"), host);
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "checked-vector loop", false);
    }

    /** Extracts a kernel's straight-line {@link Body} from its template {@link MethodNode} (registry lookup + lift). */
    private Body extractKernelBody(FusionNode.Kernel kernel) {
        return BodyExtractor.extract(templates.methodNode(kernel.descriptor()));
    }

    /**
     * Emits an {@link FusionNode.Input} leaf's value for the current position onto the operand stack, typed to the
     * leaf's own element. The two stack-based paths differ only here: the plain-vector fast path reads
     * {@code rawArray[p]}, while the checked-vector path reads a value already loaded into the leaf's value local.
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
     * <p>It is NOT used by the nullable/multi-valued {@link BlockDfsEmitter} or the 3VL {@link LogicalDfsEmitter}:
     * those interleave present/live/warning control flow with the walk (they are real semantic forks), though they
     * reuse the same {@link #extractKernelBody}/{@link #checkKernelArity}/{@link #loadConstantParam} primitives.
     */
    private final class StackKernelEmitter {
        private final InsnList insns;
        private final Map<FusionNode.Constant, Integer> constantSlots;
        private final LeafReader leafReader;
        private final KernelPreBody preBody;
        private int kernelBase;

        StackKernelEmitter(
            InsnList insns,
            Map<FusionNode.Constant, Integer> constantSlots,
            int kernelBase,
            LeafReader leafReader,
            KernelPreBody preBody
        ) {
            this.insns = insns;
            this.constantSlots = constantSlots;
            this.kernelBase = kernelBase;
            this.leafReader = leafReader;
            this.preBody = preBody;
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
                // Embedded literal: load it from its trailing method-parameter slot (no leaf read, no input slot).
                loadConstantParam(insns, expected, constant, constantSlots.get(constant));
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
            checkKernelArity(kernel, argTypes.length, children.size());
            int offset = 0;
            for (int i = 0; i < argTypes.length; i++) {
                // Each child produces this kernel's argument element (a cast child bridges a differing leaf element).
                emit(children.get(i), Element.of(argTypes[i]));
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), nodeBase + offset));
                offset += argTypes[i].getSize();
            }
            preBody.beforeBody(kernel);
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
        return defineOrThrow(caller, emitBlockLoop(caller, tree), "block-loop class");
    }

    /**
     * Builds the class bytes for the fused block loop. Kept separate from {@link #compileBlockLoop} so the
     * verification boundary is exactly the {@code defineHiddenClass} call — everything here is pure ASM tree
     * manipulation on cloned kernel bodies.
     */
    private byte[] emitBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
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
        Element[] inputElements = inputElements(signature);
        Element outputElement = Element.of(rootReturnType(tree));
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
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
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
        // A reusable BytesRef spare for any BYTES_REF (keyword/text) leaf, allocated once below the DFS work region and
        // initialised before the loop. Reserved unconditionally (a one-slot gap when the tree has no reference input,
        // so the layout is uniform); only initialised + consulted when a reference input is present.
        int spareBytesRefSlot = frame.slot();
        int workBase = frame.mark();
        boolean needsSpare = hasReferenceInput(inputElements);

        // Each kernel's warning-source slot into the runtime Warnings[]: its overflow warning and its direct leaf
        // operands' multi-value warnings register on warnings[its slot], matching the unfused per-node evaluator source.
        Map<FusionNode, Integer> sourceIndices = signature.warningSourceIndices();

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

        // Allocate the reusable BytesRef spare once, before the loop, when any leaf reads a reference (BYTES_REF)
        // column. It is a plain local (no breaker accounting), so it lives outside the builder's try region.
        if (needsSpare) {
            emitSpareBytesRefInit(insns, spareBytesRefSlot);
        }

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
            spareBytesRefSlot,
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

        ClassNode classNode = newFusedClass(fusedClassName(caller, "Fused$$BlockLoop"), host);
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "block loop", false);
    }

    /**
     * Compiles the MULTI-VALUE MAPPING block loop for a unary {@code @ConvertEvaluator}-style kernel (e.g.
     * {@code TO_LOWER}/{@code TO_UPPER}) — a kernel that maps over EVERY value of a multi-valued position rather than
     * treating a multi-value as a single-value error. The tree must be a single {@link FusionNode.Kernel} root with
     * exactly one reference (BytesRef) input operand and zero-or-more {@code @Fixed} reference constants, a BytesRef
     * output, and a non-throwing straight-line body. This mirrors the unfused convert evaluator's {@code evalBlock}:
     * an empty position appends null, a single-value position appends one converted value, and a multi-value position
     * appends a converted value per input value inside a {@code beginPositionEntry}/{@code endPositionEntry} — so a
     * multi-valued input yields a multi-valued (not null-with-warning) result, which the ordinary single-value block
     * path cannot reproduce.
     *
     * @throws StitchingException on verification/link/access failure — the caller falls back to the unfused chain
     */
    public Class<?> compileMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return defineOrThrow(caller, emitMappingBlockLoop(caller, tree), "mapping block-loop class");
    }

    private byte[] emitMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        if (tree instanceof FusionNode.Kernel == false) {
            throw new StitchingException("mapping path requires a single convert kernel root", null);
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        Element[] inputElements = inputElements(signature);
        Element outputElement = Element.of(rootReturnType(tree));
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
            throw new StitchingException("mapping path requires a unary BytesRef->BytesRef non-throwing convert kernel", null);
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
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
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

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        desc.append(inputElements[0].blockDescriptor());
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

        // builder = bf.new*BlockBuilder(positionCount); spare = new BytesRef();
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
        emitSpareBytesRefInit(insns, spareSlot);

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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getValueCount", "(I)I", true));
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
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BLOCK, "getFirstValueIndex", "(I)I", true));
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
                loadConstantParam(insns, constant.isReference() ? null : Element.of(argType), constant, constantSlots.get(constant));
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ISTORE), argSlot));
            }
            off += argType.getSize();
        }
        // Run the (non-throwing) convert body; it leaves the converted value on the stack, then append it.
        insns.add(body.computation());
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
        ClassNode classNode = newFusedClass(fusedClassName(caller, "Fused$$MappingBlockLoop"), host);
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "mapping block loop", false);
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
     * <p><b>Both operands evaluated; only the VALUE short-circuits (B3a).</b> Both operand helpers are always called,
     * so each sub-comparison's multi-value warning fires exactly as the unfused {@code BooleanLogicExpressionEvaluator}
     * (which evaluates both sides eagerly over the whole page) would — <b>exact warning parity</b>, matching the
     * arithmetic block path's per-kernel emission rather than the earlier subset. The 3VL result is then a pure
     * function of the two tri-states (a decisive operand — {@code false} for {@code AND}, {@code true} for {@code OR} —
     * still fixes the value without consulting the other), so <em>values and null positions are unchanged</em>; only
     * which warnings fire changed (now equal to, still ⊆, the unfused set).
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
        return defineOrThrow(caller, emitLogicalBlockLoop(caller, tree), "logical block-loop class");
    }

    /**
     * Builds the class bytes for the fused logical block loop: one {@code private static} {@code cmpN} helper per
     * comparison operand plus the top-level {@code fused} loop that calls them and combines their tri-states with the
     * short-circuit 3VL truth tables.
     */
    private byte[] emitLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        // One walk derives arity, warning-source indices and constants-in-emit-order for this emit path.
        FusionSignature signature = FusionSignature.of(tree);
        int arity = signature.arity();
        // INPUT element: the numeric kind of the comparison operands' columns (long/int/double). OUTPUT: nullable boolean.
        Element inputElement = Element.of(logicalInputType(tree));
        Element outputElement = Element.of(Type.BOOLEAN_TYPE);

        String internalName = fusedClassName(caller, "Fused$$LogicalBlockLoop");

        // One helper per comparison operand, in DFS order; the map lets the loop emitter resolve each comparison's call.
        Map<FusionNode, String> helperNames = new IdentityHashMap<>();
        List<MethodNode> helpers = new ArrayList<>();
        assignComparisonHelpers(tree, inputElement, helperNames, helpers);
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
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(constants, frame);
        // Scratch region above the parameters: builder, loop counter, built result, caught-throwable; the tri-state
        // node slots start at workBase.
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int workBase = frame.mark();

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

        ClassNode classNode = newFusedClass(internalName, host);
        classNode.methods.addAll(helpers);
        // Enforce the inlining budget on the top-level loop method (criterion #6). The helpers keep it small even for
        // nested trees; if it somehow exceeds the budget we refuse to fuse (→ unfused fallback) rather than emit an
        // un-inlinable hot body.
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "logical block loop", true);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Filter + Eval mega-fusion (S3.2a): fuse a WHERE predicate + a single EVAL projection into ONE per-position pass.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Fuses a WHERE {@code predicateTree} and a single EVAL {@code projectionTree} into ONE per-position block loop,
     * eliminating the intermediate full-width predicate {@code BooleanBlock} and the {@code Filter → Eval} operator
     * boundary. The emitted {@link #FUSED_METHOD_NAME} method has the shape
     * <pre>{@code
     * static LongBlock fused(BlockFactory bf, Warnings[] warnings, int positionCount, int[] positions,
     *                        <predicate input *Blocks...>, <projection input *Blocks...>,
     *                        <predicate constants...>, <projection constants...>) {
     *     LongBlock.Builder out = bf.newLongBlockBuilder(positionCount);   // COMPACT: one entry per surviving row
     *     int rowCount = 0;
     *     try {
     *         for (int p = 0; p < positionCount; p++) {
     *             int t = pred(warnings, p, <predBlocks>, <predConsts>);   // 3VL: 0 false / 1 true / 2 null
     *             if (t != 1) continue;                                    // false OR null ⇒ row dropped (like Filter)
     *             projAppend(out, warnings, p, <projBlocks>, <projConsts>);// evaluate + append projection (survivors only)
     *             positions[rowCount++] = p;                               // record the surviving source position
     *         }
     *         return out.build();
     *     } finally { out.close(); }
     * }
     * }</pre>
     *
     * <p>The surviving row SET, values and null positions are byte-for-byte those of the unfused
     * {@code FilterOperator(predicate) → EvalOperator(projection)} chain: a position survives iff the predicate is
     * TRUE (3VL {@code false}/{@code null} drop it, exactly as {@link org.elasticsearch.compute.operator.FilterOperator}
     * treats null/multi-value as false), and the projection is evaluated <b>only</b> for surviving rows — so a dropped
     * row emits NO projection warnings, matching the unfused {@code Eval}, which only ever sees survivors. Predicate
     * warnings, by contrast, fire for every position (the {@code pred} helper runs per position), matching the unfused
     * {@code Filter}, which evaluates the predicate over the whole page.
     *
     * <p><b>Reuse of the existing emit machinery.</b> The predicate is evaluated by the same block/logical DFS the
     * standalone predicate paths use — {@link LogicalDfsEmitter} (for a 3VL {@code AND}/{@code OR} tree, with one
     * {@code cmpN} helper per comparison) or {@link BlockDfsEmitter} (for a comparison {@link FusionNode.Kernel}, incl.
     * comparison-over-arithmetic). The projection is evaluated by the arithmetic {@link BlockDfsEmitter} kernel DFS that
     * {@link #emitBlockLoop} uses. To keep the top-level {@code fused} loop small and inlinable (criterion #6 budget
     * guard, ENFORCED here) the two per-position bodies are factored into {@code private static} helpers ({@code pred}
     * returning the tri-state, {@code projAppend} evaluating + appending the projection value-or-null), so the top-level
     * loop only calls them and records survivors. The helpers are themselves below the budget for the representative
     * shapes; the guard refuses to fuse (→ unfused fallback) if the top-level loop ever exceeds it.
     *
     * <p><b>Per-source warnings threading.</b> The runtime {@code Warnings[]} is the predicate's warning sources
     * followed by the projection's (each offset by {@code predWarnCount}), so every kernel registers on the same node
     * source its unfused generated evaluator would — keeping the fused warning set a subset of the unfused chain's.
     *
     * <p><b>Block path only.</b> A 3VL predicate is nullable, so — like {@link #compileBlockLoop} /
     * {@link #compileLogicalBlockLoop} — this defines the hidden class into the caller's own module and there is no
     * vector fast path.
     *
     * @param caller         the caller's lookup (from the module that owns the kernels); the fused class is defined here
     * @param predicateTree  the WHERE predicate tree; a boolean-returning comparison {@link FusionNode.Kernel} or a 3VL
     *                       {@link FusionNode.Logical}
     * @param projectionTree the EVAL projection tree; an arithmetic {@link FusionNode.Kernel} at the root
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, the emitted class fails JVM
     *                            verification/linking, or the top-level loop exceeds the inlining budget — the caller
     *                            should fall back to the unfused {@code Filter → Eval} chain
     */
    public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        return defineOrThrow(caller, emitFilterEvalBlockLoop(caller, predicateTree, projectionTree), "filter-eval block-loop class");
    }

    /**
     * Builds the class bytes for the fused filter+eval block loop: the {@code pred} helper (predicate tri-state), any
     * {@code cmpN} comparison helpers a logical predicate needs, the {@code projAppend} helper (projection value/null
     * append) and the top-level {@code fused} loop that calls them and records surviving positions. Kept separate from
     * {@link #compileFilterEvalBlockLoop} so the verification boundary is exactly the {@code defineHiddenClass} call.
     */
    private byte[] emitFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        // Shape guards: the projection root must be a kernel (a bare column/constant EVAL is not fused here), and the
        // predicate must be a boolean-returning comparison kernel or a 3VL logical tree. An ill-shaped tree throws a
        // bare RuntimeException before the define boundary, which the plan-time caller treats as a fusion failure and
        // falls back to the unfused chain (criterion #5).
        if ((projectionTree instanceof FusionNode.Kernel) == false) {
            throw new IllegalArgumentException("fused filter-eval requires a kernel projection root, not " + projectionTree);
        }
        boolean predicateIsLogical = predicateTree instanceof FusionNode.Logical;
        if (predicateIsLogical == false) {
            if ((predicateTree instanceof FusionNode.Kernel) == false) {
                throw new IllegalArgumentException("fused filter-eval requires a kernel/logical predicate root, not " + predicateTree);
            }
            if (rootReturnType(predicateTree).getSort() != Type.BOOLEAN) {
                throw new IllegalArgumentException("fused filter-eval predicate root must return boolean");
            }
        }

        // One walk per sub-tree derives its arity, constants-in-emit-order and warning-source indices.
        FusionSignature predSignature = FusionSignature.of(predicateTree);
        FusionSignature projSignature = FusionSignature.of(projectionTree);
        int predArity = predSignature.arity();
        int projArity = projSignature.arity();
        Element[] predInputElements = inputElements(predSignature);
        Element predLogicalElement = predicateIsLogical ? Element.of(logicalInputType(predicateTree)) : null;
        Element[] projInputElements = inputElements(projSignature);
        Element projOutputElement = Element.of(rootReturnType(projectionTree));

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

        String internalName = fusedClassName(caller, "Fused$$FilterEvalBlockLoop");

        // Build the helper methods. A logical predicate additionally needs one cmpN helper per comparison operand.
        List<MethodNode> methods = new ArrayList<>();
        Map<FusionNode, String> comparisonHelperNames = new IdentityHashMap<>();
        if (predicateIsLogical) {
            List<MethodNode> comparisonHelpers = new ArrayList<>();
            assignComparisonHelpers(predicateTree, predLogicalElement, comparisonHelperNames, comparisonHelpers);
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
            comparisonHelperNames
        );
        MethodNode projHelper = buildProjectionAppendHelper(
            projectionTree,
            projArity,
            projInputElements,
            projOutputElement,
            projConstants,
            mergedProjSources
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
        Map<FusionNode.Constant, Integer> topPredConstantSlots = allocConstantSlots(predConstants, frame);
        Map<FusionNode.Constant, Integer> topProjConstantSlots = allocConstantSlots(projConstants, frame);
        // Scratch region above the parameters.
        int builderSlot = frame.slot();
        int pSlot = frame.slot();
        int rowCountSlot = frame.slot();
        int resultSlot = frame.slot();
        int excSlot = frame.slot();
        int tSlot = frame.slot();

        StringBuilder desc = new StringBuilder("(L").append(BLOCK_FACTORY).append(";").append(WARNINGS_ARRAY_DESCRIPTOR).append("I[I");
        for (int i = 0; i < predArity; i++) {
            desc.append(predInputElements[i].blockDescriptor());
        }
        for (int i = 0; i < projArity; i++) {
            desc.append(projInputElements[i].blockDescriptor());
        }
        appendConstantParamDescriptors(desc, predConstants);
        appendConstantParamDescriptors(desc, projConstants);
        desc.append(")").append(projOutputElement.blockDescriptor());

        MethodNode host = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            FUSED_METHOD_NAME,
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
                BLOCK_FACTORY,
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
        StringBuilder predCallDesc = new StringBuilder("(").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        for (int i = 0; i < predArity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, predInputBase + i));
            predCallDesc.append(predInputElements[i].blockDescriptor());
        }
        for (FusionNode.Constant constant : predConstants) {
            pushConstantArg(insns, constant.element(), topPredConstantSlots.get(constant));
        }
        appendConstantParamDescriptors(predCallDesc, predConstants);
        predCallDesc.append(")I");
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internalName, "pred", predCallDesc.toString(), false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, tSlot));

        // if (t != 1) skip this position entirely (false OR null ⇒ dropped, no projection evaluated).
        insns.add(new VarInsnNode(Opcodes.ILOAD, tSlot));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));

        // projAppend(out, warnings, p, <projBlocks...>, <projConsts...>); — evaluates + appends the projection value/null.
        StringBuilder projCallDesc = new StringBuilder("(").append(projOutputElement.builderDescriptor())
            .append(WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        insns.add(new VarInsnNode(Opcodes.ALOAD, builderSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, warningsArraySlot));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pSlot));
        for (int i = 0; i < projArity; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, projInputBase + i));
            projCallDesc.append(projInputElements[i].blockDescriptor());
        }
        for (FusionNode.Constant constant : projConstants) {
            pushConstantArg(insns, constant.element(), topProjConstantSlots.get(constant));
        }
        appendConstantParamDescriptors(projCallDesc, projConstants);
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

        ClassNode classNode = newFusedClass(internalName, host);
        classNode.methods.addAll(methods);
        // The two per-position bodies are factored into the pred/projAppend (and cmpN) helpers, so the top-level fused
        // loop stays small and inlinable; enforce the budget on it (→ unfused fallback if a pathological shape exceeds).
        return serializeWithBudgetGuard(classNode, frameComputingWriter(caller), "filter-eval block loop", true);
    }

    /**
     * Builds the {@code private static int pred(Warnings[] w, int p, <input *Blocks>, <constants>)} helper that
     * evaluates the WHERE predicate for one position and returns its tri-state: {@code 0} false, {@code 1} true,
     * {@code 2} null. A {@link FusionNode.Logical} predicate reuses {@link LogicalDfsEmitter} (calling the {@code cmpN}
     * comparison helpers); a comparison {@link FusionNode.Kernel} (incl. comparison-over-arithmetic) reuses
     * {@link BlockDfsEmitter}, returning its boolean value when present and {@code 2} otherwise. Predicate warnings are
     * attributed to each kernel's own source (indices {@code [0, predWarnCount)} of the shared {@code Warnings[]}).
     */
    private MethodNode buildPredicateHelper(
        FusionNode predicateTree,
        int predArity,
        Element[] predInputElements,
        Element predLogicalElement,
        List<FusionNode.Constant> predConstants,
        Map<FusionNode, Integer> predSources,
        String internalName,
        Map<FusionNode, String> comparisonHelperNames
    ) {
        StringBuilder desc = new StringBuilder("(").append(WARNINGS_ARRAY_DESCRIPTOR).append("I");
        for (int i = 0; i < predArity; i++) {
            desc.append(predInputElements[i].blockDescriptor());
        }
        appendConstantParamDescriptors(desc, predConstants);
        desc.append(")I");

        MethodNode pred = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "pred", desc.toString(), null, null);
        InsnList insns = pred.instructions;

        // Params [0..): Warnings[], int p, one *Block per input, one primitive per constant. Then the scratch region.
        FrameLayout frame = new FrameLayout();
        int warningsArraySlot = frame.slot();
        int pSlot = frame.slot();
        int inputBase = frame.slots(predArity);
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(predConstants, frame);
        int countSlot = frame.slot();
        int liveSlot = frame.slot();
        int overflowExcSlot = frame.slot();
        int spareBytesRefSlot = frame.slot();
        int workBase = frame.mark();

        if (predicateTree instanceof FusionNode.Logical) {
            LogicalDfsEmitter emitter = new LogicalDfsEmitter(
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
            if (hasReferenceInput(predInputElements)) {
                emitSpareBytesRefInit(insns, spareBytesRefSlot);
            }
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
                spareBytesRefSlot,
                workBase,
                constantSlots
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
     * Builds the {@code private static void projAppend(<Proj>Block.Builder out, Warnings[] w, int p, <input *Blocks>,
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
        Map<FusionNode, Integer> mergedProjSources
    ) {
        StringBuilder desc = new StringBuilder("(").append(projOutputElement.builderDescriptor())
            .append(WARNINGS_ARRAY_DESCRIPTOR)
            .append("I");
        for (int i = 0; i < projArity; i++) {
            desc.append(projInputElements[i].blockDescriptor());
        }
        appendConstantParamDescriptors(desc, projConstants);
        desc.append(")V");

        MethodNode proj = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "projAppend", desc.toString(), null, null);
        InsnList insns = proj.instructions;

        // Params [0..): builder, Warnings[], int p, one *Block per input, one primitive per constant. Then scratch.
        FrameLayout frame = new FrameLayout();
        int builderSlot = frame.slot();
        int warningsArraySlot = frame.slot();
        int pSlot = frame.slot();
        int inputBase = frame.slots(projArity);
        Map<FusionNode.Constant, Integer> constantSlots = allocConstantSlots(projConstants, frame);
        int countSlot = frame.slot();
        int liveSlot = frame.slot();
        int overflowExcSlot = frame.slot();
        int spareBytesRefSlot = frame.slot();
        int workBase = frame.mark();

        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, liveSlot));
        if (hasReferenceInput(projInputElements)) {
            emitSpareBytesRefInit(insns, spareBytesRefSlot);
        }
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
            spareBytesRefSlot,
            workBase,
            constantSlots
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

    /** Type-correct {@code xLOAD} of a constant argument (by its {@code J}/{@code I}/{@code D} element) from {@code slot}. */
    private static void pushConstantArg(InsnList insns, char element, int slot) {
        int opcode = switch (element) {
            case 'J' -> Opcodes.LLOAD;
            case 'I' -> Opcodes.ILOAD;
            case 'D' -> Opcodes.DLOAD;
            default -> throw new IllegalArgumentException("unsupported fused constant element [" + element + "]");
        };
        insns.add(new VarInsnNode(opcode, slot));
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
        List<FusionNode.Constant> helperConstants = constantsInEmitOrder(comparison);

        StringBuilder desc = new StringBuilder("(L").append(WARNINGS).append(";I");
        for (int i = 0; i < columnOperands; i++) {
            desc.append(inputElement.blockDescriptor());
        }
        appendConstantParamDescriptors(desc, helperConstants);
        desc.append(")I");

        MethodNode helper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, methodName, desc.toString(), null, null);
        InsnList insns = helper.instructions;

        // Params [0..): Warnings, int p, one *Block per column operand, one primitive per constant. Then scratch + body.
        FrameLayout frame = new FrameLayout();
        int warningsSlot = frame.slot();
        int pSlot = frame.slot();
        int blockBase = frame.slots(columnOperands);         // block params occupy [2 .. 2+columnOperands)
        Map<FusionNode.Constant, Integer> helperConstantSlots = allocConstantSlots(helperConstants, frame);
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

    /**
     * Emits {@code <warnings>.registerException(IllegalArgumentException.class, "single-value function encountered
     * multi-value")} — the carried-forward multi-value warning the generated unfused evaluator raises when a scalar
     * kernel meets a multi-valued position. The target {@code Warnings} must already be on top of the operand stack
     * (pushed by {@link #loadWarnings} for the node's own warning-source slot, or a bare {@code ALOAD} of a single
     * {@code Warnings} local in a {@code cmpN} helper); this method consumes it and leaves the stack unchanged
     * otherwise.
     *
     * <p>Unlike the generated unfused evaluator (which currently {@code new IllegalArgumentException(...)}s — see the
     * {@code TODO} in {@code StandardArgument}), the fused hot loop uses the allocation-free
     * {@link #WARNINGS_REGISTER_CLASS_DESCRIPTOR registerException(Class, String)} overload: the class and message are
     * compile-time constants, so a {@code ldc} of the class + the message avoids constructing an exception per
     * multi-valued position. The resulting warning header is identical (that overload is what the {@code Exception}
     * overload delegates to), so the fused-⊆-unfused warning invariant is preserved.
     */
    static void emitMultiValueWarningOn(InsnList insns) {
        insns.add(new LdcInsnNode(Type.getObjectType(ILLEGAL_ARGUMENT_EXCEPTION)));
        insns.add(new LdcInsnNode(MULTI_VALUE_MESSAGE));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WARNINGS, "registerException", WARNINGS_REGISTER_CLASS_DESCRIPTOR, false));
    }

    /**
     * Pushes {@code warnings[index]} onto the operand stack: {@code ALOAD warningsArraySlot; <push index>; AALOAD}. The
     * {@code index} is the node's {@linkplain #warningsSourceIndices structural warning-source slot}, so the loaded
     * {@code Warnings} is the one built from that node's own source.
     */
    static void loadWarnings(InsnList insns, int warningsArraySlot, int index) {
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
    static void loadConstantParam(InsnList insns, Element element, FusionNode.Constant constant, int slot) {
        if (constant.isReference()) {
            // A reference (object) @Fixed constant: loaded with ALOAD from its trailing reference parameter slot. It is
            // not typed by the ambient primitive Element (there is none for e.g. a Locale), so it does not consult it.
            insns.add(new VarInsnNode(Opcodes.ALOAD, slot));
            return;
        }
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
        return FusionSignature.of(tree).constantsInEmitOrder();
    }

    /**
     * Reserves one trailing parameter slot per constant from {@code frame} (in {@link #constantsInEmitOrder canonical
     * emit order}, each sized by its element width) and maps each constant (by identity) to its slot. The frame
     * guarantees a {@code long}/{@code double} constant gets its two slots without hand-rolled arithmetic. Distinct
     * {@link FusionNode.Constant} instances get distinct slots even when their values are equal (two equal-valued
     * literals are still separate leaves), so an {@link IdentityHashMap} keys on the node instance, not value-equality.
     */
    private static Map<FusionNode.Constant, Integer> allocConstantSlots(List<FusionNode.Constant> constants, FrameLayout frame) {
        Map<FusionNode.Constant, Integer> slots = new IdentityHashMap<>();
        for (FusionNode.Constant constant : constants) {
            slots.put(constant, frame.reserve(constantSlotSize(constant)));
        }
        return slots;
    }

    private static int constantSlotSize(FusionNode.Constant constant) {
        return constant.element() == 'J' || constant.element() == 'D' ? 2 : 1;
    }

    /**
     * Appends one JVM parameter descriptor per constant: for a primitive constant the element char {@code J}/{@code I}/
     * {@code D} is itself the descriptor; for a reference (object) {@code @Fixed} constant it is the full
     * {@code L…;} descriptor of its declared type (e.g. {@code Ljava/util/Locale;}).
     */
    private static void appendConstantParamDescriptors(StringBuilder desc, List<FusionNode.Constant> constants) {
        for (FusionNode.Constant constant : constants) {
            if (constant.isReference()) {
                desc.append(Type.getType(constant.refType()).getDescriptor());
            } else {
                desc.append(constant.element());
            }
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
    static void pushInt(InsnList insns, int value) {
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
        return FusionSignature.of(tree).warningSourceIndices();
    }

    /** The number of distinct warning-source slots {@code tree} needs, i.e. the size of the runtime {@code Warnings[]}. */
    public static int warningsSourceCount(FusionNode tree) {
        return FusionSignature.of(tree).warningSourceCount();
    }

    /** The disjoint local slots a {@link BlockDfsEmitter} node evaluates into: a {@code present} flag and its value. */

    /**
     * The per-input {@link Element}, indexed by {@link FusionNode.Input#index()}. With per-node typing a tree may mix
     * element kinds — a leaf reads its column at the element the kernel that consumes it expects (its descriptor's
     * argument type at that operand position), which the planner guarantees equals the column's own element (bridging
     * any mismatch with an explicit cast kernel). So {@code doubleCol + castIntToDouble(intCol)} yields
     * {@code [double, int]}: the fused method reads channel 0 as a {@code double} array and channel 1 as an
     * {@code int} array (the cast body then widens it). Every index in {@code [0, arity)} is populated (the planner
     * assigns input indices densely and each is consumed exactly once).
     */
    private static Element[] inputElements(FusionSignature signature) {
        // The per-input JVM types are derived once, in the FusionSignature walk (with the over-nullify fallback already
        // applied, so every slot is non-null); here we only map each to its bytecode-emission Element — no tree walk.
        Type[] types = signature.inputTypes();
        Element[] elements = new Element[types.length];
        for (int i = 0; i < types.length; i++) {
            elements[i] = Element.of(types[i]);
        }
        return elements;
    }

    /** Whether any input leaf reads a reference element (a {@code BYTES_REF}), i.e. the host must allocate a spare. */
    private static boolean hasReferenceInput(Element[] inputElements) {
        for (Element e : inputElements) {
            if (e != null && e.reference()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits {@code slot = new BytesRef();} — the reusable spare a {@code BYTES_REF} leaf reads into. Called once by a
     * host BEFORE its per-position loop so all positions reuse one spare (matching the generated evaluator's per-eval
     * {@code valScratch}).
     */
    private static void emitSpareBytesRefInit(InsnList insns, int slot) {
        insns.add(new TypeInsnNode(Opcodes.NEW, BYTES_REF));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BYTES_REF, "<init>", "()V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
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
     * The plain-vector path splits an oversize body into a {@code private static compute()} helper (B1,
     * {@link #emitVectorLoopSplit}) rather than refusing; this guard's {@code enforce=true} then applies only to the
     * resulting tiny call-loop. Splitting the checked-vector / block combined bodies the same way remains a follow-up.
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

    byte[] filterEvalBlockLoopBytecode(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        return emitFilterEvalBlockLoop(caller, predicateTree, projectionTree);
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
