/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ql.qa.debug;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;

/**
 * A generic proxy that will execute the given action against a specific node.
 */
public class TransportActionNodeProxy<Request extends ActionRequest, Response extends ActionResponse> {

    private final TransportService transportService;
    private final ActionType<Response> action;
    private final TransportRequestOptions transportOptions;

    public TransportActionNodeProxy(Settings settings, ActionType<Response> action, TransportService transportService) {
        this.action = action;
        this.transportService = transportService;
        this.transportOptions = action.transportOptions();
    }

    public void execute(final DiscoveryNode node, final Request request, final ActionListener<Response> listener) {
        ActionRequestValidationException validationException = request.validate();
        if (validationException != null) {
            listener.onFailure(validationException);
            return;
        }
        transportService.sendRequest(node, action.name(), request, transportOptions, new ActionListenerResponseHandler<>(listener, action
                .getResponseReader()));
    }
}
