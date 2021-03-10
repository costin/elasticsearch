/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.eql.qa.debug;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.xpack.eql.action.EqlSearchAction;
import org.elasticsearch.xpack.eql.action.EqlSearchRequest;
import org.elasticsearch.xpack.eql.action.EqlSearchResponse;
import org.elasticsearch.xpack.eql.action.EqlSearchTask;
import org.elasticsearch.xpack.eql.execution.PlanExecutor;
import org.elasticsearch.xpack.eql.plugin.TransportEqlSearchAction;
import org.elasticsearch.xpack.ql.index.IndexResolver;
import org.elasticsearch.xpack.ql.qa.debug.QlNodeClient;
import org.elasticsearch.xpack.ql.type.DefaultDataTypeRegistry;

import java.util.Objects;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Implements embedded sql mode by intercepting requests to SQL APIs and executing them locally.
 */
public class EqlNodeClient extends QlNodeClient {
    private final IndexResolver resolver;
    private final NamedWriteableRegistry writeableRegistry;

    private final PlanExecutor planExecutor;

    public EqlNodeClient(Client in) {
        super(in);

        SearchModule searchModule = new SearchModule(Settings.EMPTY, emptyList());
        this.writeableRegistry = new NamedWriteableRegistry(searchModule.getNamedWriteables());
        this.resolver = new IndexResolver(in, "elasticsearch", DefaultDataTypeRegistry.INSTANCE);
        this.planExecutor = new PlanExecutor(in, resolver, writeableRegistry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Request extends ActionRequest, Response extends ActionResponse> void
            doExecute(ActionType<Response> action, Request request, ActionListener<Response> listener) {
        Objects.requireNonNull(planExecutor, "plan executor not set on EmbeddedClient");

        if (action == EqlSearchAction.INSTANCE) {
            EqlSearchTask task = new EqlSearchTask(10, "type", EqlSearchAction.NAME, "eql-task", new TaskId("node_id", 0),
                    emptyMap(), null, null, EqlSearchRequest.DEFAULT_KEEP_ALIVE);
            TransportEqlSearchAction.operation(planExecutor,
                    task,
                    (EqlSearchRequest) request,
                    "user", "elasticsearch","node_id",
                    (ActionListener<EqlSearchResponse>) listener);
        } else {
            super.doExecute(action, request, listener);
        }
    }
}
