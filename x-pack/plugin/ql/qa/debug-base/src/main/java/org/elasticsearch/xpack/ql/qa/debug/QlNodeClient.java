/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ql.qa.debug;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;

import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.emptyMap;

/**
 * Base implementation for a client node that executes QL queries locally.
 */
public abstract class QlNodeClient extends NodeClient {
    private final Client in;
    private final AtomicLong taskCounter = new AtomicLong();

    public QlNodeClient(Client in) {
        super(in.settings(), in.threadPool());
        this.in = in;

        initialize(emptyMap(), null, () -> "debug-node", null, null, null);
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> Task
            executeLocally(ActionType<Response> action, Request request, ActionListener<Response> listener) {
        doExecute(action, request, listener);
        return new CancellableTask(taskCounter.incrementAndGet(), "ql", "debug", "description", null, emptyMap());
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void
            doExecute(ActionType<Response> action, Request request, ActionListener<Response> listener) {
        in.execute(action, request, listener);
    }
}
