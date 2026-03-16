/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasource.s3;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProvider;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProviderFactory;

import java.io.IOException;
import java.util.Map;

public class S3DataSourcePluginTests extends ESTestCase {

    private final S3DataSourcePlugin plugin = new S3DataSourcePlugin();

    public void testSupportedSchemes() {
        assertTrue(plugin.supportedSchemes().contains("s3"));
        assertTrue(plugin.supportedSchemes().contains("s3a"));
        assertTrue(plugin.supportedSchemes().contains("s3n"));
    }

    public void testNodeSettingsRegistered() {
        var settingsList = plugin.getSettings();
        assertEquals(4, settingsList.size());
        assertTrue(settingsList.contains(S3DataSourcePlugin.ACCESS_KEY_SETTING));
        assertTrue(settingsList.contains(S3DataSourcePlugin.SECRET_KEY_SETTING));
        assertTrue(settingsList.contains(S3DataSourcePlugin.ENDPOINT_SETTING));
        assertTrue(settingsList.contains(S3DataSourcePlugin.REGION_SETTING));
    }

    public void testCreateWithNoConfig() throws IOException {
        StorageProviderFactory factory = plugin.storageProviders(Settings.EMPTY).get("s3");
        try (StorageProvider provider = factory.create(Settings.EMPTY)) {
            assertNotNull(provider);
        }
    }

    public void testCreateWithNodeSettings() throws IOException {
        Settings nodeSettings = Settings.builder()
            .put("esql.s3.access_key", "node-key")
            .put("esql.s3.secret_key", "node-secret")
            .put("esql.s3.endpoint", "http://localhost:9000")
            .put("esql.s3.region", "us-east-1")
            .build();
        StorageProviderFactory factory = plugin.storageProviders(nodeSettings).get("s3");
        try (StorageProvider provider = factory.create(nodeSettings)) {
            assertNotNull(provider);
        }
    }

    public void testCreateWithPerQueryConfigOverridesNodeSettings() throws IOException {
        Settings nodeSettings = Settings.builder()
            .put("esql.s3.access_key", "node-key")
            .put("esql.s3.secret_key", "node-secret")
            .put("esql.s3.endpoint", "http://node-endpoint:9000")
            .put("esql.s3.region", "us-west-2")
            .build();
        Map<String, Object> queryConfig = Map.of("access_key", "query-key", "secret_key", "query-secret");

        StorageProviderFactory factory = plugin.storageProviders(nodeSettings).get("s3");
        try (StorageProvider provider = factory.create(nodeSettings, queryConfig)) {
            assertNotNull(provider);
        }
    }

    public void testCreateWithEmptyConfigFallsBackToNodeSettings() throws IOException {
        Settings nodeSettings = Settings.builder().put("esql.s3.access_key", "node-key").put("esql.s3.secret_key", "node-secret").build();
        StorageProviderFactory factory = plugin.storageProviders(nodeSettings).get("s3");
        try (StorageProvider providerEmpty = factory.create(nodeSettings, Map.of())) {
            assertNotNull(providerEmpty);
        }
        try (StorageProvider providerNull = factory.create(nodeSettings, null)) {
            assertNotNull(providerNull);
        }
    }

    public void testAllSchemesReturnSameFactory() {
        Map<String, StorageProviderFactory> providers = plugin.storageProviders(Settings.EMPTY);
        assertSame(providers.get("s3"), providers.get("s3a"));
        assertSame(providers.get("s3"), providers.get("s3n"));
    }
}
