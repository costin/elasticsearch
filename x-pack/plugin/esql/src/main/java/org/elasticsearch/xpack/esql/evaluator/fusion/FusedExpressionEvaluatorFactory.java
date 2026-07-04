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
import org.elasticsearch.compute.operator.fusion.Stitcher;
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
 * Three emit shapes exist on the {@link Stitcher}; which are compiled is fixed at plan time:
 * <ul>
 *   <li><b>Block path</b> ({@link Stitcher#compileBlockLoop}) — always compiled. Null/multi-value tolerant and
 *       overflow-aware; it is the general runtime path and the fallback whenever the inputs are not dense vectors.</li>
 *   <li><b>Plain vector fast path</b> ({@link Stitcher#compileVectorLoop}) — compiled when <b>no</b> kernel is
 *       overflow-checked (so nothing can throw and an {@code ArrayVector} with no null bitmask is a valid output).</li>
 *   <li><b>Checked vector fast path</b> ({@link Stitcher#compileVectorLoopChecked}) — compiled when the tree is
 *       overflow-checked, for <b>every</b> numeric element (including {@code double}).</li>
 * </ul>
 * All three paths define their hidden class in <b>this</b> (esql-proper) caller module. The vector paths reach a dense
 * {@code *ArrayVector}'s package-private backing array through the public exported
 * {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder ({@code INVOKESTATIC}) rather than teleporting into
 * {@code compute.data} — so an overflow-checked {@code double} kernel body's {@code NumericUtils.asFiniteNumber} call
 * (in {@code org.elasticsearch.xpack.esql.core}, outside {@code java.base}) links on the vector path just as it does on
 * the block path. The old {@code double}+overflow hard gate is therefore gone; {@link VectorStrategy#NONE} now applies
 * only to the block-only {@link org.elasticsearch.compute.operator.fusion.FusionNode.Logical} (3VL) trees.
 *
 * <h2>Fallback (acceptance criterion #5)</h2>
 * If any required stitch fails at plan time ({@link Stitcher.StitchingException} / {@link LinkageError}),
 * {@link #tryCreate} logs a warning, records the failure in {@link FusionTelemetry}, and returns the unfused factory
 * unchanged — the caller cannot tell fusion was attempted. As a defensive net, a {@link LinkageError} escaping the
 * fused method at evaluation time is likewise caught by {@link FusedExpressionEvaluator} and re-served from the
 * unfused chain.
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
        /** {@link Stitcher#compileVectorLoop}: dense no-null {@code ArrayVector} inputs, non-overflow kernels only. */
        PLAIN_VECTOR,
        /** {@link Stitcher#compileVectorLoopChecked}: dense inputs, overflow-checked (any numeric element, incl. double). */
        CHECKED_VECTOR,
        /** No vector fast path — always use the block path. Only the block-only 3VL {@code Logical} trees land here. */
        NONE
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
     * as {@code int}), so runtime vector-eligibility narrows each input to its OWN {@code *ArrayVector}.
     */
    private final ElementKind[] inputElements;
    private final MethodHandle blockHandle;
    private final MethodHandle vectorHandle;
    private final VectorStrategy vectorStrategy;
    private final ExpressionEvaluator.Factory unfused;
    private final String shape;
    /**
     * The embedded constants' (boxed) values in the {@link Stitcher#constantsInEmitOrder canonical emit order}, one per
     * trailing primitive parameter of the fused method. Appended (after the input vectors/blocks) to the invoke args at
     * evaluation time; empty when the shape has no constant leaves. {@code invokeWithArguments} unboxes each to the
     * primitive parameter type the resolved {@link MethodType} declares.
     */
    private final Object[] constantValues;

    private FusedExpressionEvaluatorFactory(
        Source[] warningSources,
        int[] inputChannels,
        ElementKind[] inputElements,
        MethodHandle blockHandle,
        MethodHandle vectorHandle,
        VectorStrategy vectorStrategy,
        ExpressionEvaluator.Factory unfused,
        String shape,
        Object[] constantValues
    ) {
        this.warningSources = warningSources;
        this.inputChannels = inputChannels;
        this.inputElements = inputElements;
        this.blockHandle = blockHandle;
        this.vectorHandle = vectorHandle;
        this.vectorStrategy = vectorStrategy;
        this.unfused = unfused;
        this.shape = shape;
        this.constantValues = constantValues;
    }

    /**
     * Stitches {@code tree} into its runtime path(s) and returns a fused factory, or — on any stitch failure — logs a
     * warning, records the failure, and returns {@code unfused} unchanged (criterion #5). This is the single place the
     * routing decision (which vector fast path, if any, to compile) is made.
     *
     * @param compiler the stitch entry points; the test seam substitutes a failing implementation here
     */
    static ExpressionEvaluator.Factory tryCreate(
        Source[] warningSources,
        FusionNode tree,
        int[] inputChannels,
        ElementKind[] inputElements,
        ElementKind outputElement,
        boolean overflowChecked,
        ExpressionEvaluator.Factory unfused,
        FusionPlanner.FusedClassCompiler compiler,
        String shape,
        List<FusionNode.Constant> constants
    ) {
        FusedExpressionEvaluatorFactory fused = tryCreateFusedOrNull(
            warningSources,
            tree,
            inputChannels,
            inputElements,
            outputElement,
            overflowChecked,
            unfused,
            compiler,
            shape,
            constants
        );
        return fused != null ? fused : unfused;
    }

    /**
     * Stitches {@code tree} and returns the fused factory, or {@code null} on any stitch failure (logging a warning and
     * recording the failure, exactly as {@link #tryCreate}). Unlike {@link #tryCreate} this does <b>not</b> substitute
     * the unfused factory on failure — it returns {@code null} — so the caller can distinguish "fused" from "could not
     * fuse". The adaptive path ({@link AdaptiveFusionEvaluatorFactory}) uses this so a deferred stitch that fails is
     * abandoned (the evaluator stays on its already-running unfused chain) rather than swapped for a fresh unfused
     * factory.
     */
    static FusedExpressionEvaluatorFactory tryCreateFusedOrNull(
        Source[] warningSources,
        FusionNode tree,
        int[] inputChannels,
        ElementKind[] inputElements,
        ElementKind outputElement,
        boolean overflowChecked,
        ExpressionEvaluator.Factory unfused,
        FusionPlanner.FusedClassCompiler compiler,
        String shape,
        List<FusionNode.Constant> constants
    ) {
        int arity = inputChannels.length;
        // Embedded constants are trailing primitive parameters of the fused method now (iter 30): their types are
        // appended to every resolved MethodType and their values are marshalled into the invoke args, both in the
        // Stitcher's single canonical emit order — so one stitched class serves every constant value.
        Class<?>[] constantParamTypes = constantParamTypes(constants);
        Object[] constantValues = constantValues(constants);
        try {
            if (tree instanceof FusionNode.Logical) {
                // A 3VL AND/OR tree is nullable boolean: block path only, no vector fast path (S3.1).
                Class<?> logicalClass = compiler.compileLogicalBlockLoop(LOOKUP, tree);
                MethodHandle logicalHandle = LOOKUP.findStatic(
                    logicalClass,
                    Stitcher.FUSED_METHOD_NAME,
                    blockType(inputElements, outputElement, arity, constantParamTypes)
                );
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    logicalHandle,
                    null,
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constantValues
                );
            }

            Class<?> blockClass = compiler.compileBlockLoop(LOOKUP, tree);
            MethodHandle blockHandle = LOOKUP.findStatic(
                blockClass,
                Stitcher.FUSED_METHOD_NAME,
                blockType(inputElements, outputElement, arity, constantParamTypes)
            );

            if (hasBytesRefInput(inputElements)) {
                // A BytesRef (keyword/text) column may be ordinal-encoded rather than a plain ArrayVector, so this
                // slice deliberately does NOT compile a vector fast path for it — the tree always runs the block path
                // (which reads each BytesRef via getBytesRef into a reusable spare). Block-only, like the 3VL logical
                // trees, but with a real (non-null) block handle.
                return new FusedExpressionEvaluatorFactory(
                    warningSources,
                    inputChannels,
                    inputElements,
                    blockHandle,
                    null,
                    VectorStrategy.NONE,
                    unfused,
                    shape,
                    constantValues
                );
            }

            MethodHandle vectorHandle;
            VectorStrategy strategy;
            if (overflowChecked == false) {
                Class<?> vectorClass = compiler.compileVectorLoop(LOOKUP, tree);
                vectorHandle = LOOKUP.findStatic(
                    vectorClass,
                    Stitcher.FUSED_METHOD_NAME,
                    plainVectorType(outputElement, arity, constantParamTypes)
                );
                strategy = VectorStrategy.PLAIN_VECTOR;
            } else {
                // Overflow-checked trees (including double, whose kernels call NumericUtils.asFiniteNumber) all use the
                // checked-vector path now: the fused class is defined in the caller's (esql-proper) module — reaching
                // the backing array through the public VectorUnsafe forwarder rather than a compute.data teleport — so
                // it links non-java.base kernel callees just like the block path. The old double+overflow hard gate
                // (VectorStrategy.NONE) is gone.
                Class<?> vectorClass = compiler.compileVectorLoopChecked(LOOKUP, tree);
                vectorHandle = LOOKUP.findStatic(
                    vectorClass,
                    Stitcher.FUSED_METHOD_NAME,
                    checkedVectorType(outputElement, arity, constantParamTypes)
                );
                strategy = VectorStrategy.CHECKED_VECTOR;
            }

            return new FusedExpressionEvaluatorFactory(
                warningSources,
                inputChannels,
                inputElements,
                blockHandle,
                vectorHandle,
                strategy,
                unfused,
                shape,
                constantValues
            );
        } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Any plan-time stitch failure must fall back to the unfused chain and record the shape (criterion #5):
            // - StitchingException / LinkageError: the stitch failed to verify, link, or define.
            // - ReflectiveOperationException (NoSuchMethod / IllegalAccess from findStatic): the emitted method's
            // signature did not match what we resolve for.
            // - RuntimeException: Stitcher.emit* can reject an ill-shaped tree with a bare IllegalArgumentException
            // before the define-hidden-class try-block, and we defensively treat any other unchecked failure the
            // same way — fusion is an optimization and must never break the query.
            logger.warn(() -> "fusion stitch failed for shape [" + shape + "]; falling back to unfused evaluator chain", e);
            FusionTelemetry.recordFailure(shape);
            return null;
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
            vectorStrategy,
            unfused,
            context,
            shape,
            constantValues
        );
    }

    /** Test hook (package-private): which vector fast path this shape uses (or {@code NONE} for block-only trees). */
    VectorStrategy vectorStrategy() {
        return vectorStrategy;
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

    /** Appends the trailing constant primitive parameter types (in canonical emit order) to a MethodType param list. */
    private static void appendConstantParams(List<Class<?>> params, Class<?>[] constantParamTypes) {
        for (Class<?> constantParamType : constantParamTypes) {
            params.add(constantParamType);
        }
    }

    /** The primitive parameter type for each constant (in canonical emit order), for the resolved {@link MethodType}. */
    private static Class<?>[] constantParamTypes(List<FusionNode.Constant> constants) {
        Class<?>[] types = new Class<?>[constants.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = constantParamClass(constants.get(i).element());
        }
        return types;
    }

    /** The (boxed) constant values in canonical emit order, marshalled into the invoke args at evaluation time. */
    private static Object[] constantValues(List<FusionNode.Constant> constants) {
        Object[] values = new Object[constants.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = constants.get(i).value();
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
     * dispatches to the fused method: the vector fast path when every input is a dense, no-null, single-valued
     * {@code *ArrayVector} (and a vector strategy was compiled), otherwise the null/overflow-aware block path.
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

        private Warnings[] warnings;
        // Lazily built only if a defensive LinkageError forces a per-page fallback; closed with this evaluator.
        private ExpressionEvaluator unfusedFallback;
        // Latched true after the first eval-time LinkageError so every later page routes straight to the unfused
        // fallback instead of re-attempting (and re-failing) the fused handle per page.
        private boolean fusionDisabled;

        FusedExpressionEvaluator(
            Source[] warningSources,
            int[] inputChannels,
            ElementKind[] inputElements,
            MethodHandle blockHandle,
            MethodHandle vectorHandle,
            VectorStrategy vectorStrategy,
            ExpressionEvaluator.Factory unfusedFactory,
            DriverContext driverContext,
            String shape,
            Object[] constantValues
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
        }

        @Override
        public Block eval(Page page) {
            if (fusionDisabled) {
                // A prior page already hit an eval-time LinkageError; stay on the unfused chain for the rest of this
                // evaluator's life rather than re-failing the fused handle per page.
                return unfusedFallback().eval(page);
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
                Vector[] vectors = vectorHandle == null ? null : vectorEligible(inputs);
                if (vectors != null) {
                    if (vectorStrategy == VectorStrategy.PLAIN_VECTOR) {
                        Object[] args = new Object[2 + arity + constCount];
                        args[0] = blockFactory;
                        args[1] = positionCount;
                        System.arraycopy(vectors, 0, args, 2, arity);
                        System.arraycopy(constantValues, 0, args, 2 + arity, constCount);
                        Vector out = (Vector) vectorHandle.invokeWithArguments(args);
                        return out.asBlock();
                    }
                    // CHECKED_VECTOR
                    Object[] args = new Object[3 + arity + constCount];
                    args[0] = blockFactory;
                    args[1] = warnings();
                    args[2] = positionCount;
                    System.arraycopy(vectors, 0, args, 3, arity);
                    System.arraycopy(constantValues, 0, args, 3 + arity, constCount);
                    return (Block) vectorHandle.invokeWithArguments(args);
                }
                // Block path: null/multi-value tolerant, overflow-aware. Also the logical (3VL) path.
                Object[] args = new Object[3 + arity + constCount];
                args[0] = blockFactory;
                args[1] = warnings();
                args[2] = positionCount;
                System.arraycopy(inputs, 0, args, 3, arity);
                System.arraycopy(constantValues, 0, args, 3 + arity, constCount);
                return (Block) blockHandle.invokeWithArguments(args);
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
         * Returns the input vectors when every input is a dense, no-null, single-valued {@code *ArrayVector} of the
         * fused element type (so {@code rawValues()} is a valid tight read), else {@code null} to route to the block
         * path. {@link Block#asVector()} already returns {@code null} for any block carrying nulls or multi-values.
         */
        private Vector[] vectorEligible(Block[] inputs) {
            Vector[] vectors = new Vector[inputs.length];
            for (int i = 0; i < inputs.length; i++) {
                Vector vector = inputs[i].asVector();
                // Each input must be a dense no-null single-valued *ArrayVector of its OWN element (the tree may mix
                // elements via cast kernels), so rawValues() is a valid tight read for that input.
                if (vector == null || inputElements[i].arrayVectorClass.isInstance(vector) == false) {
                    return null;
                }
                vectors[i] = vector;
            }
            return vectors;
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
            if (unfusedFallback != null) {
                unfusedFallback.close();
            }
        }

        @Override
        public String toString() {
            return "Fused[shape=" + shape + ", strategy=" + vectorStrategy + "]";
        }
    }
}
