/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.eql.qa.debug;

import org.apache.http.HttpHost;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.client.EqlClient;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xpack.ql.qa.debug.EmbeddedQlServer;
import org.elasticsearch.xpack.ql.qa.debug.QlHttpServer;
import org.elasticsearch.xpack.ql.qa.debug.QlTransportClient;

import java.net.InetSocketAddress;

/**
 * Embedded JDBC server for the JDBC endpoints.
 */
public class EmbeddedEqlServer extends EmbeddedQlServer {

    private EqlClient eqlClient;
    private RestHighLevelClient restClient;

    @Override
    protected EqlTransportClient client() {
        return new EqlTransportClient();
    }

    @Override
    protected QlHttpServer server(QlTransportClient client) {
        return new EqlHttpServer(client);
    }

    @Override
    protected void postProcess(QlHttpServer server) {
        InetSocketAddress address = server.address();
        restClient = new RestHighLevelClient(RestClient.builder(new HttpHost(address.getAddress(), address.getPort())));
        eqlClient = restClient.eql();
    }

    @Override
    protected void after() {
        IOUtils.closeWhileHandlingException(restClient);
        super.after();
    }

    EqlClient eqlClient() {
        return eqlClient;
    }
}