
/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.eql.qa.debug;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.elasticsearch.client.eql.EqlSearchResponse;
import org.elasticsearch.test.eql.EqlSpec;
import org.elasticsearch.test.eql.EqlSpecLoader;
import org.elasticsearch.test.eql.EqlSpecTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;

import java.util.HashSet;
import java.util.List;


@TestLogging(value = "org.elasticsearch.xpack.eql.execution.sequence:TRACE", reason = "debug")
public class MitreDebugEqlSpec extends DebugEqlSpec {

    @ParametersFactory(shuffle = false, argumentFormatting = "%2$s")
    public static List<Object[]> readTestSpecs() throws Exception {
        List<EqlSpec> specs = EqlSpecLoader.load("/debug/mitre_queries.toml", new HashSet<>());
        return EqlSpecTestCase.asArray(specs);
    }

    public MitreDebugEqlSpec(String query, String name, long[] eventIds) {
        super(query, name, eventIds);
    }

    @Override
    protected EqlSearchResponse runQuery(String index, String query) throws Exception {
        return super.runQuery("mitre", query);
    }

    @Override
    protected int requestFetchSize() {
        return 10000;
    }

    @Override
    protected int requestSize() {
        return 2000;
    }

    @Override
    protected String requestResultPosition() {
        return "head";
    }

    @Override
    protected String eventCategory() {
        return "event_type";
    }

    @Override
    protected String tiebreaker() {
        return "serial_id";
    }
}
