/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.core.Releasables;

/**
 * Filters rows from input {@link Page}s. Keeps things that evaluate to {@code true}
 * and discards things that return {@code false} or {@code null}.
 * {@snippet lang="txt" :
 * ┌──────┬──────────┬────────────┐    ┌──────┬──────────┬────────────┐
 * │  ref │ class    │ discovered │    │  ref │ class    │ discovered │
 * ├──────┼──────────┼────────────┤    ├──────┼──────────┼────────────┤
 * │  173 │ Euclid   │ 1993-01-01 │    │  173 │ Euclid   │ 1993-01-01 │
 * │ 2317 │ Keter    │ 1922-01-01 │ -> │ 2639 │ Euclid   │ 2010-01-01 │
 * │ 2639 │ Euclid   │ 2010-01-01 │    │ 3001 │ Euclid   │ 2000-01-02 │
 * │ 3000 │ Thaumiel │ 1971-01-01 │    └──────┴──────────┴────────────┘
 * │ 3001 │ Euclid   │ 2000-01-02 │
 * │ 5000 │ Safe     │ 2020-12-04 │
 * └──────┴──────────┴────────────┘
 * }
 * <p>
 *     {@link ExpressionEvaluator}s are the things that actually evaluate the filter.
 *     They form a tree. {@code GREATER_THAN(LENGTH(class), DATE_EXTRACT("month", discovered))}
 *     looks like:
 * </p>
 * {@snippet lang="txt" :
 *                  ┌──────────────┐
 *                  │ GREATER_THAN │
 *                  └──────┬───────┘
 *        ┌────────────────┴────────────────┐
 *        ▼                                 ▼
 *   ┌────────┐                      ┌──────────────┐
 *   │ LENGTH │                      │ DATE_EXTRACT │
 *   └────┬───┘                      └──────┬───────┘
 *        │                      ┌──────────┴──────────┐
 *        ▼                      ▼                     ▼
 * ┌─────────────┐     ┌──────────────────┐  ┌──────────────────┐
 * │ LOAD(class) │     │ LITERAL("month") │  │ LOAD(discovered) │
 * └─────────────┘     └──────────────────┘  └──────────────────┘
 * }
 * <p>
 *     Which evaluates to like:00b
 * </p>
 * {@snippet lang="txt" :
 * ┌──────┬──────────┬────────────┐    ┌──────┬──────────┬────────────┐
 * │  ref │ class    │ discovered │    │  ref │ class    │ discovered │
 * ├──────┼──────────┼────────────┤    ├──────┼──────────┼────────────┤
 * │  173 │ Euclid   │ 1993-01-01 │    │  173 │ Euclid   │ 1993-01-01 │
 * │ 2317 │ Keter    │ 1922-01-01 │    │ 2317 │ Keter    │ 1922-01-01 │
 * │ 2639 │ Euclid   │ 2010-01-01 │ -> │ 2639 │ Euclid   │ 2010-01-01 │
 * │ 3000 │ Thaumiel │ 1971-01-01 │    │ 3000 │ Thaumiel │ 1971-01-01 │
 * │ 3001 │ Euclid   │ 2000-01-02 │    │ 3001 │ Euclid   │ 2000-01-02 │
 * │ 5000 │ Safe     │ 2020-12-04 │    └──────┴──────────┴────────────┘
 * └──────┴──────────┴────────────┘
 * }
 * <p>
 *     An evaluator that implements {@link PositionsEvaluator} writes the selected positions
 *     itself. Any other evaluator returns a {@link BooleanBlock} that is scanned for its
 *     {@code true} positions.
 * </p>
 */
public class FilterOperator extends AbstractPageMappingOperator {

    private final ExpressionEvaluator evaluator;
    /**
     * {@link #evaluator} as a {@link PositionsEvaluator}, or {@code null} when it can only
     * {@link ExpressionEvaluator#eval}. Resolved once here rather than with an {@code instanceof} per page.
     */
    private final PositionsEvaluator positionsEvaluator;
    /**
     * Cached {@link #toString()} representation. The evaluator tree is immutable after construction,
     * so its string form is deterministic. {@link Driver} reads this on every status update; for
     * deep predicates (e.g. nested {@code LIKE} / boolean logic chains) recomputing it walks the
     * whole evaluator tree and can dominate CPU.
     */
    private final String description;

    public record FilterOperatorFactory(ExpressionEvaluator.Factory evaluatorSupplier) implements OperatorFactory {

        @Override
        public Operator get(DriverContext driverContext) {
            return new FilterOperator(evaluatorSupplier.get(driverContext));
        }

        @Override
        public String describe() {
            return "FilterOperator[evaluator=" + evaluatorSupplier + "]";
        }
    }

    public FilterOperator(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
        this.positionsEvaluator = evaluator instanceof PositionsEvaluator positions ? positions : null;
        this.description = "FilterOperator[evaluator=" + evaluator + ']';
    }

    @Override
    protected Page process(Page page) {
        try {
            int positionCount = page.getPositionCount();
            int[] positions = new int[positionCount];
            int rowCount = positionsEvaluator != null
                ? positionsEvaluator.filterPositions(page, positions)
                : evalPositions(page, positions);
            if (rowCount == 0) {
                // Also covers a predicate that is null everywhere: null is like false, so no values are selected.
                return null;
            }
            if (rowCount == positionCount) {
                return page.shallowCopy();
            }
            return page.filter(false, positions, 0, rowCount);
        } finally {
            page.releaseBlocks();
        }
    }

    /** Evaluates the predicate into a {@link BooleanBlock} and scans it for the selected positions. */
    private int evalPositions(Page page, int[] positions) {
        try (BooleanBlock test = (BooleanBlock) evaluator.eval(page)) {
            // TODO we can detect constant true or false from the type
            return PositionsEvaluator.selectedPositions(test, positions);
        }
    }

    @Override
    public String toString() {
        return description;
    }

    @Override
    public void close() {
        Releasables.closeExpectNoException(evaluator, super::close);
    }
}
