/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.search.aggregations.matrix.MatrixAggregationPlugin;
import org.elasticsearch.xpack.ql.qa.debug.QlTransportClient;
import org.elasticsearch.xpack.sql.plugin.SqlPlugin;

import java.util.List;

class SqlTransportClient extends QlTransportClient {

    SqlTransportClient() {
        super(List.of(
            // maybe?
            // ReindexPlugin.class, PercolatorPlugin.class, MustachePlugin.class, ParentJoinPlugin.class
            MatrixAggregationPlugin.class,
            // SQL
            SqlPlugin.class));
    }
}