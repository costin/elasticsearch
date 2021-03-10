/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ql.qa.debug;

import org.apache.logging.log4j.LogManager;
import org.junit.rules.ExternalResource;

import java.security.AccessControlException;

/**
 * Embedded server that uses the transport client to power
 * some endpoints in the same JVM as the tests.
 */
public abstract class EmbeddedQlServer extends ExternalResource implements AutoCloseable {

    private QlTransportClient client;
    private QlHttpServer server;

    @Override
    protected void before() throws Throwable {
        try {
            client = client();
            // update static reference
            ClientReference.HANDLER.actualClient = client;
        } catch (ExceptionInInitializerError e) {
            if (e.getCause() instanceof AccessControlException) {
                throw new RuntimeException(getClass().getSimpleName() + " is not available with the security manager", e);
            } else {
                throw e;
            }
        }

        server = server(client);
        server.start(0);

        postProcess(server);

        LogManager.getLogger(EmbeddedQlServer.class).info("{} started at [{}]", getClass().getSimpleName(), server.url());
    }

    protected abstract QlTransportClient client();

    protected abstract QlHttpServer server(QlTransportClient client);

    protected void postProcess(QlHttpServer server) {}

    @Override
    public void close() throws Exception {
        after();
    }

    @Override
    protected void after() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}