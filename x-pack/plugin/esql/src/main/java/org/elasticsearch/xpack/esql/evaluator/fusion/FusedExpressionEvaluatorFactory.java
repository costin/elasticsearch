/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

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
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
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
 *       {@code AND}/{@code OR} tree alongside its block path. At eval time, a page whose every input
 *       {@code asVector()} is non-null takes the 2VL vector loop (ALL-RAW or ALL-INTERFACE); otherwise the 3VL
 *       block path.</li>
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
         * of an {@code AND}/{@code OR} tree. Pages with nulls or multi-values still take the 3VL block path.
         */
        LOGICAL_VECTOR,
        /**
         * No vector fast path — always use the block path. BytesRef-in/out trees, mapping kernels, and a vector-path
         * stitch that degraded after the block path already succeeded land here.
         */
        NONE
    }

    /**
     * Test-only: which fused path the last {@link FusedExpressionEvaluator#eval} used. Production dispatch does not
     * read this; the evaluator writes it after choosing a handle so tests can pin ALL-RAW vs ALL-INTERFACE vs the
     * 3VL block fallback. An always-interface regression would otherwise still pass the value differentials.
     */
    enum LastEvalPath {
        /**
         * ALL-RAW: every input was an exact {@code *ArrayVector}. PLAIN/LOGICAL invoke
         * {@link Stitcher#FUSED_METHOD_NAME}; CHECKED invokes {@link Stitcher#FUSED_RAW_METHOD_NAME} first (builder
         * {@code fused} only on overflow).
         */
        RAW,
        /** ALL-INTERFACE: at least one input was a non-array vector; {@link Stitcher#FUSED_INTERFACE_METHOD_NAME}. */
        INTERFACE,
        /** Block path (nulls / multi-values, or no vector strategy). */
        BLOCK
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
    private final MethodHandle blockHandle;
    private final MethodHandle vectorHandle;
    /**
     * The ALL-INTERFACE sibling of {@link #vectorHandle}. Bound for {@link VectorStrategy#LOGICAL_VECTOR},
     * {@link VectorStrategy#PLAIN_VECTOR} (scalar, not SIMD), and {@link VectorStrategy#CHECKED_VECTOR} (the builder
     * {@code fusedInterface}). Null when no interface variant was compiled (block-only, SIMD). The factory picks
     * {@link #vectorHandle} (ALL-RAW) vs this handle per page.
     */
    private final MethodHandle vectorHandleInterface;
    /**
     * The overflow-abandoning raw-array sibling of {@link #vectorHandle}, bound only for
     * {@link VectorStrategy#CHECKED_VECTOR}. Null on every other strategy. Eval invokes this first; a {@code null}
     * {@link Block} means the page overflowed and {@link #vectorHandle} (the builder-based {@code fused} method)
     * re-runs it. Dedicated field — not {@link #vectorHandleInterface} — so LOGICAL_VECTOR's interface handle stays
     * unambiguous.
     */
    private final MethodHandle vectorHandleRaw;
    /**
     * The ALL-INTERFACE sibling of {@link #vectorHandleRaw}, bound only for {@link VectorStrategy#CHECKED_VECTOR}.
     * Eval invokes this first on dense non-array pages; a {@code null} {@link Block} means the page overflowed and
     * {@link #vectorHandleInterface} (builder {@code fusedInterface}) re-runs it.
     */
    private final MethodHandle vectorHandleRawInterface;
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
     * True only for the {@link FusionPlan#mapping()} construction path (TO_LOWER/TO_UPPER). The unfused encoding fast
     * path computes per dictionary entry (and short-circuits constants), so for ordinal/constant inputs the fused
     * per-row loop is both slower and loses the output encoding; the guard restores parity by deferring to the unfused
     * evaluator for those pages only.
     */
    private final boolean mapping;

    private FusedExpressionEvaluatorFactory(
        Source[] warningSources,
        int[] inputChannels,
        ElementKind[] inputElements,
        MethodHandle blockHandle,
        MethodHandle vectorHandle,
        VectorStrategy vectorStrategy,
        ExpressionEvaluator.Factory unfused,
        String shape,
        List<FusionNode.Constant> constants,
        boolean mapping
    ) {
        this(
            warningSources,
            inputChannels,
            inputElements,
            blockHandle,
            vectorHandle,
            null,
            null,
            null,
            vectorStrategy,
            unfused,
            shape,
            constants,
            mapping
        );
    }

    private FusedExpressionEvaluatorFactory(
        Source[] warningSources,
        int[] inputChannels,
        ElementKind[] inputElements,
        MethodHandle blockHandle,
        MethodHandle vectorHandle,
        MethodHandle vectorHandleInterface,
        VectorStrategy vectorStrategy,
        ExpressionEvaluator.Factory unfused,
        String shape,
        List<FusionNode.Constant> constants,
        boolean mapping
    ) {
        this(
            warningSources,
            inputChannels,
            inputElements,
            blockHandle,
            vectorHandle,
            vectorHandleInterface,
            null,
            null,
            vectorStrategy,
            unfused,
            shape,
            constants,
            mapping
        );
    }

    private FusedExpressionEvaluatorFactory(
        Source[] warningSources,
        int[] inputChannels,
        ElementKind[] inputElements,
        MethodHandle blockHandle,
        MethodHandle vectorHandle,
        MethodHandle vectorHandleInterface,
        MethodHandle vectorHandleRaw,
        MethodHandle vectorHandleRawInterface,
        VectorStrategy vectorStrategy,
        ExpressionEvaluator.Factory unfused,
        String shape,
        List<FusionNode.Constant> constants,
        boolean mapping
    ) {
        this.warningSources = warningSources;
        this.inputChannels = inputChannels;
        this.inputElements = inputElements;
        this.blockHandle = blockHandle;
        this.vectorHandle = vectorHandle;
        this.vectorHandleInterface = vectorHandleInterface;
        this.vectorHandleRaw = vectorHandleRaw;
        this.vectorHandleRawInterface = vectorHandleRawInterface;
        this.vectorStrategy = vectorStrategy;
        this.unfused = unfused;
        this.shape = shape;
        this.constants = constants;
        this.mapping = mapping;
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
        try {
            if (plan.mapping()) {
                // Multi-value mapping convert kernel (TO_LOWER/TO_UPPER): block-only (it maps over multi-values, so no
                // vector fast path). Its method signature is the ordinary block shape, so binding is identical.
                compilingPath = FusedClassCache.Path.MAPPING_BLOCK;
                Class<?> mappingClass = compiler.compileMappingBlockLoop(LOOKUP, tree);
                MethodHandle mappingHandle = LOOKUP.findStatic(
                    mappingClass,
                    Stitcher.FUSED_METHOD_NAME,
                    blockType(inputElements, outputElement, arity, constantParamTypes)
                );
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    mappingHandle,
                    null,
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    true
                );
            }
            if (tree instanceof FusionNode.Logical) {
                // A 3VL AND/OR tree always compiles the nullable block path (nulls / multi-values). The dense 2VL
                // vector loop is compiled alongside it; a stitch failure there degrades to block-only rather than
                // dropping fusion entirely (same policy as the arithmetic vector-path degrade).
                compilingPath = FusedClassCache.Path.LOGICAL_BLOCK;
                Class<?> logicalClass = compiler.compileLogicalBlockLoop(LOOKUP, tree);
                MethodHandle logicalHandle = LOOKUP.findStatic(
                    logicalClass,
                    Stitcher.FUSED_METHOD_NAME,
                    blockType(inputElements, outputElement, arity, constantParamTypes)
                );
                try {
                    Class<?> vectorClass = compiler.compileLogicalVectorLoop(LOOKUP, tree);
                    MethodType vectorType = logicalVectorType(arity, constantParamTypes);
                    MethodHandle rawHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, vectorType);
                    MethodHandle interfaceHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, vectorType);
                    return new FusedExpressionEvaluatorFactory(
                        warningSources,
                        inputChannels,
                        inputElements,
                        logicalHandle,
                        rawHandle,
                        interfaceHandle,
                        VectorStrategy.LOGICAL_VECTOR,
                        unfused,
                        shape,
                        constants,
                        false
                    );
                } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError vectorFailure) {
                    logPlanTimeStitchFailure(logger, shape, FusedClassCache.Path.LOGICAL_VECTOR, false, vectorFailure);
                    FusionTelemetry.recordVectorDegraded(shape);
                    return new FusedExpressionEvaluatorFactory(
                        warningSources,
                        inputChannels,
                        inputElements,
                        logicalHandle,
                        null,
                        VectorStrategy.NONE,
                        unfused,
                        shape,
                        constants,
                        false
                    );
                }
            }

            Class<?> blockClass = compiler.compileBlockLoop(LOOKUP, tree);
            MethodHandle blockHandle = LOOKUP.findStatic(
                blockClass,
                Stitcher.FUSED_METHOD_NAME,
                blockType(inputElements, outputElement, arity, constantParamTypes)
            );

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
                    null,
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    false
                );
            }

            MethodHandle vectorHandle;
            MethodHandle vectorHandleInterface = null;
            MethodHandle vectorHandleRaw = null;
            MethodHandle vectorHandleRawInterface = null;
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
                        vectorClass = compiler.compileVectorLoopSimd(LOOKUP, tree, plan.vectorComparison());
                        vectorHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, plainType);
                    } else {
                        vectorClass = compiler.compileVectorLoop(LOOKUP, tree);
                        vectorHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, plainType);
                        vectorHandleInterface = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, plainType);
                    }
                    strategy = VectorStrategy.PLAIN_VECTOR;
                } else {
                    // Overflow-checked trees (including double, whose kernels call NumericUtils.asFiniteNumber) all use
                    // the checked-vector path now: the fused class is defined in the caller's (esql-proper) module —
                    // reaching the backing array through the public VectorUnsafe forwarder rather than a compute.data
                    // teleport — so it links non-java.base kernel callees just like the block path. The old
                    // double+overflow hard gate (VectorStrategy.NONE) is gone. One class carries fused/fusedInterface
                    // (builder) and fusedRaw/fusedRawInterface (dense ArrayVector, abandon on overflow).
                    Class<?> vectorClass = compiler.compileVectorLoopChecked(LOOKUP, tree);
                    MethodType checkedType = checkedVectorType(outputElement, arity, constantParamTypes);
                    vectorHandle = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_METHOD_NAME, checkedType);
                    vectorHandleInterface = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_INTERFACE_METHOD_NAME, checkedType);
                    vectorHandleRaw = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_RAW_METHOD_NAME, checkedType);
                    vectorHandleRawInterface = LOOKUP.findStatic(vectorClass, Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME, checkedType);
                    strategy = VectorStrategy.CHECKED_VECTOR;
                }
            } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError vectorFailure) {
                // The block path already stitched, verified and linked; a vector-fast-path failure must NOT drop the
                // whole fusion back to the unfused chain. The most common cause is the plain-vector inlining-budget
                // guard refusing a deep tree (StitchingException) — the block path is budget-unenforced and still fuses
                // it correctly. Degrade to BLOCK-ONLY (VectorStrategy.NONE, no vector handle) rather than losing all
                // fusion: values/nulls/warnings are identical to the block path, we just forgo the vector fast path.
                logPlanTimeStitchFailure(logger, shape, vectorPath, false, vectorFailure);
                FusionTelemetry.recordVectorDegraded(shape);
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    blockHandle,
                    null,
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constants,
                    false
                );
            }

            return new FusedExpressionEvaluatorFactory(
                warningSources,
                inputChannels,
                inputElements,
                blockHandle,
                vectorHandle,
                vectorHandleInterface,
                vectorHandleRaw,
                vectorHandleRawInterface,
                strategy,
                unfused,
                shape,
                constants,
                false
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
            FusionTelemetry.recordFailure(shape);
            return null;
        }
    }

    /**
     * The single plan-time stitch-failure log site. WHY this lives in the factory, not the cache: only the factory
     * knows whether fusion was lost entirely ({@code unfusedFallback}) or the shape still fused via the block path.
     * The cache supplies {@link Stitcher.StitchingException#isFirstFailure()} so a full-unfused fallback WARNs
     * exactly once per shape; vector/SIMD/logical-vector degrades, and every repeat, stay at DEBUG. Non-cached
     * failures (a {@code findStatic} miss, a test compiler that bypasses the cache) are treated as first failures.
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
        return new FusedExpressionEvaluator(
            warningSources,
            inputChannels,
            inputElements,
            blockHandle,
            vectorHandle,
            vectorHandleInterface,
            vectorHandleRaw,
            vectorHandleRawInterface,
            vectorStrategy,
            unfused,
            context,
            shape,
            resolveConstantValues(constants, context),
            mapping
        );
    }

    /** Test hook (package-private): which vector fast path this shape uses (or {@code NONE} for block-only trees). */
    VectorStrategy vectorStrategy() {
        return vectorStrategy;
    }

    /**
     * Test-only: which fused path the last {@code eval} of {@code evaluator} used. Production dispatch is unchanged.
     * {@code evaluator} must have been produced by {@link #get(DriverContext)}.
     */
    static LastEvalPath lastEvalPath(ExpressionEvaluator evaluator) {
        return ((FusedExpressionEvaluator) evaluator).lastEvalPath;
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
     * The parameter type for each constant (in canonical emit order), for the resolved {@link MethodType}: the
     * primitive class for a primitive constant, or the declared reference type for an object {@code @Fixed} constant
     * (B3b) — matching the {@code L…;} descriptor the Stitcher emitted for it.
     */
    private static Class<?>[] constantParamTypes(List<FusionNode.Constant> constants) {
        Class<?>[] types = new Class<?>[constants.size()];
        for (int i = 0; i < types.length; i++) {
            FusionNode.Constant constant = constants.get(i);
            types[i] = constant.isReference() ? constant.refType() : constantParamClass(constant.element());
        }
        return types;
    }

    /**
     * Resolves each constant operand (in canonical emit order) to the value marshalled into the invoke args for THIS
     * driver: a primitive or captured-reference constant yields its plan-time value, while a per-driver SCRATCH operand
     * (#8b) is built fresh from its supplier with {@code context} — so each driver owns its own mutable buffer (matching
     * the {@code THREAD_LOCAL} {@code @Fixed} contract) and concurrent drivers never share one. Called once per
     * {@code get(DriverContext)}, never per row.
     */
    private static Object[] resolveConstantValues(List<FusionNode.Constant> constants, DriverContext context) {
        Object[] values = new Object[constants.size()];
        for (int i = 0; i < values.length; i++) {
            FusionNode.Constant constant = constants.get(i);
            values[i] = constant.isScratch() ? constant.scratchSupplier().apply(context) : constant.value();
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
     * and {@link VectorStrategy#LOGICAL_VECTOR} all accept any dense vector kind ({@code Constant*Vector},
     * {@code *BigArrayVector}, {@code *ArrayVector} — {@code asVector() != null}): exact {@code *ArrayVector} pages
     * take ALL-RAW, other dense pages take ALL-INTERFACE. Null / multi-value pages take the block path.
     */
    private static final class FusedExpressionEvaluator implements ExpressionEvaluator {

        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FusedExpressionEvaluator.class);

        private final Source[] warningSources;
        private final int[] inputChannels;
        private final ElementKind[] inputElements;
        private final MethodHandle blockHandle;
        private final MethodHandle vectorHandle;
        private final VectorStrategy vectorStrategy;
        private final ExpressionEvaluator.Factory unfusedFactory;
        private final DriverContext driverContext;
        private final String shape;
        // The embedded constants' values, appended (after the input vectors/blocks) to the fused method's invoke args.
        private final Object[] constantValues;
        /**
         * True only for the mapping (TO_LOWER/TO_UPPER) construction path. The unfused encoding fast path computes per
         * dictionary entry (and short-circuits constants), so for ordinal/constant inputs the fused per-row loop is
         * both slower and loses the output encoding; the guard restores parity by deferring to the unfused evaluator
         * for those pages only.
         */
        private final boolean mapping;

        // Spreaders bound ONCE per evaluator: handle.asSpreader(Object[].class, argCount) is resolved here (ctor)
        // rather than re-resolved every page by handle.invokeWithArguments (which does an Invokers.spreadInvoker
        // MethodType map lookup per call). Paired with a reusable per-page scratch Object[] (allocated once, sized
        // per path) to remove the per-page array allocation. Safe because each Factory.get(DriverContext) yields a
        // fresh evaluator and a Driver runs single-threaded — no two threads ever call eval on the same instance
        // (see ExpressionEvaluator's contract) — so this is the idiomatic scratch-reuse pattern.
        private final MethodHandle blockSpreader;
        private final Object[] blockScratch;
        // Null when no vector fast path was compiled (vectorHandle == null).
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
        // Latched true after the first eval-time LinkageError so every later page routes straight to the unfused
        // fallback instead of re-attempting (and re-failing) the fused handle per page.
        private boolean fusionDisabled;
        // Guards close() so a Releasable per-driver scratch buffer is released exactly once (a double close would
        // double-decrement its circuit-breaker reservation).
        private boolean closed;
        /**
         * Test-only: last fused path taken by {@link #eval}. Written after the handle is chosen; never read in
         * production. See {@link LastEvalPath}.
         */
        private LastEvalPath lastEvalPath;

        FusedExpressionEvaluator(
            Source[] warningSources,
            int[] inputChannels,
            ElementKind[] inputElements,
            MethodHandle blockHandle,
            MethodHandle vectorHandle,
            MethodHandle vectorHandleInterface,
            MethodHandle vectorHandleRaw,
            MethodHandle vectorHandleRawInterface,
            VectorStrategy vectorStrategy,
            ExpressionEvaluator.Factory unfusedFactory,
            DriverContext driverContext,
            String shape,
            Object[] constantValues,
            boolean mapping
        ) {
            this.warningSources = warningSources;
            this.inputChannels = inputChannels;
            this.inputElements = inputElements;
            this.blockHandle = blockHandle;
            this.vectorHandle = vectorHandle;
            this.vectorStrategy = vectorStrategy;
            this.unfusedFactory = unfusedFactory;
            this.driverContext = driverContext;
            this.shape = shape;
            this.constantValues = constantValues;
            this.mapping = mapping;

            int arity = inputChannels.length;
            int constCount = constantValues.length;
            // Block path (incl. logical/bytesref): (BlockFactory, Warnings[], int) + inputs + constants.
            int blockArgCount = 3 + arity + constCount;
            this.blockSpreader = blockHandle.asSpreader(Object[].class, blockArgCount);
            this.blockScratch = new Object[blockArgCount];
            if (vectorHandle == null) {
                this.vectorSpreader = null;
                this.vectorScratch = null;
                this.vectorInterfaceSpreader = null;
                this.vectorRawSpreader = null;
                this.vectorRawInterfaceSpreader = null;
            } else {
                // Plain / logical vector: (BlockFactory, int) + vectors + constants. Checked vector: adds Warnings[].
                int vectorArgCount = (vectorStrategy == VectorStrategy.CHECKED_VECTOR ? 3 : 2) + arity + constCount;
                this.vectorSpreader = vectorHandle.asSpreader(Object[].class, vectorArgCount);
                this.vectorScratch = new Object[vectorArgCount];
                this.vectorInterfaceSpreader = vectorHandleInterface == null
                    ? null
                    : vectorHandleInterface.asSpreader(Object[].class, vectorArgCount);
                this.vectorRawSpreader = vectorHandleRaw == null ? null : vectorHandleRaw.asSpreader(Object[].class, vectorArgCount);
                this.vectorRawInterfaceSpreader = vectorHandleRawInterface == null
                    ? null
                    : vectorHandleRawInterface.asSpreader(Object[].class, vectorArgCount);
            }
        }

        @Override
        public Block eval(Page page) {
            if (fusionDisabled) {
                // A prior page already hit an eval-time LinkageError; stay on the unfused chain for the rest of this
                // evaluator's life rather than re-failing the fused handle per page.
                return unfusedFallback().eval(page);
            }
            // Mapping convert kernels (TO_LOWER/TO_UPPER): the unfused encoding fast path computes per dictionary
            // entry (and short-circuits constants), so for ordinal/constant inputs the fused per-row loop is both
            // slower and loses the output encoding. Defer to the unfused evaluator for those pages only.
            if (mapping && inputChannels.length == 1) {
                Block input = page.getBlock(inputChannels[0]);
                if (input instanceof BytesRefBlock block) {
                    if (block.asOrdinals() != null) {
                        return unfusedFallback().eval(page);
                    }
                    BytesRefVector vector = block.asVector();
                    if (vector != null && (vector.isConstant() || vector.asOrdinals() != null)) {
                        return unfusedFallback().eval(page);
                    }
                }
            }
            int arity = inputChannels.length;
            // Borrowed from the page; the page owns them, so we neither incRef nor close them.
            Block[] inputs = new Block[arity];
            for (int i = 0; i < arity; i++) {
                inputs[i] = page.getBlock(inputChannels[i]);
            }
            int positionCount = page.getPositionCount();
            BlockFactory blockFactory = driverContext.blockFactory();
            try {
                if (injectEvalLinkageErrorForTests) {
                    // Test seam: simulate a stitch that verified at plan time but fails to link at first eval.
                    throw new LinkageError("injected eval-time linkage failure for shape [" + shape + "]");
                }
                // Trailing constant arguments follow the input vectors/blocks in the fused method's signature; unbox is
                // handled by invokeWithArguments against the primitive parameter types of the resolved MethodType.
                int constCount = constantValues.length;
                if (vectorHandle != null) {
                    Vector[] dense = denseVectors(inputs);
                    if (dense != null) {
                        Block vectorResult = evalDenseVectorPage(dense, blockFactory, positionCount, arity, constCount);
                        if (vectorResult != null) {
                            return vectorResult;
                        }
                    }
                    lastEvalPath = LastEvalPath.BLOCK;
                }
                // Block path: null/multi-value tolerant, overflow-aware. Also the logical (3VL) fallback and the
                // SIMD-without-interface fallback for non-array dense pages.
                Object[] args = blockScratch;
                args[0] = blockFactory;
                args[1] = warnings();
                args[2] = positionCount;
                System.arraycopy(inputs, 0, args, 3, arity);
                System.arraycopy(constantValues, 0, args, 3 + arity, constCount);
                return (Block) blockSpreader.invoke(args);
            } catch (LinkageError e) {
                // Defensive (criterion #5): a stitch that verified at plan time should never fail to link at eval, but
                // if it somehow does we must still complete the query. Latch fusion off so subsequent pages skip the
                // fused handle entirely, record the failure once, and serve this page from the unfused chain.
                fusionDisabled = true;
                logger.warn(() -> "fused evaluation failed to link for shape [" + shape + "]; falling back to unfused", e);
                FusionTelemetry.recordFailure(shape);
                return unfusedFallback().eval(page);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                // Fusable arithmetic kernels declare no checked exceptions; this is unreachable in practice.
                throw new RuntimeException(t);
            }
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
                case LOGICAL_VECTOR -> evalLogicalVector(dense, exact, blockFactory, positionCount, arity, constCount);
                case PLAIN_VECTOR -> evalPlainVector(dense, exact, blockFactory, positionCount, arity, constCount);
                case CHECKED_VECTOR -> evalCheckedVector(dense, exact, blockFactory, positionCount, arity, constCount);
                case NONE -> null;
            };
        }

        private Block evalLogicalVector(
            Vector[] dense,
            boolean exact,
            BlockFactory blockFactory,
            int positionCount,
            int arity,
            int constCount
        ) throws Throwable {
            lastEvalPath = exact ? LastEvalPath.RAW : LastEvalPath.INTERFACE;
            MethodHandle spreader = exact ? vectorSpreader : vectorInterfaceSpreader;
            Object[] args = vectorScratch;
            args[0] = blockFactory;
            args[1] = positionCount;
            System.arraycopy(dense, 0, args, 2, arity);
            System.arraycopy(constantValues, 0, args, 2 + arity, constCount);
            return (Block) spreader.invoke(args);
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
            lastEvalPath = exact ? LastEvalPath.RAW : LastEvalPath.INTERFACE;
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
            lastEvalPath = exact ? LastEvalPath.RAW : LastEvalPath.INTERFACE;
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
