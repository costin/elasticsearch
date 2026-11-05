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
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.operator.fusion.FusedFrameInfo;
import org.elasticsearch.compute.operator.fusion.FusionDump;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.Bound;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * The WHERE counterpart of {@link FusedExpressionEvaluatorFactory}: a fused boolean predicate whose evaluators are
 * {@link PositionsEvaluator}s, writing the positions where the predicate is {@code true} straight into the
 * {@code FilterOperator}'s {@code int[]}. Fusing the predicate alone still left the filter materialising a
 * {@link BooleanBlock} and then scanning it a second time (a null check, a value-count check and a read per row);
 * compiling the selection into the fused loop removes both, while the per-position evaluation — values, nulls,
 * multi-values, overflow and warnings — is the value-output loop's own.
 *
 * <h2>Routing</h2>
 * Each runtime path of {@link FusedExpressionEvaluatorFactory}, in its POSITIONS form:
 * <ul>
 *   <li><b>Block path</b> ({@link Stitcher#compileBlockPositionsLoop}) — always compiled. Null/multi-value tolerant and
 *       overflow-aware, and the re-run for a dense page whose vector path met an overflow.</li>
 *   <li><b>Vector path</b> ({@link Stitcher#compileVectorPositionsLoop}) — every input a vector. Exact
 *       {@code *ArrayVector} pages take ALL-RAW {@code fused}, other dense pages ALL-INTERFACE {@code fusedInterface};
 *       a logical tree is INTERFACE-only. An overflow-checked tree returns {@code -1} on overflow and the page re-runs on
 *       the block path. Not compiled for {@code BYTES_REF} inputs.</li>
 *   <li><b>Encoding-aware</b> — a single ordinal or constant {@code BYTES_REF} column with no warning kernel: the block
 *       path runs once over the dictionary (or the one constant value) and rows are then selected by ordinal.</li>
 *   <li><b>Unfused</b> — a nested mapping on a non-vector page, or every page after an eval-time {@link LinkageError}:
 *       the unfused predicate's {@link BooleanBlock}, read by {@link PositionsEvaluator#selectedPositions}.</li>
 * </ul>
 * Plan-time failure handling, per-source warnings and verify mode follow {@link FusedExpressionEvaluatorFactory}: a
 * block-path stitch failure returns the unfused factory, a vector-path failure degrades to block-only, and verify mode
 * compares the selected positions against the unfused predicate's.
 *
 * <p>{@link ExpressionEvaluator#eval} is answered by the unfused predicate, so the evaluator stays correct for any
 * caller; {@code FilterOperator} only calls {@link PositionsEvaluator#filterPositions}.
 */
final class FusedFilterEvaluatorFactory implements ExpressionEvaluator.Factory {

    private static final Logger logger = LogManager.getLogger(FusedFilterEvaluatorFactory.class);

    private final Source[] warningSources;
    private final int[] inputChannels;
    private final ElementKind[] inputElements;
    private final MethodHandle blockSpreader;
    /** ALL-RAW vector spreader; {@code null} for a logical tree (INTERFACE-only) or block-only. */
    private final MethodHandle vectorRawSpreader;
    /** ALL-INTERFACE vector spreader; {@code null} when block-only ({@link VectorStrategy#NONE}). */
    private final MethodHandle vectorInterfaceSpreader;
    private final VectorStrategy vectorStrategy;
    private final ExpressionEvaluator.Factory unfused;
    private final String shape;
    private final String kernelMap;
    private final List<FusionNode.Constant> constants;
    private final boolean encodingAware;
    private final boolean nestedMapping;
    /** Strong holds on the cached units, so the handles dispatched through stay alive with this factory. */
    private final FusedClassCache.CachedFused blockUnit;
    private final FusedClassCache.CachedFused vectorUnit;

    private FusedFilterEvaluatorFactory(
        FusionPlan plan,
        ExpressionEvaluator.Factory unfused,
        Bound block,
        Bound vectorRaw,
        Bound vectorInterface,
        VectorStrategy vectorStrategy,
        FusedClassCache.CachedFused blockUnit,
        FusedClassCache.CachedFused vectorUnit
    ) {
        this.warningSources = plan.warningSources();
        this.inputChannels = plan.channels();
        this.inputElements = plan.inputElements();
        this.blockSpreader = block.spreader();
        this.vectorRawSpreader = vectorRaw == null ? null : vectorRaw.spreader();
        this.vectorInterfaceSpreader = vectorInterface == null ? null : vectorInterface.spreader();
        this.vectorStrategy = vectorStrategy;
        this.unfused = unfused;
        this.shape = plan.shape();
        this.kernelMap = FusedFrameInfo.kernelMapOf(FusedExpressionEvaluatorFactory.LOOKUP, block.handle());
        this.constants = plan.constants();
        this.encodingAware = plan.encodingAware();
        this.nestedMapping = FusionPlanner.containsNestedMapping(plan.tree());
        this.blockUnit = blockUnit;
        this.vectorUnit = vectorUnit;
        assert this.blockUnit != null;
        assert this.vectorUnit != null || vectorInterface == null;
    }

    /**
     * Stitches the POSITIONS classes for {@code plan}'s predicate and returns the fused factory, or — when the block
     * path cannot be stitched — logs (WARN once per shape), records the failure, and returns {@code unfused}. A
     * vector-path failure keeps the block path and degrades at DEBUG, like {@link FusedExpressionEvaluatorFactory}.
     */
    static ExpressionEvaluator.Factory tryCreate(
        FusionPlan plan,
        ExpressionEvaluator.Factory unfused,
        FusionPlanner.FusedClassCompiler compiler
    ) {
        FusionNode tree = plan.tree();
        String shape = plan.shape();
        int arity = plan.channels().length;
        Class<?>[] constantParamTypes = FusedExpressionEvaluatorFactory.constantParamTypes(plan.constants());
        FusedClassCache.Path blockPath = FusionCompilationService.primaryPathOf(tree);
        List<byte[]> captured = null;
        if (FusionSettings.isVerifyEnabled()) {
            captured = new ArrayList<>();
            FusionDump.beginThreadCapture(captured);
        }
        try {
            MethodType blockType = blockType(plan.inputElements(), constantParamTypes);
            FusedClassCache.CachedFused blockUnit = compiler.compileBlockPositionsLoop(
                FusedExpressionEvaluatorFactory.LOOKUP,
                tree,
                blockType,
                Stitcher.FUSED_METHOD_NAME
            );
            Bound block = FusedExpressionEvaluatorFactory.bind(blockUnit, Stitcher.FUSED_METHOD_NAME, blockType);
            if (FusedExpressionEvaluatorFactory.hasBytesRefInput(plan.inputElements())) {
                // As for value output: a BytesRef column has no dense array to read, so there is no vector path. Ordinal
                // and constant pages still take the encoding-aware path.
                return new FusedFilterEvaluatorFactory(plan, unfused, block, null, null, VectorStrategy.NONE, blockUnit, null);
            }
            FusedClassCache.Path vectorPath = FusionCompilationService.vectorPathOf(tree);
            try {
                MethodType vectorType = vectorType(arity, constantParamTypes);
                Bound raw = null;
                FusedClassCache.CachedFused vectorUnit;
                if (vectorPath == FusedClassCache.Path.LOGICAL_VECTOR) {
                    vectorUnit = compiler.compileVectorPositionsLoop(
                        FusedExpressionEvaluatorFactory.LOOKUP,
                        tree,
                        vectorType,
                        Stitcher.FUSED_INTERFACE_METHOD_NAME
                    );
                } else {
                    vectorUnit = compiler.compileVectorPositionsLoop(
                        FusedExpressionEvaluatorFactory.LOOKUP,
                        tree,
                        vectorType,
                        Stitcher.FUSED_METHOD_NAME,
                        Stitcher.FUSED_INTERFACE_METHOD_NAME
                    );
                    raw = FusedExpressionEvaluatorFactory.bind(vectorUnit, Stitcher.FUSED_METHOD_NAME, vectorType);
                }
                Bound iface = FusedExpressionEvaluatorFactory.bind(vectorUnit, Stitcher.FUSED_INTERFACE_METHOD_NAME, vectorType);
                return new FusedFilterEvaluatorFactory(plan, unfused, block, raw, iface, strategyOf(vectorPath), blockUnit, vectorUnit);
            } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError vectorFailure) {
                // The block path stitched under the same huge-method guard, so keep fusing through it alone.
                FusedExpressionEvaluatorFactory.logPlanTimeStitchFailure(logger, shape, vectorPath, false, vectorFailure);
                FusionTelemetry.recordVectorDegraded(shape);
                return new FusedFilterEvaluatorFactory(plan, unfused, block, null, null, VectorStrategy.NONE, blockUnit, null);
            }
        } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Fusion is an optimization and must never break the query: fall back to the unfused predicate.
            FusedExpressionEvaluatorFactory.logPlanTimeStitchFailure(logger, shape, blockPath, true, e);
            FusionTelemetry.recordFailure(shape, blockPath);
            return unfused;
        } finally {
            if (captured != null) {
                FusionDump.endThreadCapture();
                FusionVerify.recordClassBytes(shape, captured);
            }
        }
    }

    private static VectorStrategy strategyOf(FusedClassCache.Path vectorPath) {
        return switch (vectorPath) {
            case PLAIN_VECTOR -> VectorStrategy.PLAIN_VECTOR;
            case CHECKED_VECTOR -> VectorStrategy.CHECKED_VECTOR;
            case LOGICAL_VECTOR -> VectorStrategy.LOGICAL_VECTOR;
            case BLOCK, LOGICAL_BLOCK -> throw new IllegalArgumentException("not a vector path [" + vectorPath + "]");
        };
    }

    /** {@code (BlockFactory, Warnings[], int, int[], <input *Blocks>, <constants>)int}. */
    private static MethodType blockType(ElementKind[] inputElements, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(Warnings[].class);
        params.add(int.class);
        params.add(int[].class);
        for (ElementKind element : inputElements) {
            params.add(element.blockClass());
        }
        FusedExpressionEvaluatorFactory.appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(int.class, params);
    }

    /** {@code (BlockFactory, int, int[], <input Vectors>, <constants>)int}; no {@code Warnings[]}, see the Stitcher. */
    private static MethodType vectorType(int arity, Class<?>[] constantParamTypes) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(int.class);
        params.add(int[].class);
        for (int i = 0; i < arity; i++) {
            params.add(Vector.class);
        }
        FusedExpressionEvaluatorFactory.appendConstantParams(params, constantParamTypes);
        return MethodType.methodType(int.class, params);
    }

    @Override
    public ExpressionEvaluator get(DriverContext context) {
        return getForTests(context, FusionSettings.isVectorReadsInterface());
    }

    /** Test-only: like {@link #get} but forces the ALL-INTERFACE vs ALL-RAW choice (see the value-output factory). */
    PositionsEvaluator getForTests(DriverContext context, boolean vectorReadsInterface) {
        return new FusedFilterEvaluator(
            this,
            context,
            FusedExpressionEvaluatorFactory.resolveConstantValues(constants, context),
            vectorReadsInterface
        );
    }

    /** Test hook: which vector path this shape uses ({@link VectorStrategy#NONE} for block-only). */
    VectorStrategy vectorStrategy() {
        return vectorStrategy;
    }

    /** Test hook: the plan shape, used for verify-mode class capture and failure telemetry. */
    String shape() {
        return shape;
    }

    /** Test hook: the unfused predicate this factory falls back to. */
    ExpressionEvaluator.Factory unfusedFactory() {
        return unfused;
    }

    /** Test-only: which path the last {@code filterPositions} of {@code evaluator} took (written under {@code assert}). */
    static LastEvalPath lastEvalPath(ExpressionEvaluator evaluator) {
        return ((FusedFilterEvaluator) evaluator).lastEvalPath;
    }

    @Override
    public String toString() {
        return "Fused[shape=" + shape + ", strategy=" + vectorStrategy + ", unfused=" + unfused + "]";
    }

    /**
     * The runtime evaluator: reads its inputs from the page (borrowed, never closed) and dispatches one page to the
     * encoding-aware, vector or block POSITIONS method. Per-driver state — the scratch argument arrays, the
     * {@code Warnings[]}, the resolved constants and the lazily built unfused evaluators — lives here.
     */
    private static final class FusedFilterEvaluator implements PositionsEvaluator {

        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FusedFilterEvaluator.class);

        private final FusedFilterEvaluatorFactory factory;
        private final DriverContext driverContext;
        private final Object[] constantValues;
        /** Instance snapshot of {@link FusionSettings#isVectorReadsInterface()} so tests can force ALL-INTERFACE. */
        private final boolean vectorReadsInterface;
        /** {@code [bf, Warnings[], positionCount, positions, inputs..., constants...]}, reused across pages. */
        private final Object[] blockScratch;
        /** {@code [bf, positionCount, positions, vectors..., constants...]}; {@code null} when block-only. */
        private final Object[] vectorScratch;

        private Warnings[] warnings;
        private ExpressionEvaluator unfusedFallback;
        private ExpressionEvaluator verifyUnfused;
        /** Latched after the first eval-time {@link LinkageError}: every later page takes the unfused predicate. */
        private boolean fusionDisabled;
        private boolean runtimeFallbackRecorded;
        private boolean nestedMappingMvFallbackRecorded;
        private boolean closed;
        /** Test-only, written under {@code assert}: the last path {@link #filterPositions} took. */
        private LastEvalPath lastEvalPath;

        FusedFilterEvaluator(
            FusedFilterEvaluatorFactory factory,
            DriverContext driverContext,
            Object[] constantValues,
            boolean vectorReadsInterface
        ) {
            this.factory = factory;
            this.driverContext = driverContext;
            this.constantValues = constantValues;
            this.vectorReadsInterface = vectorReadsInterface;
            int arity = factory.inputChannels.length;
            this.blockScratch = new Object[4 + arity + constantValues.length];
            this.vectorScratch = factory.vectorInterfaceSpreader == null ? null : new Object[3 + arity + constantValues.length];
        }

        private boolean recordPath(LastEvalPath path) {
            this.lastEvalPath = path;
            return true;
        }

        @Override
        public int filterPositions(Page page, int[] positions) {
            int positionCount = page.getPositionCount();
            assert positions.length >= positionCount : "positions [" + positions.length + "] < positionCount [" + positionCount + "]";
            if (positionCount == 0) {
                // Nothing to select; also keeps the constant encoding-aware path from reading position 0 of an empty vector.
                return 0;
            }
            if (fusionDisabled) {
                recordRuntimeFallbackOnce();
                return unfusedPositions(unfusedFallback(), page, positions);
            }
            int arity = factory.inputChannels.length;
            Block[] inputs = new Block[arity];
            for (int i = 0; i < arity; i++) {
                inputs[i] = page.getBlock(factory.inputChannels[i]);
            }
            if (factory.nestedMapping && anyNonVector(inputs)) {
                // A nested mapping on a page with nulls or multi-values keeps the unfused multi-value semantics.
                assert recordPath(LastEvalPath.UNFUSED_FALLBACK);
                recordNestedMappingMvFallbackOnce();
                return unfusedPositions(unfusedFallback(), page, positions);
            }
            int count;
            try {
                if (FusedExpressionEvaluatorFactory.injectEvalLinkageErrorForTests) {
                    throw new LinkageError("injected eval-time linkage failure for shape [" + factory.shape + "]");
                }
                count = -1;
                if (factory.encodingAware && arity == 1 && inputs[0] instanceof BytesRefBlock bytesRefInput) {
                    count = filterEncodingAware(bytesRefInput, positionCount, positions);
                }
                if (count < 0 && vectorScratch != null) {
                    Vector[] dense = denseVectors(inputs);
                    if (dense != null) {
                        count = filterDenseVectors(dense, positionCount, positions);
                    }
                }
                if (count < 0) {
                    // Nulls / multi-values, no vector path, or a dense page that overflowed on the vector path.
                    assert recordPath(LastEvalPath.BLOCK);
                    count = invokeBlock(inputs, positionCount, positions);
                }
            } catch (LinkageError e) {
                // Defensive, as in the value-output evaluator: latch fusion off and serve this page unfused.
                fusionDisabled = true;
                logger.warn(() -> "fused filter failed to link for shape [" + factory.shape + "]; falling back to unfused", e);
                FusionTelemetry.recordFailure(factory.shape);
                recordRuntimeFallbackOnce();
                return unfusedPositions(unfusedFallback(), page, positions);
            } catch (RuntimeException | Error e) {
                e.addSuppressed(new FusedFrameInfo(factory.shape, factory.kernelMap));
                throw e;
            } catch (Throwable t) {
                // The fused methods declare no checked exceptions; unreachable in practice.
                RuntimeException wrapped = new RuntimeException(t);
                wrapped.addSuppressed(new FusedFrameInfo(factory.shape, factory.kernelMap));
                throw wrapped;
            }
            // Verify-mode A/B: a static-final read the JIT folds away in production.
            if (FusionSettings.isVerifyEnabled() && FusionVerify.skipForTests == false) {
                FusionVerify.verifyPositions(
                    this::verifyUnfused,
                    page,
                    positions,
                    count,
                    factory.shape,
                    factory.vectorStrategy,
                    lastEvalPath
                );
            }
            return count;
        }

        /**
         * Dense page: ALL-RAW when every input is its element's exact {@code *ArrayVector} (and a RAW method exists),
         * else ALL-INTERFACE. Returns {@code -1} when an overflow-checked method abandoned the page.
         */
        private int filterDenseVectors(Vector[] dense, int positionCount, int[] positions) throws Throwable {
            boolean raw = factory.vectorRawSpreader != null && allExactArrayVectors(dense);
            assert recordPath(raw ? LastEvalPath.RAW : LastEvalPath.INTERFACE);
            Object[] args = vectorScratch;
            args[0] = driverContext.blockFactory();
            args[1] = positionCount;
            args[2] = positions;
            System.arraycopy(dense, 0, args, 3, dense.length);
            System.arraycopy(constantValues, 0, args, 3 + dense.length, constantValues.length);
            MethodHandle spreader = raw ? factory.vectorRawSpreader : factory.vectorInterfaceSpreader;
            return (int) spreader.invokeExact(args);
        }

        private int invokeBlock(Block[] inputs, int positionCount, int[] positions) throws Throwable {
            Object[] args = blockScratch;
            args[0] = driverContext.blockFactory();
            args[1] = warnings();
            args[2] = positionCount;
            args[3] = positions;
            System.arraycopy(inputs, 0, args, 4, inputs.length);
            System.arraycopy(constantValues, 0, args, 4 + inputs.length, constantValues.length);
            return (int) factory.blockSpreader.invokeExact(args);
        }

        /**
         * Encoding-aware selection for a single {@code BYTES_REF} input, or {@code -1} to use the per-row paths. An
         * ordinal vector (dense, or whose dictionary is no larger than the page) is evaluated once per dictionary entry
         * and rows are selected by ordinal; a constant vector is evaluated once and selects every row or none. Only
         * shapes without a warning or throwing kernel get here, so evaluating unreferenced entries is unobservable.
         */
        private int filterEncodingAware(BytesRefBlock input, int positionCount, int[] positions) throws Throwable {
            BytesRefVector vector = input.asVector();
            if (vector == null) {
                return -1;
            }
            OrdinalBytesRefVector ordinals = vector.asOrdinals();
            if (ordinals != null) {
                BytesRefVector dict = ordinals.getDictionaryVector();
                int dictCount = dict.getPositionCount();
                if (ordinals.isDense() == false && dictCount > positionCount) {
                    // After a selective filter the dictionary can dwarf the page; per-row is cheaper then.
                    return -1;
                }
                boolean[] selectedEntries = new boolean[dictCount];
                int[] entryPositions = new int[dictCount];
                dict.incRef();
                Block dictBlock = dict.asBlock();
                try {
                    int selected = invokeBlock(new Block[] { dictBlock }, dictCount, entryPositions);
                    for (int i = 0; i < selected; i++) {
                        selectedEntries[entryPositions[i]] = true;
                    }
                } finally {
                    Releasables.closeExpectNoException(dictBlock);
                }
                IntVector ords = ordinals.getOrdinalsVector();
                int count = 0;
                for (int p = 0; p < positionCount; p++) {
                    positions[count] = p;
                    count += selectedEntries[ords.getInt(p)] ? 1 : 0;
                }
                assert recordPath(LastEvalPath.ENCODING_AWARE);
                return count;
            }
            if (vector.isConstant()) {
                int selected;
                try (Block one = driverContext.blockFactory().newConstantBytesRefBlockWith(vector.getBytesRef(0, new BytesRef()), 1)) {
                    selected = invokeBlock(new Block[] { one }, 1, new int[1]);
                }
                assert recordPath(LastEvalPath.ENCODING_AWARE);
                if (selected == 0) {
                    return 0;
                }
                for (int p = 0; p < positionCount; p++) {
                    positions[p] = p;
                }
                return positionCount;
            }
            return -1;
        }

        /** True when every input is the exact {@code *ArrayVector} of its element (the ALL-RAW read). */
        private boolean allExactArrayVectors(Vector[] vectors) {
            if (vectorReadsInterface) {
                return false;
            }
            for (int i = 0; i < vectors.length; i++) {
                if (factory.inputElements[i].arrayVectorClass().isInstance(vectors[i]) == false) {
                    return false;
                }
            }
            return true;
        }

        /** The inputs' vectors when every one is a vector (no nulls, no multi-values), else {@code null}. */
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

        private static boolean anyNonVector(Block[] inputs) {
            for (Block input : inputs) {
                if (input.asVector() == null) {
                    return true;
                }
            }
            return false;
        }

        /** The unfused predicate's {@link BooleanBlock}, read with the reference selection. */
        private static int unfusedPositions(ExpressionEvaluator unfused, Page page, int[] positions) {
            try (BooleanBlock mask = (BooleanBlock) unfused.eval(page)) {
                return PositionsEvaluator.selectedPositions(mask, positions);
            }
        }

        /** Answered by the unfused predicate; {@code FilterOperator} calls {@link #filterPositions} instead. */
        @Override
        public Block eval(Page page) {
            return unfusedFallback().eval(page);
        }

        /** One {@code Warnings} per warning-emitting node, built lazily from that node's source (see the value factory). */
        private Warnings[] warnings() {
            if (warnings == null) {
                Warnings[] built = new Warnings[factory.warningSources.length];
                for (int i = 0; i < built.length; i++) {
                    built[i] = Warnings.createWarnings(driverContext, factory.warningSources[i]);
                }
                this.warnings = built;
            }
            return warnings;
        }

        private ExpressionEvaluator unfusedFallback() {
            if (unfusedFallback == null) {
                this.unfusedFallback = factory.unfused.get(driverContext);
            }
            return unfusedFallback;
        }

        /** Verify-mode reference, bound to {@link DriverContext#ignoringWarnings()} so it adds no warnings of its own. */
        private ExpressionEvaluator verifyUnfused() {
            if (verifyUnfused == null) {
                this.verifyUnfused = factory.unfused.get(driverContext.ignoringWarnings());
            }
            return verifyUnfused;
        }

        private void recordRuntimeFallbackOnce() {
            if (runtimeFallbackRecorded == false) {
                runtimeFallbackRecorded = true;
                FusionTelemetry.recordRuntimeFallback();
            }
        }

        private void recordNestedMappingMvFallbackOnce() {
            if (nestedMappingMvFallbackRecorded == false) {
                nestedMappingMvFallbackRecorded = true;
                FusionTelemetry.recordNestedMappingMvFallback();
            }
        }

        @Override
        public long baseRamBytesUsed() {
            return BASE_RAM_BYTES_USED;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Releasables.closeExpectNoException(unfusedFallback, verifyUnfused);
            // Per-driver SCRATCH constants that are Releasable (breaker-accounted) are owned by this evaluator.
            for (Object constantValue : constantValues) {
                if (constantValue instanceof Releasable releasable) {
                    Releasables.closeExpectNoException(releasable);
                }
            }
        }

        @Override
        public String toString() {
            return "Fused[shape=" + factory.shape + ", strategy=" + factory.vectorStrategy + "]";
        }
    }
}
