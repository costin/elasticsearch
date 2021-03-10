/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.eql.qa.debug;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.apache.http.HttpHost;
import org.elasticsearch.client.EqlClient;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.eql.EqlSpecTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.ClassRule;

import java.io.IOException;
import java.util.List;


@TestLogging(value = "org.elasticsearch.xpack.eql:TRACE", reason = "debug")
public class DebugEqlSpec extends EqlSpecTestCase {

    @ClassRule
    public static final EmbeddedEqlServer EMBEDDED_SERVER = new EmbeddedEqlServer();

    @ParametersFactory(shuffle = false, argumentFormatting = "%2$s")
    public static List<Object[]> readTestSpecs() throws Exception {
        // List<EqlSpec> specs = EqlSpecLoader.load("/debug/debug_test_queries.toml", new HashSet<>());
        //return EqlSpecTestCase.asArray(specs);

        return EqlSpecTestCase.readTestSpecs();
    }

    public DebugEqlSpec(String query, String name, long[] eventIds) {
        super(query, name, eventIds);
    }

    @Override
    protected EqlClient eqlClient() {
        return EMBEDDED_SERVER.eqlClient();
    }

    @Override
    protected TimeValue timeout() {
        return TimeValue.timeValueMinutes(10);
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
