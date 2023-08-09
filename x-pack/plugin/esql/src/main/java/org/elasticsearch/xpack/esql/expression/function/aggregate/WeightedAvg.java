/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.aggregate;

import org.elasticsearch.xpack.esql.expression.SurrogateExpression;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.DataTypes;

import java.util.List;

import static java.util.Collections.singletonList;
import static org.elasticsearch.xpack.ql.expression.TypeResolutions.ParamOrdinal.DEFAULT;
import static org.elasticsearch.xpack.ql.expression.TypeResolutions.isNumeric;

public class WeightedAvg extends AggregateFunction implements SurrogateExpression {

    private final Expression weight;

    public WeightedAvg(Source source, Expression field, Expression weight) {
        super(source, field, singletonList(weight));
        this.weight = weight;
    }

    @Override
    protected TypeResolution resolveType() {
        var sourceText = sourceText();
        var resolution = isNumeric(field(), sourceText, DEFAULT);
        if (resolution.resolved()) {
            resolution = isNumeric(weight, sourceText, DEFAULT);
        }
        return resolution;
    }

    @Override
    public DataType dataType() {
        return DataTypes.DOUBLE;
    }

    @Override
    protected NodeInfo<WeightedAvg> info() {
        return NodeInfo.create(this, WeightedAvg::new, field(), weight);
    }

    @Override
    public WeightedAvg replaceChildren(List<Expression> newChildren) {
        return new WeightedAvg(source(), newChildren.get(0), newChildren.get(1));
    }

    @Override
    public Expression surrogate() {
        var s = source();
        var field = field();

        Expression surrogate = null;
        // if the weight is a constant, turn this into a regular average
        if (weight.foldable()) {
            var literal = Literal.of(weight);
            surrogate = new Mul(s, new Avg(s, field), literal);
        } else {
            var mul = new Mul(s, field, weight);
            surrogate = new Div(s, new Sum(s, mul), new Sum(s, weight));
        }
        return surrogate;
    }
}
