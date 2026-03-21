/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.datasources.spi.StorageObject;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProvider;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class GlobExpanderTests extends ESTestCase {

    // -- isMultiFile --

    public void testIsMultiFileWithGlob() {
        assertTrue(GlobExpander.isMultiFile("s3://bucket/*.parquet"));
        assertTrue(GlobExpander.isMultiFile("s3://bucket/data?.csv"));
        assertTrue(GlobExpander.isMultiFile("s3://bucket/{a,b}.parquet"));
        assertTrue(GlobExpander.isMultiFile("s3://bucket/[abc].parquet"));
    }

    public void testIsMultiFileWithComma() {
        assertTrue(GlobExpander.isMultiFile("s3://bucket/a.parquet,s3://bucket/b.parquet"));
    }

    public void testIsMultiFileLiteral() {
        assertFalse(GlobExpander.isMultiFile("s3://bucket/data.parquet"));
        assertFalse(GlobExpander.isMultiFile(null));
    }

    // -- expandGlob --

    public void testExpandGlobLiteralReturnsUnresolved() throws IOException {
        StubProvider provider = new StubProvider(List.of());
        FileSet result = GlobExpander.expandGlob("s3://bucket/data.parquet", provider);
        assertTrue(result.isUnresolved());
    }

    public void testExpandGlobMatchesFiles() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/file1.parquet", 100),
            entry("s3://bucket/data/file2.parquet", 200),
            entry("s3://bucket/data/file3.csv", 50)
        );
        StubProvider provider = new StubProvider(listing);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/*.parquet", provider);
        assertTrue(result.isResolved());
        assertEquals(2, result.size());
        assertEquals("s3://bucket/data/file1.parquet", result.files().get(0).path().toString());
        assertEquals("s3://bucket/data/file2.parquet", result.files().get(1).path().toString());
    }

    public void testExpandGlobNoMatchReturnsEmpty() throws IOException {
        List<StorageEntry> listing = List.of(entry("s3://bucket/data/file.csv", 50));
        StubProvider provider = new StubProvider(listing);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/*.parquet", provider);
        assertTrue(result.isEmpty());
    }

    public void testExpandGlobPreservesPattern() throws IOException {
        List<StorageEntry> listing = List.of(entry("s3://bucket/data/f.parquet", 10));
        StubProvider provider = new StubProvider(listing);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/*.parquet", provider);
        assertEquals("s3://bucket/data/*.parquet", result.originalPattern());
    }

    // -- expandCommaSeparated --

    public void testExpandCommaSeparatedMixedGlobAndLiteral() throws IOException {
        List<StorageEntry> listing = List.of(entry("s3://bucket/data/a.parquet", 100), entry("s3://bucket/data/b.parquet", 200));
        StubProvider provider = new StubProvider(listing);
        provider.existingPaths.add("s3://bucket/extra.parquet");

        FileSet result = GlobExpander.expandCommaSeparated("s3://bucket/data/*.parquet, s3://bucket/extra.parquet", provider);
        assertTrue(result.isResolved());
        assertEquals(3, result.size());
    }

    public void testExpandCommaSeparatedAllMissing() throws IOException {
        StubProvider provider = new StubProvider(List.of());
        FileSet result = GlobExpander.expandCommaSeparated("s3://bucket/missing.parquet", provider);
        assertTrue(result.isEmpty());
    }

    // -- partition-aware glob rewriting --

    public void testRewriteGlobWithEqualsHint() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.EQUALS, 2024));
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=*/*.parquet", hints);
        assertEquals("s3://bucket/year=2024/*.parquet", rewritten);
    }

    public void testRewriteGlobWithInHint() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.IN, 2023, 2024));
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=*/*.parquet", hints);
        assertEquals("s3://bucket/year={2023,2024}/*.parquet", rewritten);
    }

    public void testRewriteGlobWithRangeHintNoRewrite() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.GREATER_THAN_OR_EQUAL, 2020));
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=*/*.parquet", hints);
        assertEquals("s3://bucket/year=*/*.parquet", rewritten);
    }

    public void testRewriteGlobMultipleHints() {
        var hints = List.of(
            hint("year", PartitionFilterHintExtractor.Operator.EQUALS, 2024),
            hint("month", PartitionFilterHintExtractor.Operator.IN, 1, 2, 3)
        );
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=*/month=*/*.parquet", hints);
        assertEquals("s3://bucket/year=2024/month={1,2,3}/*.parquet", rewritten);
    }

    public void testRewriteGlobNonWildcardNotRewritten() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.EQUALS, 2024));
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=2023/*.parquet", hints);
        assertEquals("s3://bucket/year=2023/*.parquet", rewritten);
    }

    public void testRewriteGlobNoHintsNoChange() {
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/year=*/*.parquet", List.of());
        assertEquals("s3://bucket/year=*/*.parquet", rewritten);
    }

    public void testExpandGlobWithHivePartitionDetection() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/year=2024/file1.parquet", 100),
            entry("s3://bucket/data/year=2023/file2.parquet", 200)
        );
        StubProvider provider = new StubProvider(listing);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/year=*/*.parquet", provider, null, true);
        assertTrue(result.isResolved());
        assertEquals(2, result.size());
        assertNotNull(result.partitionMetadata());
        assertFalse(result.partitionMetadata().isEmpty());
        assertTrue(result.partitionMetadata().partitionColumns().containsKey("year"));
    }

    public void testExpandGlobWithHivePartitioningDisabled() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/year=2024/file1.parquet", 100),
            entry("s3://bucket/data/year=2023/file2.parquet", 200)
        );
        StubProvider provider = new StubProvider(listing);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/year=*/*.parquet", provider, null, false);
        assertTrue(result.isResolved());
        assertEquals(2, result.size());
        assertNull(result.partitionMetadata());
    }

    public void testExpandGlobNonHivePathsNoPartitionMetadata() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/2024/file1.parquet", 100),
            entry("s3://bucket/data/2023/file2.parquet", 200)
        );
        StubProvider provider = new StubProvider(listing);

        @SuppressWarnings("RegexpMultiline")
        FileSet result = GlobExpander.expandGlob("s3://bucket/data/**/*.parquet", provider, null, true);
        assertTrue(result.isResolved());
        assertNull(result.partitionMetadata());
    }

    // -- template-based glob rewriting --

    public void testRewriteGlobWithTemplateHints() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.EQUALS, 2024));
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}");
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/*/*/*.parquet", hints, config);
        // First wildcard maps to {year} → rewritten to 2024
        assertEquals("s3://bucket/2024/*/*.parquet", rewritten);
    }

    public void testRewriteGlobWithTemplateInHints() {
        var hints = List.of(hint("month", PartitionFilterHintExtractor.Operator.IN, 1, 2));
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}");
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/*/*/*.parquet", hints, config);
        // Second wildcard maps to {month} → rewritten to {1,2}
        assertEquals("s3://bucket/*/{1,2}/*.parquet", rewritten);
    }

    public void testRewriteGlobWithTemplateRangeHintsNoRewrite() {
        var hints = List.of(hint("year", PartitionFilterHintExtractor.Operator.GREATER_THAN_OR_EQUAL, 2020));
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}");
        // Range hints are not rewritable, so pattern should be unchanged
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/*/*/*.parquet", hints, config);
        assertEquals("s3://bucket/*/*/*.parquet", rewritten);
    }

    public void testRewriteGlobWithTemplateNoMatchingHints() {
        var hints = List.of(hint("region", PartitionFilterHintExtractor.Operator.EQUALS, "us-east"));
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}");
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/*/*/*.parquet", hints, config);
        // "region" not in template, so Hive rewriting also won't match → unchanged
        assertEquals("s3://bucket/*/*/*.parquet", rewritten);
    }

    public void testRewriteGlobWithTemplateThreeColumns() {
        var hints = List.of(
            hint("year", PartitionFilterHintExtractor.Operator.EQUALS, 2024),
            hint("day", PartitionFilterHintExtractor.Operator.EQUALS, 15)
        );
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}/{day}");
        String rewritten = GlobExpander.rewriteGlobWithHints("s3://bucket/*/*/*/*.parquet", hints, config);
        assertEquals("s3://bucket/2024/*/15/*.parquet", rewritten);
    }

    public void testExpandGlobWithPartitionConfig() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/2024/01/file1.parquet", 100),
            entry("s3://bucket/data/2023/12/file2.parquet", 200)
        );
        StubProvider provider = new StubProvider(listing);
        PartitionConfig config = new PartitionConfig(PartitionConfig.TEMPLATE, "{year}/{month}");

        @SuppressWarnings("RegexpMultiline")
        FileSet result = GlobExpander.expandGlob("s3://bucket/data/**/*.parquet", provider, null, true, config, Map.of());
        assertTrue(result.isResolved());
        assertEquals(2, result.size());
        assertNotNull(result.partitionMetadata());
        assertTrue(result.partitionMetadata().partitionColumns().containsKey("year"));
        assertTrue(result.partitionMetadata().partitionColumns().containsKey("month"));
    }

    public void testExpandGlobWithNonePartitionConfig() throws IOException {
        List<StorageEntry> listing = List.of(
            entry("s3://bucket/data/year=2024/file1.parquet", 100),
            entry("s3://bucket/data/year=2023/file2.parquet", 200)
        );
        StubProvider provider = new StubProvider(listing);
        PartitionConfig config = new PartitionConfig(PartitionConfig.NONE, null);

        FileSet result = GlobExpander.expandGlob("s3://bucket/data/year=*/*.parquet", provider, null, true, config, Map.of());
        assertTrue(result.isResolved());
        assertNull(result.partitionMetadata());
    }

    // -- helpers --

    private static PartitionFilterHintExtractor.PartitionFilterHint hint(
        String column,
        PartitionFilterHintExtractor.Operator op,
        Object... values
    ) {
        return new PartitionFilterHintExtractor.PartitionFilterHint(column, op, List.of(values));
    }

    private static StorageEntry entry(String path, long length) {
        return new StorageEntry(StoragePath.of(path), length, Instant.EPOCH);
    }

    private static class StubProvider implements StorageProvider {
        private final List<StorageEntry> listing;
        private final List<String> existingPaths = new ArrayList<>();

        StubProvider(List<StorageEntry> listing) {
            this.listing = listing;
        }

        @Override
        public StorageObject newObject(StoragePath path) {
            return new StubStorageObject(path);
        }

        @Override
        public StorageObject newObject(StoragePath path, long length) {
            return new StubStorageObject(path, length);
        }

        @Override
        public StorageObject newObject(StoragePath path, long length, Instant lastModified) {
            return new StubStorageObject(path, length);
        }

        @Override
        public StorageIterator listObjects(StoragePath prefix, boolean recursive) {
            return new StorageIterator() {
                private final Iterator<StorageEntry> it = listing.iterator();

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public StorageEntry next() {
                    if (it.hasNext() == false) {
                        throw new NoSuchElementException();
                    }
                    return it.next();
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public boolean exists(StoragePath path) {
            return existingPaths.contains(path.toString());
        }

        @Override
        public List<String> supportedSchemes() {
            return List.of("s3");
        }

        @Override
        public void close() {}
    }

    private static class StubStorageObject implements StorageObject {
        private final StoragePath path;
        private final long length;

        StubStorageObject(StoragePath path) {
            this(path, 0);
        }

        StubStorageObject(StoragePath path, long length) {
            this.path = path;
            this.length = length;
        }

        @Override
        public InputStream newStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream newStream(long position, long length) {
            return InputStream.nullInputStream();
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public Instant lastModified() {
            return Instant.EPOCH;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public StoragePath path() {
            return path;
        }
    }

    // -- applyFileMetadataFilters --

    public void testFileMetadataFilterByModifiedTime() {
        Instant cutoff = Instant.parse("2024-06-01T00:00:00Z");
        List<StorageEntry> entries = List.of(
            new StorageEntry(StoragePath.of("s3://b/old.parquet"), 100, Instant.parse("2024-01-15T00:00:00Z")),
            new StorageEntry(StoragePath.of("s3://b/new.parquet"), 200, Instant.parse("2024-07-15T00:00:00Z")),
            new StorageEntry(StoragePath.of("s3://b/newer.parquet"), 300, Instant.parse("2024-12-01T00:00:00Z"))
        );

        var hint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.modified",
            PartitionFilterHintExtractor.Operator.GREATER_THAN,
            List.of(cutoff.toEpochMilli())
        );

        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(hint));
        assertEquals(2, filtered.size());
        assertEquals("s3://b/new.parquet", filtered.get(0).path().toString());
        assertEquals("s3://b/newer.parquet", filtered.get(1).path().toString());
    }

    public void testFileMetadataFilterBySize() {
        List<StorageEntry> entries = List.of(
            new StorageEntry(StoragePath.of("s3://b/tiny.parquet"), 10, Instant.EPOCH),
            new StorageEntry(StoragePath.of("s3://b/small.parquet"), 1000, Instant.EPOCH),
            new StorageEntry(StoragePath.of("s3://b/big.parquet"), 1000000, Instant.EPOCH)
        );

        var hint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.size",
            PartitionFilterHintExtractor.Operator.GREATER_THAN_OR_EQUAL,
            List.of(1000L)
        );

        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(hint));
        assertEquals(2, filtered.size());
        assertEquals("s3://b/small.parquet", filtered.get(0).path().toString());
        assertEquals("s3://b/big.parquet", filtered.get(1).path().toString());
    }

    public void testFileMetadataFilterByName() {
        List<StorageEntry> entries = List.of(
            new StorageEntry(StoragePath.of("s3://b/events_2024.parquet"), 100, Instant.EPOCH),
            new StorageEntry(StoragePath.of("s3://b/events_2025.parquet"), 100, Instant.EPOCH),
            new StorageEntry(StoragePath.of("s3://b/other.parquet"), 100, Instant.EPOCH)
        );

        var hint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.name",
            PartitionFilterHintExtractor.Operator.EQUALS,
            List.of("events_2024.parquet")
        );

        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(hint));
        assertEquals(1, filtered.size());
        assertEquals("s3://b/events_2024.parquet", filtered.get(0).path().toString());
    }

    public void testFileMetadataFilterIgnoresNonFileHints() {
        List<StorageEntry> entries = List.of(new StorageEntry(StoragePath.of("s3://b/file.parquet"), 100, Instant.EPOCH));

        var hint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "year",
            PartitionFilterHintExtractor.Operator.EQUALS,
            List.of(2024)
        );

        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(hint));
        assertEquals(1, filtered.size());
    }

    public void testFileMetadataFilterNullTimestampIsConservative() {
        List<StorageEntry> entries = List.of(new StorageEntry(StoragePath.of("s3://b/file.parquet"), 100, null));

        var hint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.modified",
            PartitionFilterHintExtractor.Operator.GREATER_THAN,
            List.of(Instant.parse("2024-06-01T00:00:00Z").toEpochMilli())
        );

        // Null timestamp → conservative, don't filter
        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(hint));
        assertEquals(1, filtered.size());
    }

    public void testFileMetadataFilterCombinesMultipleHints() {
        Instant cutoff = Instant.parse("2024-06-01T00:00:00Z");
        List<StorageEntry> entries = List.of(
            new StorageEntry(StoragePath.of("s3://b/old_small.parquet"), 10, Instant.parse("2024-01-01T00:00:00Z")),
            new StorageEntry(StoragePath.of("s3://b/old_big.parquet"), 1000000, Instant.parse("2024-01-01T00:00:00Z")),
            new StorageEntry(StoragePath.of("s3://b/new_small.parquet"), 10, Instant.parse("2024-07-01T00:00:00Z")),
            new StorageEntry(StoragePath.of("s3://b/new_big.parquet"), 1000000, Instant.parse("2024-07-01T00:00:00Z"))
        );

        var timeHint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.modified",
            PartitionFilterHintExtractor.Operator.GREATER_THAN,
            List.of(cutoff.toEpochMilli())
        );
        var sizeHint = new PartitionFilterHintExtractor.PartitionFilterHint(
            "_file.size",
            PartitionFilterHintExtractor.Operator.GREATER_THAN,
            List.of(100L)
        );

        // Both hints must match: modified > cutoff AND size > 100
        List<StorageEntry> filtered = GlobExpander.applyFileMetadataFilters(entries, List.of(timeHint, sizeHint));
        assertEquals(1, filtered.size());
        assertEquals("s3://b/new_big.parquet", filtered.get(0).path().toString());
    }
}
