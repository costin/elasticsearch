/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.xpack.ql.qa.debug.EmbeddedQlServer;
import org.elasticsearch.xpack.ql.qa.debug.QlHttpServer;
import org.elasticsearch.xpack.ql.qa.debug.QlTransportClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.Assert.assertTrue;

/**
 * Embedded JDBC server for the JDBC endpoints.
 */
public class EmbeddedSqlServer extends EmbeddedQlServer {

    private String jdbcUrl;
    private final Properties properties;
    private boolean initialized = false;

    public EmbeddedSqlServer() {
        this(false);
    }

    public EmbeddedSqlServer(boolean debug) {
        properties = new Properties();
        if (debug) {
            properties.setProperty("debug", "true");
        }
    }

    @Override
    protected SqlTransportClient client() {
        initialized = true;
        return new SqlTransportClient();
    }

    @Override
    protected QlHttpServer server(QlTransportClient client) {
        return new SqlHttpServer(client);
    }

    @Override
    protected void postProcess(QlHttpServer server) {
        jdbcUrl = ((SqlHttpServer) server).jdbcUrl();
    }

    public Connection connection(Properties props) throws SQLException {
        assertTrue("ES JDBC Server is null - make sure ES is properly run as a @ClassRule", initialized);
        Properties p = new Properties(properties);
        p.putAll(props);
        return DriverManager.getConnection(jdbcUrl, p);

        //        JdbcDataSource dataSource = new JdbcDataSource();
        //        dataSource.setProperties(properties);
        //        dataSource.setUrl(jdbcUrl);
        //        return dataSource.getConnection();
    }
}