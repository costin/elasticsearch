/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanArrayVector;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.IntArrayVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.operator.fusion.FusionDump;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.FusionSimd;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link ExpressionEvaluator.Factory} that evaluates a fully-fusable arithmetic subtree by dispatching to a single
 * stitched per-position body (built by the {@link Stitcher}) instead of the tree of generated {@code *Evaluator}s.
 *
 * <p>This class is the plan-time product of {@link FusionPlanner}: given a validated {@link FusionNode} tree, the
 * input channels its leaves read, and the unfused factory chain to fall back to, it stitches and defines the fused
 * hidden class(es) once (cold path) and hands each {@link DriverContext} a {@link FusedExpressionEvaluator} bound to
 * the resulting {@link MethodHandle}s.
 *
 * <h2>Routing</h2>
 * Emit shapes exist on the {@link Stitcher}; which are compiled is fixed at plan time:
 * <ul>
 *   <li><b>Block path</b> ({@link Stitcher#compileBlockLoop}) — always compiled. Null/multi-value tolerant and
 *       overflow-aware; it is the general runtime path and the fallback whenever the inputs are not dense vectors.</li>
 *   <li><b>Plain vector fast path</b> ({@link Stitcher#compileVectorLoop}) — compiled when <b>no</b> kernel is
 *       overflow-checked (so nothing can throw and an {@code ArrayVector} with no null bitmask is a valid output).
 *       Exact {@code *ArrayVector} pages take ALL-RAW {@code fused}; other dense vectors
 *       ({@code Constant*Vector}, {@code *BigArrayVector}) take ALL-INTERFACE {@code fusedInterface}.</li>
 *   <li><b>Checked vector fast path</b> ({@link Stitcher#compileVectorLoopChecked}) — compiled when the tree is
 *       overflow-checked, for <b>every</b> numeric element (including {@code double}). Eval invokes {@code fusedRaw}
 *       (or {@code fusedRawInterface}) first (dense {@code *ArrayVector} output); a {@code null} Block means the page
 *       overflowed and the matching builder-based {@code fused}/{@code fusedInterface} method re-runs that page.</li>
 *   <li><b>Dense logical vector path</b> ({@link Stitcher#compileLogicalVectorLoop}) — compiled for a 3VL
 *       {@code AND}/{@code OR}/{@code NOT} tree alongside its block path. INTERFACE-only after B2: a page whose
 *       every input {@code asVector()} is non-null takes {@code fusedInterface}; otherwise the 3VL block path. The
 *       logical RAW loop is not compiled.</li>
 * </ul>
 * All paths define their hidden class in <b>this</b> (esql-proper) caller module. The vector paths reach a dense
 * {@code *ArrayVector}'s package-private backing array through the public exported
 * {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder ({@code INVOKESTATIC}) rather than teleporting into
 * {@code compute.data} — so an overflow-checked {@code double} kernel body's {@code NumericUtils.asFiniteNumber} call
 * (in {@code org.elasticsearch.xpack.esql.core}, outside {@code java.base}) links on the vector path just as it does on
 * the block path. The old {@code double}+overflow hard gate is therefore gone; {@link VectorStrategy#NONE} now applies
 * to BytesRef-in/out trees, mapping kernels, and a vector-path stitch that degraded after the block path succeeded.
 *
 * <h2>Fallback (acceptance criterion #5)</h2>
 * If any required stitch fails at plan time ({@link Stitcher.StitchingException} / {@link LinkageError}),
 * {@link #tryCreate} records the failure in {@link FusionTelemetry} and returns the unfused factory unchanged —
 * the caller cannot tell fusion was attempted. A first-time full fallback WARNs (the expression is now unfused);
 * a vector-path-only failure that still fuses via the block path stays at DEBUG. Repeats of a negatively-cached
 * shape are DEBUG. As a defensive net, a {@link LinkageError} escaping the fused method at evaluation time is
 * likewise caught by {@link FusedExpressionEvaluator} and re-served from the unfused chain.
 *
 * <h2>Verify mode (test/CI)</h2>
 * When {@link FusionSettings#isVerifyEnabled()} (default {@code Assertions.ENABLED}, i.e. on under {@code -ea}),
 * {@link FusedExpressionEvaluator#eval} also runs the unfused chain on the same page (via
 * {@link DriverContext#ignoringWarnings()}, so comparison eval does not union warnings into the real collector)
 * and fails on any value or encoding difference — see {@link FusionVerify} for the typed-equality / NaN /
 * warning-isolation contracts. The {@code if (isVerifyEnabled())} branch is a {@code static final} read the JIT
 * folds away in production.
 */
final class FusedExpressionEvaluatorFactory implements ExpressionEvaluator.Factory {

    private static final Logger logger = LogManager.getLogger(FusedExpressionEvaluatorFactory.class);

    /**
     * Lookup of <b>this</b> (esql-proper) module. Passed to the {@link Stitcher} so the fused hidden class is defined
     * in a module that can read both {@code compute} (the public block/vector API) and {@code esql-core} (kernel call
     * targets such as {@code NumericUtils}). See binding rule #1.
     */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * Test-only: when {@code true}, the <b>first</b> {@link FusedExpressionEvaluator#eval} on a fresh evaluator throws a
     * {@link LinkageError} instead of invoking the fused handle, exercising the eval-time fallback and the per-evaluator
     * disable latch. Never set in production.
     */
    static volatile boolean injectEvalLinkageErrorForTests = false;

    /** Which vector fast path (if any) was compiled for this shape. */
    enum VectorStrategy {
        /**
         * {@link Stitcher#compileVectorLoop}: dense no-null vectors, non-overflow kernels only. One hidden class,
         * two methods: ALL-RAW {@code fused} and ALL-INTERFACE {@code fusedInterface}.
         */
        PLAIN_VECTOR,
        /**
         * {@link Stitcher#compileVectorLoopChecked}: dense inputs, overflow-checked (any numeric element, incl. double).
         * One hidden class, four methods: {@code fusedRaw}/{@code fusedRawInterface} then builder
         * {@code fused}/{@code fusedInterface} on overflow-abandon.
         */
        CHECKED_VECTOR,
        /**
         * {@link Stitcher#compileLogicalVectorLoop}: dense (any vector kind, no nulls / multi-values) 2VL evaluation
         * of an {@code AND}/{@code OR}/{@code NOT} tree, INTERFACE-only after B2 ({@code fusedInterface}; no RAW
         * sibling). Pages with nulls or multi-values still take the 3VL block path.
         */
        LOGICAL_VECTOR,
        /**
         * No vector fast path — always use the block path. BytesRef-in/out trees, mapping kernels, and a vector-path
         * stitch that degraded after the block path already succeeded land here.
         */
        NONE
    }

    /**
     * The four VECTOR {@link MethodHandle}s bound at plan time. {@code blockHandle} is always present and stays a
     * separate factory parameter. Null components mean that variant was not compiled (block-only, SIMD without
     * INTERFACE, LOGICAL_VECTOR INTERFACE-only). MethodHandles are immutable; the factory spreads them once and
     * shares the spreaders across drivers.
     */
    private record VectorHandles(MethodHandle plain, MethodHandle plainInterface, MethodHandle raw, MethodHandle rawInterface) {
        static VectorHandles none() {
            return new VectorHandles(null, null, null, null);
        }

        /** One VECTOR handle (SIMD ALL-RAW, or LOGICAL_VECTOR INTERFACE-only stored as {@code plain}). */
        static VectorHandles plain(MethodHandle handle) {
            return new VectorHandles(handle, null, null, null);
        }

        /** Scalar PLAIN_VECTOR: ALL-RAW {@code fused} plus ALL-INTERFACE {@code fusedInterface}. */
        static VectorHandles plainInterface(MethodHandle handle, MethodHandle iface) {
            return new VectorHandles(handle, iface, null, null);
        }

        /**
         * CHECKED_VECTOR: overflow-abandoning {@code fusedRaw}/{@code fusedRawInterface} plus builder
         * {@code fused}/{@code fusedInterface}.
         */
        static VectorHandles checked(MethodHandle raw, MethodHandle rawInterface, MethodHandle builder, MethodHandle builderInterface) {
            return new VectorHandles(builder, builderInterface, raw, rawInterface);
        }
    }

    /**
     * Test-only: which fused path the last {@link FusedExpressionEvaluator#eval} used. Production dispatch does not
     * read this. The path is written via {@code assert recordPath(...)} under {@code -ea} (test/verify only), never
     * on the production hot path, so tests can pin ALL-RAW vs ALL-INTERFACE vs the 3VL block fallback. An
     * always-interface regression would otherwise still pass the value differentials.
     */
    enum LastEvalPath {
        /**
         * ALL-RAW: every input was an exact {@code *ArrayVector}. PLAIN invokes {@link Stitcher#FUSED_METHOD_NAME};
         * CHECKED invokes {@link Stitcher#FUSED_RAW_METHOD_NAME} first (builder {@code fused} only on overflow).
         * LOGICAL_VECTOR never takes this path (INTERFACE-only after B2).
         */
        RAW,
        /**
         * ALL-INTERFACE: {@link Stitcher#FUSED_INTERFACE_METHOD_NAME}. Arithmetic: at least one input was a non-array
         * vector. {@link VectorStrategy#LOGICAL_VECTOR} always (INTERFACE-only after B2).
         */
        INTERFACE,
        /** Block path (nulls / multi-values, or no vector strategy). */
        BLOCK,
        /**
         * Encoding-aware path (D1): a single BYTES_REF column with no warning-capable kernel, evaluated once over
         * the ordinal dictionary (or once for a constant) so the fused output keeps ordinal/constant encoding.
         */
        ENCODING_AWARE,
        /**
         * Nested-mapping tree (D3) on a non-vector page (nulls and/or multi-values): the fused single-value block
         * path would warn+null at the parent, so the whole page is evaluated by {@code unfusedFallback()}.
         */
        UNFUSED_FALLBACK
    }

    /**
     * One {@link Source} per warning-emitting node ({@link org.elasticsearch.compute.operator.fusion.FusionNode.Kernel}),
     * indexed by the node's structural warning-source slot
     * ({@link org.elasticsearch.compute.operator.fusion.Stitcher#warningsSourceIndices}). At eval time a matching
     * {@code Warnings[]} is built from these so each fused warning is attributed to the SAME node source the unfused
     * chain's generated evaluator would use — keeping fused warning headers a subset of the unfused ones (criterion #1).
     */
    private final Source[] warningSources;
    private final int[] inputChannels;
    /**
     * The numeric element of each input column, indexed by input (parallel to {@link #inputChannels}). With per-node
     * typing a tree may mix elements (e.g. {@code doubleCol + intCol} reads channel 0 as {@code double} and channel 1
     * as {@code int}). RAW requires every input to be that element's exact {@code *ArrayVector}; INTERFACE accepts
     * any input with {@code asVector()!=null} and reads via typed {@code *Vector} getters.
     */
    private final ElementKind[] inputElements;
    /**
     * Spreaders bound ONCE per factory ({@code handle.asSpreader} is immutable and safe to share across drivers).
     * Each evaluator still allocates its own per-instance scratch {@code Object[]} — a Driver reuses that buffer
     * single-threaded across pages; two drivers must never share one mutable array.
     */
    private final MethodHandle blockSpreader;
    private final MethodHandle vectorSpreader;
    private final MethodHandle vectorInterfaceSpreader;
    private final MethodHandle vectorRawSpreader;
    private final MethodHandle vectorRawInterfaceSpreader;
    private final int blockArgCount;
    private final int vectorArgCount;
    private final VectorStrategy vectorStrategy;
    private final ExpressionEvaluator.Factory unfused;
    private final String shape;
    /**
     * The fused method's trailing constant operands in the {@link Stitcher#constantsInEmitOrder canonical emit order},
     * one per trailing parameter. Each {@code get(DriverContext)} resolves them to values (see
     * {@link #resolveConstantValues}): a primitive/captured-reference constant yields its plan-time value, while a
     * per-driver SCRATCH operand (#8b) is freshly built from its supplier so concurrent drivers never share one mutable
     * buffer. The resolved values are appended (after the input vectors/blocks) to the invoke args at evaluation time.
     */
    private final List<FusionNode.Constant> constants;
    /**
     * When true, {@link FusedExpressionEvaluator#eval} may evaluate ordinal/constant {@code BYTES_REF} pages once
     * over the dictionary (or a single constant position) and rebuild the output encoding. See
     * {@link FusionPlan#encodingAware()}.
     */
    private final boolean encodingAware;
    /**
     * True when a mapping convert kernel sits under a single-value parent (D3). Non-vector pages (nulls and/or
     * multi-values) evaluate via {@code unfusedFallback()} so MV semantics stay exact; dense/ordinal/constant
     * vector pages take the fused + encoding-aware paths.
     */
    private final boolean nestedMapping;

    private FusedExpressionEvaluatorFactory(
        Source[] warningSources,
        int[] inputChannels,
        ElementKind[] inputElements,
        MethodHandle blockHandle,
        VectorHandles vectorHandles,
        VectorStrategy vectorStrategy,
        ExpressionEvaluator.Factory unfused,
        String shape,
        List<FusionNode.Constant> constants,
        boolean encodingAware,
        boolean nestedMapping
    ) {
        this.warningSources = warningSources;
        this.inputChannels = inputChannels;
        this.inputElements = inputElements;
        this.vectorStrategy = vectorStrategy;
        this.unfused = unfused;
        this.shape = shape;
        this.constants = constants;
        this.encodingAware = encodingAware;
        this.nestedMapping = nestedMapping;

        int arity = inputChannels.length;
        // Same skip-baked count as resolveConstantValues(...).length / constantParamTypes(...).length: baked
        // constants live in the bytecode and are not invoke arguments.
        int constCount = invokeArgConstantCount(constants);
        this.blockArgCount = 3 + arity + constCount;
        this.blockSpreader = blockHandle.asSpreader(Object[].class, blockArgCount);
        if (vectorHandles.plain() == null) {
            this.vectorArgCount = 0;
            this.vectorSpreader = null;
            this.vectorInterfaceSpreader = null;
            this.vectorRawSpreader = null;
            this.vectorRawInterfaceSpreader = null;
        } else {
            this.vectorArgCount = (vectorStrategy == VectorStrategy.CHECKED_VECTOR ? 3 : 2) + arity + constCount;
            this.vectorSpreader = vectorHandles.plain().asSpreader(Object[].class, vectorArgCount);
            this.vectorInterfaceSpreader = vectorHandles.plainInterface() == null
                ? null
                : vectorHandles.plainInterface().asSpreader(Object[].class, vectorArgCount);
            this.vectorRawSpreader = vectorHandles.raw() == null ? null : vectorHandles.raw().asSpreader(Object[].class, vectorArgCount);
            this.vectorRawInterfaceSpreader = vectorHandles.rawInterface() == null
                ? null
                : vectorHandles.rawInterface().asSpreader(Object[].class, vectorArgCount);
        }
    }

    /**
     * Stitches {@code tree} into its runtime path(s) and returns a fused factory, or — on any stitch failure that
     * loses fusion entirely — logs a warning (once per shape), records the failure, and returns {@code unfused}
     * unchanged (criterion #5). A vector-path-only failure degrades to block-only fusion at DEBUG. This is the
     * single place the routing decision (which vector fast path, if any, to compile) is made.
     *
     * @param compiler the stitch entry points; the test seam substitutes a failing implementation here
     */
    static ExpressionEvaluator.Factory tryCreate(
        FusionPlan plan,
        ExpressionEvaluator.Factory unfused,
        FusionPlanner.FusedClassCompiler compiler
    ) {
        FusedExpressionEvaluatorFactory fused = tryCreateFusedOrNull(plan, unfused, compiler);
        return fused != null ? fused : unfused;
    }

    /**
     * Stitches {@code tree} and returns the fused factory, or {@code null} on any stitch failure that loses fusion
     * entirely (logging and recording the failure, exactly as {@link #tryCreate}). Unlike {@link #tryCreate} this
     * does <b>not</b> substitute the unfused factory on failure — it returns {@code null} — so the caller can
     * distinguish "fused" from "could not fuse". The adaptive path ({@link AdaptiveFusionEvaluatorFactory}) uses this
     * so a deferred stitch that fails is abandoned (the evaluator stays on its already-running unfused chain) rather
     * than swapped for a fresh unfused factory.
     */
    static FusedExpressionEvaluatorFactory tryCreateFusedOrNull(
        FusionPlan plan,
        ExpressionEvaluator.Factory unfused,
        FusionPlanner.FusedClassCompiler compiler
    ) {
        // Unpack the typed plan once; the emit/bind body below is unchanged.
        Source[] warningSources = plan.warningSources();
        FusionNode tree = plan.tree();
        boolean nestedMapping = FusionPlanner.containsNestedMapping(tree);
        int[] inputChannels = plan.channels();
        ElementKind[] inputElements = plan.inputElements();
        ElementKind outputElement = plan.outputElement();
        boolean overflowChecked = plan.overflowChecked();
        String shape = plan.shape();
        List<FusionNode.Constant> constants = plan.constants();
        // Opt-in SIMD (B2): a non-empty vectorComparison marks a SIMD-eligible boolean comparison; usable only when the
        // node opted in AND the Vector API is available in this runtime. Otherwise the scalar plain-vector path is used.
        boolean useSimd = plan.vectorComparison().isEmpty() == false && FusionSettings.isSimdEnabled() && FusionSimd.available();
        int arity = inputChannels.length;
        // Embedded constants are trailing primitive parameters of the fused method now (iter 30): their types are
        // appended to every resolved MethodType and their values are marshalled into the invoke args, both in the
        // Stitcher's single canonical emit order — so one stitched class serves every constant value.
        Class<?>[] constantParamTypes = constantParamTypes(constants);
        // Tracks which compile path is in flight so the outer catch can name it. Starts as BLOCK (the common
        // arithmetic entry); mapping / logical-block overwrite it before those compiles run.
        FusedClassCache.Path compilingPath = FusedClassCache.Path.BLOCK;
        List<byte[]> captured = null;
        if (FusionSettings.isVerifyEnabled()) {
            captured = new ArrayList<>();
            FusionDump.beginThreadCapture(captured);
        }
        try {
            if (plan.mapping()) {
                // Multi-value mapping convert kernel (TO_LOWER/TO_UPPER): block-only (it maps over multi-values, so no
                // vector fast path). Its method signature is the ordinary block shape, so binding is identical.
                compilingPath = FusedClassCache.Path.MAPPING_BLOCK;
                MethodType mappingType = blockType(inputElements, outputElement, arity, constantParamTypes);
                Class<?> mappingClass = compiler.compileMappingBlockLoop(LOOKUP, tree, mappingType, Stitcher.FUSED_METHOD_NAME);
                MethodHandle mappingHandle = LOOKUP.findStatic(mappingClass, Stitcher.FUSED_METHOD_NAME, mappingType);
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    mappingHandle,
                    VectorHandles.none(),
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    plan.encodingAware(),
                    nestedMapping
                );
            }
            if (FusionNode.isBooleanLogic(tree)) {
                // A 3VL AND/OR/NOT tree always compiles the nullable block path (nulls / multi-values). The dense 2VL
                // vector loop is compiled alongside it; a stitch failure there degrades to block-only rather than
                // dropping fusion entirely (same policy as the arithmetic vector-path degrade).
                compilingPath = FusedClassCache.Path.LOGICAL_BLOCK;
                MethodType logicalType = blockType(inputElements, outputElement, arity, constantParamTypes);
                Class<?> logicalClass = compiler.compileLogicalBlockLoop(LOOKUP, tree, logicalType, Stitcher.FUSED_METHOD_NAME);
                MethodHandle logicalHandle = LOOKUP.findStatic(logicalClass, Stitcher.FUSED_METHOD_NAME, logicalType);
                try {
                    MethodType vectorType = logicalVectorType(arity, constantParamTypes);
                    // B2: INTERFACE-only — resolve fusedInterface, not fused. A stitch/findStatic miss still
                    // degrades to LOGICAL_BLOCK (the block handle already succeeded), not full unfused.
                    Class<?> vectorClass = compiler.compileLogicalVectorLoop(
                        LOOKUP,
                        tree,
                        vectorType,
                        Stitcher.FUSED_INTERFACE_METHOD_NAME
                    );
                    MethodHandle interfaceHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, vectorType);
                    return new FusedExpressionEvaluatorFactory(
                        warningSources,
                        inputChannels,
                        inputElements,
                        logicalHandle,
                        VectorHandles.plain(interfaceHandle),
                        VectorStrategy.LOGICAL_VECTOR,
                        unfused,
                        shape,
                        constants,
                        plan.encodingAware(),
                        nestedMapping
                    );
                } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError vectorFailure) {
                    logPlanTimeStitchFailure(logger, shape, FusedClassCache.Path.LOGICAL_VECTOR, false, vectorFailure);
                    FusionTelemetry.recordVectorDegraded(shape);
                    return new FusedExpressionEvaluatorFactory(
                        warningSources,
                        inputChannels,
                        inputElements,
                        logicalHandle,
                        VectorHandles.none(),
                        VectorStrategy.NONE,
                        unfused,
                        shape,
                        constants,
                        plan.encodingAware(),
                        nestedMapping
                    );
                }
            }

            MethodType blockMethodType = blockType(inputElements, outputElement, arity, constantParamTypes);
            Class<?> blockClass = compiler.compileBlockLoop(LOOKUP, tree, blockMethodType, Stitcher.FUSED_METHOD_NAME);
            MethodHandle blockHandle = LOOKUP.findStatic(blockClass, Stitcher.FUSED_METHOD_NAME, blockMethodType);

            if (hasBytesRefInput(inputElements) || outputElement == ElementKind.BYTES_REF) {
                // A BytesRef (keyword/text) column may be ordinal-encoded rather than a plain ArrayVector, and a
                // variable-width BytesRef OUTPUT (B3b) has no dense array vector at all, so this slice deliberately
                // does NOT compile a vector fast path for either — the tree always runs the block path (BytesRef inputs
                // read via getBytesRef into a reusable spare; a BytesRef output appends through the block builder).
                // Block-only (no vector fast path), like a mapping kernel, but with a real (non-null) block handle.
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    blockHandle,
                    VectorHandles.none(),
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    plan.encodingAware(),
                    nestedMapping
                );
            }

            VectorHandles vectorHandles;
            VectorStrategy strategy;
            // Named before the try so the catch can report which vector path failed (the cache cannot know the
            // consequence, so this factory is the single site that distinguishes degrade-to-block from unfused).
            FusedClassCache.Path vectorPath = overflowChecked == false
                ? (useSimd ? FusedClassCache.Path.SIMD_VECTOR : FusedClassCache.Path.PLAIN_VECTOR)
                : FusedClassCache.Path.CHECKED_VECTOR;
            try {
                if (overflowChecked == false) {
                    Class<?> vectorClass;
                    MethodType plainType = plainVectorType(outputElement, arity, constantParamTypes);
                    if (useSimd) {
                        // SIMD (B2): the plain-vector-shaped Vector-API variant. Grant this module a read edge to
                        // jdk.incubator.vector before defining the hidden class (see FusionSimd), then compile the
                        // lane-compare loop. The MethodType is the plain-vector shape, so eval-time dispatch is
                        // unchanged (VectorStrategy.PLAIN_VECTOR). SIMD stays ALL-RAW (exact *ArrayVector) only —
                        // Constant/*BigArrayVector pages fall through to the block path.
                        FusionSimd.enableReadsFrom(LOOKUP.lookupClass().getModule());
                        vectorClass = compiler.compileVectorLoopSimd(
                            LOOKUP,
                            tree,
                            plan.vectorComparison(),
                            plainType,
                            Stitcher.FUSED_METHOD_NAME
                        );
                        vectorHandles = VectorHandles.plain(LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, plainType));
                    } else {
                        vectorClass = compiler.compileVectorLoop(
                            LOOKUP,
                            tree,
                            plainType,
                            Stitcher.FUSED_METHOD_NAME,
                            Stitcher.FUSED_INTERFACE_METHOD_NAME
                        );
                        vectorHandles = VectorHandles.plainInterface(
                            LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, plainType),
                            LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, plainType)
                        );
                    }
                    strategy = VectorStrategy.PLAIN_VECTOR;
                } else {
                    // Overflow-checked trees (including double, whose kernels call NumericUtils.asFiniteNumber) all use
                    // the checked-vector path now: the fused class is defined in the caller's (esql-proper) module —
                    // reaching the backing array through the public VectorUnsafe forwarder rather than a compute.data
                    // teleport — so it links non-java.base kernel callees just like the block path. The old
                    // double+overflow hard gate (VectorStrategy.NONE) is gone. One class carries fused/fusedInterface
                    // (builder) and fusedRaw/fusedRawInterface (dense ArrayVector, abandon on overflow).
                    MethodType checkedType = checkedVectorType(outputElement, arity, constantParamTypes);
                    Class<?> vectorClass = compiler.compileVectorLoopChecked(
                        LOOKUP,
                        tree,
                        checkedType,
                        Stitcher.FUSED_METHOD_NAME,
                        Stitcher.FUSED_INTERFACE_METHOD_NAME,
                        Stitcher.FUSED_RAW_METHOD_NAME,
                        Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME
                    );
                    MethodHandle builder = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, checkedType);
                    MethodHandle builderInterface = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, checkedType);
                    MethodHandle raw = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_RAW_METHOD_NAME, checkedType);
                    MethodHandle rawInterface = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME, checkedType);
                    vectorHandles = VectorHandles.checked(raw, rawInterface, builder, builderInterface);
                    strategy = VectorStrategy.CHECKED_VECTOR;
                }
            } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError vectorFailure) {
                // The block path already stitched, verified and linked under the same huge-method guard, so its
                // largest method is known to be <= the limit. A vector-fast-path failure (most commonly the huge-method
                // guard refusing a deep tree whose vector body exceeds 8000 while the block body does not) must NOT
                // drop the whole fusion back to the unfused chain. Degrade to BLOCK-ONLY (VectorStrategy.NONE, no
                // vector handle) rather than losing all fusion: values/nulls/warnings are identical to the block path,
                // we just forgo the vector fast path. A block-too-big StitchingException never reaches this catch —
                // compileBlockLoop runs before this try, and that throw lands in the outer catch → unfused.
                logPlanTimeStitchFailure(logger, shape, vectorPath, false, vectorFailure);
                FusionTelemetry.recordVectorDegraded(shape);
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    blockHandle,
                    VectorHandles.none(),
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    plan.encodingAware(),
                    nestedMapping
                );
            }

            return new FusedExpressionEvaluatorFactory(
                warningSources,
                inputChannels,
                inputElements,
                blockHandle,
                vectorHandles,
                strategy,
                unfused,
                shape,
                constants,
                plan.encodingAware(),
                nestedMapping
            );
        } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Any plan-time stitch failure must fall back to the unfused chain and record the shape (criterion #5):
            // - StitchingException / LinkageError: the stitch failed to verify, link, or define.
            // - ReflectiveOperationException (NoSuchMethod / IllegalAccess from findStatic): the emitted method's
            // signature did not match what we resolve for.
            // - RuntimeException: Stitcher.emit* can reject an ill-shaped tree with a bare IllegalArgumentException
            // before the define-hidden-class try-block, and we defensively treat any other unchecked failure the
            // same way — fusion is an optimization and must never break the query.
            // WARN once per shape (firstFailure from the cache) because fusion is lost entirely; repeats DEBUG.
            logPlanTimeStitchFailure(logger, shape, compilingPath, true, e);
            FusionTelemetry.recordFailure(shape, compilingPath);
            return null;
        } finally {
            if (captured != null) {
                FusionDump.endThreadCapture();
                FusionVerify.recordClassBytes(shape, captured);
            }
        }
    }

    /**
     * The single plan-time stitch-failure log site. WHY this lives in the factory, not the cache: only the factory
     * knows whether fusion was lost entirely ({@code unfusedFallback}) or the shape still fused via the block path.
     * The cache supplies {@link Stitcher.StitchingException#isFirstFailure()} so a full-unfused fallback WARNs
     * exactly once per shape; vector/SIMD/logical-vector degrades, and every repeat, stay at DEBUG. Production
     * {@code findStatic} runs inside the cached unit ({@link FusedClassCache#stitchAndResolve}), so a resolution miss
     * is a {@link Stitcher.StitchingException} carrying the cache's first-failure flag. Remaining first-failure
     * defaults are test compilers that bypass the cache, or a 2-arg compile that did not resolve.
     */
    static void logPlanTimeStitchFailure(Logger log, String shape, FusedClassCache.Path path, boolean unfusedFallback, Throwable failure) {
        boolean firstFailure = true;
        if (failure instanceof Stitcher.StitchingException se) {
            firstFailure = se.isFirstFailure();
        }
        if (unfusedFallback && firstFailure) {
            log.warn("fusion stitch failed for shape [{}] on path [{}]; falling back to unfused evaluator chain", shape, path, failure);
        } else if (unfusedFallback) {
            log.debug(
                "fusion stitch previously failed for shape [{}] on path [{}]; falling back to unfused evaluator chain",
                shape,
                path,
                failure
            );
        } else {
            log.debug("fusion stitch failed for shape [{}] on path [{}]; degrading to block-only fusion", shape, path, failure);
        }
    }

    @Override
    public ExpressionEvaluator get(DriverContext context) {
        // Production snapshots the class-init static-final so the per-page branch reads a final instance field
        // assigned from a C2-foldable constant (false unless -Desql.fusion.vector_reads=interface).
        return new FusedExpressionEvaluator(
            warningSources,
            inputChannels,
            inputElements,
            blockSpreader,
            vectorSpreader,
            vectorInterfaceSpreader,
            vectorRawSpreader,
            vectorRawInterfaceSpreader,
            blockArgCount,
            vectorArgCount,
            vectorStrategy,
            unfused,
            context,
            shape,
            resolveConstantValues(constants, context),
            encodingAware,
            nestedMapping,
            FusionSettings.isVectorReadsInterface()
        );
    }

    /**
     * Test-only: like {@link #get} but forces the vector_reads INTERFACE vs RAW choice. WHY this lives here:
     * {@code esql.fusion.vector_reads} is a {@code static final} resolved at class init (so production dispatch is
     * C2-foldable), and a unit test cannot flip it in-process without a mutable static. This constructs an evaluator
     * with the chosen boolean and leaves {@link FusionSettings} untouched. Never called in production.
     */
    ExpressionEvaluator getForTests(DriverContext context, boolean vectorReadsInterface) {
        return new FusedExpressionEvaluator(
            warningSources,
            inputChannels,
            inputElements,
            blockSpreader,
            vectorSpreader,
            vectorInterfaceSpreader,
            vectorRawSpreader,
            vectorRawInterfaceSpreader,
            blockArgCount,
            vectorArgCount,
            vectorStrategy,
            unfused,
            context,
            shape,
            resolveConstantValues(constants, context),
            encodingAware,
            nestedMapping,
            vectorReadsInterface
        );
    }

    /** Test hook (package-private): which vector fast path this shape uses (or {@code NONE} for block-only trees). */
    VectorStrategy vectorStrategy() {
        return vectorStrategy;
    }

    /** Test hook (package-private): the shape identifier used for cache keys and verify-mode dump filenames. */
    String shape() {
        return shape;
    }

    /**
     * Test-only: which fused path the last {@code eval} of {@code evaluator} used. Production dispatch is unchanged.
     * {@code evaluator} must have been produced by {@link #get(DriverContext)}.
     */
    static LastEvalPath lastEvalPath(ExpressionEvaluator evaluator) {
        return ((FusedExpressionEvaluator) evaluator).lastEvalPath;
    }

    /**
     * Test-only: dictionary {@code positionCount} last passed to {@code invokeBlockHandle} from the encoding-aware
     * ordinal path. {@code 0} when that path was not taken. Written under {@code assert} like {@link #lastEvalPath}.
     */
    static int lastEncodingAwareDictPositions(ExpressionEvaluator evaluator) {
        return ((FusedExpressionEvaluator) evaluator).lastEncodingAwareDictPositions;
    }

    /** Test hook (package-private): the unfused factory chain this fused factory falls back to, for differential checks. */
    ExpressionEvaluator.Factory unfusedFactory() {
        return unfused;
    }

    @Override
    public String toString() {
        return "Fused[shape=" + shape + ", strategy=" + vectorStrategy + ", unfused=" + unfused + "]";
    }

    /** Whether any input column is a {@code BYTES_REF} (keyword/text), which forces the block-only route (no vector fast path). */
    private static boolean hasBytesRefInput(ElementKind[] inputElements) {
        for (ElementKind element : inputElements) {
            if (element == ElementKind.BYTES_REF) {
                return true;
            }
        }
        return false;
    }

    private static MethodType blockType(ElementKind[] inputElements, ElementKind outputElement, int arity, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(Warnings[].class);
        params.add(int.class);
        for (int i = 0; i < arity; i++) {
            // Each input *Block is its own numeric element (a tree may mix them via cast kernels); the produced block
            // is the output element (boolean for a comparison root).
            params.add(inputElements[i].blockClass);
        }
        appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(outputElement.blockClass, params);
    }

    private static MethodType checkedVectorType(ElementKind outputElement, int arity, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(Warnings[].class);
        params.add(int.class);
        for (int i = 0; i < arity; i++) {
            // Inputs are passed as the generic Vector interface (narrowed once inside the fused method).
            params.add(Vector.class);
        }
        appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(outputElement.blockClass, params);
    }

    private static MethodType plainVectorType(ElementKind outputElement, int arity, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(int.class);
        for (int i = 0; i < arity; i++) {
            params.add(Vector.class);
        }
        appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(outputElement.vectorClass, params);
    }

    /**
     * Dense-logical vector signature: {@code (BlockFactory, int, Vector..., constants...)BooleanBlock}. Returns a
     * {@link BooleanBlock} (via {@code newBooleanArrayVector(out, n).asBlock()}) rather than a {@link BooleanVector}
     * so eval-time dispatch can {@code (Block) invoke} without a second {@code asBlock()} in Java. No {@code Warnings[]}
     * — comparisons do not warn and dense vectors have no multi-values.
     */
    private static MethodType logicalVectorType(int arity, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(int.class);
        for (int i = 0; i < arity; i++) {
            params.add(Vector.class);
        }
        appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(BooleanBlock.class, params);
    }

    /** Appends the trailing constant primitive parameter types (in canonical emit order) to a MethodType param list. */
    private static void appendConstantParams(List<Class<?>> params, Class<?>[] constantParamTypes) {
        for (Class<?> constantParamType : constantParamTypes) {
            params.add(constantParamType);
        }
    }

    /**
     * How many trailing invoke arguments a fused method takes for constants: non-baked only. Baked constants live
     * in the bytecode. Shared by {@link #constantParamTypes}, {@link #resolveConstantValues}, and the factory-ctor
     * spreader {@code argCount} so the three cannot drift.
     */
    private static int invokeArgConstantCount(List<FusionNode.Constant> constants) {
        int n = 0;
        for (FusionNode.Constant constant : constants) {
            if (constant.isBaked() == false) {
                n++;
            }
        }
        return n;
    }

    /**
     * The parameter type for each <em>non-baked</em> constant (in canonical emit order), for the resolved
     * {@link MethodType}: the primitive class for a primitive constant, or the declared reference type for an object
     * {@code @Fixed} constant (B3b) — matching the {@code L…;} descriptor the Stitcher emitted for it. Baked constants
     * live in the bytecode and are not invoke arguments.
     */
    private static Class<?>[] constantParamTypes(List<FusionNode.Constant> constants) {
        int n = invokeArgConstantCount(constants);
        Class<?>[] types = new Class<?>[n];
        int i = 0;
        for (FusionNode.Constant constant : constants) {
            if (constant.isBaked()) {
                continue;
            }
            types[i++] = constant.isReference() ? constant.refType() : constantParamClass(constant.element());
        }
        return types;
    }

    /**
     * Resolves each <em>non-baked</em> constant operand (in canonical emit order) to the value marshalled into the
     * invoke args for THIS driver: a primitive or captured-reference constant yields its plan-time value, while a
     * per-driver SCRATCH operand (#8b) is built fresh from its supplier with {@code context} — so each driver owns its
     * own mutable buffer (matching the {@code THREAD_LOCAL} {@code @Fixed} contract) and concurrent drivers never share
     * one. Baked constants are bytecode immediates, not invoke arguments. Called once per {@code get(DriverContext)},
     * never per row.
     */
    private static Object[] resolveConstantValues(List<FusionNode.Constant> constants, DriverContext context) {
        Object[] values = new Object[invokeArgConstantCount(constants)];
        int i = 0;
        for (FusionNode.Constant constant : constants) {
            if (constant.isBaked()) {
                continue;
            }
            values[i++] = constant.isScratch() ? constant.scratchSupplier().apply(context) : constant.value();
        }
        return values;
    }

    private static Class<?> constantParamClass(char element) {
        return switch (element) {
            case 'J' -> long.class;
            case 'I' -> int.class;
            case 'D' -> double.class;
            default -> throw new IllegalArgumentException("unsupported fused constant element [" + element + "]");
        };
    }

    /**
     * The concrete {@code compute.data} types for one supported fused element kind. Confines the per-primitive
     * differences (block/vector class for {@link MethodType} construction, the {@code *ArrayVector} class for the
     * runtime vector-eligibility {@code instanceof} check) to one table.
     */
    enum ElementKind {
        LONG(LongBlock.class, LongVector.class, LongArrayVector.class),
        INT(IntBlock.class, IntVector.class, IntArrayVector.class),
        DOUBLE(DoubleBlock.class, DoubleVector.class, DoubleArrayVector.class),
        /** Output-only in this scope: the boolean result of a comparison-rooted tree (a > b, a + b > c, ...). */
        BOOLEAN(BooleanBlock.class, BooleanVector.class, BooleanArrayVector.class),
        /**
         * Input-only in this scope: a keyword/text ({@code BytesRef}) column consumed by a string kernel (e.g.
         * {@code LENGTH}) that returns a primitive/boolean. A {@code BytesRef} column may be ordinal-encoded rather than
         * backed by a plain {@code ArrayVector}, so this slice takes the safe route: a tree with a {@code BYTES_REF}
         * input is routed through the BLOCK path only (no vector fast path). {@code arrayVectorClass} is therefore never
         * consulted for it; it is set to {@link BytesRefVector} purely to satisfy the enum contract.
         */
        BYTES_REF(BytesRefBlock.class, BytesRefVector.class, BytesRefVector.class);

        private final Class<? extends Block> blockClass;
        private final Class<? extends Vector> vectorClass;
        private final Class<? extends Vector> arrayVectorClass;

        ElementKind(Class<? extends Block> blockClass, Class<? extends Vector> vectorClass, Class<? extends Vector> arrayVectorClass) {
            this.blockClass = blockClass;
            this.vectorClass = vectorClass;
            this.arrayVectorClass = arrayVectorClass;
        }

        /** The concrete {@code *Block} class for this element, for resolving fused {@link MethodType}s in sibling factories. */
        Class<? extends Block> blockClass() {
            return blockClass;
        }
    }

    /**
     * The runtime evaluator. Reads its leaf inputs directly from the page's channels (borrowed, never closed), then
     * dispatches to the fused method. Arithmetic {@link VectorStrategy#PLAIN_VECTOR}/{@link VectorStrategy#CHECKED_VECTOR}
     * accept any dense vector kind ({@code Constant*Vector}, {@code *BigArrayVector}, {@code *ArrayVector} —
     * {@code asVector() != null}): exact {@code *ArrayVector} pages take ALL-RAW, other dense pages take ALL-INTERFACE.
     * {@link VectorStrategy#LOGICAL_VECTOR} is INTERFACE-only after B2 (no RAW sibling). Null / multi-value pages
     * take the block path.
     */
    private static final class FusedExpressionEvaluator implements ExpressionEvaluator {

        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FusedExpressionEvaluator.class);

        private final Source[] warningSources;
        private final int[] inputChannels;
        private final ElementKind[] inputElements;
        private final VectorStrategy vectorStrategy;
        private final ExpressionEvaluator.Factory unfusedFactory;
        private final DriverContext driverContext;
        private final String shape;
        // The embedded constants' values, appended (after the input vectors/blocks) to the fused method's invoke args.
        private final Object[] constantValues;
        /**
         * When true, ordinal/constant {@code BYTES_REF} pages are evaluated once over the dictionary (or a single
         * constant position) so the fused output keeps that encoding. See {@link FusionPlan#encodingAware()}.
         */
        private final boolean encodingAware;
        /**
         * True when a mapping convert kernel sits under a single-value parent. Non-vector pages fall back to unfused.
         */
        private final boolean nestedMapping;

        // Spreaders bound ONCE per factory (immutable MethodHandles, shared across drivers). Scratch Object[]s
        // stay per-evaluator: a Driver reuses them single-threaded across pages; two drivers must never share one
        // mutable buffer. Safe because each Factory.get(DriverContext) yields a fresh evaluator and a Driver runs
        // single-threaded — no two threads ever call eval on the same instance (see ExpressionEvaluator's contract).
        private final MethodHandle blockSpreader;
        private final Object[] blockScratch;
        // Null when no vector fast path was compiled (VectorHandles.none()).
        private final MethodHandle vectorSpreader;
        private final Object[] vectorScratch;
        /**
         * ALL-INTERFACE spreader ({@code fusedInterface}). Null when no interface variant was compiled (block-only,
         * SIMD). Shares {@link #vectorScratch}.
         */
        private final MethodHandle vectorInterfaceSpreader;
        /**
         * {@code fusedRaw} spreader. Production {@link VectorStrategy#CHECKED_VECTOR} always binds this together with
         * {@link #vectorSpreader} (a {@code findStatic(fusedRaw)} failure degrades the whole vector path to
         * {@link VectorStrategy#NONE}, not a builder-only CHECKED_VECTOR). Null here is only a belt-and-braces guard
         * for test compilers that emit {@code fused} without {@code fusedRaw}. Shares {@link #vectorScratch} with
         * {@link #vectorSpreader} (same descriptor).
         */
        private final MethodHandle vectorRawSpreader;
        /**
         * {@code fusedRawInterface} spreader. Production {@link VectorStrategy#CHECKED_VECTOR} always binds this
         * together with {@link #vectorInterfaceSpreader}. Shares {@link #vectorScratch}.
         */
        private final MethodHandle vectorRawInterfaceSpreader;

        private Warnings[] warnings;
        // Lazily built only if a defensive LinkageError forces a per-page fallback; closed with this evaluator.
        private ExpressionEvaluator unfusedFallback;
        // Verify-mode only: unfused chain bound to DriverContext.ignoringWarnings() so A/B eval does not union
        // unfused warnings into the driver's collector. Closed with this evaluator. Null when verify is off.
        private ExpressionEvaluator verifyUnfused;
        // Latched true after the first eval-time LinkageError so every later page routes straight to the unfused
        // fallback instead of re-attempting (and re-failing) the fused handle per page.
        private boolean fusionDisabled;
        // Coverage: a runtime unfused fallback (nested-mapping MV, LinkageError latch) is recorded once per instance,
        // not once per page. Always written on the fallback path; recordRuntimeFallback itself is a no-op when off.
        private boolean runtimeFallbackRecorded;
        // Guards close() so a Releasable per-driver scratch buffer is released exactly once (a double close would
        // double-decrement its circuit-breaker reservation).
        private boolean closed;
        /**
         * Test-only: last fused path taken by {@link #eval}. Written via {@link #recordPath} under {@code assert}
         * (tests and verify mode run with {@code -ea}); never read on the production hot path. See {@link LastEvalPath}.
         */
        private LastEvalPath lastEvalPath;

        /**
         * Test-only: dictionary size last evaluated by {@link #evalEncodingAwareOrdinals}. Written via
         * {@link #recordEncodingAwareDictPositions} under {@code assert}; {@code 0} when the ordinal dictionary
         * path was not taken. See {@link #lastEncodingAwareDictPositions(ExpressionEvaluator)}.
         */
        private int lastEncodingAwareDictPositions;

        /**
         * Test/verify side effect: records {@code p} and returns {@code true} so {@code assert recordPath(p)} compiles.
         * Because the write is under {@code assert}, verify ON with assertions OFF ({@code -Desql.fusion.verify=true}
         * without {@code -ea}) leaves {@link #lastEvalPath} unset and {@link FusionVerify} dump filenames fall back
         * to the {@code BLOCK} label — a documented cosmetic limitation of that non-CI combo (CI always runs with
         * {@code -ea}).
         */
        private boolean recordPath(LastEvalPath p) {
            this.lastEvalPath = p;
            return true;
        }

        private boolean recordEncodingAwareDictPositions(int n) {
            this.lastEncodingAwareDictPositions = n;
            return true;
        }

        /**
         * Snapshot of {@link FusionSettings#isVectorReadsInterface()} (a class-init {@code static final}). WHY an
         * instance {@code final} rather than a per-page static/volatile read: production {@link #get} stores the
         * compile-time constant (false unless {@code -Desql.fusion.vector_reads=interface}), so C2 sees a monomorphic
         * evaluator whose field is constant-false and eliminates the {@link #allExactArrayVectors} branch; tests pass
         * the other value through {@link #getForTests} without mutating global state. A9's per-page volatile is gone.
         */
        private final boolean vectorReadsInterface;

        FusedExpressionEvaluator(
            Source[] warningSources,
            int[] inputChannels,
            ElementKind[] inputElements,
            MethodHandle blockSpreader,
            MethodHandle vectorSpreader,
            MethodHandle vectorInterfaceSpreader,
            MethodHandle vectorRawSpreader,
            MethodHandle vectorRawInterfaceSpreader,
            int blockArgCount,
            int vectorArgCount,
            VectorStrategy vectorStrategy,
            ExpressionEvaluator.Factory unfusedFactory,
            DriverContext driverContext,
            String shape,
            Object[] constantValues,
            boolean encodingAware,
            boolean nestedMapping,
            boolean vectorReadsInterface
        ) {
            this.warningSources = warningSources;
            this.inputChannels = inputChannels;
            this.inputElements = inputElements;
            this.vectorStrategy = vectorStrategy;
            this.unfusedFactory = unfusedFactory;
            this.driverContext = driverContext;
            this.shape = shape;
            this.constantValues = constantValues;
            this.encodingAware = encodingAware;
            this.nestedMapping = nestedMapping;
            this.vectorReadsInterface = vectorReadsInterface;
            this.blockSpreader = blockSpreader;
            this.blockScratch = new Object[blockArgCount];
            this.vectorSpreader = vectorSpreader;
            this.vectorInterfaceSpreader = vectorInterfaceSpreader;
            this.vectorRawSpreader = vectorRawSpreader;
            this.vectorRawInterfaceSpreader = vectorRawInterfaceSpreader;
            this.vectorScratch = vectorSpreader == null ? null : new Object[vectorArgCount];
        }

        @Override
        public Block eval(Page page) {
            if (fusionDisabled) {
                // A prior page already hit an eval-time LinkageError; stay on the unfused chain for the rest of this
                // evaluator's life rather than re-failing the fused handle per page.
                recordRuntimeFallbackOnce();
                return unfusedFallback().eval(page);
            }
            int arity = inputChannels.length;
            // Borrowed from the page; the page owns them, so we neither incRef nor close them.
            Block[] inputs = new Block[arity];
            for (int i = 0; i < arity; i++) {
                inputs[i] = page.getBlock(inputChannels[i]);
            }
            // Nested mapping (D3): a non-vector page (nulls and/or multi-values) must keep unfused MV semantics.
            // Fall back when ANY input is a non-vector (not only arity==1) so a refused-but-still-stitched
            // multi-column tree cannot skip the fallback. Dense/ordinal/constant vector pages take the fused
            // + encoding-aware paths below.
            if (nestedMapping) {
                boolean nonVector = false;
                for (int i = 0; i < arity; i++) {
                    if (inputs[i].asVector() == null) {
                        nonVector = true;
                        break;
                    }
                }
                if (nonVector) {
                    assert recordPath(LastEvalPath.UNFUSED_FALLBACK);
                    recordRuntimeFallbackOnce();
                    return unfusedFallback().eval(page);
                }
            }
            int positionCount = page.getPositionCount();
            BlockFactory blockFactory = driverContext.blockFactory();
            Block fused;
            try {
                if (injectEvalLinkageErrorForTests) {
                    // Test seam: simulate a stitch that verified at plan time but fails to link at first eval.
                    throw new LinkageError("injected eval-time linkage failure for shape [" + shape + "]");
                }
                // Trailing constant arguments follow the input vectors/blocks in the fused method's signature; unbox is
                // handled by invokeWithArguments against the primitive parameter types of the resolved MethodType.
                int constCount = constantValues.length;
                fused = null;
                // Encoding-aware (D1): a single BYTES_REF column with no warning-capable kernel. Dense ordinal
                // vectors (and ordinals whose dictionary is no larger than the page) are evaluated once over the
                // dictionary; constants once; sparse ordinals (dictionary larger than the page) and anything else
                // fall through to the normal per-row path.
                if (encodingAware && arity == 1 && inputs[0] instanceof BytesRefBlock bytesRefInput) {
                    assert recordEncodingAwareDictPositions(0);
                    fused = evalEncodingAware(bytesRefInput, blockFactory, positionCount);
                }
                if (fused == null && vectorSpreader != null) {
                    Vector[] dense = denseVectors(inputs);
                    if (dense != null) {
                        fused = evalDenseVectorPage(dense, blockFactory, positionCount, arity, constCount);
                    }
                    if (fused == null) {
                        assert recordPath(LastEvalPath.BLOCK);
                    }
                } else if (fused == null) {
                    assert recordPath(LastEvalPath.BLOCK);
                }
                if (fused == null) {
                    // Block path: null/multi-value tolerant, overflow-aware. Also the logical (3VL) fallback and the
                    // SIMD-without-interface fallback for non-array dense pages.
                    Object[] args = blockScratch;
                    args[0] = blockFactory;
                    args[1] = warnings();
                    args[2] = positionCount;
                    System.arraycopy(inputs, 0, args, 3, arity);
                    System.arraycopy(constantValues, 0, args, 3 + arity, constCount);
                    fused = (Block) blockSpreader.invoke(args);
                }
            } catch (LinkageError e) {
                // Defensive (criterion #5): a stitch that verified at plan time should never fail to link at eval, but
                // if it somehow does we must still complete the query. Latch fusion off so subsequent pages skip the
                // fused handle entirely, record the failure once, and serve this page from the unfused chain.
                fusionDisabled = true;
                logger.warn(() -> "fused evaluation failed to link for shape [" + shape + "]; falling back to unfused", e);
                FusionTelemetry.recordFailure(shape);
                recordRuntimeFallbackOnce();
                return unfusedFallback().eval(page);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                // Fusable arithmetic kernels declare no checked exceptions; this is unreachable in practice.
                throw new RuntimeException(t);
            }
            // Verify-mode A/B: a single static-final read that the JIT folds away in production (no -ea). Input page
            // blocks are not consumed by eval, so the unfused chain can read them again. The comparison block is
            // closed inside FusionVerify; this method returns the fused result. Unfused eval uses ignoringWarnings()
            // so short-circuit-dropped warnings do not union into the driver's collector.
            if (FusionSettings.isVerifyEnabled()) {
                if (FusionVerify.skipForTests) {
                    return fused;
                }
                return FusionVerify.verifyPage(verifyUnfused(), page, fused, shape, vectorStrategy, lastEvalPath);
            }
            return fused;
        }

        /**
         * Encoding-aware fused eval (D1). Returns a result when the input is an ordinal or constant {@code BYTES_REF}
         * vector, else {@code null} so the caller falls through to the normal fused path. Detection order matches the
         * unfused convert evaluator: ordinals first, then constant. Ordinal <em>blocks</em> (nulls in the ordinals)
         * have {@code asVector() == null} and stay on the block path.
         *
         * <p>The ordinal dictionary path is taken only when the ordinals are
         * {@link OrdinalBytesRefVector#isDense() dense} or the dictionary is no larger than the page. After
         * filter/{@code keepMask} the dictionary stays full while the page may have few rows; evaluating the whole
         * dictionary would be {@code O(dictionary)} versus unfused {@code O(rows)}.
         */
        private Block evalEncodingAware(BytesRefBlock input, BlockFactory blockFactory, int positionCount) throws Throwable {
            BytesRefVector vector = input.asVector();
            if (vector == null) {
                return null;
            }
            OrdinalBytesRefVector ordinals = vector.asOrdinals();
            if (ordinals != null) {
                // isDense() is a redundant fast-path signal: dense implies dict <= positionCount/2 <= positionCount,
                // so the size clause alone admits every dense page. Kept because it matches the plan wording.
                if (ordinals.isDense() || ordinals.getDictionaryVector().getPositionCount() <= positionCount) {
                    return evalEncodingAwareOrdinals(ordinals, blockFactory, positionCount);
                }
                return null;
            }
            if (vector.isConstant()) {
                return evalEncodingAwareConstant(vector, blockFactory, positionCount);
            }
            return null;
        }

        /**
         * Evaluate the fused handle once over the dictionary, then wrap (BYTES_REF) or gather (primitive/boolean).
         * {@code dict} is page-owned so it is incRef'd before wrapping as a block; that extra ref is closed with
         * {@code dictBlock}. Result ordinals are incRef'd because the output co-owns them with the page.
         */
        private Block evalEncodingAwareOrdinals(OrdinalBytesRefVector ordinals, BlockFactory blockFactory, int positionCount)
            throws Throwable {
            BytesRefVector dict = ordinals.getDictionaryVector();
            dict.incRef();
            Block dictBlock = dict.asBlock();
            Block dictResult = null;
            try {
                assert recordEncodingAwareDictPositions(dict.getPositionCount());
                dictResult = invokeBlockHandle(dictBlock, dict.getPositionCount());
                Block wrapped = wrapOrGatherEncodingAware(dictResult, ordinals.getOrdinalsVector(), positionCount, blockFactory);
                if (wrapped != null) {
                    assert recordPath(LastEvalPath.ENCODING_AWARE);
                }
                return wrapped;
            } finally {
                Releasables.closeExpectNoException(dictResult, dictBlock);
            }
        }

        /**
         * Evaluate the fused handle on a single constant position, then expand to {@code positionCount}.
         */
        private Block evalEncodingAwareConstant(BytesRefVector vector, BlockFactory blockFactory, int positionCount) throws Throwable {
            BytesRef scratch = new BytesRef();
            Block oneInput = blockFactory.newConstantBytesRefBlockWith(vector.getBytesRef(0, scratch), 1);
            Block oneResult = null;
            try {
                oneResult = invokeBlockHandle(oneInput, 1);
                Vector oneVec = oneResult.asVector();
                if (oneVec == null) {
                    return null;
                }
                Block expanded = expandConstant(oneVec, positionCount, blockFactory);
                assert recordPath(LastEvalPath.ENCODING_AWARE);
                return expanded;
            } finally {
                Releasables.closeExpectNoException(oneResult, oneInput);
            }
        }

        /**
         * Run the same fused block-loop handle used for the normal path, over {@code input} of {@code positionCount}
         * positions (the dictionary, or a 1-position constant).
         */
        private Block invokeBlockHandle(Block input, int positionCount) throws Throwable {
            Object[] args = blockScratch;
            int arity = inputChannels.length;
            args[0] = driverContext.blockFactory();
            args[1] = warnings();
            args[2] = positionCount;
            args[3] = input;
            System.arraycopy(constantValues, 0, args, 3 + arity, constantValues.length);
            return (Block) blockSpreader.invoke(args);
        }

        /**
         * BYTES_REF dictionary result: retain ordinals and wrap as {@link OrdinalBytesRefVector}. Primitive/boolean:
         * gather {@code out[p] = dict[ord[p]]} into a raw array vector. Returns {@code null} when the dictionary
         * evaluation produced nulls (no dense vector) so the caller falls through to the row-wise block path.
         */
        private static Block wrapOrGatherEncodingAware(Block dictResult, IntVector ordinals, int positionCount, BlockFactory blockFactory) {
            Vector dictVec = dictResult.asVector();
            if (dictVec == null) {
                return null;
            }
            if (dictResult instanceof BytesRefBlock) {
                return wrapOrdinalBytesRef((BytesRefVector) dictVec, ordinals);
            }
            return gatherPrimitive(dictVec, ordinals, positionCount, blockFactory);
        }

        /**
         * Transfers an extra ref on {@code ordinals} and {@code dictVec} into a new ordinal vector's block. The
         * {@link OrdinalBytesRefVector#asBlock()} pattern shares those children without a further incRef (same as the
         * unfused convert evaluator). On failure the extra refs are released.
         */
        private static Block wrapOrdinalBytesRef(BytesRefVector dictVec, IntVector ordinals) {
            ordinals.incRef();
            dictVec.incRef();
            boolean success = false;
            try {
                Block result = new OrdinalBytesRefVector(ordinals, dictVec).asBlock();
                success = true;
                return result;
            } finally {
                if (success == false) {
                    Releasables.closeExpectNoException(ordinals, dictVec);
                }
            }
        }

        private static Block gatherPrimitive(Vector dictVec, IntVector ordinals, int positionCount, BlockFactory blockFactory) {
            if (dictVec instanceof BooleanVector dict) {
                boolean[] out = new boolean[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    out[p] = dict.getBoolean(ordinals.getInt(p));
                }
                return blockFactory.newBooleanArrayVector(out, positionCount).asBlock();
            }
            if (dictVec instanceof IntVector dict) {
                int[] out = new int[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    out[p] = dict.getInt(ordinals.getInt(p));
                }
                return blockFactory.newIntArrayVector(out, positionCount).asBlock();
            }
            if (dictVec instanceof LongVector dict) {
                long[] out = new long[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    out[p] = dict.getLong(ordinals.getInt(p));
                }
                return blockFactory.newLongArrayVector(out, positionCount).asBlock();
            }
            if (dictVec instanceof DoubleVector dict) {
                double[] out = new double[positionCount];
                for (int p = 0; p < positionCount; p++) {
                    out[p] = dict.getDouble(ordinals.getInt(p));
                }
                return blockFactory.newDoubleArrayVector(out, positionCount).asBlock();
            }
            throw new AssertionError("unexpected encoding-aware dictionary result " + dictVec.getClass());
        }

        private static Block expandConstant(Vector one, int positionCount, BlockFactory blockFactory) {
            if (one instanceof BytesRefVector v) {
                BytesRef scratch = new BytesRef();
                return blockFactory.newConstantBytesRefBlockWith(v.getBytesRef(0, scratch), positionCount);
            }
            if (one instanceof BooleanVector v) {
                return blockFactory.newConstantBooleanBlockWith(v.getBoolean(0), positionCount);
            }
            if (one instanceof IntVector v) {
                return blockFactory.newConstantIntBlockWith(v.getInt(0), positionCount);
            }
            if (one instanceof LongVector v) {
                return blockFactory.newConstantLongBlockWith(v.getLong(0), positionCount);
            }
            if (one instanceof DoubleVector v) {
                return blockFactory.newConstantDoubleBlockWith(v.getDouble(0), positionCount);
            }
            throw new AssertionError("unexpected encoding-aware constant result " + one.getClass());
        }

        /**
         * Per-page RAW vs INTERFACE dispatch for a dense (no-null, no-multi-value) page. Returns {@code null} when
         * the chosen variant has no spreader (SIMD PLAIN_VECTOR has no interface sibling) so the caller falls through
         * to the block path.
         */
        private Block evalDenseVectorPage(Vector[] dense, BlockFactory blockFactory, int positionCount, int arity, int constCount)
            throws Throwable {
            boolean exact = allExactArrayVectors(dense);
            return switch (vectorStrategy) {
                case LOGICAL_VECTOR -> evalLogicalVector(dense, blockFactory, positionCount, arity, constCount);
                case PLAIN_VECTOR -> evalPlainVector(dense, exact, blockFactory, positionCount, arity, constCount);
                case CHECKED_VECTOR -> evalCheckedVector(dense, exact, blockFactory, positionCount, arity, constCount);
                case NONE -> null;
            };
        }

        /**
         * LOGICAL_VECTOR is INTERFACE-only after B2: always {@code fusedInterface}. Does not consult
         * {@link #allExactArrayVectors} / {@code vectorReadsInterface} — there is no RAW logical spreader to pick.
         */
        private Block evalLogicalVector(Vector[] dense, BlockFactory blockFactory, int positionCount, int arity, int constCount)
            throws Throwable {
            assert recordPath(LastEvalPath.INTERFACE);
            Object[] args = vectorScratch;
            args[0] = blockFactory;
            args[1] = positionCount;
            System.arraycopy(dense, 0, args, 2, arity);
            System.arraycopy(constantValues, 0, args, 2 + arity, constCount);
            return (Block) vectorSpreader.invoke(args);
        }

        private Block evalPlainVector(
            Vector[] dense,
            boolean exact,
            BlockFactory blockFactory,
            int positionCount,
            int arity,
            int constCount
        ) throws Throwable {
            MethodHandle spreader = exact ? vectorSpreader : vectorInterfaceSpreader;
            if (spreader == null) {
                // SIMD compiles PLAIN_VECTOR without an interface sibling; non-array dense pages use the block path.
                return null;
            }
            assert recordPath(exact ? LastEvalPath.RAW : LastEvalPath.INTERFACE);
            Object[] args = vectorScratch;
            args[0] = blockFactory;
            args[1] = positionCount;
            System.arraycopy(dense, 0, args, 2, arity);
            System.arraycopy(constantValues, 0, args, 2 + arity, constCount);
            Vector out = (Vector) spreader.invoke(args);
            return out.asBlock();
        }

        private Block evalCheckedVector(
            Vector[] dense,
            boolean exact,
            BlockFactory blockFactory,
            int positionCount,
            int arity,
            int constCount
        ) throws Throwable {
            MethodHandle rawSpreader = exact ? vectorRawSpreader : vectorRawInterfaceSpreader;
            MethodHandle builderSpreader = exact ? vectorSpreader : vectorInterfaceSpreader;
            if (rawSpreader == null && builderSpreader == null) {
                return null;
            }
            assert recordPath(exact ? LastEvalPath.RAW : LastEvalPath.INTERFACE);
            // Invoke fusedRaw / fusedRawInterface first. A null Block means the page overflowed — abandon the
            // uncharged out[] (GC) and re-run the same args through the matching builder method, which produces the
            // unfused nulls + warnings. The raw methods ignore Warnings[]; they are populated only on the re-run.
            Object[] args = vectorScratch;
            args[0] = blockFactory;
            args[1] = warnings();
            args[2] = positionCount;
            System.arraycopy(dense, 0, args, 3, arity);
            System.arraycopy(constantValues, 0, args, 3 + arity, constCount);
            if (rawSpreader != null) {
                Block raw = (Block) rawSpreader.invoke(args);
                if (raw != null) {
                    return raw;
                }
            }
            if (builderSpreader == null) {
                // Production always binds both; a test compiler emitting only the raw method would NPE here.
                // Returning null lets eval fall through to the block path.
                return null;
            }
            return (Block) builderSpreader.invoke(args);
        }

        /**
         * Returns the input vectors when every input is <b>any</b> vector ({@link Block#asVector()} non-null — no
         * nulls, no multi-values), else {@code null} to route to the block path. Accepts {@code Constant*Vector} /
         * {@code *BigArrayVector} / {@code *ArrayVector}; the caller then picks ALL-RAW vs ALL-INTERFACE.
         */
        private static Vector[] denseVectors(Block[] inputs) {
            Vector[] vectors = new Vector[inputs.length];
            for (int i = 0; i < inputs.length; i++) {
                Vector vector = inputs[i].asVector();
                if (vector == null) {
                    return null;
                }
                vectors[i] = vector;
            }
            return vectors;
        }

        /** True when every vector is the exact {@code *ArrayVector} of its input's element (the ALL-RAW variant). */
        private boolean allExactArrayVectors(Vector[] vectors) {
            // Benchmark/test-only esql.fusion.vector_reads=interface: treat exact *ArrayVector inputs as non-exact
            // so eval takes ALL-INTERFACE (typed *Vector.getX) instead of ALL-RAW (VectorUnsafe). SIMD PLAIN_VECTOR
            // has no interface sibling — forcing non-exact would fall through to BLOCK — so only force when an
            // INTERFACE spreader exists. That keeps the hook on the scalar RAW-vs-INTERFACE choice and leaves SIMD
            // / block-only paths alone. Reads the instance final (production: class-init static-final snapshot),
            // never a volatile.
            if (vectorReadsInterface && (vectorInterfaceSpreader != null || vectorRawInterfaceSpreader != null)) {
                return false;
            }
            for (int i = 0; i < vectors.length; i++) {
                if (inputElements[i].arrayVectorClass.isInstance(vectors[i]) == false) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The per-warning-source {@code Warnings[]} (built lazily, once), indexed by each fusable node's structural
         * warning-source slot. Entry {@code i} is built from {@code warningSources[i]} — the node's own source — so the
         * fused method registers each warning against the same source the unfused chain's generated evaluator would,
         * making reached warnings equal (and, with short-circuit, the fused set a subset).
         */
        private Warnings[] warnings() {
            if (warnings == null) {
                Warnings[] built = new Warnings[warningSources.length];
                for (int i = 0; i < built.length; i++) {
                    built[i] = Warnings.createWarnings(driverContext.warningsMode(), warningSources[i]);
                }
                this.warnings = built;
            }
            return warnings;
        }

        private ExpressionEvaluator unfusedFallback() {
            if (unfusedFallback == null) {
                this.unfusedFallback = unfusedFactory.get(driverContext);
            }
            return unfusedFallback;
        }

        /** Coverage: one runtime-fallback increment per evaluator instance, regardless of how many pages take this path. */
        private void recordRuntimeFallbackOnce() {
            if (runtimeFallbackRecorded) {
                return;
            }
            runtimeFallbackRecorded = true;
            FusionTelemetry.recordRuntimeFallback();
        }

        /**
         * Unfused chain for verify-mode A/B. Bound to {@link DriverContext#ignoringWarnings()} so comparison
         * eval does not register into the real warning collector (see {@link FusionVerify}).
         */
        private ExpressionEvaluator verifyUnfused() {
            if (verifyUnfused == null) {
                this.verifyUnfused = unfusedFactory.get(driverContext.ignoringWarnings());
            }
            return verifyUnfused;
        }

        @Override
        public long baseRamBytesUsed() {
            return BASE_RAM_BYTES_USED;
        }

        @Override
        public void close() {
            if (closed) {
                return; // idempotent: never release a breaker-accounted scratch twice
            }
            closed = true;
            if (unfusedFallback != null) {
                unfusedFallback.close();
            }
            if (verifyUnfused != null) {
                verifyUnfused.close();
            }
            // Release any per-driver SCRATCH @Fixed buffer this evaluator owns that is Releasable. Today's LEFT scratch
            // (BytesRef / UTF8CodePoint) is not Releasable so it is skipped, but SPACE's BreakingBytesRefBuilder is a
            // circuit-breaker-accounted Releasable owned per driver and MUST be freed here or its breaker reservation
            // leaks. Captured reference constants (e.g. a Locale) and boxed primitives are never Releasable.
            for (Object constantValue : constantValues) {
                if (constantValue instanceof Releasable releasable) {
                    Releasables.closeExpectNoException(releasable);
                }
            }
        }

        @Override
        public String toString() {
            return "Fused[shape=" + shape + ", strategy=" + vectorStrategy + "]";
        }
    }
}
