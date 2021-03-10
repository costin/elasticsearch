/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ql.qa.debug;

import com.sun.net.httpserver.HttpServer;

import org.elasticsearch.client.Client;
import org.elasticsearch.core.SuppressForbidden;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressForbidden(reason = "use http server")
public abstract class QlHttpServer {

    private final Client client;
    private final QlNodeClient qlClient;

    private HttpServer server;
    private ExecutorService executor;

    public QlHttpServer(Client client) {
        this.client = client;
        this.qlClient = client instanceof QlNodeClient ? (QlNodeClient) client : wrapClient(client);
    }

    protected abstract QlNodeClient wrapClient(Client client);

    public void start(int port) throws IOException {
        // similar to Executors.newCached but with a smaller bound and much smaller keep-alive
        executor = new ThreadPoolExecutor(1, 10, 250, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>());

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        initialize(server);
        server.setExecutor(executor);
        server.start();
    }

    private void initialize(HttpServer server) {
        // initialize cursor
        initializeActions(server, qlClient);
    }

    protected abstract void initializeActions(HttpServer server, QlNodeClient client);


    public void stop() {
        server.stop(1);
        server = null;
        executor.shutdownNow();
        executor = null;
    }

    public InetSocketAddress address() {
        return server != null ? server.getAddress() : null;
    }

    public String url() {
        return server != null ? "localhost:" + address().getPort() : "<not started>";
    }

    public Client client() {
        return client;
    }
}
