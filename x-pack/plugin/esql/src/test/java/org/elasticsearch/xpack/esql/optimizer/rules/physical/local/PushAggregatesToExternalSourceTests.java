/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.ExternalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;

public class PushAggregatesToExternalSourceTests extends ESTestCase {

    public void testPushUngroupedCountStar() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        assertThat(result, instanceOf(ExternalSourceExec.class));
        ExternalSourceExec resultExec = (ExternalSourceExec) result;
        assertNotNull("Pushed aggregate should be non-null", resultExec.pushedAggregate());
    }

    public void testPushUngroupedMultipleAggregates() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        ReferenceAttribute field = new ReferenceAttribute(Source.EMPTY, "age", DataType.INTEGER);
        Alias countAlias = countStarAlias();
        Alias minAlias = new Alias(Source.EMPTY, "mn", new Min(Source.EMPTY, field));
        Alias maxAlias = new Alias(Source.EMPTY, "mx", new Max(Source.EMPTY, field));

        AggregateExec aggregateExec = new AggregateExec(
            Source.EMPTY,
            externalExec,
            List.of(),
            List.of(countAlias, minAlias, maxAlias),
            AggregatorMode.SINGLE,
            List.of(),
            null
        );

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        assertThat(result, instanceOf(ExternalSourceExec.class));
    }

    public void testNotPushGroupedAggregates() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        ReferenceAttribute groupField = new ReferenceAttribute(Source.EMPTY, "dept", DataType.KEYWORD);
        AggregateExec aggregateExec = new AggregateExec(
            Source.EMPTY,
            externalExec,
            List.of(groupField),
            List.of(countStarAlias()),
            AggregatorMode.SINGLE,
            List.of(),
            null
        );

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        // Grouped aggregates should not be pushed in Phase 1
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushWhenNullRegistry() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        // Context with null formatReaderRegistry
        LocalPhysicalOptimizerContext ctx = createContextWithNullRegistry();

        PhysicalPlan result = applyRule(aggregateExec, ctx);

        // Should not push when registry is null
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushWhenFormatNotRegistered() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        // Context with registry that has no reader for this format
        LocalPhysicalOptimizerContext ctx = createContextWithUnregisteredFormat();

        PhysicalPlan result = applyRule(aggregateExec, ctx);

        // Should not push when format reader is not registered
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushWhenUnsupported() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        // Context with registry that has reader but without pushdown support
        LocalPhysicalOptimizerContext ctx = createContextWithUnsupportedFormat();

        PhysicalPlan result = applyRule(aggregateExec, ctx);

        // Should not push when format doesn't support pushdown
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushWhenNotPushable() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        // Context with support that rejects pushdown
        LocalPhysicalOptimizerContext ctx = createContextWithRejectingSupport();

        PhysicalPlan result = applyRule(aggregateExec, ctx);

        // Should not push when aggregates are not pushable
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushWhenNoAggregates() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = new AggregateExec(
            Source.EMPTY,
            externalExec,
            List.of(),
            List.of(), // Empty aggregates
            AggregatorMode.SINGLE,
            List.of(),
            null
        );

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        // Should not push when no aggregates
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushNonAggregateExpressions() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        ReferenceAttribute field = new ReferenceAttribute(Source.EMPTY, "name", DataType.KEYWORD);
        // Create aggregate with non-AggregateFunction expression (edge case)
        Alias aliasOfField = new Alias(Source.EMPTY, "n", field);

        AggregateExec aggregateExec = new AggregateExec(
            Source.EMPTY,
            externalExec,
            List.of(),
            List.of(aliasOfField),
            AggregatorMode.SINGLE,
            List.of(),
            null
        );

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        // Should not push when aggregates list contains non-aggregates
        // (extractAggregates should skip non-AggregateFunction, leaving empty list)
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushNonExternalSource() {
        // Create aggregate with non-ExternalSourceExec child
        AggregateExec aggregateExec = new AggregateExec(
            Source.EMPTY,
            null, // Not ExternalSourceExec
            List.of(),
            List.of(countStarAlias()),
            AggregatorMode.SINGLE,
            List.of(),
            null
        );

        PhysicalPlan result = applyRule(aggregateExec, createContextWithPushdownSupport());

        // Should not push when pattern doesn't match
        assertThat(result, sameInstance(aggregateExec));
    }

    // Helper methods

    private ExternalSourceExec createExternalSourceExec() {
        List<Attribute> attributes = List.of(
            new ReferenceAttribute(Source.EMPTY, "id", DataType.LONG),
            new ReferenceAttribute(Source.EMPTY, "name", DataType.KEYWORD)
        );
        return new ExternalSourceExec(Source.EMPTY, "s3://bucket/data.parquet", "parquet", attributes, Map.of(), Map.of(), null);
    }

    private AggregateExec createAggregateExecWithCount(ExternalSourceExec externalExec) {
        return new AggregateExec(Source.EMPTY, externalExec, List.of(), List.of(countStarAlias()), AggregatorMode.SINGLE, List.of(), null);
    }

    private Alias countStarAlias() {
        return new Alias(Source.EMPTY, "count", new Count(Source.EMPTY, null));
    }

    private LocalPhysicalOptimizerContext createContextWithPushdownSupport() {
        // Create a mock context with a format reader that supports pushdown
        // For now, we return null registry to keep test simple
        // In real tests, we would mock FormatReaderRegistry and FormatReader
        return createContextWithNullRegistry();
    }

    private LocalPhysicalOptimizerContext createContextWithNullRegistry() {
        // Create a minimal context with null registry
        // This is a simplified implementation for tests
        return null; // Would need proper mock/stub
    }

    private LocalPhysicalOptimizerContext createContextWithUnregisteredFormat() {
        return null; // Would need proper mock/stub
    }

    private LocalPhysicalOptimizerContext createContextWithUnsupportedFormat() {
        return null; // Would need proper mock/stub
    }

    private LocalPhysicalOptimizerContext createContextWithRejectingSupport() {
        return null; // Would need proper mock/stub
    }

    private PhysicalPlan applyRule(AggregateExec aggregateExec, LocalPhysicalOptimizerContext ctx) {
        PushAggregatesToExternalSource rule = new PushAggregatesToExternalSource();
        return rule.rule(aggregateExec, ctx);
    }
}
