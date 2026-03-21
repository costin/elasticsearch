# Virtual File Metadata Columns: Deep Analysis & Implementation Plan

## Revision History

- **v1** (2026-03-20): Initial plan with `_file`-prefix naming and separate WITH config options
- **v2** (2026-03-20): Revised after deeper research into SPI applicability (Flight/Iceberg), naming debate, config-vs-query architecture, and listing-time filter extraction
- **v3** (2026-03-20): Addressed review feedback — grammar path verification, serialization/BWC, name conflict strategy, optimizer assumptions, pruning correctness, observability, information disclosure
- **v4** (2026-03-21): Final naming: `_file.*` dot-namespaced columns (collision-proof against Hive partitions). Always-on (Option A). Removed unused fileMetadata field on FileSplit, TransportVersion CSV, and EsqlCapability. Separated partition values from file metadata in VirtualColumnInjector to fix HashMap overwrite bug.

---

## The Problem: Source Identity and Provenance

When querying multiple files via a glob pattern (`FROM "s3://bucket/*.parquet"`), users need to:
1. **Attribute rows to their source** — "which file did this row come from?"
2. **Filter by source metadata** — "only read files modified after June 2024"
3. **Aggregate by source** — "count rows per file", "total size per partition"

Every competing system provides this. ESQL currently does not.

---

## v4 Final Design (supersedes naming in earlier sections)

### Naming: `_file.*` Dot-Namespaced Columns

**Decision:** Use `_file.` prefix with dot separation. This is collision-proof against Hive partition keys (which cannot contain dots per `HivePartitionDetector`) and provides a natural extensible namespace.

**Phase 1 columns:**

| Column | Type | Value | Example |
|---|---|---|---|
| `_file.path` | `keyword` | Full URI including scheme | `s3://bucket/dir/events.parquet` |
| `_file.name` | `keyword` | Basename (object name only) | `events.parquet` |
| `_file.directory` | `keyword` | Parent directory URI | `s3://bucket/dir/` |
| `_file.size` | `long` | File size in bytes | `52428800` |
| `_file.modified` | `datetime` | Last modification timestamp (UTC) | `2024-07-15T10:30:00Z` |

**Phase 2 columns (future, not implemented now):**

| Column | Type | Notes |
|---|---|---|
| `_file.format` | `keyword` | `parquet`, `csv`, `ndjson` — known from extension |
| `_file.offset` | `long` | Split start byte within file (per-split, not per-file) |
| `_file.split_length` | `long` | Split byte length (per-split) |
| `_file.row_number` | `long` | Row ordinal within file (per-row, not per-file) |

### Why `_file.*`

1. **Collision-proof against Hive partitions** — dots are rejected by `HivePartitionDetector`
2. **Consistent with ES** — `_source` is a metadata object, `_file` follows the same pattern
3. **Industry-aligned** — Flink uses `file.path`, `file.name`, `file.size`, `file.modification-time`
4. **Extensible** — new properties fit naturally under `_file.*`
5. **Unquoted in ESQL** — `_file.path` is a valid dotted field reference

### Behavioral Design

- **Always-on** — columns added automatically to every external source query (like Hive partition columns)
- **Data column wins** — if a source file has a column named `_file.path` (extremely unlikely with dots), the data column takes precedence and the metadata column is silently skipped
- **PruneColumns removes unused** — zero cost if not referenced in query
- **Separate from partition values** — file metadata values are NOT merged into partitionValues map. VirtualColumnInjector receives both maps separately to prevent HashMap overwrite bugs

### What Was Removed (from v3)

- **`fileMetadata` field on FileSplit** — unnecessary; file metadata extracted at injection time from StorageEntry
- **TransportVersion `esql_external_source_file_metadata`** — no new serialization needed
- **`EXTERNAL_SOURCE_METADATA_COLUMNS` capability** — no grammar change, no client gating needed
- **Grammar/parser/analyzer changes** — columns are always-on, no METADATA clause

---

## Cross-System Comparison

### What Each System Exposes

| Column Concept | Spark | Trino | ClickHouse | DuckDB | ESQL Today |
|---|---|---|---|---|---|
| **Full file path** | `_metadata.file_path` | `"$path"` | `_path` | `filename` | --- |
| **File name only** | `_metadata.file_name` | --- | `_file` | --- | --- |
| **File size** | `_metadata.file_size` | `"$file_size"` | `_size` | --- | --- |
| **Modification time** | `_metadata.file_modification_time` | `"$file_modified_time"` | `_time` | --- | --- |
| **Block start/length** | `_metadata.file_block_start/length` | --- | --- | --- | --- |
| **Row number in file** | --- | --- | --- | `file_row_number` | --- |
| **Partition string** | --- | `"$partition"` | --- | --- | --- |
| **ETag** | --- | --- | `_etag` (S3) | --- | --- |

### How They're Accessed

| System | Access Pattern | Visibility in Schema |
|---|---|---|
| **Spark** | `SELECT _metadata.file_path FROM ...` (struct access) | Hidden from `DESCRIBE`; explicit reference only |
| **Trino** | `SELECT "$path" FROM ...` (dollar-prefix, requires quoting) | Hidden from `SHOW COLUMNS`/`DESCRIBE` |
| **ClickHouse** | `SELECT _path, _file FROM s3(...)` (underscore-prefix) | Always available, no special syntax |
| **DuckDB** | `SELECT filename FROM read_parquet(...)` (auto since v1.3) | Auto-added virtual column |
| **ES (indices)** | `FROM index METADATA _id, _source` (explicit declaration) | Only when declared in METADATA clause |

### Pushdown / Pruning Capabilities

| System | Path-based file pruning | Modification time pruning |
|---|---|---|
| **Spark** | No (but `pathGlobFilter` at listing time) | `modifiedBefore`/`modifiedAfter` at **listing time** (most efficient); `_metadata.file_modification_time` in WHERE at scan time |
| **Trino** | `$path` split-level pruning (PR #27000, Oct 2025) | Post-filter only (not pushed to split gen) |
| **ClickHouse** | `_file`/`_path` file-level pruning against listing results | Post-filter |
| **DuckDB** | No pushdown on filename | No modification time column |

### Key Architectural Insight

There are **two fundamentally different levels** of filtering:

1. **Listing-time filtering** (before any I/O) — Spark's `modifiedBefore`/`modifiedAfter` prevent files from entering the query plan at all. This is the most efficient approach.

2. **Scan-time filtering** (after listing, during execution) — `WHERE _metadata.file_modification_time > X` requires files to be listed and splits to be created, but can skip reading data files. Less efficient but more flexible (any predicate shape).

Both are valuable. Listing-time filtering should be done via `WITH` configuration options (analogous to Spark's DataSource options). Scan-time filtering should work via standard `WHERE` clauses on virtual columns.

---

## Design Decisions for ESQL

### 1. Naming Convention: Underscore Prefix

**Decision:** Use `_` prefix, matching ES's existing metadata convention (`_source`, `_id`, `_index`) and ClickHouse's pattern.

**Proposed columns:**

| Column | Type | Value | Constant per... |
|---|---|---|---|
| `_path` | `keyword` | Full URI: `s3://bucket/dir/file.parquet` | File |
| `_file` | `keyword` | Filename only: `file.parquet` | File |
| `_file_size` | `long` | File size in bytes | File |
| `_last_modified` | `datetime` | Last modification timestamp | File |

### 2. Access Pattern: METADATA Clause

**Decision:** Follow the ES index metadata pattern — virtual columns are available when explicitly requested via the `METADATA` keyword:

```sql
FROM "s3://bucket/data/*.parquet" METADATA _path, _file_size
| WHERE _file_size > 1000000
| STATS count(*) BY _path
```

This is consistent with how ES indices work today:
```sql
FROM my_index METADATA _id, _index
```

**Rationale:**
- Consistent with existing ESQL syntax
- Avoids polluting `SELECT *` (like Trino/ClickHouse avoid via `hidden` flag)
- Explicit opt-in means no overhead when not needed
- The parser/analyzer pattern already exists for ES indices

### 3. Flat Columns vs Struct

**Decision:** Flat columns (not a Spark-style `_metadata` struct). Reasons:
- ES metadata fields are already flat (`_id`, `_source`, not `_metadata.id`)
- Simpler implementation — reuses existing `VirtualColumnInjector` directly
- ESQL's type system doesn't currently use anonymous structs for metadata
- Each column can be independently requested (no need to SELECT the whole struct)

### 4. Listing-Time Filtering

**Decision:** No separate `WITH modified_after` config option. Instead, `WHERE _last_modified > X` predicates are automatically extracted by `PartitionFilterHintExtractor` (Phase 3) and applied at listing time (Phase 4.1). This is the ClickHouse model — one mechanism, not two.

```sql
-- This automatically prunes files at listing time:
FROM "s3://bucket/data/*.parquet" METADATA _last_modified
| WHERE _last_modified > "2024-06-01T00:00:00Z"::datetime AND revenue > 100
```

**Rationale:** ESQL already has `PartitionFilterHintExtractor` that extracts WHERE predicates BEFORE glob expansion. The same infrastructure handles file metadata predicates. Spark needs separate config options because its optimizer doesn't push `_metadata` predicates to listing; ESQL's existing infrastructure already does this for partition columns.

### 5. Name Conflict Prevention

**Decision:** Use `_` prefix with **data-column-wins** precedence and **analyzer-time warning**.

**Names chosen:** `_path`, `_file`, `_file_size`, `_last_modified`

**Conflict strategy comparison:**

| Strategy | Used By | Chosen? | Reason |
|---|---|---|---|
| Hard-reject reserved names | PostgreSQL | No | Too inflexible; adding new metadata = breaking change |
| `$` prefix (illegal in unquoted SQL) | Trino | No | Foreign to ES conventions; requires quoting |
| `_` prefix, data wins silently | ClickHouse | **Partial** | Match ES convention, but add warning |
| User-specifiable alias | DuckDB | No | Adds complexity for rare edge case |
| Rename + API access | Spark 3.5+ | No | ESQL has no metadataColumn() API |

**Conflict resolution rules:**
1. If an external source (Parquet/CSV) has a column matching a requested METADATA name (e.g., file has `_path` column AND user writes `METADATA _path`): **error at analysis time** with message: `"Column '_path' exists in source schema and conflicts with metadata field '_path'. Rename the source column or omit this metadata field."`
2. If user requests `METADATA _path` but source has no column named `_path`: **no conflict**, metadata column added normally.
3. If user does NOT request METADATA: **no issue** regardless of source column names.

**Why error (not silent precedence):**
- Silent data-wins (ClickHouse) means users get wrong results with no warning
- Silent metadata-wins means data columns vanish
- An error is safe and actionable — user knows exactly what to do
- Conflicts are rare (external files rarely have `_path` columns) so the error rarely fires

**Names to avoid:** `_source` (already ES metadata), `_index` (already ES metadata), `_id` (already ES metadata), `_version`, `_score`, `_size` (already ES metadata for mapper-size plugin).

### 6. Information Disclosure

`_path` exposes full bucket/prefix URI (e.g., `s3://company-data-lake/sensitive-project/finance/q4-2024.parquet`). This can reveal:
- Internal bucket naming conventions
- Organization structure from path hierarchy
- Existence of specific datasets

**Mitigation:** File metadata columns are opt-in via `METADATA` clause (not in `SELECT *`). Access control relies on ES security — if the user can query the external source, they already know the URI (it's in the `FROM` clause). The `_path` column reveals additional paths only for glob queries where multiple files are returned. Document this in the feature docs with a note about glob expansion revealing sibling paths.

---

## Verified Architecture: Grammar, Optimizer, Serialization

### Grammar Path (Verified Gap)

The `externalCommand` grammar rule is **completely separate** from `indexPatternAndMetadataFields`:

```antlr
// Current grammar - METADATA only available for index sources:
fromCommand: FROM indexPatternAndMetadataFields ;
indexPatternAndMetadataFields: indexPatternOrSubquery (COMMA indexPatternOrSubquery)* metadata? ;

// External command - NO METADATA support:
externalCommand: DEV_EXTERNAL stringOrParameter commandNamedParameters ;
```

**`UnresolvedExternalRelation`** has NO `metadataFields` field. `MetadataAttribute.create()` will REJECT `_path`/`_file` because they're not in `ATTRIBUTES_MAP` (only `_id`, `_source`, `_index`, etc.).

**Required changes (6 items):**
1. Grammar: Add `metadata?` to `externalCommand` rule
2. `UnresolvedExternalRelation`: Add `List<NamedExpression> metadataFields` field
3. `LogicalPlanBuilder.visitExternalCommand()`: Extract METADATA clause and pass to constructor
4. New: `FileMetadataAttribute.create()` or extend `MetadataAttribute` to recognize external-source metadata names
5. `Analyzer.ResolveExternalRelations`: Validate requested metadata fields, merge into output schema
6. `ExternalRelation`: Add `metadataFields` field to carry through logical plan

### Optimizer Assumptions (Verified Risks)

**`PruneColumns`** explicitly handles `ExternalRelation` — unused metadata columns are pruned automatically. This is correct behavior.

**`projectedColumns` derivation is the critical bug vector.** Currently in `ExternalSourceOperatorFactory`:
```java
for (Attribute attr : attributes) {
    projectedColumns.add(attr.name());  // Includes ALL attributes
}
```
If metadata columns (e.g., `_path`) are in `attributes`, they'll be requested from the FormatReader which doesn't have them → **runtime error**.

**Fix:** Exclude metadata columns from `projectedColumns` the same way partition columns are already excluded. The `VirtualColumnInjector` adds them back as constant blocks.

**`PushFiltersToSource`** would try to push `WHERE _path LIKE '%x%'` to the format reader. `FilterPushdownSupport` implementations must not attempt to push metadata column predicates.

**Fix:** In the filter pushdown path, recognize metadata column references and keep them as remainder filters (not pushed to source). The metadata predicate serves as listing-time filter via `PartitionFilterHintExtractor` and as a scan-time no-op.

### Serialization / BWC (Verified)

**`FileSplit`** currently serializes ALL fields unconditionally (no TransportVersion gates). Adding `fileMetadata` requires:

1. New TransportVersion: Create `esql_external_source_file_metadata` in `server/src/main/resources/transport/definitions/referable/`
2. Conditional serialization in `FileSplit.writeTo()`:
   ```java
   if (out.getTransportVersion().supports(ESQL_FILE_METADATA)) {
       out.writeGenericMap(fileMetadata);
   }
   ```
3. Conditional deserialization in `FileSplit(StreamInput)`:
   ```java
   this.fileMetadata = in.getTransportVersion().supports(ESQL_FILE_METADATA)
       ? in.readGenericMap() : Map.of();
   ```

**Safe to extend without version gates:** `SourceOperatorContext` (not serialized), `FileSet` (not serialized), `ExternalRelation` (logical plan, not serialized across nodes).

**`ExternalSourceExec`** is serialized across nodes but `fileSet` and `pushedFilter` are already NOT serialized. The `attributes` field (which includes metadata columns) IS serialized — this is fine as long as the receiving node understands the new attribute types.

### Hints vs Filtering Contract

**Contract for metadata predicates:**

| Predicate Shape | Listing-time? | Scan-time? | Mechanism |
|---|---|---|---|
| `_last_modified > literal` | Yes | Yes (no-op) | `PartitionFilterHintExtractor` → `GlobExpander` |
| `_last_modified > (subquery)` | No (dynamic) | Yes (real filter) | Cannot extract static value |
| `_path = 'literal'` | Yes | Yes (no-op) | Same hint extraction path |
| `_path LIKE 'pattern'` | Depends on hint support | Yes | LIKE requires hint evaluator extension |
| `_file IN ('a', 'b')` | Yes | Yes (no-op) | IN-list extraction already supported |
| `_file_size > literal` | Yes (post-listing) | Yes (no-op) | Filter against `StorageEntry.length()` |

**Avoiding double-application:** The listing-time filter reduces the file set. The scan-time filter evaluates against constant blocks injected by `VirtualColumnInjector`. Because the values are constants for surviving files, the scan-time predicate always evaluates to `true` — it's a semantic no-op but logically correct. No special handling needed to avoid double-application.

**Avoiding semantic drift:** Listing-time evaluation must be **conservative** (no false positives — never skip a file that should be included). False negatives are acceptable (including a file that could have been skipped just means the scan-time filter does the work). This means:
- Timezone handling: listing-time comparison uses UTC millis from `StorageEntry.lastModified()`, which is exactly what S3/GCS/Azure return. The scan-time comparison uses ESQL's datetime semantics (also UTC millis). No drift.
- Inclusive/exclusive boundaries: `PartitionFilterHint` evaluation must match SQL semantics (`>` is exclusive, `>=` is inclusive). Verify with edge-case tests.
- LIKE escaping: If LIKE support is added to hint evaluation, it must match ESQL's LIKE semantics exactly. Initially, LIKE predicates should NOT be extracted as hints (safe fallback to scan-time only).

---

## Datetime Representation (Verified)

| Layer | Type | Representation |
|---|---|---|
| `StorageEntry.lastModified()` | `java.time.Instant` | Nanosecond precision (from S3 API) |
| ESQL `DataType.DATETIME` | `long` | Milliseconds since epoch UTC |
| `VirtualColumnInjector` constant block | `LongBlock` | Same millis-since-epoch |
| Query comparison | `DatetimeComparison` | Millis-since-epoch UTC |

**Conversion:** `Instant.toEpochMilli()` — truncates to millis (S3 provides second-level precision, so no loss). The `VirtualColumnInjector.createConstantBlock()` currently handles `Long` values via `blockFactory.newConstantLongBlockWith()`. DATETIME attributes backed by LongBlock will work correctly — no accidental string/keyword encoding.

**Edge case:** `Instant.toEpochMilli()` throws `ArithmeticException` for dates outside `Long` range (before year -292275055 or after year 292278994). Not a practical concern for file modification times.

---

## Verification & Acceptance Criteria

### No-Regression
- Queries without `METADATA` must behave identically: same schema, same results, same performance
- Verify by running existing external source test suite (csv-spec tests) unchanged
- Performance: confirm no additional `StorageEntry` metadata fetching when METADATA not requested

### Value Correctness
- Multi-file fixture with known ground truth:
  - File A: known path, known size, known modification time
  - File B: different path, different size, different modification time
  - Query: `METADATA _path, _file, _file_size, _last_modified | KEEP _path, _file, _file_size, _last_modified`
  - Assert: each row matches its source file's actual metadata
- Edge cases:
  - Non-ASCII paths (e.g., `s3://bucket/données/файл.parquet`)
  - Large file sizes (> 2^31 bytes — must use `long`, not `int`)
  - UTC semantics: pin `_last_modified` to UTC millis; test with files created in different timezones
  - Single-file query (no glob): `_path` still works, equals the FROM path

### Conflict Handling
- Test: Parquet file with column named `_path` + `METADATA _path` → clear error
- Test: Parquet file with column named `_path` + NO METADATA → works, `_path` is a normal data column
- Test: Parquet file without `_path` column + `METADATA _path` → works, `_path` is metadata
- Deterministic: same inputs always produce same outcome (error or success)

### Pruning Correctness
- "Files skipped" verification via test double/counter on `StorageProvider`:
  - 10 files, 5 modified before cutoff, 5 after
  - `WHERE _last_modified > cutoff` → assert only 5 files opened (not 10)
  - Use mock `StorageProvider` that counts `newObject()` calls
- Unsupported predicate fallback:
  - `WHERE _last_modified > (SELECT ...)` → all 10 files opened (safe fallback)
  - `WHERE _path LIKE '%complex%'` (if LIKE not in hint evaluator) → all files opened

### BWC / Mixed-Version
- `FileSplit` serialization round-trip with new `fileMetadata` field
- Simulate old-version deserialization: verify `fileMetadata` defaults to `Map.of()` gracefully
- TransportVersion gate test: write with new version, read with old → no crash, metadata absent
- Mixed-cluster test: coordinator on new version, data node on old version → query works (metadata columns empty/absent on old nodes)

### Observability
- Debug log line in `GlobExpander` when listing-time pruning applied:
  ```
  [DEBUG] [o.e.x.e.d.GlobExpander] Listing-time filter applied: _last_modified > 2024-06-01T00:00:00Z, files before: 100, files after: 42
  ```
- Counter/metric: `esql.external_source.files_pruned_by_metadata_filter` (optional, for production diagnostics)
- EXPLAIN / profile output: include whether metadata columns were requested and whether listing-time pruning was applied (future enhancement, not required for initial implementation)

---

## What Already Exists (Foundation)

The ESQL codebase provides ~80% of the infrastructure needed:

| Component | Existing Pattern | What It Does |
|---|---|---|
| `VirtualColumnInjector` | Partition columns | Injects constant blocks into Pages, matching column positions |
| `StorageEntry` | File listing | Record with `path`, `length`, `lastModified` — already captures all needed metadata |
| `FileSplit.partitionValues` | Per-file values | `Map<String, Object>` carried per split — can carry file metadata too |
| `PartitionMetadata` | Schema + per-file values | Pattern for declaring virtual column types and per-file values |
| `ExternalSourceResolver` | Schema enrichment | Where partition columns are added to schema — same point for file metadata |
| `AsyncExternalSourceOperatorFactory` | Column injection | Where `VirtualColumnInjector` is created per-split |
| `MetadataAttribute` | ES index metadata | Parser/analyzer pattern for `METADATA _id, _source` clause |
| `EsqlCapabilities` | Feature flags | Where to gate the new feature |

**The gap is in the middle layers:** parsing, analysis, and resolution for external sources don't currently wire the `METADATA` clause.

---

## Detailed Implementation Plan

### Stage 1: Define File Metadata Columns + FileSplit BWC Extension

**Goal:** Establish the virtual column definitions, metadata carrier structures, and version-gated serialization.
**Status:** Not Started

**Files to modify/create:**

1. **New: `FileMetadataColumns.java`** in `datasources/` package

   A registry of well-known file metadata virtual columns. NOT an extension of `MetadataAttribute.ATTRIBUTES_MAP` (which is ES-index-specific). This is a parallel registry for external sources.

   ```java
   public final class FileMetadataColumns {
       public static final String PATH = "_path";
       public static final String FILE = "_file";
       public static final String FILE_SIZE = "_file_size";
       public static final String LAST_MODIFIED = "_last_modified";

       // Name → DataType mapping (used by analyzer for validation and type resolution)
       public static final Map<String, DataType> COLUMNS = Map.of(
           PATH, DataType.KEYWORD,
           FILE, DataType.KEYWORD,
           FILE_SIZE, DataType.LONG,
           LAST_MODIFIED, DataType.DATETIME
       );

       /** Extract metadata values from a StorageEntry. Converts Instant to epoch millis for DATETIME. */
       public static Map<String, Object> extractValues(StorageEntry entry) {
           return Map.of(
               PATH, new BytesRef(entry.path().toString()),       // keyword → BytesRef
               FILE, new BytesRef(entry.path().objectName()),     // keyword → BytesRef
               FILE_SIZE, entry.length(),                          // long
               LAST_MODIFIED, entry.lastModified().toEpochMilli() // datetime → long millis UTC
           );
       }

       /** Check if a name is a known file metadata column. */
       public static boolean isFileMetadataColumn(String name) {
           return COLUMNS.containsKey(name);
       }
   }
   ```

   **Note:** Values are stored in ESQL's runtime representation (BytesRef for keywords, long for datetime) not Java types — this ensures `VirtualColumnInjector.createConstantBlock()` creates the correct block type.

2. **Extend `FileSplit`** — Add `Map<String, Object> fileMetadata` field with TransportVersion gate:

   - New TransportVersion: `esql_external_source_file_metadata` in `server/src/main/resources/transport/definitions/referable/`
   - Conditional write: `if (out.getTransportVersion().supports(VERSION)) out.writeGenericMap(fileMetadata);`
   - Conditional read: `this.fileMetadata = in.getTransportVersion().supports(VERSION) ? in.readGenericMap() : Map.of();`
   - Accessor: `public Map<String, Object> fileMetadata() { return fileMetadata; }`

**Success Criteria:**
- `FileMetadataColumns.extractValues()` returns correct types (BytesRef, long) for all four columns
- `FileMetadataColumns.isFileMetadataColumn()` returns true for known names, false for others
- `FileSplit` serialization round-trips with fileMetadata values (new version)
- `FileSplit` deserialization from old version produces empty `fileMetadata` map (BWC)

**Tests:**
- `FileMetadataColumnsTests` — extraction from StorageEntry, type correctness
- `FileSplitTests` — serialization round-trip (new version), BWC deserialization (old version → empty map)
- `FileSplitTests` — verify TransportVersion gate via mock StreamInput/Output

---

### Stage 2: Grammar, Parser & Analyzer Support

**Goal:** Allow `METADATA _path, _file_size` on external source queries, end-to-end from grammar to resolved plan.
**Status:** Not Started

**Verified gap:** The `externalCommand` grammar rule is separate from `indexPatternAndMetadataFields`. `UnresolvedExternalRelation` has no `metadataFields`. `MetadataAttribute.create()` rejects unknown names (returns `UnresolvedMetadataAttributeExpression`).

**Files to modify (6 changes):**

1. **`EsqlBaseParser.g4`** — Add `metadata?` to the `externalCommand` rule:
   ```antlr
   externalCommand
       : DEV_EXTERNAL stringOrParameter commandNamedParameters metadata?
       ;
   ```
   The `metadata` rule already exists and produces `METADATA UNQUOTED_SOURCE (COMMA UNQUOTED_SOURCE)*`.

2. **`UnresolvedExternalRelation.java`** — Add `List<NamedExpression> metadataFields` field:
   - New constructor parameter
   - Getter: `public List<NamedExpression> metadataFields()`
   - Update `equals()`/`hashCode()`/`nodeInfo()`/`replaceChildren()`

3. **`LogicalPlanBuilder.java`** — In `visitExternalCommand()` (~line 788), extract METADATA clause:
   ```java
   // Parse METADATA fields using same logic as visitRelation():
   List<NamedExpression> metadataFields = List.of();
   if (ctx.metadata() != null) {
       Map<String, NamedExpression> metadataMap = new LinkedHashMap<>();
       for (var c : ctx.metadata().UNQUOTED_SOURCE()) {
           String id = c.getText();
           // Use FileMetadataColumns.create() instead of MetadataAttribute.create()
           metadataMap.put(id, FileMetadataColumns.createAttribute(source(c), id));
       }
       metadataFields = List.copyOf(metadataMap.values());
   }
   return new UnresolvedExternalRelation(source, tablePath, params, metadataFields);
   ```
   **Note:** Cannot reuse `MetadataAttribute.create()` — it checks ES-index ATTRIBUTES_MAP. Need new factory method in `FileMetadataColumns` that creates `ReferenceAttribute` for known names or throws for unknown.

4. **`Analyzer.java`** — In `ResolveExternalRelations` rule (~line 460-493):
   - After resolving schema from `ExternalSourceResolution`, validate `metadataFields`:
     - Each field must be in `FileMetadataColumns.COLUMNS` → create `ReferenceAttribute(name, type)`
     - Unknown field → analysis error: `"Unknown external source metadata field [X]. Available: _path, _file, _file_size, _last_modified"`
     - For connector sources (Flight): → analysis error: `"File metadata columns are not available for connector sources"`
   - **Conflict detection:** Check resolved schema for name collisions with requested metadata fields. If source has column `_path` AND user requests `METADATA _path` → error: `"Column '_path' in source schema conflicts with metadata field '_path'"`
   - Append validated metadata attributes to output schema (after data columns, after partition columns)

5. **`ExternalRelation.java`** — Add `List<Attribute> metadataFields` to carry through logical plan. Update `withAttributes()` to preserve metadata fields.

6. **`EsqlCapabilities.java`** — Add `EXTERNAL_SOURCE_METADATA_COLUMNS` capability flag.

**Success Criteria:**
- `FROM "s3://b/f.parquet" METADATA _path, _file_size | LIMIT 10` parses and resolves correctly
- Unknown metadata fields produce clear error with available field list
- Conflict with source column names produces clear error
- METADATA on Flight source produces clear error
- Feature is gated behind capability
- Queries without METADATA are completely unchanged (no regression)

**Tests:**
- Grammar tests: METADATA clause parses on external sources
- Parser tests: `visitExternalCommand()` produces correct UnresolvedExternalRelation with metadataFields
- Analyzer tests: resolution succeeds for valid fields, fails for unknown/conflict/connector
- Capability gating test: feature unavailable when cap disabled
- No-regression: existing external source tests pass unchanged

---

### Stage 3: Physical Planning — Wire Metadata Through Execution

**Goal:** Carry requested file metadata columns through physical planning to operator execution, with correct exclusion from FormatReader projection and filter pushdown.
**Status:** Not Started

**Verified risk:** `projectedColumns` in `ExternalSourceOperatorFactory` includes ALL attributes. If metadata columns are in `attributes`, they'll be requested from FormatReader → runtime error. Must exclude them, same as partition columns.

**Files to modify:**

1. **`LocalExecutionPlanner.java`** (~line 1275-1305) — Extract `fileMetadataColumnNames` from the resolved attributes intersected with `FileMetadataColumns.COLUMNS.keySet()`. Merge with `partitionColumnNames` into a unified `virtualColumnNames` set. Pass to `SourceOperatorContext`.

2. **`SourceOperatorContext.java`** — Option A: Generalize `partitionColumnNames` to `virtualColumnNames` (encompasses both partition and file metadata). Option B: Add separate `fileMetadataColumnNames` set. **Prefer Option A** — both are constant-value columns injected by `VirtualColumnInjector`, the distinction is implementation detail not architectural.

3. **`ExternalSourceOperatorFactory.java`** (~line 99-101) — When building `projectedColumns`, **exclude** virtual column names:
   ```java
   List<String> projectedColumns = new ArrayList<>();
   for (Attribute attr : attributes) {
       if (!virtualColumnNames.contains(attr.name())) {
           projectedColumns.add(attr.name());
       }
   }
   ```
   This is the critical fix — without it, FormatReader receives column names it can't resolve.

4. **`PushFiltersToSource.java`** — In `planFilterExecForExternalSource()`, ensure filter expressions referencing metadata columns are NOT pushed to `FilterPushdownSupport`. Keep them as remainder filters. The metadata predicate works at listing-time (via hints) and scan-time (as no-op on constant blocks).

5. **`FileSplitProvider.java` / `GlobExpander.java`** — During file listing, extract file metadata values from `StorageEntry` using `FileMetadataColumns.extractValues()` and store in `FileSplit.fileMetadata`.

**Success Criteria:**
- File metadata column names flow through physical plan to operator creation
- `projectedColumns` sent to FormatReader does NOT include metadata column names
- Filter pushdown does NOT attempt to push metadata predicates to format reader
- `FileSplit` carries file metadata values from listing
- `PruneColumns` correctly prunes unused metadata columns (already works — verified)

**Tests:**
- Unit test: `projectedColumns` excludes metadata columns when present in attributes
- Unit test: filter pushdown on `_path` → remains as scan-time filter, not pushed
- FileSplitProvider tests: metadata population from StorageEntry
- No-regression: queries without METADATA produce identical projectedColumns as before

---

### Stage 4: Execution — Inject File Metadata into Pages

**Goal:** Actually produce the file metadata values in query results.
**Status:** Not Started

**Files to modify:**

1. **`AsyncExternalSourceOperatorFactory.java`** — Merge file metadata values with partition values when creating `VirtualColumnInjector`:
   ```java
   Map<String, Object> virtualValues = new HashMap<>();
   virtualValues.putAll(split.partitionValues());
   virtualValues.putAll(split.fileMetadata());  // May be empty on old-version nodes (BWC)
   // virtualColumnNames already includes both partition + metadata column names
   VirtualColumnInjector injector = new VirtualColumnInjector(
       fullOutput, virtualColumnNames, virtualValues, blockFactory);
   ```

   **BWC consideration:** On old data nodes receiving splits from new coordinator, `fileMetadata` is `Map.of()` (version gate). The `VirtualColumnInjector` must handle missing values gracefully — inject NULL blocks for metadata columns when values are absent.

2. **`VirtualColumnInjector.java`** — Two changes:
   - **DATETIME type:** Values stored as `Long` (epoch millis). The existing `Long` case in `createConstantBlock()` creates `LongBlock`. The `Attribute.dataType()` is `DATETIME` but backed by `LongBlock` — verify this matches ESQL's expectation for datetime columns. If ESQL expects a specific block type for DATETIME attributes, add explicit handling.
   - **NULL handling:** When a virtual column has no value (BWC, or Iceberg _last_modified unavailable), create a null constant block: `blockFactory.newConstantNullBlock(positions)`.

3. **FormatReader projection** — Virtual columns excluded from `projectedColumns` (handled in Stage 3). Verify in `AsyncExternalSourceOperatorFactory` that the `FormatReadContext` receives only data columns.

**Success Criteria:**
- End-to-end query works:
  ```sql
  FROM "s3://bucket/data/*.parquet" METADATA _path, _file_size, _last_modified
  | STATS count(*) BY _path
  ```
- Each row carries correct file path, size, and modification time
- Multi-file: different files produce different metadata values
- DATETIME values are correct UTC millis (not accidental string/keyword encoding)
- BWC: old data node with new coordinator → metadata columns are NULL (not error)

**Tests:**
- Unit test: VirtualColumnInjector with Long value for DATETIME attribute → correct LongBlock
- Unit test: VirtualColumnInjector with missing value → null constant block
- Integration tests (csv-spec format) for all four metadata columns on multi-file fixture
- Value correctness: assert `_path`, `_file`, `_file_size`, `_last_modified` match ground truth
- Edge cases: non-ASCII paths, large file sizes (> 2^31), single-file query

---

### Stage 5: Listing-Time Filtering via WHERE Extraction (Replaces Config Options)

**Goal:** Automatically extract `_last_modified` and `_path`/`_file` predicates from WHERE and apply them at listing time — no separate `WITH` options needed.
**Status:** Not Started

**Rationale:** ESQL's `PartitionFilterHintExtractor` already extracts WHERE predicates before glob expansion (Phase 3 of the pipeline). The same mechanism handles file metadata predicates. This is the ClickHouse model — single mechanism, no separate config options.

**Why NOT config options (Spark's `modifiedAfter` approach):**
- Spark needs config options because its optimizer doesn't push `_metadata` predicates to the listing phase
- ESQL already has predicate extraction infrastructure (`PartitionFilterHintExtractor`) that runs BEFORE listing
- One mechanism is simpler than two — users write `WHERE _last_modified > X`, it just works
- ClickHouse proves this approach: `WHERE _time > X` prunes at listing time, no config option exists

**Files to modify:**

1. **`PartitionFilterHintExtractor.java`** — Extend to recognize file metadata column names (`_path`, `_file`, `_last_modified`, `_file_size`) in addition to partition columns. Extract their predicates as hints.

   Currently extracts hints only for `UnresolvedAttribute` names that match partition columns. Change: also extract hints for names in `FileMetadataColumns.COLUMNS`.

2. **`GlobExpander.java`** — After listing files, apply file metadata hints as post-listing filters:
   ```java
   // After StorageProvider.listObjects() returns StorageEntry list:
   if (hints.containsKey("_last_modified")) {
       for (PartitionFilterHint hint : hints.get("_last_modified")) {
           entries = entries.stream()
               .filter(e -> hint.evaluate(e.lastModified()))
               .collect(toList());
       }
   }
   if (hints.containsKey("_path")) {
       // Similar: evaluate path predicates against StorageEntry.path()
   }
   ```

3. **`PartitionFilterHint`** — May need to support datetime comparison (currently handles String/Integer/Long for partition values). Add `Instant` comparison support.

**Success Criteria:**
```sql
-- This query should only list and read files modified after June 2024:
FROM "s3://bucket/data/*.parquet" METADATA _last_modified
| WHERE _last_modified > "2024-06-01T00:00:00Z"::datetime AND revenue > 100

-- The _last_modified predicate is extracted at Phase 3,
-- applied at Phase 4.1 (listing), and is a no-op at Phase 12 (scan)
```

**Tests:**
- Unit test for PartitionFilterHintExtractor with _last_modified predicates
- Unit test for GlobExpander applying timestamp-based file filtering
- Integration test verifying files before cutoff are never opened (check via mock StorageProvider)

---

### Stage 6: Path-Based File Pruning (Enhancement)

**Goal:** Also push `_path` and `_file` predicates to listing-time pruning.
**Status:** Not Started

**Approach:** Same as Stage 5 — `PartitionFilterHintExtractor` extracts `_path`/`_file` predicates, `GlobExpander` applies them against file listing results.

Supported operations:
- `WHERE _file = 'events_2024.parquet'` → exact match on filename
- `WHERE _path LIKE '%events%'` → LIKE pattern on full path (requires LIKE support in hint evaluation)
- `WHERE _file IN ('a.parquet', 'b.parquet')` → IN-list on filename

**Files to modify:**
- Same as Stage 5 (`PartitionFilterHintExtractor`, `GlobExpander`)
- Add LIKE operator support to `PartitionFilterHint` evaluation

**Success Criteria:** `WHERE _file LIKE 'events_2024%'` prunes files at listing time.

---

## Applicability Across Source Types

### File-Based Sources (S3, GCS, Azure, HTTP, Local)

All four metadata columns are fully applicable:
- `_path`: `StorageEntry.path().toString()` → `"s3://bucket/data/file.parquet"`
- `_file`: `StorageEntry.path().objectName()` → `"file.parquet"`
- `_file_size`: `StorageEntry.length()` → bytes
- `_last_modified`: `StorageEntry.lastModified()` → Instant

### Iceberg (Catalog Sources)

Metadata columns are meaningful because Iceberg resolves to physical files:
- `_path`: `DataFile.path()` → S3 URI of underlying Parquet file (from `FileScanTask.file().path()`)
- `_file`: filename extracted from `DataFile.path()`
- `_file_size`: `DataFile.sizeInBytes()` (from `FileScanTask.file().fileSizeInBytes()`)
- `_last_modified`: **Decision: NULL for Iceberg.** Iceberg manifests do not store file modification time. Getting it would require per-file S3 HEAD requests during `planScan()` — unacceptable cost for potentially thousands of files. Return NULL and document this limitation. Users needing time-based filtering on Iceberg should use Iceberg's snapshot/time-travel features instead.

Iceberg's `TableCatalog.planScan()` returns `DataFile[]` — each carries path, format, sizeInBytes, recordCount. The `_path`, `_file`, `_file_size` metadata is available without extra I/O. Only `_last_modified` is unavailable.

### Flight/gRPC (Connector Sources)

**File metadata columns are NOT applicable.** Flight is an API protocol, not a file protocol.

When `METADATA _path` is requested on a Flight source, the analyzer produces a clear error:
> "File metadata columns (_path, _file, _file_size, _last_modified) are not available for connector sources. They are only available for file-based and catalog-based sources."

If Flight-specific metadata is needed in the future (endpoint, target, ticket), it would be a separate set of connector-specific metadata columns — but that's out of scope for this plan.

### Decision Matrix

| Column | File-based | Iceberg | Flight |
|---|---|---|---|
| `_path` | Full URI | Underlying file URI | Error |
| `_file` | Filename | Underlying filename | Error |
| `_file_size` | StorageEntry.length() | DataFile.sizeInBytes() | Error |
| `_last_modified` | StorageEntry.lastModified() | From object metadata | Error |

---

## Implementation Order & Dependencies

```
Stage 1: FileMetadataColumns + FileSplit extension
    ↓ (no dependencies)
Stage 2: Parser + Analyzer (METADATA clause for external sources)
    ↓ (depends on Stage 1 for column definitions)
Stage 3: Physical planning (wire through to operators)
    ↓ (depends on Stage 2 for plan node changes)
Stage 4: Execution (inject into Pages via VirtualColumnInjector)
    ↓ (depends on Stage 3 for operator context)
Stage 5: Listing-time filtering via WHERE extraction
    ↓ (depends on Stages 1-2 for column recognition)
Stage 6: Path-based file pruning (enhancement)
    ↓ (depends on Stage 5 for hint infrastructure)
```

Stages 1-4 form the core feature (virtual columns visible in results).
Stage 5 adds the performance optimization (listing-time filtering).
Stage 6 extends pruning to path predicates.

## Risk Areas (Verified)

- **Serialization/BWC** — `FileSplit` currently has no TransportVersion gates. New `fileMetadata` field requires `esql_external_source_file_metadata` version gate. Old nodes receiving new splits get `Map.of()` (empty metadata). New coordinator + old data node → metadata columns are NULL, not errors. Mixed-version test coverage required.
- **Datetime representation** — ESQL DATETIME is long millis-since-epoch UTC. `StorageEntry.lastModified()` returns `Instant`. Convert via `toEpochMilli()`. `VirtualColumnInjector` creates `LongBlock` for Long values — matches DATETIME block encoding. Verified: no accidental string/keyword.
- **Column name conflicts** — Strategy: **error at analysis time** if source column collides with requested METADATA column (not silent precedence). ES does NOT prevent underscore-prefixed field names in indices or external sources. Avoid `_source` (taken by ES metadata). Chosen names (`_path`, `_file`, `_file_size`, `_last_modified`) do not conflict with existing ES metadata.
- **projectedColumns mismatch** — Critical: metadata columns MUST be excluded from `projectedColumns` sent to FormatReader. Same exclusion pattern as partition columns. Without this fix, FormatReader gets column names it can't resolve → runtime error.
- **Filter pushdown** — `PushFiltersToSource` must not push `_path`/`_last_modified` predicates to `FilterPushdownSupport`. These are handled at listing-time (via hints) or scan-time (constant block evaluation). Metadata column references in filter expressions should remain as remainder filters.
- **Hint extraction limitations** — `PartitionFilterHintExtractor` only extracts `attribute OP literal` predicates from AND-conjoined WHERE. Complex predicates (subqueries, OR-combined, function calls) cannot be extracted → safe fallback to scan-time filtering. LIKE predicates: defer to Stage 6 (initially not extracted).
- **Iceberg `_last_modified` cost** — Decided: NULL for Iceberg. Iceberg manifests don't store modification time. Per-file HEAD requests too expensive. Document this limitation.
- **Information disclosure** — `_path` reveals bucket/prefix structure. Mitigated: opt-in via METADATA clause, user already knows the URI from FROM clause, glob expansion is the only incremental disclosure. Document in feature docs.
- **Grammar backward compatibility** — Adding `metadata?` to `externalCommand` rule is additive (optional element). Old queries parse identically. New queries with METADATA require new ESQL capability flag → version-gated on client side.
