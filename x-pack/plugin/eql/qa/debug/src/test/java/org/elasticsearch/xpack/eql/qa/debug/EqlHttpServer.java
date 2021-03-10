/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.eql.qa.debug;


import com.sun.net.httpserver.HttpServer;

import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.ql.qa.debug.QlHttpServer;
import org.elasticsearch.xpack.ql.qa.debug.QlNodeClient;

public class EqlHttpServer extends QlHttpServer {

    public EqlHttpServer(Client client) {
        super(client);
    }

    @Override
    protected QlNodeClient wrapClient(Client client) {
        return new EqlNodeClient(client);
    }

    @Override
    protected void initializeActions(HttpServer server, QlNodeClient client) {
        // root action
        server.createContext("/", new EqlRestHandler(client));
    }
}