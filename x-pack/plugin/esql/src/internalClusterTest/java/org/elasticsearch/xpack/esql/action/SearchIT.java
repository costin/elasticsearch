/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.Build;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.esql.action.ColumnInfo;
import org.junit.Before;

import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.getValuesList;
import static org.hamcrest.Matchers.equalTo;

public class SearchIT extends AbstractEsqlIntegTestCase {

    long epoch = System.currentTimeMillis();


    @Override
    protected EsqlQueryResponse run(EsqlQueryRequest request) {
        assumeTrue("time series available in snapshot builds only", Build.current().isSnapshot());
        return super.run(request);
    }

    @Before
    public void setupIndex() {
        createAndPopulateIndex("test");
    }

    public void testScoreOnNumeric() {
        try (EsqlQueryResponse results = run("""
            search test [
              | score count > 40 and count < 46
            ]
            | keep count, score
            | sort count
            """)) {
            assertThat(results.columns(), equalTo(List.of(new ColumnInfo("count", "long"), new ColumnInfo("score", "double"))));
            assertThat(getValuesList(results).size(), equalTo(40));
            assertThat(getValuesList(results).get(0).get(0), equalTo(1));
        }
    }

    //
    // Clone of EsqlActionIT
    //

    private void createAndPopulateIndex(String indexName) {
        createAndPopulateIndex(indexName, Settings.EMPTY);
    }

    private void createAndPopulateIndex(String indexName, Settings additionalSettings) {
        assertAcked(
            client().admin()
                .indices()
                .prepareCreate(indexName)
                .setSettings(Settings.builder().put(additionalSettings).put("index.number_of_shards", ESTestCase.randomIntBetween(1, 5)))
                .setMapping(
                    "data",
                    "type=long",
                    "data_d",
                    "type=double",
                    "count",
                    "type=long",
                    "count_d",
                    "type=double",
                    "time",
                    "type=long",
                    "color",
                    "type=keyword"
                )
        );
        long timestamp = epoch;
        for (int i = 0; i < 10; i++) {
            client().prepareBulk()
                .add(
                    new IndexRequest(indexName).id("1" + i)
                        .source("data", 1, "count", 40, "data_d", 1d, "count_d", 40d, "time", timestamp++, "color", "red")
                )
                .add(
                    new IndexRequest(indexName).id("2" + i)
                        .source("data", 2, "count", 42, "data_d", 2d, "count_d", 42d, "time", timestamp++, "color", "blue")
                )
                .add(
                    new IndexRequest(indexName).id("3" + i)
                        .source("data", 1, "count", 44, "data_d", 1d, "count_d", 44d, "time", timestamp++, "color", "green")
                )
                .add(
                    new IndexRequest(indexName).id("4" + i)
                        .source("data", 2, "count", 46, "data_d", 2d, "count_d", 46d, "time", timestamp++, "color", "red")
                )
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
        }
        ensureYellow(indexName);
    }
}
