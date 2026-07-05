/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasables;

/**
 * Fuses a WHERE predicate and a single EVAL projection into one operator: it selects the rows whose predicate is TRUE
 * and, in the same per-position pass, appends the projection column for exactly those rows — producing the same page a
 * {@code FilterOperator(predicate) → EvalOperator(projection)} chain would, without the intermediate full-width
 * predicate {@code BooleanBlock} or the operator boundary.
 *
 * <p>The heavy lifting is done by a {@link FilterEvalEvaluator} (a stitched fused {@link java.lang.invoke.MethodHandle}
 * or an unfused fallback): it fills the surviving positions and returns the COMPACT projection block. This operator
 * owns the page lifecycle — it mirrors {@link FilterOperator}'s page ownership (release the input page's blocks in a
 * {@code finally}) and {@link EvalOperator}'s append semantics (the surviving page gains the projection column).
 */
public class FilterEvalOperator extends AbstractPageMappingOperator {
    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FilterEvalOperator.class);

    public record FilterEvalOperatorFactory(FilterEvalEvaluator.Factory evaluatorSupplier) implements OperatorFactory {

        @Override
        public Operator get(DriverContext driverContext) {
            return new FilterEvalOperator(driverContext, evaluatorSupplier.get(driverContext));
        }

        @Override
        public String describe() {
            return "FilterEvalOperator[evaluator=" + evaluatorSupplier.describe() + "]";
        }
    }

    private final DriverContext ctx;
    private final FilterEvalEvaluator evaluator;
    private final String description;

    public FilterEvalOperator(DriverContext ctx, FilterEvalEvaluator evaluator) {
        this.ctx = ctx;
        this.evaluator = evaluator;
        this.description = "FilterEvalOperator[evaluator=" + evaluator + "]";
        ctx.breaker().addEstimateBytesAndMaybeBreak(BASE_RAM_BYTES_USED + evaluator.baseRamBytesUsed(), "ESQL");
    }

    @Override
    protected Page process(Page page) {
        int positionCount = page.getPositionCount();
        int[] positions = new int[positionCount];
        Block projection = null;
        Page filtered = null;
        boolean handedOff = false;
        try {
            // Fills positions[0..count) with the surviving source rows and returns the compact projection block whose
            // positionCount is exactly that surviving count.
            projection = evaluator.evalProjection(page, positions);
            int count = projection.getPositionCount();
            if (count == 0) {
                // Every row dropped (like FilterOperator returning null): produce no page.
                return null;
            }
            // Build the surviving page (FilterOperator semantics), then append the projection column (EvalOperator
            // semantics). page.filter builds independent filtered blocks; shallowCopy incRefs when all rows survive.
            filtered = (count == positionCount) ? page.shallowCopy() : page.filter(false, positions, 0, count);
            Page result = filtered.appendBlock(projection);
            // The appended page adopts filtered's blocks and the projection block; do not release them here.
            handedOff = true;
            return result;
        } finally {
            if (handedOff == false) {
                // On the drop-all early return or any failure before hand-off, release what we still own.
                if (projection != null) {
                    projection.close();
                }
                if (filtered != null) {
                    filtered.releaseBlocks();
                }
            }
            // Always release the input page's blocks (mirrors FilterOperator): the surviving page holds independent or
            // incRef'd copies, so the original blocks are ours to release.
            page.releaseBlocks();
        }
    }

    @Override
    public String toString() {
        return description;
    }

    @Override
    public void close() {
        Releasables.closeExpectNoException(
            evaluator,
            () -> ctx.breaker().addWithoutBreaking(-BASE_RAM_BYTES_USED - evaluator.baseRamBytesUsed()),
            super::close
        );
    }
}
