/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.datasources.spi.AggregatePushdownSupport;
import org.elasticsearch.xpack.esql.datasources.spi.FormatReader;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.optimizer.PhysicalOptimizerRules;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.ExternalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pushes ungrouped aggregate functions (COUNT, MIN, MAX) to external sources when the
 * format reader supports aggregate pushdown and the statistics are available in file metadata.
 * <p>
 * Phase 1 (current): Ungrouped aggregates only (no GROUP BY).
 * Phase 2 (future): Grouped aggregates, filtered aggregates, partial pushdown across mixed files.
 * <p>
 * This rule operates on the pattern: AggregateExec → ExternalSourceExec
 * and must run AFTER PushFiltersToSource to ensure aggregate statistics reflect filtered data.
 * <p>
 * Design:
 * <ul>
 *   <li>Detect ungrouped AggregateExec with ExternalSourceExec child</li>
 *   <li>Query format reader's AggregatePushdownSupport to check if aggregates are pushable</li>
 *   <li>If pushable, create new ExternalSourceExec with opaque aggregate hint</li>
 *   <li>Handle partial pushdown: some aggregates pushed, others remain in AggregateExec</li>
 *   <li>If all aggregates pushed, eliminate AggregateExec entirely</li>
 * </ul>
 */
public class PushAggregatesToExternalSource extends PhysicalOptimizerRules.ParameterizedOptimizerRule<
    AggregateExec,
    LocalPhysicalOptimizerContext> {

    private static final Logger logger = LogManager.getLogger(PushAggregatesToExternalSource.class);

    @Override
    protected PhysicalPlan rule(AggregateExec aggregateExec, LocalPhysicalOptimizerContext ctx) {
        // Pattern: AggregateExec → ExternalSourceExec
        if (!(aggregateExec.child() instanceof ExternalSourceExec externalExec)) {
            return aggregateExec;
        }

        // Phase 1: Ungrouped only (GROUP BY not supported yet)
        if (!aggregateExec.groupings().isEmpty()) {
            return aggregateExec;
        }

        // Get format reader for this source type
        // formatReaderRegistry may be null in test environments or when not configured
        if (ctx.formatReaderRegistry() == null) {
            return aggregateExec;
        }
        String sourceType = externalExec.sourceType();
        FormatReader formatReader;
        try {
            formatReader = ctx.formatReaderRegistry().byName(sourceType);
        } catch (IllegalArgumentException e) {
            // Expected: format reader not registered for this source type, skip optimization
            logger.debug("Format reader not found for source type: {}", sourceType);
            return aggregateExec;
        } catch (Exception e) {
            // Unexpected: log and skip to avoid query failures
            logger.debug("Failed to lookup format reader for aggregate pushdown on type: {}", sourceType, e);
            return aggregateExec;
        }

        // Query format reader's pushdown support
        AggregatePushdownSupport support = formatReader.aggregatePushdownSupport();
        if (support == AggregatePushdownSupport.UNSUPPORTED) {
            return aggregateExec;
        }

        // Extract AggregateFunction expressions from aggregates
        // Aggregates are typically NamedExpression (often Alias with AggregateFunction child)
        List<Expression> aggs = extractAggregates(aggregateExec.aggregates());
        if (aggs.isEmpty()) {
            return aggregateExec;
        }

        // Check if aggregates can be pushed
        if (support.canPushAggregates(aggs, List.of()) != AggregatePushdownSupport.Pushability.YES) {
            return aggregateExec;
        }

        // Get pushed hint + remainder aggregates
        AggregatePushdownSupport.AggregatePushdownResult result = support.pushAggregates(aggs, List.of());

        // Validate that the pushed hint is non-null (SPI contract requirement)
        Object pushedHint = result.pushedHint();
        if (pushedHint == null) {
            throw new IllegalStateException(
                "AggregatePushdownSupport returned null pushedHint after successful pushdown for format: " + sourceType
            );
        }

        // Create new ExternalSourceExec with pushedAggregate hint
        ExternalSourceExec pushed = externalExec.withPushedAggregate(pushedHint);

        // If all aggregates pushed, return just the external source (no AggregateExec needed)
        if (result.remainder().isEmpty()) {
            return pushed;
        }

        // Otherwise, keep remaining aggregates in AggregateExec
        List<NamedExpression> remainingAggs = buildRemainderAggregates(aggregateExec.aggregates(), result.remainder());
        // Replace the child and update the aggregates
        AggregateExec withPushedChild = aggregateExec.replaceChild(pushed);
        return withPushedChild.withAggregates(remainingAggs);
    }

    /**
     * Extract AggregateFunction expressions from the aggregates list.
     * Aggregates may be wrapped in Alias, so we extract the function expressions.
     * Only validates the extracted expressions are actually AggregateFunction instances.
     */
    private List<Expression> extractAggregates(List<? extends NamedExpression> aggregates) {
        List<Expression> result = new ArrayList<>();
        for (NamedExpression agg : aggregates) {
            Expression toCheck = agg;
            if (agg instanceof Alias alias) {
                // Extract the child expression from the alias
                toCheck = alias.child();
            }

            // Validate that the extracted expression is an AggregateFunction
            if (!(toCheck instanceof AggregateFunction)) {
                logger.debug("Skipping non-aggregate in aggregates list: {} ({})", agg.name(), toCheck.getClass().getSimpleName());
                continue;
            }

            result.add(toCheck);
        }
        return result;
    }

    /**
     * Build the list of aggregates that remain (not pushed).
     * Keep the NamedExpression wrappers for aggregates in the remainder list.
     *
     * <p>Remainder matching relies on Expression.equals() for semantic equivalence.
     * This works correctly for simple aggregates like COUNT(*), MIN(x), MAX(y) where
     * the expression tree is stable. More complex scenarios (filtered aggregates,
     * partial pushdown across mixed formats) may need more sophisticated matching.
     */
    private List<NamedExpression> buildRemainderAggregates(List<? extends NamedExpression> original, List<Expression> remainder) {
        if (remainder.isEmpty()) {
            return List.of();
        }

        // Create a set of remainder expressions for faster lookup by equality
        Set<Expression> remainderSet = new HashSet<>(remainder);

        List<NamedExpression> result = new ArrayList<>();
        for (NamedExpression origAgg : original) {
            Expression toCheck = origAgg;
            if (origAgg instanceof Alias alias) {
                toCheck = alias.child();
            }
            // Check if this aggregate is in the remainder list using semantic equality
            if (remainderSet.contains(toCheck)) {
                result.add(origAgg);
            }
        }
        return result;
    }
}
