/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of well-known file metadata virtual columns for external data sources.
 * Uses dot-namespaced names under {@code _file.*} to avoid collisions with
 * Hive partition columns (which cannot contain dots).
 * Separate from {@code MetadataAttribute.ATTRIBUTES_MAP} which covers ES index metadata.
 */
public final class FileMetadataColumns {

    public static final String PATH = "_file.path";
    public static final String NAME = "_file.name";
    public static final String DIRECTORY = "_file.directory";
    public static final String SIZE = "_file.size";
    public static final String MODIFIED = "_file.modified";

    public static final Map<String, DataType> COLUMNS;

    static {
        var map = new LinkedHashMap<String, DataType>();
        map.put(PATH, DataType.KEYWORD);
        map.put(NAME, DataType.KEYWORD);
        map.put(DIRECTORY, DataType.KEYWORD);
        map.put(SIZE, DataType.LONG);
        map.put(MODIFIED, DataType.DATETIME);
        COLUMNS = Collections.unmodifiableMap(map);
    }

    public static final Set<String> NAMES = COLUMNS.keySet();

    private FileMetadataColumns() {}

    public static boolean isFileMetadataColumn(String name) {
        return COLUMNS.containsKey(name);
    }

    public static Map<String, Object> extractValues(StoragePath path, long length, Instant lastModified) {
        var map = new LinkedHashMap<String, Object>(8);
        map.put(PATH, new BytesRef(path.toString()));
        map.put(NAME, new BytesRef(path.objectName()));
        StoragePath parent = path.parentDirectory();
        map.put(DIRECTORY, parent != null ? new BytesRef(parent.toString()) : null);
        map.put(SIZE, length);
        map.put(MODIFIED, lastModified != null ? lastModified.toEpochMilli() : null);
        return Collections.unmodifiableMap(map);
    }

    public static Map<String, Object> extractValues(StorageEntry entry) {
        return extractValues(entry.path(), entry.length(), entry.lastModified());
    }
}
