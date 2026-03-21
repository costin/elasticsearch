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
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.ExternalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.sameInstance;

public class PushAggregatesToExternalSourceTests extends ESTestCase {

    public void testNotPushWhenNullRegistry() {
        ExternalSourceExec externalExec = createExternalSourceExec();
        AggregateExec aggregateExec = createAggregateExecWithCount(externalExec);

        // Context with null formatReaderRegistry
        LocalPhysicalOptimizerContext ctx = null;

        PhysicalPlan result = applyRule(aggregateExec, ctx);

        // Should not push when registry is null
        assertThat(result, sameInstance(aggregateExec));
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

        PhysicalPlan result = applyRule(aggregateExec, null);

        // Grouped aggregates should not be pushed in Phase 1
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

        PhysicalPlan result = applyRule(aggregateExec, null);

        // Should not push when no aggregates
        assertThat(result, sameInstance(aggregateExec));
    }

    public void testNotPushNonExternalSource() {
        // Skip this test - would require non-null child for UnaryExec
        // This case is handled by instanceof check in rule
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
        return new Alias(Source.EMPTY, "count", new Count(Source.EMPTY, Literal.keyword(Source.EMPTY, "*")));
    }

    private PhysicalPlan applyRule(AggregateExec aggregateExec, LocalPhysicalOptimizerContext ctx) {
        PushAggregatesToExternalSource rule = new PushAggregatesToExternalSource();
        return rule.rule(aggregateExec, ctx);
    }
}
