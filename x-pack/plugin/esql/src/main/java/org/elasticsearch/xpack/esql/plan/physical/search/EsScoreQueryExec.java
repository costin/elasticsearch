/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical.search;

import org.elasticsearch.common.Strings;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.index.EsIndex;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.NodeUtils;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataTypes;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EsScoreQueryExec extends EsQueryExec {

    static final EsField SCORE_FIELD = new EsField("_score", DataTypes.DOUBLE, Map.of(), false);

    public EsScoreQueryExec(Source source,
                            EsIndex index,
                            List<Attribute> attrs,
                            QueryBuilder query,
                            Expression limit,
                            List<FieldSort> sorts,
                            Integer estimatedRowSize) {
        super(source, index, IndexMode.STANDARD, attrs, query, limit, sorts, estimatedRowSize);
    }

    public static boolean isScoreAttribute(Attribute attr) {
        return attr instanceof MetadataAttribute && SCORE_FIELD.getName().equals(attr.name());
    }

    @Override
    protected NodeInfo<EsQueryExec> info() {
        return NodeInfo.create(this, EsScoreQueryExec::new, index(), attrs(), query(), limit(), sorts(), estimatedRowSize());
    }

    public EsScoreQueryExec withLimit(Expression limit) {
        return Objects.equals(this.limit(), limit)
            ? this
            : new EsScoreQueryExec(source(), index(), attrs(), query(), limit, sorts(), estimatedRowSize());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
