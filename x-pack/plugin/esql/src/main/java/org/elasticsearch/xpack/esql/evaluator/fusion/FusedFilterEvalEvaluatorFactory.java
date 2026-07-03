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
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link FilterEvalEvaluator.Factory} that fuses a WHERE predicate and a single EVAL projection into ONE stitched
 * per-position body (built by {@link Stitcher#compileFilterEvalBlockLoop}) instead of the two-operator
 * {@code Filter → Eval} chain. It is the plan-time product of {@link FusionPlanner#maybeFuseFilterEval}.
 *
 * <h2>Runtime shape</h2>
 * The fused {@link MethodHandle} takes {@code (BlockFactory, Warnings[], int positionCount, int[] positions,
 * <predicate input *Blocks…>, <projection input *Blocks…>, <predicate constants…>, <projection constants…>)} and
 * returns the COMPACT projection {@link Block} (one entry per surviving row) after filling {@code positions[0..count)}
 * with the surviving source positions. Because a 3VL predicate is nullable, this is a block-only path (no vector fast
 * path), mirroring {@link Stitcher#compileLogicalBlockLoop}.
 *
 * <h2>Fallback (criterion #5, kill-switch discipline)</h2>
 * If the stitch fails at plan time ({@link Stitcher.StitchingException} / {@link LinkageError} / a resolution failure),
 * {@link #create} logs a warning, records the failure in {@link FusionTelemetry}, and returns a fallback factory whose
 * evaluator reproduces {@code Filter → Eval} from the two unfused {@link ExpressionEvaluator.Factory}s — so the
 * {@link org.elasticsearch.compute.operator.FilterEvalOperator} still works, identically. As a defensive net, a
 * {@link LinkageError} escaping the fused handle at evaluation time is likewise caught and re-served from the unfused
 * pair (latched off for the rest of the evaluator's life).
 */
public final class FusedFilterEvalEvaluatorFactory implements FilterEvalEvaluator.Factory {

    private static final Logger logger = LogManager.getLogger(FusedFilterEvalEvaluatorFactory.class);

    /** Lookup of this (esql-proper) module, so the fused hidden class links both {@code compute} and {@code esql-core}. */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * Test-only: when {@code true}, the first {@code evalProjection} on a fresh fused evaluator throws a
     * {@link LinkageError} instead of invoking the fused handle, exercising the eval-time fallback + disable latch.
     */
    static volatile boolean injectEvalLinkageErrorForTests = false;

    // The combined per-warning-source array: the predicate's sources first, then the projection's (matching the
    // Stitcher's offset), so each fused warning is attributed to the same node source the unfused chain would use.
    private final Source[] warningSources;
    private final int[] predicateChannels;
    private final int[] projectionChannels;
    private final ElementKind[] predicateInputElements;
    private final ElementKind[] projectionInputElements;
    // Null iff the stitch failed at plan time (then the evaluator always uses the unfused pair).
    private final MethodHandle fusedHandle;
    private final Object[] predicateConstants;
    private final Object[] projectionConstants;
    private final ExpressionEvaluator.Factory unfusedPredicate;
    private final ExpressionEvaluator.Factory unfusedProjection;
    private final String shape;

    private FusedFilterEvalEvaluatorFactory(
        Source[] warningSources,
        int[] predicateChannels,
        int[] projectionChannels,
        ElementKind[] predicateInputElements,
        ElementKind[] projectionInputElements,
        MethodHandle fusedHandle,
        Object[] predicateConstants,
        Object[] projectionConstants,
        ExpressionEvaluator.Factory unfusedPredicate,
        ExpressionEvaluator.Factory unfusedProjection,
        String shape
    ) {
        this.warningSources = warningSources;
        this.predicateChannels = predicateChannels;
        this.projectionChannels = projectionChannels;
        this.predicateInputElements = predicateInputElements;
        this.projectionInputElements = projectionInputElements;
        this.fusedHandle = fusedHandle;
        this.predicateConstants = predicateConstants;
        this.projectionConstants = projectionConstants;
        this.unfusedPredicate = unfusedPredicate;
        this.unfusedProjection = unfusedProjection;
        this.shape = shape;
    }

    /**
     * Stitches the predicate + projection into a fused factory, or — on any stitch failure — returns a fallback factory
     * that reproduces {@code Filter → Eval} from {@code unfusedPredicate}/{@code unfusedProjection} (criterion #5).
     * Always returns a non-null factory so the {@link org.elasticsearch.compute.operator.FilterEvalOperator} can run.
     */
    static FusedFilterEvalEvaluatorFactory create(
        Source[] warningSources,
        FusionNode predicateTree,
        FusionNode projectionTree,
        int[] predicateChannels,
        int[] projectionChannels,
        ElementKind[] predicateInputElements,
        ElementKind[] projectionInputElements,
        ElementKind projectionOutputElement,
        List<FusionNode.Constant> predicateConstants,
        List<FusionNode.Constant> projectionConstants,
        ExpressionEvaluator.Factory unfusedPredicate,
        ExpressionEvaluator.Factory unfusedProjection,
        FusionPlanner.FusedClassCompiler compiler,
        String shape
    ) {
        Object[] predConstValues = constantValues(predicateConstants);
        Object[] projConstValues = constantValues(projectionConstants);
        MethodHandle handle = null;
        try {
            Class<?> fusedClass = compiler.compileFilterEvalBlockLoop(LOOKUP, predicateTree, projectionTree);
            MethodType type = fusedType(
                predicateInputElements,
                projectionInputElements,
                projectionOutputElement,
                constantParamTypes(predicateConstants),
                constantParamTypes(projectionConstants)
            );
            handle = LOOKUP.findStatic(fusedClass, Stitcher.FUSED_METHOD_NAME, type);
        } catch (Stitcher.StitchingException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            logger.warn(() -> "filter-eval fusion stitch failed for shape [" + shape + "]; falling back to unfused chain", e);
            FusionTelemetry.recordFailure(shape);
            handle = null;
        }
        return new FusedFilterEvalEvaluatorFactory(
            warningSources,
            predicateChannels,
            projectionChannels,
            predicateInputElements,
            projectionInputElements,
            handle,
            predConstValues,
            projConstValues,
            unfusedPredicate,
            unfusedProjection,
            shape
        );
    }

    /** Whether the stitch succeeded (this factory dispatches to the fused handle) vs. fell back to the unfused pair. */
    boolean fused() {
        return fusedHandle != null;
    }

    @Override
    public FilterEvalEvaluator get(DriverContext context) {
        return new FusedFilterEvalEvaluator(
            warningSources,
            predicateChannels,
            projectionChannels,
            fusedHandle,
            predicateConstants,
            projectionConstants,
            unfusedPredicate,
            unfusedProjection,
            context,
            shape
        );
    }

    @Override
    public String describe() {
        return "Fused[shape=" + shape + ", fused=" + fused() + "]";
    }

    @Override
    public String toString() {
        return describe();
    }

    /** The resolved fused method signature (input blocks then constant primitives, returning the projection block). */
    private static MethodType fusedType(
        ElementKind[] predicateInputElements,
        ElementKind[] projectionInputElements,
        ElementKind projectionOutputElement,
        Class<?>[] predicateConstantTypes,
        Class<?>[] projectionConstantTypes
    ) {
        List<Class<?>> params = new ArrayList<>();
        params.add(BlockFactory.class);
        params.add(Warnings[].class);
        params.add(int.class);
        params.add(int[].class);
        for (ElementKind element : predicateInputElements) {
            params.add(element.blockClass());
        }
        for (ElementKind element : projectionInputElements) {
            params.add(element.blockClass());
        }
        for (Class<?> t : predicateConstantTypes) {
            params.add(t);
        }
        for (Class<?> t : projectionConstantTypes) {
            params.add(t);
        }
        return MethodType.methodType(projectionOutputElement.blockClass(), params);
    }

    private static Class<?>[] constantParamTypes(List<FusionNode.Constant> constants) {
        Class<?>[] types = new Class<?>[constants.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = constantParamClass(constants.get(i).element());
        }
        return types;
    }

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
     * The runtime evaluator. Reads its leaf inputs directly from the page (borrowed, never closed), then dispatches to
     * the fused method — filling the surviving {@code positions} and returning the compact projection block — or, when
     * fusion failed at plan time (or a defensive eval-time {@link LinkageError} disabled it), reproduces
     * {@code Filter → Eval} from the two unfused evaluators.
     */
    private static final class FusedFilterEvalEvaluator implements FilterEvalEvaluator {

        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FusedFilterEvalEvaluator.class);

        private final Source[] warningSources;
        private final int[] predicateChannels;
        private final int[] projectionChannels;
        private final MethodHandle fusedHandle;
        private final Object[] predicateConstants;
        private final Object[] projectionConstants;
        private final ExpressionEvaluator.Factory unfusedPredicateFactory;
        private final ExpressionEvaluator.Factory unfusedProjectionFactory;
        private final DriverContext driverContext;
        private final String shape;

        private Warnings[] warnings;
        // Lazily built unfused pair, used either as the plan-time fallback (fusedHandle == null) or after a defensive
        // eval-time LinkageError; closed with this evaluator.
        private ExpressionEvaluator unfusedPredicate;
        private ExpressionEvaluator unfusedProjection;
        private boolean fusionDisabled;

        FusedFilterEvalEvaluator(
            Source[] warningSources,
            int[] predicateChannels,
            int[] projectionChannels,
            MethodHandle fusedHandle,
            Object[] predicateConstants,
            Object[] projectionConstants,
            ExpressionEvaluator.Factory unfusedPredicateFactory,
            ExpressionEvaluator.Factory unfusedProjectionFactory,
            DriverContext driverContext,
            String shape
        ) {
            this.warningSources = warningSources;
            this.predicateChannels = predicateChannels;
            this.projectionChannels = projectionChannels;
            this.fusedHandle = fusedHandle;
            this.predicateConstants = predicateConstants;
            this.projectionConstants = projectionConstants;
            this.unfusedPredicateFactory = unfusedPredicateFactory;
            this.unfusedProjectionFactory = unfusedProjectionFactory;
            this.driverContext = driverContext;
            this.shape = shape;
        }

        @Override
        public Block evalProjection(Page page, int[] positions) {
            if (fusedHandle == null || fusionDisabled) {
                return unfusedProjection(page, positions);
            }
            int positionCount = page.getPositionCount();
            int predArity = predicateChannels.length;
            int projArity = projectionChannels.length;
            int predConst = predicateConstants.length;
            int projConst = projectionConstants.length;
            BlockFactory blockFactory = driverContext.blockFactory();
            try {
                if (injectEvalLinkageErrorForTests) {
                    throw new LinkageError("injected eval-time linkage failure for shape [" + shape + "]");
                }
                Object[] args = new Object[4 + predArity + projArity + predConst + projConst];
                int i = 0;
                args[i++] = blockFactory;
                args[i++] = warnings();
                args[i++] = positionCount;
                args[i++] = positions;
                for (int c = 0; c < predArity; c++) {
                    args[i++] = page.getBlock(predicateChannels[c]);
                }
                for (int c = 0; c < projArity; c++) {
                    args[i++] = page.getBlock(projectionChannels[c]);
                }
                System.arraycopy(predicateConstants, 0, args, i, predConst);
                i += predConst;
                System.arraycopy(projectionConstants, 0, args, i, projConst);
                return (Block) fusedHandle.invokeWithArguments(args);
            } catch (LinkageError e) {
                fusionDisabled = true;
                logger.warn(() -> "fused filter-eval failed to link for shape [" + shape + "]; falling back to unfused", e);
                FusionTelemetry.recordFailure(shape);
                return unfusedProjection(page, positions);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        /**
         * The unfused fallback: evaluate the predicate over the whole page (computing surviving positions exactly as
         * {@link org.elasticsearch.compute.operator.FilterOperator} does — null / multi-value treated as false), then
         * evaluate the projection over ONLY the surviving rows (via a filtered page, so it sees the same survivors the
         * unfused {@code Eval} would and emits no warnings for dropped rows), returning the compact projection block.
         */
        private Block unfusedProjection(Page page, int[] positions) {
            int positionCount = page.getPositionCount();
            int count = 0;
            try (BooleanBlock test = (BooleanBlock) unfusedPredicate().eval(page)) {
                for (int p = 0; p < positionCount; p++) {
                    if (test.isNull(p) || test.getValueCount(p) != 1) {
                        continue;
                    }
                    if (test.getBoolean(test.getFirstValueIndex(p))) {
                        positions[count++] = p;
                    }
                }
            }
            Page filtered = (count == positionCount) ? page.shallowCopy() : page.filter(false, positions, 0, count);
            try {
                return unfusedProjection().eval(filtered);
            } finally {
                filtered.releaseBlocks();
            }
        }

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

        private ExpressionEvaluator unfusedPredicate() {
            if (unfusedPredicate == null) {
                this.unfusedPredicate = unfusedPredicateFactory.get(driverContext);
            }
            return unfusedPredicate;
        }

        private ExpressionEvaluator unfusedProjection() {
            if (unfusedProjection == null) {
                this.unfusedProjection = unfusedProjectionFactory.get(driverContext);
            }
            return unfusedProjection;
        }

        @Override
        public long baseRamBytesUsed() {
            return BASE_RAM_BYTES_USED;
        }

        @Override
        public void close() {
            org.elasticsearch.core.Releasables.closeExpectNoException(unfusedPredicate, unfusedProjection);
        }

        @Override
        public String toString() {
            return "Fused[shape=" + shape + ", fused=" + (fusedHandle != null) + "]";
        }
    }
}
