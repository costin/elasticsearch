# Plan: Add LZ4, Snappy, and Brotli Decompression Codecs to ESQL Data Sources

> **Status**: Ready for implementation
> **Date**: 2026-03-20

## Context

The ESQL datasources compression SPI supports three file-level codecs: **gzip**, **zstd**, and **bzip2**. Missing: **LZ4**, **Snappy**, and **Brotli** — common in data lakes and Kafka sink connectors.

**Goals:**
1. Add LZ4/Snappy/Brotli as `DecompressionCodec` plugins for compound extensions (`.csv.lz4`, `.ndjson.snappy`, `.csv.br`)
2. Share compression libraries across plugins — both for file-level decompression AND format-internal use (Parquet/ORC column compression)

---

## Architecture: Shared Compression Plugin

### The Problem Today

Compression libraries are duplicated across multiple plugin classloaders:

| Library | Currently loaded by | Copies |
|---|---|---|
| `aircompressor` 2.0.3 | ORC (transitive via orc-core), Iceberg (transitive) | **2x** |
| `zstd-jni` 1.5.7-6 | ORC (transitive via orc-core), esql-datasource-zstd (implementation) | **2x** |
| `brotli4j` 1.18.0 | ORC (transitive via orc-core) | 1x |
| `lz4-java` 1.10.1 | Server (via libs/lz4) | 1x |
| gzip | JDK built-in | N/A |

Adding new Snappy/LZ4/Brotli codec plugins would make this worse (aircompressor 3x, etc.).

### The Solution: `esql-datasource-compression-libs`

A peer-level plugin that bundles **all shared compression libraries once**. Both format plugins (Parquet, ORC, Iceberg) and codec plugins (Snappy, LZ4, Zstd) extend it via `extendedPlugins` to share the same loaded copies.

```
System ClassLoader (boot layer — lz4-java via libs/lz4, gzip via JDK)
  └── ESQL plugin CL
      │
      ├── esql-datasource-compression-libs CL   ← NEW: single source of truth
      │     extendedPlugins = ['x-pack-esql']
      │     bundles: aircompressor, zstd-jni
      │     (extended by all plugins needing compression)
      │
      │  ┌─ FORMAT PLUGINS (internal column/stripe compression) ───────┐
      ├── esql-datasource-parquet CL   extends [x-pack-esql, compression-libs]
      ├── esql-datasource-orc CL       extends [x-pack-esql, compression-libs]
      ├── esql-datasource-iceberg CL   extends [x-pack-esql, compression-libs]
      │  └────────────────────────────────────────────────────────────┘
      │
      │  ┌─ CODEC PLUGINS (file-level decompression) ─────────────────┐
      ├── esql-datasource-snappy CL    extends [x-pack-esql, compression-libs]
      ├── esql-datasource-zstd CL      extends [x-pack-esql, compression-libs]  ← RETROFITTED
      ├── esql-datasource-lz4 CL       extends [x-pack-esql]  ← lz4-java from server boot
      ├── esql-datasource-brotli CL    extends [x-pack-esql]  ← bundles org.brotli:dec
      ├── esql-datasource-gzip CL      extends [x-pack-esql]  ← JDK built-in
      └── esql-datasource-bzip2 CL     extends [x-pack-esql]  ← custom forked impl
      │  └────────────────────────────────────────────────────────────┘
```

### What compression-libs bundles and why

| Library | Version | Needed by (format-internal) | Needed by (file-level codec) |
|---|---|---|---|
| `aircompressor` | 2.0.3 | ORC (Snappy/LZ4 stripes), Parquet (LZ4_RAW columns) | esql-datasource-snappy |
| `zstd-jni` | 1.5.7-6 | ORC (Zstd stripes via native ZstdCodec) | esql-datasource-zstd |

**Not in compression-libs (no sharing benefit):**
- `lz4-java` — already on server classpath via `libs/lz4`. All plugins access it through boot layer.
- `gzip` — JDK built-in `java.util.zip.GZIPInputStream`. No library needed.
- `bzip2` — Self-contained forked implementation in esql-datasource-bzip2. No external dependency.
- `org.brotli:dec` — Only needed by esql-datasource-brotli. No format uses Brotli internally (ORC's brotli4j transitive dep is optional and unused in ES).
- `brotli4j` — ORC's transitive dep. With compression-libs providing aircompressor, ORC no longer pulls brotli4j independently (or it becomes an excluded transitive).

**How classloader sharing works:**
- `extendedPlugins` supports multiple parents (13 existing examples in codebase)
- NOT transitive — must explicitly declare each parent
- Topological sort ensures parents load before children
- `ExtendedPluginsClassLoader.findClass()` iterates parents in declaration order, first match wins
- Non-modular plugins (all datasource plugins) run in the unnamed module → can access all exported packages from all named modules

**How each consumer resolves compression:**

| Consumer | Needs | Resolution |
|---|---|---|
| **ORC Snappy stripe** | `io.airlift.compress.snappy.SnappyDecompressor` | ORC's `createCodec()` switch → aircompressor from compression-libs parent CL |
| **ORC Zstd stripe** | `com.github.luben.zstd.ZstdInputStream` | ORC's native ZstdCodec → zstd-jni from compression-libs parent CL |
| **ORC LZ4 stripe** | `io.airlift.compress.lz4.Lz4Decompressor` | ORC's `AircompressorCodec` → aircompressor from compression-libs parent CL |
| **ORC Zlib stripe** | `java.util.zip.Inflater` | JDK built-in. No library needed. |
| **Parquet LZ4_RAW column** | `io.airlift.compress.lz4.Lz4Decompressor` | `Class.forName()` → aircompressor from compression-libs parent CL |
| **Parquet Snappy column** | `org.xerial.snappy.Snappy` | Parquet's `SnappyCodec` uses xerial. hadoop-client-runtime may shade it. **Needs verification.** |
| **Parquet Gzip column** | `org.apache.hadoop.io.compress.GzipCodec` | From hadoop-client-api (already bundled). |
| **Parquet Zstd column** | `org.apache.parquet.hadoop.codec.ZstandardCodec` | Parquet bundles this; internally uses zstd-jni from compression-libs parent CL |
| **`.csv.snappy` file-level** | `SnappyFramedInputStream` from aircompressor | Snappy codec → compression-libs parent CL |
| **`.csv.lz4` file-level** | `LZ4FrameInputStream` from lz4-java | Server boot layer via `libs/lz4` |
| **`.csv.zst` file-level** | `ZstdInputStream` from zstd-jni | Zstd codec → compression-libs parent CL (RETROFITTED) |
| **`.csv.gz` file-level** | `GZIPInputStream` | JDK built-in. No change. |
| **`.csv.bz2` file-level** | Custom `CBZip2InputStream` | Self-contained. No change. |

**Packaging coupling (CRITICAL):** Any distribution shipping Parquet, ORC, Iceberg, Snappy, or Zstd plugins **must** also ship `esql-datasource-compression-libs`. This is enforced by `extendedPlugins` — ES will refuse to load a plugin whose declared parent is missing. This is automatic and fail-fast at node startup.

### Retrofit summary: existing codecs

| Existing plugin | Current dep model | After retrofit |
|---|---|---|
| `esql-datasource-zstd` | `implementation "zstd-jni"` (bundles its own copy) | `compileOnly "zstd-jni"` + `extendedPlugins += compression-libs` |
| `esql-datasource-gzip` | No deps (JDK built-in) | **No change** |
| `esql-datasource-bzip2` | No deps (custom impl) | **No change** |

---

## Wire/File Format Decisions

| Codec | Extension(s) | Standard format | Internal buffer |
|---|---|---|---|
| **LZ4** | `.lz4` | LZ4 Frame (RFC, `lz4` CLI) | 2 × block max (default 4MB = **8MB total**) |
| **Snappy** | `.snappy` only | Snappy framing (64KB chunks + CRC-32C) | **~128KB** fixed |
| **Brotli** | `.br` | Brotli stream (RFC 7932) | Lazy, grows to **16MB** worst case |

**`.sz` extension dropped.** Research shows `.sz` is ambiguous (Camtasia video, WinAMP skins, old MS compression). Hadoop/Spark use `.snappy`. Neither ClickHouse nor DuckDB recognize `.sz`. Not worth the confusion.

---

## Library Choices

| Codec | Library | On classpath? | Why this library |
|---|---|---|---|
| **LZ4** | `lz4-java` 1.10.1 | Yes — `libs/lz4` → server | Already ES's transport compression lib. `LZ4FrameInputStream` for standard frame format. |
| **Snappy** | `aircompressor` 2.0.3 | Yes — via ORC/Iceberg | Pure Java + Panama FFI native. `SnappyFramedInputStream`. Avoids adding xerial JNI. |
| **Brotli** | `org.brotli:dec` 0.1.2 | No — new (~60KB) | Google's official decoder. Pure Java, MIT license, decode-only. |

---

## Security & Entitlements

### Entitlement Policies

| Plugin | Entitlement needed | Why |
|---|---|---|
| `compression-libs` | `load_native_libraries` | aircompressor 2.0.3 uses Panama FFI for native LZ4/Snappy/Zstd when available |
| `esql-datasource-lz4` | None | lz4-java from server; codec plugin is pure Java adapter |
| `esql-datasource-snappy` | None | aircompressor from parent CL handles natives |
| `esql-datasource-brotli` | None | `org.brotli:dec` is pure Java |

### thirdPartyAudit

The `compression-libs` plugin needs `ignoreViolations` for aircompressor's `sun.misc.Unsafe` usage:
```gradle
ignoreViolations(
  'io.airlift.compress.snappy.UnsafeUtil',
  'io.airlift.compress.lz4.UnsafeUtil',
  // ... add specific classes when thirdPartyAudit fails
)
```
Pattern: copy from existing ORC/Iceberg thirdPartyAudit configurations.

---

## Decompression Bomb / Resource Protection

### Existing guardrails (already in place)

1. **Byte-based backpressure** — `AsyncExternalSourceBuffer` (default `10 × TARGET_PAGE_SIZE`) limits how much decompressed data accumulates in the pipeline. Producers block when buffer is full.
2. **Row limit** — `FormatReadContext.rowLimit` from LIMIT pushdown caps rows materialized.
3. **Batch size** — `FormatReadContext.batchSize` caps rows per page.
4. **Streaming consumption** — All three decompression streams decompress on `read()`, not upfront. The downstream consumer's pace throttles decompression.

### Per-library buffer analysis

| Library | Max internal buffer | Controlled by | Bomb risk |
|---|---|---|---|
| **LZ4FrameInputStream** | 2 × block max (max 8MB for 4MB blocks) | Frame header Block Maximum Size field (4 valid values: 64K/256K/1M/4M) | **Low** — capped at 8MB by spec. Malicious file can't declare larger. |
| **SnappyFramedInputStream** | ~128KB fixed | Format spec (64KB max chunk) | **None** — fixed allocation regardless of input |
| **BrotliInputStream** | Lazy, up to 16MB | Window bits in stream header (max 24) | **Medium** — 16MB worst case. Progressive allocation, not upfront. |

### Additional protection (new in this plan)

For extra safety, wrap decompression streams in `FormatReader.read()` with the existing `batchSize` / `rowLimit` controls. The format reader already stops reading when enough rows are produced, which naturally bounds decompression. No new circuit breaker code needed — the existing pipeline handles this.

---

## CRC-32C Checksums for Snappy

**Decision: Enable checksums by default.** `SnappyFramedInputStream(raw, true)`.

Rationale:
- Remote blob stores (S3/GCS/Azure) can have silent data corruption from network issues
- Snappy framing format's 64KB chunks mean CRC is checked frequently (not a single massive checksum at end)
- Performance overhead is minimal — CRC-32C is hardware-accelerated on modern CPUs (SSE 4.2)
- Other systems: Spark's `SnappyCodec` verifies checksums. Trino's aircompressor `SnappyFramedInputStream` uses checksums.
- Can be disabled via `WITH {"snappy_verify_checksums": false}` in a future enhancement if needed

---

## Licensing

| Dependency | License | Files needed in plugin |
|---|---|---|
| `io.airlift:aircompressor:2.0.3` | Apache 2.0 | `aircompressor-LICENSE.txt` (Apache 2.0) + `aircompressor-NOTICE.txt` (includes BSD 3-Clause for original Snappy/Google code) |
| `org.brotli:dec:0.1.2` | MIT | `dec-LICENSE.txt` (MIT with Brotli Authors copyright) |
| `at.yawk.lz4:lz4-java:1.10.1` | Apache 2.0 | Already handled by `libs/lz4` — no new license files |

---

## Implementation Stages

### Stage 0: `esql-datasource-compression-libs` (shared plugin) + Retrofit existing codecs

**Goal:** Single source of truth for compression libraries. Consolidates aircompressor (2 copies → 1) and zstd-jni (2 copies → 1).

**Files to create:**

1. **`x-pack/plugin/esql-datasource-compression-libs/build.gradle`**
   ```gradle
   apply plugin: 'elasticsearch.internal-es-plugin'
   apply plugin: 'elasticsearch.publish'

   esplugin {
     name = 'esql-datasource-compression-libs'
     description = 'Shared compression libraries for ESQL external data sources'
     classname = 'org.elasticsearch.xpack.esql.datasource.compress.CompressionLibsPlugin'
     extendedPlugins = ['x-pack-esql']
   }
   base { archivesName = 'esql-datasource-compression-libs' }

   dependencies {
     compileOnly project(path: xpackModule('esql'))
     compileOnly project(path: xpackModule('esql-core'))
     compileOnly project(path: xpackModule('core'))
     compileOnly project(':server')

     // Shared compression libraries — loaded once, used by all child plugins
     implementation "io.airlift:aircompressor:${versions.aircompressor}"  // Snappy, LZ4, Zstd (pure Java + Panama native)
     implementation "com.github.luben:zstd-jni:${versions.zstdJni}"      // Zstd native (ORC ZstdCodec, esql-datasource-zstd)
   }

   tasks.named("thirdPartyAudit").configure {
     ignoreViolations(
       // aircompressor uses sun.misc.Unsafe for Snappy/LZ4/Zstd
       // Copy specific violation classes from ORC plugin's thirdPartyAudit when they appear
     )
   }
   tasks.withType(org.elasticsearch.gradle.internal.AbstractDependenciesTask).configureEach {
     mapping from: /aircompressor-.*/, to: 'aircompressor'
     mapping from: /zstd-jni-.*/, to: 'zstd'
   }
   ```

2. **`x-pack/plugin/esql-datasource-compression-libs/src/main/plugin-metadata/entitlement-policy.yaml`**
   ```yaml
   ALL-UNNAMED:
     - load_native_libraries  # zstd-jni loads native .so; aircompressor uses Panama FFI
   ```

3. **`x-pack/plugin/esql-datasource-compression-libs/src/main/java/.../CompressionLibsPlugin.java`**
   - Minimal classloader anchor. Implements `DataSourcePlugin` with empty defaults.

4. **`x-pack/plugin/esql-datasource-compression-libs/src/main/resources/META-INF/services/org.elasticsearch.xpack.esql.datasources.spi.DataSourcePlugin`**

5. **`x-pack/plugin/esql-datasource-compression-libs/licenses/`**
   - `aircompressor-LICENSE.txt` (Apache 2.0)
   - `aircompressor-NOTICE.txt` (includes BSD 3-Clause for Snappy/Google)
   - `zstd-jni-LICENSE.txt` (copy from current esql-datasource-zstd/licenses/)
   - `zstd-jni-NOTICE.txt` (copy from current esql-datasource-zstd/licenses/)

**Files to modify — Format plugins (remove bundled compression libs):**

6. **`settings.gradle`** — add compression-libs project

7. **`x-pack/plugin/esql-datasource-orc/build.gradle`**
   - Add to `extendedPlugins`: `['x-pack-esql', 'esql-datasource-compression-libs']`
   - Remove: `resolutionStrategy.force "io.airlift:aircompressor:..."` (provided by parent)
   - Remove: `mapping from: /aircompressor-.*/` license mapping
   - Remove: `mapping from: /zstd-jni-.*/` license mapping
   - Remove: `mapping from: /brotli4j-.*/` license mapping (optional ORC dep, excluded)
   - Remove corresponding license files from `licenses/`
   - aircompressor and zstd-jni become `compileOnly` (resolved from parent CL at runtime)

8. **`x-pack/plugin/esql-datasource-iceberg/build.gradle`**
   - Add to `extendedPlugins`: `['x-pack-esql', 'esql-datasource-compression-libs']`
   - Remove: `resolutionStrategy.force "io.airlift:aircompressor:..."`
   - Remove: aircompressor license mappings

9. **`x-pack/plugin/esql-datasource-parquet/build.gradle`**
   - Add to `extendedPlugins`: `['x-pack-esql', 'esql-datasource-compression-libs']`
   - Provides aircompressor for Parquet's LZ4_RAW and zstd-jni for Parquet's Zstd column decompression

**Files to modify — Retrofit existing Zstd codec plugin:**

10. **`x-pack/plugin/esql-datasource-zstd/build.gradle`**
    - Change: `extendedPlugins = ['x-pack-esql']` → `['x-pack-esql', 'esql-datasource-compression-libs']`
    - Change: `implementation "com.github.luben:zstd-jni:..."` → `compileOnly "com.github.luben:zstd-jni:..."`
    - Remove: `mapping from: /zstd-jni-.*/` license mapping (no longer bundled)
    - Remove: `licenses/zstd-jni-LICENSE.txt` and `licenses/zstd-jni-NOTICE.txt` (now in compression-libs)
    - Keep: entitlement-policy.yaml `load_native_libraries` (zstd-jni still needs it even from parent CL)

**No changes needed for:**
- `esql-datasource-gzip` — JDK built-in, no external deps
- `esql-datasource-bzip2` — self-contained forked impl, no external deps

**Tests — verify nothing breaks after migration:**
```bash
# Compression-libs itself
./gradlew :x-pack:plugin:esql-datasource-compression-libs:check

# Format plugins (internal compression still works)
./gradlew :x-pack:plugin:esql-datasource-orc:test
./gradlew :x-pack:plugin:esql-datasource-iceberg:test
./gradlew :x-pack:plugin:esql-datasource-parquet:test

# Existing zstd codec (file-level .zst/.zstd still works)
./gradlew :x-pack:plugin:esql-datasource-zstd:test

# Full compressed format ITs
./gradlew :x-pack:plugin:esql-datasource-csv:qa:javaRestTest
./gradlew :x-pack:plugin:esql-datasource-ndjson:qa:javaRestTest
```

### Stage 1: `esql-datasource-lz4`

**Files to create:**

1. **`x-pack/plugin/esql-datasource-lz4/build.gradle`**
   ```gradle
   esplugin {
     name = 'esql-datasource-lz4'
     description = 'LZ4 decompression support for ESQL external data sources'
     classname = 'org.elasticsearch.xpack.esql.datasource.lz4.Lz4DataSourcePlugin'
     extendedPlugins = ['x-pack-esql']
   }
   dependencies {
     compileOnly project(path: xpackModule('esql'))
     compileOnly project(path: xpackModule('esql-core'))
     compileOnly project(path: xpackModule('core'))
     compileOnly project(':server')
     compileOnly project(':libs:lz4')  // LZ4FrameInputStream from server boot layer
     testImplementation project(':test:framework')
     testImplementation(testArtifact(project(xpackModule('core'))))
   }
   ```
   No bundled JARs. No license files. No entitlement policy (pure Java adapter).

2. **`Lz4DecompressionCodec.java`**
   ```java
   public class Lz4DecompressionCodec implements DecompressionCodec {
       private static final List<String> EXTENSIONS = List.of(".lz4");
       @Override public String name() { return "lz4"; }
       @Override public List<String> extensions() { return EXTENSIONS; }
       @Override public InputStream decompress(InputStream raw) throws IOException {
           return new LZ4FrameInputStream(raw);
       }
   }
   ```

3. **`Lz4DataSourcePlugin.java`** — standard boilerplate (copy from GzipDataSourcePlugin)

4. **`META-INF/services/org.elasticsearch.xpack.esql.datasources.spi.DataSourcePlugin`**

5. **`Lz4DecompressionCodecTests.java`**
   - `testNameAndExtensions()` — verify name="lz4", extensions=[".lz4"]
   - `testRoundTripDecompression()` — compress with `LZ4FrameOutputStream`, decompress, verify
   - `testInvalidLz4Throws()` — garbage bytes → IOException
   - `testEmptyInput()` — empty compressed stream → empty output
   - `testTruncatedStreamThrows()` — truncate a valid .lz4 mid-stream → clean IOException (not hang/IOOB)
   - `testLargeBlockSize()` — verify 4MB block size frames decompress correctly

**Modify:** `settings.gradle`

### Stage 2: `esql-datasource-snappy`

**Files to create:**

1. **`x-pack/plugin/esql-datasource-snappy/build.gradle`**
   ```gradle
   esplugin {
     name = 'esql-datasource-snappy'
     description = 'Snappy decompression support for ESQL external data sources'
     classname = 'org.elasticsearch.xpack.esql.datasource.snappy.SnappyDataSourcePlugin'
     extendedPlugins = ['x-pack-esql', 'esql-datasource-compression-libs']
   }
   dependencies {
     compileOnly project(path: xpackModule('esql'))
     compileOnly project(path: xpackModule('esql-core'))
     compileOnly project(path: xpackModule('core'))
     compileOnly project(':server')
     compileOnly "io.airlift:aircompressor:${versions.aircompressor}"  // from compression-libs parent
     testImplementation project(':test:framework')
     testImplementation(testArtifact(project(xpackModule('core'))))
     testImplementation "io.airlift:aircompressor:${versions.aircompressor}"  // need for test compression
   }
   ```
   No bundled JARs (aircompressor from parent CL). No license files. No entitlement policy.

2. **`SnappyDecompressionCodec.java`**
   ```java
   public class SnappyDecompressionCodec implements DecompressionCodec {
       private static final List<String> EXTENSIONS = List.of(".snappy");
       @Override public String name() { return "snappy"; }
       @Override public List<String> extensions() { return EXTENSIONS; }
       @Override public InputStream decompress(InputStream raw) throws IOException {
           return new SnappyFramedInputStream(raw, true);  // CRC-32C verification ON
       }
   }
   ```

3. **`SnappyDataSourcePlugin.java`** — standard boilerplate

4. **`META-INF/services/...`**

5. **`SnappyDecompressionCodecTests.java`**
   - `testNameAndExtensions()` — verify name="snappy", extensions=[".snappy"]
   - `testRoundTripDecompression()` — compress with `SnappyFramedOutputStream`, decompress, verify
   - `testInvalidSnappyThrows()` — garbage bytes → IOException
   - `testEmptyInput()` — empty → empty
   - `testTruncatedStreamThrows()` — truncated → clean IOException
   - `testChecksumVerification()` — corrupt a CRC in a valid framed stream → IOException

**Modify:** `settings.gradle`

### Stage 3: `esql-datasource-brotli`

**Files to create:**

1. **`x-pack/plugin/esql-datasource-brotli/build.gradle`**
   ```gradle
   esplugin {
     name = 'esql-datasource-brotli'
     description = 'Brotli decompression support for ESQL external data sources'
     classname = 'org.elasticsearch.xpack.esql.datasource.brotli.BrotliDataSourcePlugin'
     extendedPlugins = ['x-pack-esql']
   }
   dependencies {
     compileOnly project(path: xpackModule('esql'))
     compileOnly project(path: xpackModule('esql-core'))
     compileOnly project(path: xpackModule('core'))
     compileOnly project(':server')
     implementation "org.brotli:dec:${versions.brotli}"
     testImplementation project(':test:framework')
     testImplementation(testArtifact(project(xpackModule('core'))))
   }
   tasks.withType(org.elasticsearch.gradle.internal.AbstractDependenciesTask).configureEach {
     mapping from: /dec-.*/, to: 'brotli'
   }
   ```

2. **`BrotliDecompressionCodec.java`**
   ```java
   public class BrotliDecompressionCodec implements DecompressionCodec {
       private static final List<String> EXTENSIONS = List.of(".br");
       @Override public String name() { return "brotli"; }
       @Override public List<String> extensions() { return EXTENSIONS; }
       @Override public InputStream decompress(InputStream raw) throws IOException {
           return new BrotliInputStream(raw);
       }
   }
   ```

3. **`BrotliDataSourcePlugin.java`** — standard boilerplate

4. **`META-INF/services/...`**

5. **`x-pack/plugin/esql-datasource-brotli/licenses/`**
   - `dec-LICENSE.txt` — MIT license text with "Brotli Authors" copyright
   - `dec-NOTICE.txt` — standard notice

6. **`BrotliDecompressionCodecTests.java`**
   - `testNameAndExtensions()` — verify name="brotli", extensions=[".br"]
   - `testRoundTripDecompression()` — use pre-compressed `.br` fixture file (org.brotli:dec is decode-only)
   - `testInvalidBrotliThrows()` — garbage bytes → IOException
   - `testEmptyInput()` — empty compressed → empty
   - `testTruncatedStreamThrows()` — truncated → clean IOException

**Modify:**
- `settings.gradle`
- `build-tools-internal/version.properties` — add `brotli = 0.1.2`
- `gradle/verification-metadata.xml` — add `org.brotli:dec:0.1.2` checksums

### Stage 4: Integration Tests

**Test fixtures to create** (using CLI tools: `lz4`, `snzip`, `brotli`):
- `employees.csv.lz4`, `employees.ndjson.lz4`
- `employees.csv.snappy`, `employees.ndjson.snappy`
- `employees.csv.br`, `employees.ndjson.br`

**Extend existing compressed format spec ITs:**

1. **`CsvCompressedFormatSpecIT`** — add `.lz4`, `.snappy`, `.br` to the parameterized test matrix
2. **`NdJsonCompressedFormatSpecIT`** — same
3. Test against S3, HTTP, LOCAL backends

**Error handling tests (new):**
- `testTruncatedRemoteFile()` — simulate S3 returning a truncated compressed stream → verify clean error, not hang
- `testCorruptedMagicBytes()` — first bytes mangled → verify clean error message with filename context
- `testZeroBytFile()` — empty file with `.lz4`/`.snappy`/`.br` extension → verify clean error

### Stage 5: Documentation

Update public-facing ESQL documentation:
- List of supported compression formats: add `.lz4`, `.snappy`, `.br`
- Compound extension examples: `.csv.lz4`, `.ndjson.snappy`, `.csv.br`
- Note: `.sz` is NOT supported (use `.snappy` instead)

### Stage 6: Plugin Bundle Wiring

1. ES plugin bundle configuration — include all four new plugins in ESQL distribution
2. **Ensure `compression-libs` is always included when Parquet/ORC/Iceberg/Snappy are included** — this is an operational coupling that must be enforced
3. Verify `DataSourceModule` codec initialization picks up new codecs
4. Run full ESQL IT suite

---

## Dependency Impact

### Before (current state)

| Library | Bundled in | Copies |
|---|---|---|
| aircompressor 2.0.3 | ORC, Iceberg | 2 |
| zstd-jni 1.5.7-6 | ORC, esql-datasource-zstd | 2 |
| brotli4j 1.18.0 | ORC (transitive, optional) | 1 |
| lz4-java 1.10.1 | server (libs/lz4) | 1 |
| **Total duplication** | | **~3MB wasted** |

### After (with compression-libs)

| Module | Bundles | Removes from |
|---|---|---|
| `compression-libs` | aircompressor (~1MB) + zstd-jni (~800KB) | ORC (-aircompressor, -zstd-jni, -brotli4j), Iceberg (-aircompressor), zstd plugin (-zstd-jni) |
| `esql-datasource-lz4` | Nothing | N/A |
| `esql-datasource-snappy` | Nothing | N/A |
| `esql-datasource-brotli` | org.brotli:dec (~60KB) | N/A |

**Net change**: 1 copy of aircompressor + 1 copy of zstd-jni (in compression-libs) replaces 2+2=4 previous copies. **~2MB net savings** + 60KB for Brotli decoder = **~1.9MB savings total.**

---

## Key Reference Files

| Purpose | Path |
|---|---|
| DecompressionCodec SPI | `x-pack/plugin/esql/.../spi/DecompressionCodec.java` |
| Codec Registry | `x-pack/plugin/esql/.../DecompressionCodecRegistry.java` |
| CompressionDelegatingFormatReader | `x-pack/plugin/esql/.../CompressionDelegatingFormatReader.java` |
| DecompressingStorageObject | `x-pack/plugin/esql/.../DecompressingStorageObject.java` |
| DataSourceModule (codec init) | `x-pack/plugin/esql/.../DataSourceModule.java` (lines 70-76) |
| AsyncExternalSourceBuffer (backpressure) | `x-pack/plugin/esql/.../AsyncExternalSourceBuffer.java` |
| Template: gzip plugin (no deps) | `x-pack/plugin/esql-datasource-gzip/` |
| Template: zstd plugin (with ext dep) | `x-pack/plugin/esql-datasource-zstd/` |
| Template: ORC entitlements | `x-pack/plugin/esql-datasource-orc/src/main/plugin-metadata/entitlement-policy.yaml` |
| Template: zstd license files | `x-pack/plugin/esql-datasource-zstd/licenses/` |
| ES LZ4 library | `libs/lz4/build.gradle` |
| Version properties | `build-tools-internal/version.properties` |

---

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Parent CL not visible to children | Medium | Stage 0 tests catch immediately. 13 existing multi-parent examples in codebase prove pattern works. Fallback: revert to per-plugin bundling. |
| Parquet Snappy needs xerial snappy-java, not aircompressor | Medium | Parquet `Class.forName("...SnappyCodec")` → xerial. hadoop-client-runtime may shade it. Test Snappy Parquet files. If missing, add xerial to compression-libs. |
| zstd-jni native loading from parent CL | Medium | `load_native_libraries` entitlement on compression-libs. Verify zstd-jni native .so loads correctly when provided by parent. zstd plugin's own entitlement may also be needed — test both. |
| ORC's transitive brotli4j excluded | Low | ORC's `BrotliCodec` is optional. If Brotli ORC files are needed later, add brotli4j to compression-libs. |
| LZ4FrameInputStream 8MB buffer | Low | Capped at 8MB by spec. Backpressure limits downstream. |
| Brotli 16MB window | Low | Progressive allocation. Backpressure limits downstream. |
| Distribution coupling | Low | Enforced by `extendedPlugins` — ES refuses to load plugin if parent is missing. Fail-fast at node startup, no silent breakage. |
| `.snappy` extension ambiguity (raw vs framed) | Low | We use Snappy framing format. Hadoop raw `.snappy` fails with clear diagnostic error. |

---

## Verification Checklist

```bash
# Stage 0: shared compression-libs + retrofit
./gradlew :x-pack:plugin:esql-datasource-compression-libs:check
./gradlew :x-pack:plugin:esql-datasource-orc:test             # aircompressor + zstd-jni from parent
./gradlew :x-pack:plugin:esql-datasource-iceberg:test          # aircompressor from parent
./gradlew :x-pack:plugin:esql-datasource-parquet:test          # aircompressor + zstd-jni from parent
./gradlew :x-pack:plugin:esql-datasource-zstd:test             # zstd-jni from parent (RETROFITTED)

# Stage 0 IT: verify format-internal compression end-to-end
./gradlew :x-pack:plugin:esql-datasource-orc:qa:javaRestTest
./gradlew :x-pack:plugin:esql-datasource-parquet:qa:javaRestTest

# Stage 1-3: new codec plugins
./gradlew :x-pack:plugin:esql-datasource-lz4:check
./gradlew :x-pack:plugin:esql-datasource-snappy:check
./gradlew :x-pack:plugin:esql-datasource-brotli:check

# Stage 4: integration tests (compound extensions)
./gradlew :x-pack:plugin:esql-datasource-csv:qa:javaRestTest
./gradlew :x-pack:plugin:esql-datasource-ndjson:qa:javaRestTest

# Full ESQL suite
./gradlew :x-pack:plugin:esql:check
```
