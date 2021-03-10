/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.sql.qa.jdbc;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.apache.http.HttpHost;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.ClassRule;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@TestLogging(value = "org.elasticsearch.xpack.sql:TRACE,org.elasticsearch.client:ERROR", reason = "debug")
public class DebugSqlSpec extends SqlSpecTestCase {

    @ClassRule
    public static final EmbeddedSqlServer EMBEDDED_SERVER = new EmbeddedSqlServer();

    @ParametersFactory(shuffle = false, argumentFormatting = PARAM_FORMATTING)
    public static List<Object[]> readScriptSpec() throws Exception {
        Parser parser = specParser();
        return readScriptSpec("/case-functions.sql-spec", parser);
    }

    public DebugSqlSpec(String fileName, String groupName, String testName, Integer lineNumber, String query) {
        super(fileName, groupName, testName, lineNumber, query);
    }

    @Override
    public Connection esJdbc() throws SQLException {
        // use the same random path as the rest of the tests
        randomBoolean();
        return EMBEDDED_SERVER.connection(connectionProperties());
    }

    @Override
    protected boolean logEsResultSet() {
        return true;
    }

    @Override
    protected void assertResults(ResultSet expected, ResultSet elastic) throws SQLException {
        Logger log = logEsResultSet() ? logger : null;

        //
        // uncomment this to printout the result set and create new CSV tests
        //
        //JdbcTestUtils.logLikeCLI(elastic, log);
        JdbcAssert.assertResultSets(expected, elastic, log);
    }

    @Override
    protected RestClient buildClient(Settings settings, HttpHost[] hosts) throws IOException {
        RestClientBuilder builder = RestClient.builder(hosts);
        configureClient(builder, settings);
        // ignore security warnings
        builder.setStrictDeprecationMode(false);
        return builder.build();
    }
}
