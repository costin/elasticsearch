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
 * Separate from {@code MetadataAttribute.ATTRIBUTES_MAP} which covers ES index metadata.
 */
public final class FileMetadataColumns {

    public static final String PATH = "_path";
    public static final String FILE = "_file";
    public static final String FILE_SIZE = "_file_size";
    public static final String LAST_MODIFIED = "_last_modified";

    public static final Map<String, DataType> COLUMNS;

    static {
        var map = new LinkedHashMap<String, DataType>();
        map.put(PATH, DataType.KEYWORD);
        map.put(FILE, DataType.KEYWORD);
        map.put(FILE_SIZE, DataType.LONG);
        map.put(LAST_MODIFIED, DataType.DATETIME);
        COLUMNS = Collections.unmodifiableMap(map);
    }

    public static final Set<String> NAMES = COLUMNS.keySet();

    private FileMetadataColumns() {}

    public static boolean isFileMetadataColumn(String name) {
        return COLUMNS.containsKey(name);
    }

    public static Map<String, Object> extractValues(StoragePath path, long length, Instant lastModified) {
        var map = new LinkedHashMap<String, Object>(6);
        map.put(PATH, new BytesRef(path.toString()));
        map.put(FILE, new BytesRef(path.objectName()));
        map.put(FILE_SIZE, length);
        map.put(LAST_MODIFIED, lastModified != null ? lastModified.toEpochMilli() : null);
        return Collections.unmodifiableMap(map);
    }

    public static Map<String, Object> extractValues(StorageEntry entry) {
        return extractValues(entry.path(), entry.length(), entry.lastModified());
    }
}
