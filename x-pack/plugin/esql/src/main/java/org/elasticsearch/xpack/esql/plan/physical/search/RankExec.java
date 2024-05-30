/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical.search;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.UnaryExec;

import java.util.List;
import java.util.Objects;

public class RankExec extends UnaryExec {

    private final Expression query;

    public RankExec(Source source, PhysicalPlan child, Expression query) {
        super(source, child);
        this.query = query;
    }

    @Override
    protected NodeInfo<RankExec> info() {
        return NodeInfo.create(this, RankExec::new, child(), query);
    }

    @Override
    public RankExec replaceChild(PhysicalPlan newChild) {
        return new RankExec(source(), newChild, query);
    }

    public Expression query() {
        return query;
    }

    @Override
    public List<Attribute> output() {
        return child().output();
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, child());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        RankExec other = (RankExec) obj;
        return Objects.equals(query, other.query) && Objects.equals(child(), other.child());
    }
}
