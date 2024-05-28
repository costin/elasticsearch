/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.search;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.core.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataTypes;

import java.util.List;
import java.util.Objects;

import static java.util.Arrays.asList;
import static org.elasticsearch.xpack.esql.expression.NamedExpressions.mergeOutputAttributes;

/**
 * Rank commands which performs filtering and scoring of the given query.
 * The score is returned through the _score column (if its exists, it gets overwritten)
 */
public class Rank extends UnaryPlan {

    private final Expression query;
    private List<Attribute> lazyOutput;

    public Rank(Source source, LogicalPlan child, Expression query) {
        super(source, child);
        this.query = query;
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            MetadataAttribute score = new MetadataAttribute(source(), "_score", DataTypes.DOUBLE, false);
            lazyOutput = mergeOutputAttributes(asList(score), child().output());
        }

        return lazyOutput;
    }

    @Override
    public boolean expressionsResolved() {
        return query.resolved();
    }

    @Override
    public Rank replaceChild(LogicalPlan newChild) {
        return new Rank(source(), newChild, query);
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Rank::new, child(), query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), query);
    }

    @Override
    public boolean equals(Object obj) {
        if (false == super.equals(obj)) {
            return false;
        }
        return Objects.equals(query, ((Rank) obj).query);
    }
}
