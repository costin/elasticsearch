/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasable;

/**
 * The runtime seam behind {@link FilterEvalOperator}: a fused WHERE predicate + single EVAL projection evaluated in one
 * per-position pass. It computes the surviving row set of a page and, for each surviving row, the projection value — the
 * combined product of the unfused {@code FilterOperator(predicate) → EvalOperator(projection)} chain, without
 * materialising the intermediate full-width predicate {@code BooleanBlock}.
 *
 * <p>This interface lives in {@code :compute} (so {@link FilterEvalOperator} can consume it) while its implementations
 * live in esql-proper — the fused one dispatches to a stitched {@link org.elasticsearch.compute.operator.fusion.Stitcher
 * Stitcher} {@link java.lang.invoke.MethodHandle}, and a fallback one reproduces {@code Filter → Eval} from the two
 * unfused evaluators. Both honour the identical contract below.
 */
public interface FilterEvalEvaluator extends Releasable {

    /**
     * Evaluates the fused predicate + projection over {@code page}. Writes the surviving source positions (those whose
     * predicate is TRUE) into {@code positions[0..count)} in ascending order, and returns the COMPACT projection
     * {@link Block} holding one value (or null) per surviving row — so the returned block's
     * {@link Block#getPositionCount()} is exactly the surviving row {@code count}.
     *
     * @param page      the input page (borrowed; neither retained nor released by this method)
     * @param positions a scratch buffer of at least {@code page.getPositionCount()} ints to receive surviving positions
     * @return the compact projection block (caller owns it and must release it)
     */
    Block evalProjection(Page page, int[] positions);

    /** Heap used by the evaluator, mirroring {@link org.elasticsearch.compute.expression.ExpressionEvaluator#baseRamBytesUsed}. */
    long baseRamBytesUsed();

    /** A thread-safe factory building one {@link FilterEvalEvaluator} per {@link DriverContext}. */
    interface Factory {
        FilterEvalEvaluator get(DriverContext context);

        /** Human-readable description for the operator's {@code toString}/{@code describe}. */
        String describe();
    }
}
