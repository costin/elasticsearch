/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.matrix.MatrixAggregationPlugin;
import org.elasticsearch.xpack.ql.index.IndexResolver;
import org.elasticsearch.xpack.ql.qa.debug.QlNodeClient;
import org.elasticsearch.xpack.sql.action.SqlClearCursorAction;
import org.elasticsearch.xpack.sql.action.SqlClearCursorRequest;
import org.elasticsearch.xpack.sql.action.SqlClearCursorResponse;
import org.elasticsearch.xpack.sql.action.SqlQueryAction;
import org.elasticsearch.xpack.sql.action.SqlQueryRequest;
import org.elasticsearch.xpack.sql.action.SqlQueryResponse;
import org.elasticsearch.xpack.sql.execution.PlanExecutor;
import org.elasticsearch.xpack.sql.plugin.TransportSqlClearCursorAction;
import org.elasticsearch.xpack.sql.plugin.TransportSqlQueryAction;
import org.elasticsearch.xpack.sql.type.SqlDataTypeRegistry;

import java.util.Objects;

import static java.util.Collections.singletonList;

/**
 * Implements embedded sql mode by intercepting requests to SQL APIs and executing them locally.
 */
public class SqlNodeClient extends QlNodeClient {
    private final IndexResolver resolver;
    private final NamedWriteableRegistry writeableRegistry;

    private final PlanExecutor planExecutor;

    public SqlNodeClient(Client in) {
        super(in);

        SearchModule searchModule = new SearchModule(Settings.EMPTY, singletonList(new MatrixAggregationPlugin()));
        this.writeableRegistry = new NamedWriteableRegistry(searchModule.getNamedWriteables());
        this.resolver = new IndexResolver(in, "elasticsearch", SqlDataTypeRegistry.INSTANCE);
        this.planExecutor = new PlanExecutor(in, resolver, writeableRegistry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Request extends ActionRequest, Response extends ActionResponse> void
            doExecute(ActionType<Response> action, Request request, ActionListener<Response> listener) {
        Objects.requireNonNull(planExecutor, "plan executor not set on EmbeddedClient");

        if (action == SqlQueryAction.INSTANCE) {
            TransportSqlQueryAction.operation(planExecutor, (SqlQueryRequest) request, (ActionListener<SqlQueryResponse>) listener, "user",
                    "elasticsearch");
        } else if (action == SqlClearCursorAction.INSTANCE) {
            TransportSqlClearCursorAction.operation(planExecutor, (SqlClearCursorRequest) request,
                    (ActionListener<SqlClearCursorResponse>) listener);
        } else {
            super.doExecute(action, request, listener);
        }
    }
}