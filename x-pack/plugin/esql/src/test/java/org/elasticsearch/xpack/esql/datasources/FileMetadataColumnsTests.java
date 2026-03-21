/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.instanceOf;

public class FileMetadataColumnsTests extends ESTestCase {

    public void testIsFileMetadataColumn() {
        assertTrue(FileMetadataColumns.isFileMetadataColumn("_path"));
        assertTrue(FileMetadataColumns.isFileMetadataColumn("_file"));
        assertTrue(FileMetadataColumns.isFileMetadataColumn("_file_size"));
        assertTrue(FileMetadataColumns.isFileMetadataColumn("_last_modified"));

        assertFalse(FileMetadataColumns.isFileMetadataColumn("_source"));
        assertFalse(FileMetadataColumns.isFileMetadataColumn("_id"));
        assertFalse(FileMetadataColumns.isFileMetadataColumn("revenue"));
        assertFalse(FileMetadataColumns.isFileMetadataColumn(""));
    }

    public void testColumnsTypes() {
        assertEquals(DataType.KEYWORD, FileMetadataColumns.COLUMNS.get("_path"));
        assertEquals(DataType.KEYWORD, FileMetadataColumns.COLUMNS.get("_file"));
        assertEquals(DataType.LONG, FileMetadataColumns.COLUMNS.get("_file_size"));
        assertEquals(DataType.DATETIME, FileMetadataColumns.COLUMNS.get("_last_modified"));
        assertEquals(4, FileMetadataColumns.COLUMNS.size());
    }

    public void testExtractValuesFromStorageEntry() {
        StoragePath path = StoragePath.of("s3://my-bucket/data/2024/events.parquet");
        Instant modified = Instant.parse("2024-07-15T10:30:00Z");
        StorageEntry entry = new StorageEntry(path, 52428800L, modified);

        Map<String, Object> values = FileMetadataColumns.extractValues(entry);

        assertEquals(new BytesRef("s3://my-bucket/data/2024/events.parquet"), values.get("_path"));
        assertEquals(new BytesRef("events.parquet"), values.get("_file"));
        assertEquals(52428800L, values.get("_file_size"));
        assertEquals(modified.toEpochMilli(), values.get("_last_modified"));
    }

    public void testExtractValuesFromComponents() {
        StoragePath path = StoragePath.of("gs://bucket/dir/file.csv.gz");
        Instant modified = Instant.parse("2025-01-01T00:00:00Z");

        Map<String, Object> values = FileMetadataColumns.extractValues(path, 1024L, modified);

        assertEquals(new BytesRef("gs://bucket/dir/file.csv.gz"), values.get("_path"));
        assertEquals(new BytesRef("file.csv.gz"), values.get("_file"));
        assertEquals(1024L, values.get("_file_size"));
        assertEquals(modified.toEpochMilli(), values.get("_last_modified"));
    }

    public void testExtractValuesNullLastModified() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        Map<String, Object> values = FileMetadataColumns.extractValues(path, 100L, null);

        assertEquals(new BytesRef("s3://bucket/file.parquet"), values.get("_path"));
        assertEquals(new BytesRef("file.parquet"), values.get("_file"));
        assertEquals(100L, values.get("_file_size"));
        assertTrue(values.containsKey("_last_modified"));
        assertNull(values.get("_last_modified"));
    }

    public void testExtractValuesLargeFileSize() {
        StoragePath path = StoragePath.of("s3://bucket/huge.parquet");
        long largeSize = 5L * 1024 * 1024 * 1024; // 5 GB
        Map<String, Object> values = FileMetadataColumns.extractValues(path, largeSize, Instant.now());

        assertEquals(largeSize, values.get("_file_size"));
    }

    public void testExtractValuesPathTypes() {
        // Verify BytesRef type for keyword columns
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        Map<String, Object> values = FileMetadataColumns.extractValues(path, 100L, Instant.EPOCH);

        assertThat(values.get("_path"), instanceOf(BytesRef.class));
        assertThat(values.get("_file"), instanceOf(BytesRef.class));
        assertThat(values.get("_file_size"), instanceOf(Long.class));
        assertThat(values.get("_last_modified"), instanceOf(Long.class));
    }

    public void testNamesSet() {
        assertEquals(4, FileMetadataColumns.NAMES.size());
        assertTrue(FileMetadataColumns.NAMES.contains("_path"));
        assertTrue(FileMetadataColumns.NAMES.contains("_file"));
        assertTrue(FileMetadataColumns.NAMES.contains("_file_size"));
        assertTrue(FileMetadataColumns.NAMES.contains("_last_modified"));
    }
}
