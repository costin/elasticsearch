/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasource.s3;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.esql.datasources.spi.DataSourcePlugin;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProvider;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProviderFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data source plugin providing S3 storage support for ESQL.
 * Supports s3://, s3a://, and s3n:// URI schemes.
 * <p>
 * When no credentials are provided via the EXTERNAL WITH clause, falls back to
 * {@code esql.s3.*} node settings (access_key, secret_key, endpoint, region).
 */
public class S3DataSourcePlugin extends Plugin implements DataSourcePlugin {

    private static final String ESQL_S3_PREFIX = "esql.s3.";

    static final Setting<String> ACCESS_KEY_SETTING = Setting.simpleString(ESQL_S3_PREFIX + "access_key", Setting.Property.NodeScope);
    static final Setting<String> SECRET_KEY_SETTING = Setting.simpleString(ESQL_S3_PREFIX + "secret_key", Setting.Property.NodeScope);
    static final Setting<String> ENDPOINT_SETTING = Setting.simpleString(ESQL_S3_PREFIX + "endpoint", Setting.Property.NodeScope);
    static final Setting<String> REGION_SETTING = Setting.simpleString(ESQL_S3_PREFIX + "region", Setting.Property.NodeScope);

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(ACCESS_KEY_SETTING, SECRET_KEY_SETTING, ENDPOINT_SETTING, REGION_SETTING);
    }

    @Override
    public Set<String> supportedSchemes() {
        return Set.of("s3", "s3a", "s3n");
    }

    @Override
    public Map<String, StorageProviderFactory> storageProviders(Settings settings) {
        StorageProviderFactory s3Factory = new StorageProviderFactory() {
            @Override
            public StorageProvider create(Settings nodeSettings) {
                return new S3StorageProvider(configFromNodeSettings(nodeSettings));
            }

            @Override
            public StorageProvider create(Settings nodeSettings, Map<String, Object> config) {
                if (config == null || config.isEmpty()) {
                    return create(nodeSettings);
                }
                String accessKey = (String) config.get("access_key");
                String secretKey = (String) config.get("secret_key");
                String endpoint = (String) config.get("endpoint");
                String region = (String) config.get("region");

                if (accessKey == null && secretKey == null && endpoint == null && region == null) {
                    return create(nodeSettings);
                }

                S3Configuration fallback = configFromNodeSettings(nodeSettings);
                S3Configuration s3Config = S3Configuration.fromFields(
                    accessKey != null ? accessKey : (fallback != null ? fallback.accessKey() : null),
                    secretKey != null ? secretKey : (fallback != null ? fallback.secretKey() : null),
                    endpoint != null ? endpoint : (fallback != null ? fallback.endpoint() : null),
                    region != null ? region : (fallback != null ? fallback.region() : null)
                );
                return new S3StorageProvider(s3Config);
            }
        };
        return Map.of("s3", s3Factory, "s3a", s3Factory, "s3n", s3Factory);
    }

    private static S3Configuration configFromNodeSettings(Settings settings) {
        if (settings == null) {
            return null;
        }
        String accessKey = ACCESS_KEY_SETTING.get(settings);
        String secretKey = SECRET_KEY_SETTING.get(settings);
        String endpoint = ENDPOINT_SETTING.get(settings);
        String region = REGION_SETTING.get(settings);
        return S3Configuration.fromFields(
            accessKey.isEmpty() == false ? accessKey : null,
            secretKey.isEmpty() == false ? secretKey : null,
            endpoint.isEmpty() == false ? endpoint : null,
            region.isEmpty() == false ? region : null
        );
    }
}
