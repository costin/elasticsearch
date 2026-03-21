/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import org.elasticsearch.xpack.esql.core.expression.Expression;

import java.util.List;

/**
 * SPI for computing aggregates from format-level metadata (e.g., Parquet row-group stats, ORC stripe stats)
 * without scanning the actual data.
 * <p>
 * Implementations are queried during local physical optimization to determine if aggregates can be pushed
 * to the format reader. If pushable, an opaque hint object is produced for the format reader to use
 * during execution to extract aggregate values from metadata.
 * <p>
 * Phase 1 (current): Ungrouped aggregates only (COUNT, MIN, MAX via statistics).
 * Phase 2 (future): Grouped aggregates, filtered aggregates, partial pushdown.
 */
public interface AggregatePushdownSupport {

    /**
     * Pushability of an aggregate operation.
     */
    enum Pushability {
        /** Aggregate can and should be pushed to the format reader. */
        YES,
        /** Aggregate cannot be pushed (e.g., requires scanning). */
        NO
    }

    /**
     * Determine if the given aggregates can be pushed to this format.
     * <p>
     * Implementations should check:
     * - Ungrouped only (Phase 1): if grouping is non-empty, return NO
     * - Aggregate types: COUNT, MIN, MAX are typically pushable; SUM, AVG are not
     * - Statistics availability: can this format provide the required statistics?
     *
     * @param aggs List of aggregate expressions (typically AggregateFunction) to potentially push
     * @param grouping List of Expression representing grouping columns (empty for ungrouped)
     * @return Pushability.YES if all aggregates can be pushed, NO otherwise
     */
    Pushability canPushAggregates(List<Expression> aggs, List<Expression> grouping);

    /**
     * Produce an opaque hint object for the format reader to use during execution.
     * <p>
     * Called only if canPushAggregates returned YES. The returned hint is passed to the
     * format reader's read() method via FormatReadContext.pushedAggregate().
     * <p>
     * Phase 1 (current): Returns all-or-nothing pushdown (all aggregates pushed or none).
     * Phase 2 (future): Returns partial pushdown with remainder aggregates.
     *
     * @param aggs List of aggregates to push (guaranteed to be pushable)
     * @param grouping List of grouping columns (empty for ungrouped)
     * @return AggregatePushdownResult with opaque hint and any remainder aggregates
     */
    AggregatePushdownResult pushAggregates(List<Expression> aggs, List<Expression> grouping);

    /**
     * Result of pushdown attempt.
     *
     * @param pushedHint Opaque object interpreted by the format reader during execution.
     *                   Never null if aggregates were pushed.
     * @param remainder List of aggregate expressions that could not be pushed and must
     *                  remain in the AggregateExec for normal evaluation.
     *                  Empty if all aggregates were pushed.
     */
    record AggregatePushdownResult(Object pushedHint, List<Expression> remainder) {}

    /**
     * Default unsupported implementation: all aggregates must be evaluated by scanning.
     */
    AggregatePushdownSupport UNSUPPORTED = new AggregatePushdownSupport() {
        @Override
        public Pushability canPushAggregates(List<Expression> aggs, List<Expression> grouping) {
            return Pushability.NO;
        }

        @Override
        public AggregatePushdownResult pushAggregates(List<Expression> aggs, List<Expression> grouping) {
            throw new UnsupportedOperationException("Format does not support aggregate pushdown");
        }
    };
}
