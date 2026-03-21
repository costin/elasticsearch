/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.elasticsearch.common.util.Maps;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.esql.datasources.PartitionFilterHintExtractor.PartitionFilterHint;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProvider;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Expands glob patterns and comma-separated path lists into resolved {@link FileSet} instances.
 * Delegates to {@link StorageProvider#listObjects} for directory listing and uses {@link GlobMatcher}
 * for filtering results against the glob pattern.
 * Supports partition-aware glob rewriting when filter hints are provided.
 */
final class GlobExpander {

    private static final Logger logger = LogManager.getLogger(GlobExpander.class);

    private GlobExpander() {}

    static PartitionDetector resolveDetector(PartitionConfig config) {
        if (config == null) {
            return HivePartitionDetector.INSTANCE;
        }
        return switch (config.strategy()) {
            case PartitionConfig.NONE -> null;
            case PartitionConfig.HIVE -> HivePartitionDetector.INSTANCE;
            case PartitionConfig.TEMPLATE -> {
                String template = config.pathTemplate();
                if (template == null || template.isEmpty()) {
                    yield null;
                }
                yield new TemplatePartitionDetector(template);
            }
            case PartitionConfig.AUTO -> AutoPartitionDetector.fromConfig(config);
            default -> HivePartitionDetector.INSTANCE;
        };
    }

    static boolean isMultiFile(String path) {
        if (path == null) {
            return false;
        }
        for (char c : StoragePath.GLOB_METACHARACTERS) {
            if (path.indexOf(c) >= 0) {
                return true;
            }
        }
        return path.indexOf(',') >= 0;
    }

    static FileSet expandGlob(String pattern, StorageProvider provider) throws IOException {
        return expandGlob(pattern, provider, null, true);
    }

    static FileSet expandGlob(
        String pattern,
        StorageProvider provider,
        @Nullable List<PartitionFilterHint> hints,
        boolean hivePartitioning,
        @Nullable PartitionConfig partitionConfig,
        @Nullable Map<String, Object> config
    ) throws IOException {
        return doExpandGlob(pattern, provider, hints, hivePartitioning, partitionConfig, config);
    }

    static FileSet expandGlob(String pattern, StorageProvider provider, @Nullable List<PartitionFilterHint> hints, boolean hivePartitioning)
        throws IOException {
        return doExpandGlob(pattern, provider, hints, hivePartitioning, null, null);
    }

    private static FileSet doExpandGlob(
        String pattern,
        StorageProvider provider,
        @Nullable List<PartitionFilterHint> hints,
        boolean hivePartitioning,
        @Nullable PartitionConfig partitionConfig,
        @Nullable Map<String, Object> config
    ) throws IOException {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern cannot be null");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider cannot be null");
        }

        String effectivePattern = pattern;
        if (hints != null && hints.isEmpty() == false && hivePartitioning) {
            effectivePattern = rewriteGlobWithHints(pattern, hints, partitionConfig);
        }

        StoragePath storagePath = StoragePath.of(effectivePattern);

        if (storagePath.isPattern() == false) {
            return FileSet.UNRESOLVED;
        }

        StoragePath prefix = storagePath.patternPrefix();
        String glob = storagePath.globPart();
        GlobMatcher matcher = new GlobMatcher(glob);
        boolean recursive = matcher.needsRecursion();

        List<StorageEntry> matched = new ArrayList<>();
        String prefixStr = prefix.toString();

        try (StorageIterator iterator = provider.listObjects(prefix, recursive)) {
            while (iterator.hasNext()) {
                StorageEntry entry = iterator.next();
                String entryPath = entry.path().toString();
                String relativePath;
                if (entryPath.startsWith(prefixStr)) {
                    relativePath = entryPath.substring(prefixStr.length());
                } else {
                    relativePath = entry.path().objectName();
                }
                if (matcher.matches(relativePath)) {
                    matched.add(entry);
                }
            }
        }

        // Apply file metadata filters from WHERE clause hints (e.g., _file.modified > X, _file.size > Y).
        // This prunes files at listing time — before any data is read.
        if (hints != null && hints.isEmpty() == false) {
            matched = applyFileMetadataFilters(matched, hints);
        }

        if (matched.isEmpty()) {
            return FileSet.EMPTY;
        }

        matched.sort(Comparator.comparing(e -> e.path().toString()));

        PartitionMetadata partitionMetadata = detectPartitions(matched, hivePartitioning, partitionConfig, config);

        return new FileSet(matched, pattern, partitionMetadata);
    }

    static PartitionMetadata detectPartitions(
        List<StorageEntry> files,
        boolean hivePartitioning,
        @Nullable PartitionConfig partitionConfig,
        @Nullable Map<String, Object> config
    ) {
        if (hivePartitioning == false && partitionConfig == null) {
            return null;
        }
        if (partitionConfig != null && PartitionConfig.NONE.equals(partitionConfig.strategy())) {
            return null;
        }

        PartitionDetector detector = resolveDetector(partitionConfig);
        if (detector == null) {
            if (hivePartitioning) {
                detector = HivePartitionDetector.INSTANCE;
            } else {
                return null;
            }
        }

        PartitionMetadata result = detector.detect(files, config);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return result;
    }

    static FileSet expandCommaSeparated(String pathList, StorageProvider provider) throws IOException {
        return expandCommaSeparated(pathList, provider, null, true);
    }

    static FileSet expandCommaSeparated(
        String pathList,
        StorageProvider provider,
        @Nullable List<PartitionFilterHint> hints,
        boolean hivePartitioning
    ) throws IOException {
        return doExpandCommaSeparated(pathList, provider, hints, hivePartitioning, null, null);
    }

    private static FileSet doExpandCommaSeparated(
        String pathList,
        StorageProvider provider,
        @Nullable List<PartitionFilterHint> hints,
        boolean hivePartitioning,
        @Nullable PartitionConfig partitionConfig,
        @Nullable Map<String, Object> config
    ) throws IOException {
        if (pathList == null) {
            throw new IllegalArgumentException("pathList cannot be null");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider cannot be null");
        }

        String[] segments = pathList.split(",");
        List<StorageEntry> allEntries = new ArrayList<>();

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            StoragePath segmentPath = StoragePath.of(trimmed);
            if (segmentPath.isPattern()) {
                FileSet expanded = doExpandGlob(trimmed, provider, hints, hivePartitioning, partitionConfig, config);
                if (expanded.isResolved()) {
                    allEntries.addAll(expanded.files());
                }
            } else {
                if (provider.exists(segmentPath)) {
                    var obj = provider.newObject(segmentPath);
                    allEntries.add(new StorageEntry(segmentPath, obj.length(), obj.lastModified()));
                }
            }
        }

        if (allEntries.isEmpty()) {
            return FileSet.EMPTY;
        }

        allEntries.sort(Comparator.comparing(e -> e.path().toString()));

        PartitionMetadata partitionMetadata = detectPartitions(allEntries, hivePartitioning, partitionConfig, config);

        return new FileSet(allEntries, pathList, partitionMetadata);
    }

    static String rewriteGlobWithHints(String pattern, List<PartitionFilterHint> hints) {
        return rewriteGlobWithHints(pattern, hints, null);
    }

    static String rewriteGlobWithHints(String pattern, List<PartitionFilterHint> hints, @Nullable PartitionConfig partitionConfig) {
        Map<String, PartitionFilterHint> rewritableHints = indexRewritableHints(hints);
        if (rewritableHints.isEmpty()) {
            return pattern;
        }

        if (partitionConfig != null && partitionConfig.pathTemplate() != null) {
            String templateRewritten = rewriteGlobWithTemplate(pattern, rewritableHints, partitionConfig.pathTemplate());
            if (templateRewritten != null) {
                return templateRewritten;
            }
        }

        String[] segments = pattern.split("/");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(rewriteSegment(segments[i], rewritableHints));
        }
        return result.toString();
    }

    static String rewriteGlobWithTemplate(String pattern, Map<String, PartitionFilterHint> rewritableHints, String template) {
        List<String> templateColumns = TemplatePartitionDetector.parseTemplateColumns(template);
        if (templateColumns.isEmpty()) {
            return null;
        }

        String[] segments = pattern.split("/");
        List<Integer> wildcardPositions = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            if ("*".equals(segments[i])) {
                wildcardPositions.add(i);
            }
        }

        if (wildcardPositions.size() < templateColumns.size()) {
            return null;
        }

        // Map template columns to wildcard positions (positional mapping)
        int offset = wildcardPositions.size() - templateColumns.size();
        boolean changed = false;
        for (int t = 0; t < templateColumns.size(); t++) {
            String colName = templateColumns.get(t);
            PartitionFilterHint hint = rewritableHints.get(colName);
            if (hint == null) {
                continue;
            }
            int segIdx = wildcardPositions.get(offset + t);
            List<Object> values = hint.values();
            if (hint.isSingleValue()) {
                segments[segIdx] = escapeGlobMeta(String.valueOf(values.get(0)));
            } else {
                StringBuilder sb = new StringBuilder("{");
                for (int v = 0; v < values.size(); v++) {
                    if (v > 0) {
                        sb.append(',');
                    }
                    sb.append(escapeGlobMeta(String.valueOf(values.get(v))));
                }
                segments[segIdx] = sb.append('}').toString();
            }
            changed = true;
        }

        if (changed == false) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(segments[i]);
        }
        return result.toString();
    }

    private static Map<String, PartitionFilterHint> indexRewritableHints(List<PartitionFilterHint> hints) {
        Map<String, PartitionFilterHint> byColumn = Maps.newHashMapWithExpectedSize(hints.size());
        for (PartitionFilterHint hint : hints) {
            if (hint.operator().canRewriteGlob()) {
                byColumn.putIfAbsent(hint.columnName(), hint);
            }
        }
        return byColumn;
    }

    private static String rewriteSegment(String segment, Map<String, PartitionFilterHint> rewritableHints) {
        int eqIdx = segment.indexOf('=');
        if (eqIdx <= 0 || eqIdx >= segment.length() - 1) {
            return segment;
        }

        String key = segment.substring(0, eqIdx);
        String valuePart = segment.substring(eqIdx + 1);
        if ("*".equals(valuePart) == false) {
            return segment;
        }

        PartitionFilterHint hint = rewritableHints.get(key);
        if (hint == null) {
            return segment;
        }

        List<Object> values = hint.values();
        if (hint.isSingleValue()) {
            return key + "=" + escapeGlobMeta(String.valueOf(values.get(0)));
        }

        // Multiple values: use glob brace syntax key={v1,v2,...}
        StringBuilder sb = new StringBuilder(key).append("={");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeGlobMeta(String.valueOf(values.get(i))));
        }
        return sb.append('}').toString();
    }

    private static String escapeGlobMeta(String value) {
        if (value.indexOf('*') < 0 && value.indexOf('?') < 0 && value.indexOf('[') < 0 && value.indexOf('{') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                sb.append('[').append(c).append(']');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static List<StorageEntry> applyFileMetadataFilters(List<StorageEntry> entries, List<PartitionFilterHint> hints) {
        List<PartitionFilterHint> fileHints = new ArrayList<>();
        for (PartitionFilterHint hint : hints) {
            if (FileMetadataColumns.isFileMetadataColumn(hint.columnName())) {
                fileHints.add(hint);
            }
        }
        if (fileHints.isEmpty()) {
            return entries;
        }

        int beforeCount = entries.size();
        List<StorageEntry> filtered = new ArrayList<>(entries.size());
        for (StorageEntry entry : entries) {
            if (matchesAllFileHints(entry, fileHints)) {
                filtered.add(entry);
            }
        }

        if (filtered.size() < beforeCount) {
            logger.debug("File metadata filter pruned {}/{} files from listing", beforeCount - filtered.size(), beforeCount);
        }
        return filtered;
    }

    private static boolean matchesAllFileHints(StorageEntry entry, List<PartitionFilterHint> fileHints) {
        for (PartitionFilterHint hint : fileHints) {
            if (matchesFileHint(entry, hint) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFileHint(StorageEntry entry, PartitionFilterHint hint) {
        return switch (hint.columnName()) {
            case FileMetadataColumns.MODIFIED -> evaluateTimestamp(entry.lastModified(), hint);
            case FileMetadataColumns.SIZE -> evaluateLong(entry.length(), hint);
            case FileMetadataColumns.PATH -> evaluateString(entry.path().toString(), hint);
            case FileMetadataColumns.NAME -> evaluateString(entry.path().objectName(), hint);
            case FileMetadataColumns.DIRECTORY -> {
                StoragePath parent = entry.path().parentDirectory();
                yield parent != null ? evaluateString(parent.toString(), hint) : true;
            }
            default -> true; // Unknown hint — don't filter (safe fallback)
        };
    }

    private static boolean evaluateTimestamp(Instant actual, PartitionFilterHint hint) {
        if (actual == null) {
            return true; // Unknown timestamp — don't filter (conservative)
        }
        if (hint.values().isEmpty()) {
            return true;
        }
        Object hintValue = hint.values().get(0);
        long actualMillis = actual.toEpochMilli();
        long hintMillis;
        if (hintValue instanceof Long l) {
            hintMillis = l;
        } else if (hintValue instanceof String s) {
            try {
                hintMillis = Instant.parse(s).toEpochMilli();
            } catch (Exception e) {
                return true; // Unparseable — don't filter
            }
        } else {
            return true;
        }
        return evaluateComparison(Long.compare(actualMillis, hintMillis), hint.operator());
    }

    private static boolean evaluateLong(long actual, PartitionFilterHint hint) {
        if (hint.values().isEmpty()) {
            return true;
        }
        Object hintValue = hint.values().get(0);
        long hintLong;
        if (hintValue instanceof Number n) {
            hintLong = n.longValue();
        } else if (hintValue instanceof String s) {
            try {
                hintLong = Long.parseLong(s);
            } catch (NumberFormatException e) {
                return true;
            }
        } else {
            return true;
        }

        if (hint.operator() == PartitionFilterHintExtractor.Operator.IN) {
            for (Object v : hint.values()) {
                long inVal = v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
                if (actual == inVal) {
                    return true;
                }
            }
            return false;
        }
        return evaluateComparison(Long.compare(actual, hintLong), hint.operator());
    }

    private static boolean evaluateString(String actual, PartitionFilterHint hint) {
        if (actual == null || hint.values().isEmpty()) {
            return true;
        }
        Object hintValue = hint.values().get(0);
        String hintStr = hintValue.toString();

        if (hint.operator() == PartitionFilterHintExtractor.Operator.IN) {
            for (Object v : hint.values()) {
                if (actual.equals(v.toString())) {
                    return true;
                }
            }
            return false;
        }
        return evaluateComparison(actual.compareTo(hintStr), hint.operator());
    }

    private static boolean evaluateComparison(int cmp, PartitionFilterHintExtractor.Operator operator) {
        return switch (operator) {
            case EQUALS -> cmp == 0;
            case NOT_EQUALS -> cmp != 0;
            case GREATER_THAN -> cmp > 0;
            case GREATER_THAN_OR_EQUAL -> cmp >= 0;
            case LESS_THAN -> cmp < 0;
            case LESS_THAN_OR_EQUAL -> cmp <= 0;
            case IN -> false; // Handled separately in caller
        };
    }
}
