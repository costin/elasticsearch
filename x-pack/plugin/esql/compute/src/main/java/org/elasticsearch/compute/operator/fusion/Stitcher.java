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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * method catches those (and the access failure) and rethrows a typed {@link StitchingException} after a DEBUG;
 * the single plan-time WARN is emitted once per shape by
 * {@code FusedExpressionEvaluatorFactory.logPlanTimeStitchFailure} (the cache only supplies
 * {@code StitchingException.isFirstFailure()}), so the plan-time caller (iter 13's {@code EvalMapper}) can catch it
 * and fall back to the unfused evaluator chain without the query path ever seeing an {@code Error}.
 *
 * <p><b>JIT discipline (criterion #6).</b> The emitted {@code fused} method is primitive-only: host inputs are
 * moved into the kernel's argument slots with type-correct {@code xLOAD}/{@code xSTORE} and the kernel body is
 * spliced verbatim, so there is no autoboxing and no per-position {@code checkcast}. For a depth-1 binary kernel
 * the whole method is a handful of instructions — far below HotSpot's default {@code DontCompileHugeMethods}
 * (8000 bytecodes).
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
     * Name of the diagnostic {@code public static final String} ConstantValue field on every fused class.
     * Value is {@code 0=kernelClass#kernelMethod;1=…} in the same post-order as {@code LineNumberTable}
     * lines. Class metadata only — no {@code <clinit>} work beyond the constant, no per-row cost.
     */
    public static final String KERNEL_MAP_FIELD_NAME = "KERNEL_MAP";

    /**
     * ALL-INTERFACE sibling of {@link #FUSED_METHOD_NAME}: typed {@code *Vector.getX(p)} instead of
     * {@code VectorUnsafe} + {@code xALOAD}. Arithmetic plain/checked vector classes still emit both methods so the
     * factory picks per page. The logical vector class (B2) emits <em>only</em> this method — the logical RAW loop
     * was the slow path and is not generated.
     */
    public static final String FUSED_INTERFACE_METHOD_NAME = "fusedInterface";

    /**
     * The overflow-abandoning raw-array sibling of {@link #FUSED_METHOD_NAME} on the checked-vector path. One hidden
     * class carries both the builder-based {@code fused} method (nulls + warnings on overflow) and this {@code fusedRaw}
     * method (dense {@code *ArrayVector} output, {@code return null} on the first declared overflow). The factory
     * invokes {@code fusedRaw} first and falls back to {@code fused} only when a page overflows, so the common
     * no-overflow page never touches a {@code *Block.Builder}. The {@code Warnings[]} parameter is kept so both
     * methods share one descriptor and one invoke-arg scratch; {@code fusedRaw} never reads it.
     */
    public static final String FUSED_RAW_METHOD_NAME = "fusedRaw";

    /**
     * The ALL-INTERFACE sibling of {@link #FUSED_RAW_METHOD_NAME} on the checked-vector path. One hidden class carries
     * both the ALL-RAW {@code fusedRaw} (VectorUnsafe + {@code xALOAD}) and this ALL-INTERFACE {@code fusedRawInterface}
     * (typed {@code *Vector.getX(p)}) so the factory picks per page without a second {@code defineHiddenClass}. Same
     * overflow-abandon contract as {@code fusedRaw}: {@code return null} on the first declared overflow so the caller
     * re-runs the matching builder method.
     */
    public static final String FUSED_RAW_INTERFACE_METHOD_NAME = "fusedRawInterface";

    /**
     * HotSpot's default {@code DontCompileHugeMethods} (criterion #6). A fused method above this many bytecodes
     * is never JIT-compiled at all, which is strictly worse than the unfused chain of small per-expression
     * methods. Public so tests and diagnostics can name the default rather than hardcoding 8000.
     */
    public static final int DEFAULT_MAX_FUSED_METHOD_BYTECODES = 8000;

    /**
     * HotSpot's default {@code DontCompileHugeMethods} huge-method guard (criterion #6). Mutable — not a
     * constant — so it is not SCREAMING_CASE: production reads {@code -Desql.fusion.max_method_bytecodes}
     * (default {@link #DEFAULT_MAX_FUSED_METHOD_BYTECODES}) once at class init; tests flip it via
     * {@link #maxFusedMethodBytecodesForTests}. A method above this many bytecodes is never JIT-compiled.
     *
     * <p>This value governs only the {@code enforce=true} refusals: the plain-vector loop, the logical block
     * loop, and the filter-eval block loop. Over-limit trees throw {@link StitchingException} so the caller
     * falls back unfused. Other emit paths measure and DEBUG-log the size but never refuse. Values {@code <= 0}
     * are rejected: a non-positive limit would refuse every enforced stitch. Not a node setting.
     */
    static volatile int maxFusedMethodBytecodes = resolveMaxFusedMethodBytecodes(
        Integer.getInteger("esql.fusion.max_method_bytecodes", DEFAULT_MAX_FUSED_METHOD_BYTECODES)
    );

    /**
     * Current effective fused-method bytecode huge-method limit (the mutable field, possibly overridden by
     * {@code -Desql.fusion.max_method_bytecodes} or the test setter). Public so tests and diagnostics can read
     * the live limit instead of hardcoding 8000.
     */
    public static int maxFusedMethodBytecodes() {
        return maxFusedMethodBytecodes;
    }

    /**
     * Test-only: sets the fused-method bytecode budget; restore in a finally. Rejects {@code <= 0}. Production reads
     * the system property once into {@link #maxFusedMethodBytecodes}; this setter exists solely for the unit test.
     */
    static void maxFusedMethodBytecodesForTests(int value) {
        maxFusedMethodBytecodes = resolveMaxFusedMethodBytecodes(value);
    }

    /**
     * WHY reject {@code <= 0}: a non-positive limit would refuse every {@code enforce=true} stitch. Shared by
     * the class-init property and the test setter.
     */
    private static int resolveMaxFusedMethodBytecodes(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("esql.fusion.max_method_bytecodes must be > 0, got [" + value + "]");
        }
        return value;
    }

    private final TemplateRegistry templates;
    private final VectorLoopEmitter vectors;
    private final BlockLoopEmitter blocks;
    private final FilterEvalEmitter filterEval;

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
        this.vectors = new VectorLoopEmitter(templates, mn -> {
            if (hostMethodInterceptor != null) {
                hostMethodInterceptor.accept(mn);
            }
        });
        this.blocks = new BlockLoopEmitter(templates, mn -> {
            if (hostMethodInterceptor != null) {
                hostMethodInterceptor.accept(mn);
            }
        });
        this.filterEval = new FilterEvalEmitter(templates, mn -> {
            if (hostMethodInterceptor != null) {
                hostMethodInterceptor.accept(mn);
            }
        }, this.blocks);
    }

    /**
     * Defines {@code bytecode} as a hidden class in the {@code caller}'s module and returns it, mapping the two
     * failure modes of {@link MethodHandles.Lookup#defineHiddenClass} — a {@link LinkageError} (a {@link VerifyError}
     * from the JVM's own verifier is a subtype) and an {@link IllegalAccessException} — to a typed
     * {@link StitchingException} after a DEBUG. The single plan-time WARN is emitted once per shape by
     * {@code FusedExpressionEvaluatorFactory.logPlanTimeStitchFailure} (the cache only supplies
     * {@code StitchingException.isFirstFailure()}; this used to WARN here on every attempt),
     * so the plan-time caller falls back to the unfused chain without the query path ever seeing an {@code Error}.
     * Every {@code compile*} entry point funnels through here, so the define-and-map-failure contract (binding rule
     * #5's failure surface) lives once rather than being copied per path.
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
            logger.debug("fused {} failed to link; falling back to unfused", what, e);
            throw new StitchingException("failed to define fused " + what, e);
        } catch (IllegalAccessException e) {
            logger.debug("caller lookup [{}] cannot define fused {}; falling back to unfused", caller, what, e);
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
        FusedClassDebugInfo debug = FusedClassDebugInfo.ofDescriptor(descriptor.kernelClass().getSimpleName() + "$$Fused", descriptor);
        stripLineNumbers(body.computation());
        LabelNode splice = new LabelNode();
        host.instructions.add(splice);
        host.instructions.add(new LineNumberNode(0, splice));
        host.instructions.add(body.computation());
        host.instructions.add(new InsnNode(body.returnOpcode()));

        // Test-only: corrupt the assembled method to drive the verification-failure fallback. No-op in production.
        if (hostMethodInterceptor != null) {
            hostMethodInterceptor.accept(host);
        }

        ClassNode classNode = newFusedClass(hiddenClassInternalName(caller, descriptor), host, debug);

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
    static String fusedClassName(MethodHandles.Lookup caller, String simpleName) {
        String pkg = caller.lookupClass().getPackageName().replace('.', '/');
        return pkg.isEmpty() ? simpleName : pkg + "/" + simpleName;
    }

    /**
     * A fused hidden-class {@link ClassNode}: {@code public final}, class-file {@code V21}, {@code Object}
     * superclass, carrying {@code host} as its (initial) method. The two helper-emitting paths (logical, filter-eval)
     * add their {@code cmpN}/{@code pred}/{@code projAppend} helper methods afterwards.
     *
     * <p>{@code debug} supplies diagnostic-only class metadata: a {@code SourceFile} naming the fused shape
     * (stack traces render as {@code (SourceFile:kernelIndex)}) and a {@link #KERNEL_MAP_FIELD_NAME}
     * ConstantValue field. Neither adds instructions to the per-row body.
     */
    static ClassNode newFusedClass(String internalName, MethodNode host, FusedClassDebugInfo debug) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        classNode.name = internalName;
        classNode.superName = "java/lang/Object";
        classNode.sourceFile = debug.sourceFile();
        classNode.fields.add(debug.kernelMapField());
        classNode.methods.add(host);
        return classNode;
    }

    /**
     * Marks this kernel splice with a {@code LineNumberTable} entry ({@code line = kernel index}) then
     * concatenates the body. The {@link LabelNode} names the offset of the first body instruction — it
     * is not itself an opcode, so {@code code_length} (and the budget guard) is unchanged.
     */
    static void spliceKernelBody(InsnList insns, InsnList computation, FusionNode.Kernel kernel, FusedClassDebugInfo debug) {
        stripLineNumbers(computation);
        LabelNode mark = new LabelNode();
        insns.add(mark);
        insns.add(new LineNumberNode(debug.indexOf(kernel), mark));
        insns.add(computation);
    }

    /**
     * Drops the original kernel's {@code LineNumberTable} entries so a fused-class stack trace maps
     * through our splice-boundary line (the kernel index) rather than the template's Java source
     * lines under the fused {@code SourceFile} name. Labels stay; this is metadata only.
     */
    static void stripLineNumbers(InsnList insns) {
        AbstractInsnNode insn = insns.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof LineNumberNode) {
                insns.remove(insn);
            }
            insn = next;
        }
    }

    /**
     * A {@link ClassWriter#COMPUTE_FRAMES} writer whose {@code getCommonSuperClass} resolves against the
     * <b>caller's</b> class loader rather than ASM's own. The looped/branching emit paths have control-flow joins at
     * which {@code COMPUTE_FRAMES} may need a common-superclass lookup; the caller's loader can see both the public
     * {@code compute} vector/block types and the spliced kernel bodies' call targets. Our joins never actually merge
     * incompatible reference types, so this is a safety net rather than a hot dependency. (The depth-1 straight-line
     * {@link #emit} has no joins and uses a plain writer.)
     */
    static ClassWriter frameComputingWriter(MethodHandles.Lookup caller) {
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
    static final String BLOCK_FACTORY = "org/elasticsearch/compute/data/BlockFactory";

    /**
     * Internal name of {@code org.elasticsearch.compute.data.VectorUnsafe}, the public static seam through which the
     * fused vector loops read a dense {@code *ArrayVector}'s backing array. The emitted {@code INVOKESTATIC} to it
     * (rather than the package-private {@code INVOKEVIRTUAL rawValues()}) is what lets the vector-loop hidden class be
     * defined in the <b>caller's</b> module — unifying the lookup with the block path and letting non-{@code java.base}
     * kernels (e.g. {@code NumericUtils}) link on the vector path.
     */
    static final String VECTOR_UNSAFE = "org/elasticsearch/compute/data/VectorUnsafe";

    /**
     * Internal name of the {@code compute.data} {@code Vector} base type. The fused method receives its inputs as
     * this common interface and narrows each once — outside the loop — to the concrete {@code *ArrayVector}
     * whose {@code rawValues()} it reads (JIT criterion #6: no per-position {@code checkcast}).
     */
    static final String VECTOR = "org/elasticsearch/compute/data/Vector";

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
    static final String RELEASABLE = "org/elasticsearch/core/Releasable";

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
    static final String WARNINGS_REGISTER_CLASS_DESCRIPTOR = "(Ljava/lang/Class;Ljava/lang/String;)V";

    /**
     * JVM descriptor of a {@code Warnings[]} parameter. The fused block / checked-vector / logical methods take a
     * <b>per-warning-source</b> {@code Warnings} array (indexed by {@link #warningsSourceIndices}) rather than a single
     * {@code Warnings}, so each warning-emitting node registers on the {@code Warnings} built from <em>its own</em> node
     * {@link org.elasticsearch.compute.operator.WarningSourceLocation source} — byte-for-byte the header the generated
     * unfused evaluator for that node would emit (each generated {@code *Evaluator} carries its own node source). This
     * keeps the fused warning headers a <b>subset</b> of the unfused chain's (criterion #1): reached warnings are equal;
     * short-circuit only drops the never-reached ones.
     */
    static final String WARNINGS_ARRAY_DESCRIPTOR = "[L" + WARNINGS + ";";

    /** Internal name of {@code java.lang.IllegalArgumentException}, the multi-value warning the unfused evaluator raises. */
    static final String ILLEGAL_ARGUMENT_EXCEPTION = "java/lang/IllegalArgumentException";

    /**
     * The exact message the generated unfused evaluator uses when a scalar (single-value) function meets a
     * multi-valued position. Mirrored verbatim so the fused block path's carried-forward warning matches.
     */
    static final String MULTI_VALUE_MESSAGE = "single-value function encountered multi-value";

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
     * @param tree   the expression tree to fuse; dense primitive vectors (any kind: {@code *ArrayVector},
     *               {@code Constant*Vector}, {@code *BigArrayVector})
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME} (ALL-RAW) and
     *         {@link #FUSED_INTERFACE_METHOD_NAME} (ALL-INTERFACE)
     * @throws StitchingException if the caller lacks the access to define the hidden class, the emitted class fails JVM
     *                            verification/linking, or the fused loop exceeds the huge-method limit — the caller
     *                            should fall back to the unfused path
     */
    public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return defineOrThrow(caller, emitVectorLoop(caller, tree), "vector-loop class");
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
     * Overflow-aware counterpart to {@link #compileVectorLoop}: fuses a tree of no-null single-valued primitive
     * {@code *ArrayVector} inputs whose kernels are <b>overflow-checked</b> into a single counted loop that reads
     * each input's backing array (the same fast, guard-free read as the plain vector path) but writes
     * through a {@code *Block.Builder} so an overflowing position can become {@code null}. This is the "nulls-capable
     * output" the plain array-vector fast path cannot provide: an {@code ArrayVector} has no null bitmask, whereas
     * the unfused vector-path evaluator (e.g. {@code AddLongsEvaluator.eval(int, LongVector, LongVector)}) already
     * builds a {@code *Block} via a builder and appends {@code null} on a caught {@code ArithmeticException}. One hidden
     * class exposes both methods of the same descriptor:
     * <pre>{@code
     * static LongBlock fused(BlockFactory bf, Warnings[] warnings, int n, Vector in0, Vector in1) {
     *     // builder path: appendLong; on overflow registerException + appendNull
     *     long[] a0 = VectorUnsafe.longs((LongArrayVector) in0);
     *     long[] a1 = VectorUnsafe.longs((LongArrayVector) in1);
     *     LongBlock.Builder out = bf.newLongBlockBuilder(n);
     *     try {
     *         for (int p = 0; p < n; p++) {
     *             try { out.appendLong(process(a0[p], a1[p])); }
     *             catch (ArithmeticException e) { warnings[k].registerException(e); out.appendNull(); }
     *         }
     *         return out.build();
     *     } finally { out.close(); }
     * }
     * static LongBlock fusedRaw(BlockFactory bf, Warnings[] warnings, int n, Vector in0, Vector in1) {
     *     // dense ArrayVector path; warnings[] is unused (uniform binding). On the first overflow return null
     *     // so the caller re-runs fused.
     *     long[] a0 = VectorUnsafe.longs((LongArrayVector) in0);
     *     long[] a1 = VectorUnsafe.longs((LongArrayVector) in1);
     *     try {
     *         long[] out = new long[n];
     *         for (int p = 0; p < n; p++) out[p] = process(a0[p], a1[p]);
     *         return bf.newLongArrayVector(out, n).asBlock();
     *     } catch (ArithmeticException e) { return null; }
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
     * @param tree   the expression tree to fuse; dense no-null single-valued vectors (any kind)
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}/{@link #FUSED_INTERFACE_METHOD_NAME}
     *         (builder, overflow → nulls + warnings; ALL-RAW vs ALL-INTERFACE reads) and
     *         {@link #FUSED_RAW_METHOD_NAME}/{@link #FUSED_RAW_INTERFACE_METHOD_NAME} (dense {@code *ArrayVector},
     *         overflow → {@code null} so the caller re-runs the matching builder method)
     * @throws StitchingException if the caller lacks the access to define the hidden class, or the emitted class
     *                            fails JVM verification/linking — the caller should fall back to the block path or
     *                            unfused chain
     */
    public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return defineOrThrow(caller, emitVectorLoopChecked(caller, tree), "checked vector-loop class");
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

    /**
     * Fuses a <b>logical</b> tree — a three-valued-logic (3VL) {@code AND}/{@code OR} tree ({@link FusionNode.Logical})
     * or a Kleene 3VL {@link FusionNode.Not} — whose operands/child are boolean-producing comparison
     * {@link FusionNode.Kernel}s (from S3.0), nested {@link FusionNode.Logical}s, or nested {@link FusionNode.Not}s —
     * into a single nullable-boolean block loop, materialised as a hidden class (iter 20, S3.1). The emitted
     * {@link #FUSED_METHOD_NAME} has the shape
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
     * <p><b>Why {@code private static} helpers (criterion #6 huge-method guard).</b> Inlining the two per-operand
     * null/multi-value guards (with their multi-value-warning construction) into the loop body inflates the
     * top-level {@code fused} method. Splitting each comparison operand into a tiny {@code private static} helper
     * keeps the top-level loop small even for deeply nested {@code AND}/{@code OR} trees; each helper is itself well
     * under the huge-method limit. The top-level method is still measured against {@link #maxFusedMethodBytecodes}
     * and refuses to fuse (→ unfused fallback) if it ever exceeds it.
     *
     * <p><b>Block path for nulls / multi-values.</b> A 3VL result is nullable, so — like {@link #compileBlockLoop} —
     * this defines the hidden class into the caller's own module (only public exported {@code compute} API and the
     * same-class helper calls are touched; no {@code rawValues()} teleport). The dense 2VL sibling is
     * {@link #compileLogicalVectorLoop}.
     *
     * @param caller the caller's lookup (from the module that owns the comparison kernels); the fused class is defined here
     * @param tree   the logical tree to fuse; its root is a {@link FusionNode.Logical} or {@link FusionNode.Not}
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, the emitted class fails JVM
     *                            verification/linking, or the top-level loop exceeds the huge-method limit — the caller
     *                            should fall back to the unfused path
     */
    public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return defineOrThrow(caller, emitLogicalBlockLoop(caller, tree), "logical block-loop class");
    }

    /**
     * Fuses a 3VL {@code AND}/{@code OR}/{@code NOT} tree into a <b>dense 2VL vector loop</b>: when every input is a
     * vector (no nulls, no multi-values) the result is a plain boolean, so this path writes {@code boolean[]} and wraps
     * it with {@code newBooleanArrayVector(out, n).asBlock()}. The 3VL block path ({@link #compileLogicalBlockLoop})
     * stays for pages that carry nulls or multi-values.
     *
     * <p>INTERFACE-only after B2: the hidden class exposes {@link #FUSED_INTERFACE_METHOD_NAME} and not
     * {@link #FUSED_METHOD_NAME}. Each input is narrowed once to its {@code *Vector} interface and the loop does
     * {@code v.getX(p)}. The logical RAW loop ({@code VectorUnsafe} + {@code a[p]}) was the slow path (iteration-1
     * M10) and is not emitted; arithmetic plain/checked paths still keep RAW. Comparisons are spliced inline in the
     * loop (C1 does not inline helpers &gt; 35 bytes). No {@code Warnings[]} — comparisons do not warn, and dense
     * vectors have no multi-values.
     *
     * @param caller the caller's lookup (from the module that owns the comparison kernels); the fused class is defined here
     * @param tree   the logical tree to fuse; its root is a {@link FusionNode.Logical} or {@link FusionNode.Not}
     * @return the defined hidden {@link Class} exposing {@link #FUSED_INTERFACE_METHOD_NAME} only
     * @throws StitchingException if the caller lacks the access to define the hidden class or the emitted class fails
     *                            JVM verification/linking — the caller should keep the 3VL block path
     */
    public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return defineOrThrow(caller, emitLogicalVectorLoop(caller, tree), "logical vector-loop class");
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
     * standalone predicate paths use — {@link BlockLoopEmitter.LogicalDfsEmitter} (for a 3VL {@code AND}/{@code OR}/{@code NOT} tree,
     * with one {@code cmpN} helper per comparison) or {@link BlockDfsEmitter} (for a comparison
     * {@link FusionNode.Kernel}, including comparison-over-arithmetic). The projection is evaluated by the arithmetic
     * {@link BlockDfsEmitter} kernel DFS that {@link #emitBlockLoop} uses. To keep the top-level {@code fused} loop
     * small and inlinable (criterion #6 budget
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
     * @param predicateTree  the WHERE predicate tree; a boolean-returning comparison {@link FusionNode.Kernel}, a 3VL
     *                       {@link FusionNode.Logical}, or a {@link FusionNode.Not} (FilterEval fuses Not predicates)
     * @param projectionTree the EVAL projection tree; an arithmetic {@link FusionNode.Kernel} at the root
     * @return the defined hidden {@link Class} exposing {@link #FUSED_METHOD_NAME}
     * @throws StitchingException if the caller lacks the access to define the hidden class, the emitted class fails JVM
     *                            verification/linking, or the top-level loop exceeds the huge-method limit — the caller
     *                            should fall back to the unfused {@code Filter → Eval} chain
     */
    public Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        return defineOrThrow(caller, emitFilterEvalBlockLoop(caller, predicateTree, projectionTree), "filter-eval block-loop class");
    }

    /** Type-correct {@code xLOAD} of a constant argument (by its {@code J}/{@code I}/{@code D} element) from {@code slot}. */
    static void pushConstantArg(InsnList insns, char element, int slot) {
        int opcode = switch (element) {
            case 'J' -> Opcodes.LLOAD;
            case 'I' -> Opcodes.ILOAD;
            case 'D' -> Opcodes.DLOAD;
            default -> throw new IllegalArgumentException("unsupported fused constant element [" + element + "]");
        };
        insns.add(new VarInsnNode(opcode, slot));
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
     * Emits a constant leaf at a use site: a <em>baked</em> constant is pushed as a bytecode immediate
     * ({@code LDC}/{@code ICONST}/{@code LCONST}/{@code DCONST}), while a non-baked constant is loaded from its
     * trailing <b>method-parameter</b> slot with the type-correct {@code xLOAD}. A constant leaf reads no array and
     * occupies no {@link FusionNode.Input} slot on any emit path. Non-baked values arrive as runtime arguments so
     * {@code a > 5} and {@code a > 6} share one stitched class; a baked divisor is an immediate so C2 can
     * strength-reduce the division.
     *
     * <p>{@code element} is the ambient element of the tree the constant sits in (the consuming kernel's argument
     * kind). It <b>must</b> equal {@code constant.element()} — the planner guarantees this, and it types the parameter
     * or immediate — so a defensive assertion fails loud here rather than silently emitting a wrong-category load
     * (e.g. an {@code LLOAD} of a {@code long} where the kernel wants an {@code int}), which would only surface
     * downstream as an opaque {@link VerifyError}. {@code slot} is unused (and may be {@code null}) for a baked
     * constant, which has no trailing-param slot.
     */
    static void loadConstantParam(InsnList insns, Element element, FusionNode.Constant constant, Integer slot) {
        if (constant.isBaked()) {
            assert elementSortMatches(element, constant.element())
                : "baked constant element ["
                    + constant.element()
                    + "] does not match the ambient tree element ["
                    + (element == null ? "null" : element.type().getClassName())
                    + "]";
            pushBakedImmediate(insns, constant);
            return;
        }
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

    /** Pushes a baked primitive constant as a bytecode immediate typed to {@code constant.element()}. */
    static void pushBakedImmediate(InsnList insns, FusionNode.Constant constant) {
        switch (constant.element()) {
            case 'I' -> pushInt(insns, (Integer) constant.value());
            case 'J' -> pushLong(insns, (Long) constant.value());
            case 'D' -> pushDouble(insns, (Double) constant.value());
            default -> throw new IllegalArgumentException("baked constant element must be J/I/D but was [" + constant.element() + "]");
        }
    }

    /**
     * The {@link FusionNode.Constant} leaves of {@code tree} in the SINGLE canonical order every emit path visits them:
     * a stable pre-order DFS (a {@link FusionNode.Kernel}'s children left-to-right; a {@link FusionNode.Logical}'s left
     * subtree before its right; a {@link FusionNode.Not}'s child). This is the one source of truth for the trailing
     * constant-<b>parameter</b> order that
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
     * Reserves one trailing parameter slot per <em>non-baked</em> constant from {@code frame} (in
     * {@link #constantsInEmitOrder canonical emit order}, each sized by its element width) and maps each constant
     * (by identity) to its slot. Baked constants consume no slot — they are bytecode immediates. The frame
     * guarantees a {@code long}/{@code double} constant gets its two slots without hand-rolled arithmetic. Distinct
     * {@link FusionNode.Constant} instances get distinct slots even when their values are equal (two equal-valued
     * literals are still separate leaves), so an {@link IdentityHashMap} keys on the node instance, not value-equality.
     */
    static Map<FusionNode.Constant, Integer> allocConstantSlots(List<FusionNode.Constant> constants, FrameLayout frame) {
        Map<FusionNode.Constant, Integer> slots = new IdentityHashMap<>();
        for (FusionNode.Constant constant : constants) {
            if (constant.isBaked()) {
                continue;
            }
            slots.put(constant, frame.reserve(constantSlotSize(constant)));
        }
        return slots;
    }

    static int constantSlotSize(FusionNode.Constant constant) {
        return constant.element() == 'J' || constant.element() == 'D' ? 2 : 1;
    }

    /**
     * Appends one JVM parameter descriptor per <em>non-baked</em> constant: for a primitive constant the element char
     * {@code J}/{@code I}/{@code D} is itself the descriptor; for a reference (object) {@code @Fixed} constant it is
     * the full {@code L…;} descriptor of its declared type (e.g. {@code Ljava/util/Locale;}). Baked constants are
     * immediates and must not appear in the method descriptor.
     */
    static void appendConstantParamDescriptors(StringBuilder desc, List<FusionNode.Constant> constants) {
        for (FusionNode.Constant constant : constants) {
            if (constant.isBaked()) {
                continue;
            }
            if (constant.isReference()) {
                desc.append(Type.getType(constant.refType()).getDescriptor());
            } else {
                desc.append(constant.element());
            }
        }
    }

    /** Whether {@code element}'s JVM sort matches a {@link FusionNode.Constant#element()} descriptor char (J/I/D). */
    static boolean elementSortMatches(Element element, char constantElement) {
        int sort = element.type().getSort();
        return switch (constantElement) {
            case 'J' -> sort == Type.LONG;
            case 'I' -> sort == Type.INT;
            case 'D' -> sort == Type.DOUBLE;
            default -> false;
        };
    }

    /** Pushes an {@code int} constant using the tightest of {@code ICONST}/{@code BIPUSH}/{@code SIPUSH}/{@code LDC}. */
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

    /** Pushes a {@code long} constant using {@code LCONST_0}/{@code LCONST_1}/{@code LDC}. */
    static void pushLong(InsnList insns, long value) {
        if (value == 0L) {
            insns.add(new InsnNode(Opcodes.LCONST_0));
        } else if (value == 1L) {
            insns.add(new InsnNode(Opcodes.LCONST_1));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    /** Pushes a {@code double} constant using {@code DCONST_0}/{@code DCONST_1}/{@code LDC}. */
    static void pushDouble(InsnList insns, double value) {
        if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(0.0)) {
            insns.add(new InsnNode(Opcodes.DCONST_0));
        } else if (value == 1.0) {
            insns.add(new InsnNode(Opcodes.DCONST_1));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    /**
     * Assigns every {@link FusionNode.Kernel} in {@code tree} a distinct <b>warning-source slot</b> (an index into the
     * runtime {@code Warnings[]}), via a stable pre-order walk (a node before its children; a {@link FusionNode.Logical}'s
     * left subtree before its right; a {@link FusionNode.Not}'s child). {@link FusionNode.Input} leaves,
     * {@link FusionNode.Logical} nodes, and {@link FusionNode.Not} nodes are not warning sources and get no slot. Each
     * kernel's slot is where <em>its</em> overflow warning and <em>its</em> direct
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

    /**
     * The per-input {@link Element}, indexed by {@link FusionNode.Input#index()}. With per-node typing a tree may mix
     * element kinds — a leaf reads its column at the element the kernel that consumes it expects (its descriptor's
     * argument type at that operand position), which the planner guarantees equals the column's own element (bridging
     * any mismatch with an explicit cast kernel). So {@code doubleCol + castIntToDouble(intCol)} yields
     * {@code [double, int]}: the fused method reads channel 0 as a {@code double} array and channel 1 as an
     * {@code int} array (the cast body then widens it). Every index in {@code [0, arity)} is populated (the planner
     * assigns input indices densely and each is consumed exactly once).
     */
    static Element[] inputElements(FusionSignature signature) {
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
    static boolean hasReferenceInput(Element[] inputElements) {
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
    static void emitSpareBytesRefInit(InsnList insns, int slot) {
        insns.add(new TypeInsnNode(Opcodes.NEW, BYTES_REF));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BYTES_REF, "<init>", "()V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
    }

    /** The tree's output element type — the return type of the root kernel, or boolean for a Logical/Not root. */
    static Type rootReturnType(FusionNode tree) {
        if (tree instanceof FusionNode.Kernel kernel) {
            return Type.getMethodType(kernel.descriptor().kernelType()).getReturnType();
        }
        if (FusionNode.isBooleanLogic(tree)) {
            return Type.BOOLEAN_TYPE;
        }
        throw new IllegalArgumentException("a fused vector loop requires a kernel, Logical, or Not at the tree root, not a bare input");
    }

    /**
     * Serializes {@code classNode} through {@code writer} and applies the DontCompileHugeMethods huge-method
     * guard (criterion #6) to the emitted {@code fused} loop method's {@code Code} length, measured against
     * {@link #maxFusedMethodBytecodes} (HotSpot's default {@code -XX:DontCompileHugeMethods}, 8000). A method
     * above this limit is never JIT-compiled at all, which is strictly worse than the unfused chain.
     *
     * <p><b>Enforcement scope.</b> {@code enforce} is {@code true} for the <b>plain-vector loop</b>, the
     * <b>logical block loop</b>, and the <b>filter-eval block loop</b>: an over-limit body is refused with a
     * {@link StitchingException} so the caller falls back to the unfused chain. It is {@code false} for the
     * <b>checked-vector</b>, <b>block</b>, <b>SIMD</b>, <b>mapping</b>, and <b>logical-vector</b> paths — those
     * sizes are measured and DEBUG-logged but never refused, because they are correctness fallbacks invoked via
     * {@code MethodHandle} (not a normal inlinable call site). Deep arithmetic over 8000 must not regress the
     * differential suite. An enforced oversize body is logged at DEBUG here; the {@link StitchingException}
     * message carries the measured bytecode size, and the single plan-time WARN is emitted once per shape by
     * {@code FusedExpressionEvaluatorFactory.logPlanTimeStitchFailure}.
     */
    static byte[] serializeWithBudgetGuard(ClassNode classNode, ClassWriter writer, String pathName, boolean enforce)
        throws StitchingException {
        classNode.accept(writer);
        byte[] bytecode = writer.toByteArray();
        int size = fusedMethodCodeLength(bytecode);
        if (size > maxFusedMethodBytecodes) {
            if (enforce) {
                logger.debug(
                    "fused {} method is [{}] bytecodes, over the huge-method limit of [{}]; refusing to fuse (falling back to unfused)",
                    pathName,
                    size,
                    maxFusedMethodBytecodes
                );
                throw new StitchingException(
                    "fused "
                        + pathName
                        + " method ["
                        + size
                        + " bytecodes] exceeds the huge-method limit ["
                        + maxFusedMethodBytecodes
                        + "]",
                    null
                );
            }
            logger.debug(
                "fused {} method is [{}] bytecodes, over the huge-method limit of [{}] (fallback path — not enforced)",
                pathName,
                size,
                maxFusedMethodBytecodes
            );
        }
        return bytecode;
    }

    private byte[] emitVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return vectors.emitVectorLoop(caller, tree);
    }

    private byte[] emitVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison) throws StitchingException {
        return vectors.emitVectorLoopSimd(caller, tree, comparison);
    }

    private byte[] emitVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return vectors.emitVectorLoopChecked(caller, tree);
    }

    private byte[] emitLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return vectors.emitLogicalVectorLoop(caller, tree);
    }

    private byte[] emitBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return blocks.emitBlockLoop(caller, tree);
    }

    private byte[] emitMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return blocks.emitMappingBlockLoop(caller, tree);
    }

    private byte[] emitLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return blocks.emitLogicalBlockLoop(caller, tree);
    }

    private byte[] emitFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        return filterEval.emitFilterEvalBlockLoop(caller, predicateTree, projectionTree);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Test seams (package-private): the raw fused class bytes for each emit path, BEFORE defineHiddenClass verifies
    // them. They let same-package tests (StitcherBudgetTests) measure the emitted fused method's Code length against
    // maxFusedMethodBytecodes and run the bytes through CheckClassAdapter for a structural-validity check on top of
    // the JVM's defineHiddenClass verifier. The enforced paths (plain-vector, logical block, filter-eval) still throw
    // a StitchingException here if they exceed the huge-method limit — exactly as the compile* entry points would.
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

    byte[] logicalVectorLoopBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitLogicalVectorLoop(caller, tree);
    }

    byte[] filterEvalBlockLoopBytecode(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
        throws StitchingException {
        return emitFilterEvalBlockLoop(caller, predicateTree, projectionTree);
    }

    byte[] mappingBlockLoopBytecode(MethodHandles.Lookup caller, FusionNode tree) throws StitchingException {
        return emitMappingBlockLoop(caller, tree);
    }

    byte[] simdVectorLoopBytecode(MethodHandles.Lookup caller, FusionNode tree, String comparison) throws StitchingException {
        return emitVectorLoopSimd(caller, tree, comparison);
    }

    /**
     * Reads the {@code Code} attribute length (the bytecode count C2 measures against
     * {@code DontCompileHugeMethods}) of the {@link #FUSED_METHOD_NAME} method in a just-emitted class. A
     * minimal class-file walk over our own freshly written
     * bytes (no external dependency, no class loading): parse the constant pool for the UTF-8 entries, then scan the
     * method table for {@code fused}'s {@code Code} attribute and return its {@code code_length}. Returns 0 if the
     * method or its {@code Code} attribute is absent (never expected for an emitted loop).
     */
    static int fusedMethodCodeLength(byte[] classBytes) {
        return methodCodeLength(classBytes, FUSED_METHOD_NAME);
    }

    /**
     * Same class-file walk as {@link #fusedMethodCodeLength} for an arbitrary static method name — used to budget
     * {@link #FUSED_RAW_METHOD_NAME} independently of the builder-based {@code fused} sibling on the same class.
     */
    static int methodCodeLength(byte[] classBytes, String methodName) {
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
            boolean isFused = methodName.equals(utf8[nameIndex]);
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
     *
     * <p>{@link #isFirstFailure()} is the cache's "attempted this shape once" signal, not a logging decision.
     * The esql-proper factory uses it to WARN only when fusion is lost entirely (and only on the first failure
     * for that shape); a vector-path degrade that still fuses via the block path stays at DEBUG. Default
     * {@code true}: a stitcher-originated exception is a first failure unless the class cache reconstructed
     * it from a negative-cache marker ({@code firstFailure=false}).
     */
    public static final class StitchingException extends Exception {
        private final boolean firstFailure;

        public StitchingException(String message, Throwable cause) {
            this(message, cause, true);
        }

        public StitchingException(String message, Throwable cause, boolean firstFailure) {
            super(message, cause);
            this.firstFailure = firstFailure;
        }

        /**
         * {@code true} unless this exception was reconstructed from a negative-cache marker. WHY the factory
         * (not the cache) consults this: only the factory knows whether the shape fell back to unfused or
         * still fused via the block path, so the WARN-vs-DEBUG choice lives there.
         */
        public boolean isFirstFailure() {
            return firstFailure;
        }
    }
}
