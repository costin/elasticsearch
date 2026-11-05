/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.expression;

import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.FilterOperator;

/**
 * A boolean {@link ExpressionEvaluator} that can also write the positions where it is {@code true} straight into an
 * {@code int[]}. {@link FilterOperator} only needs those positions, so an evaluator that produces them directly saves
 * the intermediate {@link BooleanBlock} and the second per-row scan over it. {@link FilterOperator} detects this
 * capability once, at construction, so evaluators that only implement {@link #eval} are unaffected.
 */
public interface PositionsEvaluator extends ExpressionEvaluator {

    /**
     * Writes every position of {@code page} at which this predicate is exactly one {@code true} value into
     * {@code positions}, in ascending order starting at index {@code 0}, and returns how many it wrote. A {@code null},
     * {@code false} or multivalued result does not select its position, exactly as {@link #selectedPositions} treats
     * the {@link BooleanBlock} that {@link #eval} would return. Warnings are registered exactly as {@link #eval} would
     * register them for the same page.
     *
     * @param page      the input page; borrowed, neither retained nor released
     * @param positions where the selected positions are written; at least {@code page.getPositionCount()} long. Entries
     *                  past the returned count are scratch and may have been overwritten.
     * @return the number of selected positions, between {@code 0} and {@code page.getPositionCount()}
     */
    int filterPositions(Page page, int[] positions);

    /**
     * The reference selection over an already evaluated predicate: writes, in ascending order, every position of
     * {@code mask} holding exactly one {@code true} value and returns how many it wrote. Null and multivalued
     * positions are not selected. Shared by {@link FilterOperator} and by evaluators that fall back to {@link #eval}.
     */
    static int selectedPositions(BooleanBlock mask, int[] positions) {
        if (mask.areAllValuesNull()) {
            return 0;
        }
        int positionCount = mask.getPositionCount();
        BooleanVector vector = mask.asVector();
        int count = 0;
        if (vector != null) {
            // No nulls or multivalues: record every position and advance only past the true ones. Writing
            // positions[count] unconditionally is safe because count <= p < positionCount.
            for (int p = 0; p < positionCount; p++) {
                positions[count] = p;
                count += vector.getBoolean(p) ? 1 : 0;
            }
            return count;
        }
        for (int p = 0; p < positionCount; p++) {
            if (mask.isNull(p) || mask.getValueCount(p) != 1) {
                // Null is like false, and for now multivalued results are like false too
                continue;
            }
            if (mask.getBoolean(mask.getFirstValueIndex(p))) {
                positions[count++] = p;
            }
        }
        return count;
    }
}
