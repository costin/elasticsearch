/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.datasources.spi.AggregatePushdownSupport;
import org.elasticsearch.xpack.esql.datasources.spi.FormatReader;
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
public class PushAggregatesToExternalSource extends PhysicalOptimizerRules.ParameterizedOptimizerRule<AggregateExec, LocalPhysicalOptimizerContext> {

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
        if (ctx.formatReaderRegistry() == null) {
            return aggregateExec;
        }
        String sourceType = externalExec.sourceType();
        FormatReader formatReader;
        try {
            formatReader = ctx.formatReaderRegistry().byName(sourceType);
        } catch (Exception e) {
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

        // Create new ExternalSourceExec with pushedAggregate hint
        ExternalSourceExec pushed = externalExec.withPushedAggregate(result.pushedHint());

        // If all aggregates pushed, return just the external source (no AggregateExec needed)
        if (result.remainder().isEmpty()) {
            return pushed;
        }

        // Otherwise, keep remaining aggregates in AggregateExec
        List<NamedExpression> remainingAggs = buildRemainderAggregates(
            aggregateExec.aggregates(),
            result.remainder()
        );
        // Replace the child and update the aggregates
        AggregateExec withPushedChild = aggregateExec.replaceChild(pushed);
        return withPushedChild.withAggregates(remainingAggs);
    }

    /**
     * Extract AggregateFunction expressions from the aggregates list.
     * Aggregates may be wrapped in Alias, so we extract the function expressions.
     */
    private List<Expression> extractAggregates(List<? extends NamedExpression> aggregates) {
        List<Expression> result = new ArrayList<>();
        for (NamedExpression agg : aggregates) {
            if (agg instanceof Alias alias) {
                // Extract the child expression from the alias
                result.add(alias.child());
            } else {
                // Use the aggregate directly (in case it's an AggregateFunction)
                result.add(agg);
            }
        }
        return result;
    }

    /**
     * Build the list of aggregates that remain (not pushed).
     * Keep the NamedExpression wrappers for aggregates in the remainder list.
     */
    private List<NamedExpression> buildRemainderAggregates(
        List<? extends NamedExpression> original,
        List<Expression> remainder
    ) {
        if (remainder.isEmpty()) {
            return List.of();
        }

        // Create a set of remainder expressions for faster lookup
        Set<Expression> remainderSet = new HashSet<>(remainder);

        List<NamedExpression> result = new ArrayList<>();
        for (NamedExpression origAgg : original) {
            Expression toCheck = origAgg;
            if (origAgg instanceof Alias alias) {
                toCheck = alias.child();
            }
            // Check if this aggregate is in the remainder list
            if (remainderSet.contains(toCheck)) {
                result.add(origAgg);
            }
        }
        return result;
    }
}
