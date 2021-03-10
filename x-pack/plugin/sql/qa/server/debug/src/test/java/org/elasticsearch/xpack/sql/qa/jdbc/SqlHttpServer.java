/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import com.sun.net.httpserver.HttpServer;

import org.elasticsearch.client.Client;
import org.elasticsearch.rest.action.RestMainAction;
import org.elasticsearch.xpack.ql.qa.debug.QlHandler;
import org.elasticsearch.xpack.ql.qa.debug.QlHttpServer;
import org.elasticsearch.xpack.ql.qa.debug.QlNodeClient;
import org.elasticsearch.xpack.sql.plugin.RestSqlClearCursorAction;
import org.elasticsearch.xpack.sql.plugin.RestSqlQueryAction;
import org.elasticsearch.xpack.sql.proto.Protocol;

public class SqlHttpServer extends QlHttpServer {

    public SqlHttpServer(Client client) {
        super(client);
    }

    @Override
    protected QlNodeClient wrapClient(Client client) {
        return new SqlNodeClient(client);
    }

    @Override
    protected void initializeActions(HttpServer server, QlNodeClient client) {
        // root action
        server.createContext("/", new QlHandler(client, new RestMainAction()));
        server.createContext(Protocol.SQL_QUERY_REST_ENDPOINT, new QlHandler(client, new RestSqlQueryAction()));
        server.createContext(Protocol.CLEAR_CURSOR_REST_ENDPOINT, new QlHandler(client, new RestSqlClearCursorAction()));
    }

    public String jdbcUrl() {
        return "jdbc:es://" + url();
    }
}