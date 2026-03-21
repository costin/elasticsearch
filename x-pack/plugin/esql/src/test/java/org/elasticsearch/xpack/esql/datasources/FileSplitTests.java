/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.datasources.spi.ExternalSplit;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class FileSplitTests extends ESTestCase {

    private final NamedWriteableRegistry registry = new NamedWriteableRegistry(List.of(FileSplit.ENTRY));

    public void testConstruction() {
        StoragePath path = StoragePath.of("s3://bucket/data/year=2024/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 1024, ".parquet", Map.of("key", "val"), Map.of("year", 2024));

        assertEquals("file", split.sourceType());
        assertEquals(path, split.path());
        assertEquals(0, split.offset());
        assertEquals(1024, split.length());
        assertEquals(".parquet", split.format());
        assertEquals(Map.of("key", "val"), split.config());
        assertEquals(Map.of("year", 2024), split.partitionValues());
        assertEquals(1024, split.estimatedSizeInBytes());
    }

    public void testNullSourceTypeThrows() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        expectThrows(IllegalArgumentException.class, () -> new FileSplit(null, path, 0, 100, null, null, null));
    }

    public void testNullPathThrows() {
        expectThrows(IllegalArgumentException.class, () -> new FileSplit("file", null, 0, 100, null, null, null));
    }

    public void testNullConfigAndPartitionsDefaultToEmpty() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 100, null, null, null);
        assertEquals(Map.of(), split.config());
        assertEquals(Map.of(), split.partitionValues());
    }

    public void testNamedWriteableRoundTrip() throws IOException {
        StoragePath path = StoragePath.of("s3://bucket/data/year=2024/month=06/file.parquet");
        FileSplit original = new FileSplit(
            "file",
            path,
            100,
            2048,
            ".parquet",
            Map.of("endpoint", "https://s3.example.com"),
            Map.of("year", 2024, "month", 6)
        );

        BytesStreamOutput out = new BytesStreamOutput();
        out.writeNamedWriteable(original);

        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry);
        FileSplit deserialized = (FileSplit) in.readNamedWriteable(ExternalSplit.class);

        assertEquals(original, deserialized);
        assertEquals(original.hashCode(), deserialized.hashCode());
        assertEquals(original.sourceType(), deserialized.sourceType());
        assertEquals(original.path(), deserialized.path());
        assertEquals(original.offset(), deserialized.offset());
        assertEquals(original.length(), deserialized.length());
        assertEquals(original.format(), deserialized.format());
        assertEquals(original.config(), deserialized.config());
        assertEquals(original.partitionValues(), deserialized.partitionValues());
    }

    public void testNamedWriteableRoundTripMinimal() throws IOException {
        StoragePath path = StoragePath.of("s3://bucket/file.csv");
        FileSplit original = new FileSplit("file", path, 0, 500, null, Map.of(), Map.of());

        BytesStreamOutput out = new BytesStreamOutput();
        out.writeNamedWriteable(original);

        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry);
        FileSplit deserialized = (FileSplit) in.readNamedWriteable(ExternalSplit.class);

        assertEquals(original, deserialized);
    }

    public void testEquality() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit a = new FileSplit("file", path, 0, 100, ".parquet", Map.of(), Map.of("year", 2024));
        FileSplit b = new FileSplit("file", path, 0, 100, ".parquet", Map.of(), Map.of("year", 2024));
        FileSplit c = new FileSplit("file", path, 0, 200, ".parquet", Map.of(), Map.of("year", 2024));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    public void testGetWriteableName() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 100, null, null, null);
        assertEquals("FileSplit", split.getWriteableName());
    }

    public void testToString() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 100, null, null, Map.of("year", 2024));
        String str = split.toString();
        assertTrue(str.contains("s3://bucket/file.parquet"));
        assertTrue(str.contains("year"));
    }

    // --- File metadata tests ---

    public void testFileMetadataAccessor() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        Map<String, Object> metadata = Map.of("_path", new BytesRef("s3://bucket/file.parquet"), "_file_size", 1024L);
        FileSplit split = new FileSplit("file", path, 0, 1024, ".parquet", Map.of(), Map.of(), metadata);

        assertEquals(metadata, split.fileMetadata());
    }

    public void testFileMetadataDefaultsToEmpty() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 100, null, null, null);
        assertEquals(Map.of(), split.fileMetadata());
    }

    public void testNamedWriteableRoundTripWithFileMetadata() throws IOException {
        StoragePath path = StoragePath.of("s3://bucket/data/file.parquet");
        Map<String, Object> metadata = Map.of(
            "_path",
            new BytesRef("s3://bucket/data/file.parquet"),
            "_file",
            new BytesRef("file.parquet"),
            "_file_size",
            2048L,
            "_last_modified",
            1720000000000L
        );
        FileSplit original = new FileSplit("file", path, 0, 2048, ".parquet", Map.of(), Map.of("year", 2024), metadata);

        BytesStreamOutput out = new BytesStreamOutput();
        out.setTransportVersion(TransportVersion.current());
        out.writeNamedWriteable(original);

        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry);
        in.setTransportVersion(TransportVersion.current());
        FileSplit deserialized = (FileSplit) in.readNamedWriteable(ExternalSplit.class);

        assertEquals(original, deserialized);
        assertEquals(original.fileMetadata(), deserialized.fileMetadata());
    }

    public void testBwcDeserializationOldVersionProducesEmptyMetadata() throws IOException {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit original = new FileSplit("file", path, 0, 100, null, Map.of(), Map.of());

        // Write with old version (before file metadata support)
        TransportVersion oldVersion = TransportVersion.fromId(FileSplit.ESQL_FILE_METADATA.id() - 1_000);
        BytesStreamOutput out = new BytesStreamOutput();
        out.setTransportVersion(oldVersion);
        out.writeNamedWriteable(original);

        // Read with old version — fileMetadata should default to empty
        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), registry);
        in.setTransportVersion(oldVersion);
        FileSplit deserialized = (FileSplit) in.readNamedWriteable(ExternalSplit.class);

        assertEquals(Map.of(), deserialized.fileMetadata());
        assertEquals(original.sourceType(), deserialized.sourceType());
        assertEquals(original.path(), deserialized.path());
    }

    public void testEqualityWithFileMetadata() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        Map<String, Object> metadata = Map.of("_path", new BytesRef("s3://bucket/file.parquet"));
        FileSplit a = new FileSplit("file", path, 0, 100, null, Map.of(), Map.of(), metadata);
        FileSplit b = new FileSplit("file", path, 0, 100, null, Map.of(), Map.of(), metadata);
        FileSplit c = new FileSplit("file", path, 0, 100, null, Map.of(), Map.of(), Map.of());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    public void testToStringWithFileMetadata() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        Map<String, Object> metadata = Map.of("_path", new BytesRef("s3://bucket/file.parquet"));
        FileSplit split = new FileSplit("file", path, 0, 100, null, Map.of(), Map.of(), metadata);
        String str = split.toString();
        assertTrue(str.contains("metadata="));
    }

    public void testToStringWithoutFileMetadata() {
        StoragePath path = StoragePath.of("s3://bucket/file.parquet");
        FileSplit split = new FileSplit("file", path, 0, 100, null, null, null);
        String str = split.toString();
        assertFalse(str.contains("metadata="));
    }
}
