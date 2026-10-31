# Port plan — ES|QL JDBC data source connector onto current `main` (`esql/ds-jdbc-oct-26`)

**Audience:** the implementation agent that will do the actual port. This document is a plan only — it
was produced without modifying any source. Follow it top to bottom; every claim below was verified
against the live branches (`origin/esql/ds-jdbc` and `origin/main`) at the time of writing.

> Authorship/commit convention for ALL commits on this work (carried over from the original branch):
> author **and** committer must be **Costin Leau** (`costin@users.noreply.github.com`), nothing about
> AI/Cursor. Commit with `--no-verify` and no signature, e.g.:
> ```
> GIT_COMMITTER_NAME="Costin Leau" GIT_COMMITTER_EMAIL="costin@users.noreply.github.com" \
>   git -c commit.gpgsign=false commit --no-verify --author="Costin Leau <costin@users.noreply.github.com>" -m "..."
> ```
> Do not amend/force-push the original `esql/ds-jdbc`. Work only on `esql/ds-jdbc-oct-26`.

---

## 1. Goal

Bring the JDBC external-data-source connector (currently on `origin/esql/ds-jdbc`, tip `3d904c2e479`)
up to date on top of current `origin/main`, on a **new** branch `esql/ds-jdbc-oct-26`, without
touching the original branch. Not just the code: also the build hooks, dependency versions, SPI
wiring, entitlements, and tests — and run the tests to confirm they pass.

## 2. State of the world (verified)

- **Divergence:** merge-base is `ecbbf98ecd3` (2026‑07‑19). `main` has advanced ~**4810 commits** to
  `a42903a9e04` (2026‑10‑30). The feature commits are `815360c7265` (connector) and `b01e05a8276`
  (pushdown), plus the CI/crash fixes `86726ca4f85`, `221557c5849`, `e3defb3a0e1`, `98cbc5bb531`,
  `7386f99d528`, `51b02e29912`, `c2b4092dfa5`, `0f20b8759c1`, `40c25bb876b`.
- **Feature footprint:** 105 files, ~19.7k insertions. Two parts:
  1. A **self-contained new module** `x-pack/plugin/esql-datasource-jdbc/` (~96 of the files: main
     code under package `org.elasticsearch.xpack.esql.datasource.jdbc`, unit tests, `internalClusterTest`
     ITs + `qa/` fixtures, `build.gradle`, `licenses/`, `plugin-metadata/entitlement-policy.yaml`,
     `META-INF/services/…DataSourcePlugin`, SQL/CSV fixtures).
  2. **9 edits to the esql plugin** (`x-pack/plugin/esql/`, NOT the `esql-core` module) — the pushdown
     wiring + 2 crash fixes — see §5–§6.
  Plus: root `build.gradle` (+1: adds `:x-pack:plugin:esql-datasource-jdbc` to the `checkBuildLogic`
  project list — verify this survives the merge), `docs/changelog/154127.yaml` (a NEW file, present only
  on the feature branch), `gradle/verification-metadata.xml` (+60), and a 7-line null-guard in the
  **grpc** module's `FlightConnector.java`.
- **Module auto-registration:** x-pack plugins are discovered by `addSubProjects('', …'x-pack')` in
  `settings.gradle`. **No `settings.gradle` edit is needed** — dropping the module directory in is enough.
- **The SPI the module compiles against still exists.** `org.elasticsearch.xpack.esql.datasources.spi`
  grew from ~78 to ~108 files on `main`, but every type the module imports is still present
  (`Connector`, `ConnectorFactory`, `DataSourcePlugin`, `FilterPushdownSupport`, `QueryRequest`,
  `ResultCursor`, `SourceMetadata`, `SimpleSourceMetadata`, `StorageObject`, `StoragePath`,
  `StorageProvider`, `StorageProviderFactory`, `Split`, `ExternalSplit`, `ConfigKeyValidator`, plus
  `datasources.ExternalSourceSettings` and `datasources.StorageIterator`). Most drift is additive (new
  default methods / statics), **but there is at least one deterministic compile break** (`StorageObject`
  gained abstract methods the module's `JdbcStorageObject` does not implement) — see §7.
- **Test infrastructure the ITs extend still exists on `main`:** `AbstractExternalDataSourceIT`,
  `AbstractEsqlIntegTestCase` (esql `internalClusterTest`), the sibling `esql-datasource-http` module
  with its own `entitlement-policy.yaml` + `DataSourcePlugin` service file, and the
  `esql:qa:testFixtures` / `encryption:spi` projects.

## 3. Recommended strategy — **merge `main` into a copy of the feature branch**

A dry-run `git merge origin/main` onto `origin/esql/ds-jdbc` produced **only 5 conflicts** (§5). This
is the lowest-risk path: git carries the ~96 new module files and the 4 clean core edits automatically,
and you hand-resolve 5 files. Preferred because it preserves the feature history and minimizes manual
re-application.

```
git fetch origin
git switch -c esql/ds-jdbc-oct-26 origin/esql/ds-jdbc
git merge origin/main        # expect the 5 conflicts in §5
# …resolve §5, verify §6, re-apply §8, run §11…
```

**Alternative (prefer if a clean PR history matters):** branch from `origin/main`, bring the module
verbatim (`git checkout origin/esql/ds-jdbc -- x-pack/plugin/esql-datasource-jdbc`) and re-apply the 9
esql-plugin edits by hand from the known diffs (§5–§6). More manual work, but yields **linear history**
and does not drag `esql/ds-jdbc`'s long chain of "Merge branch 'main'…" commits (see
`git log origin/esql/ds-jdbc --oneline --merges`) or a single ~4810-commit merge commit onto the new
branch — the latter also muddies `git bisect`/`blame` on the port branch. For a one-shot October port PR
this alternative is quite reasonable **if** the agent documents the nine esql edits explicitly (they are
easy to miss with a verbatim module copy: `QueryRequest`, `OperatorFactoryRegistry`, `DataSourceModule`,
`ExternalOptimizerContext`, `AsyncConnectorSourceOperatorFactory`, `PlannerUtils`, `ComputeService`,
`DataNodeComputeHandler`, `PushFiltersToSource`). Do **not** rebase the feature across `main` —
`esql/ds-jdbc` has many merge commits and a rebase would re-resolve conflicts repeatedly.
Note: the "merge re-runs CI on unrelated changes" worry is mostly a red herring — Gradle runs
project-scoped tasks; the real cost is full precommit/IT scope on the new module + esql, regardless of
strategy.

> Environment note: the workspace may boot with a **detached HEAD on an unrelated `esql-fusion` commit**
> and without `esql/ds-jdbc` checked out locally. Start from a clean `git fetch origin` and create the
> branch explicitly as above.

## 4. Branch setup / handoff

- Base `esql/ds-jdbc-oct-26` on `origin/esql/ds-jdbc` (per §3).
- This plan file (`DS_JDBC_OCT26_PORT_PLAN.md`) is committed on the branch as the first commit so the
  implementation agent has it in-tree. Remove it (or move under `docs/`) before the PR if undesired.

## 5. The 5 merge conflicts — resolution guide

All line references are to the feature side (`HEAD`/`esql/ds-jdbc`) vs `origin/main`.

1. **`gradle/verification-metadata.xml`** — mechanical. Keep `main`'s content, then **regenerate** the
   component entries for the module's new jars rather than hand-merging (see §10).

2. **`x-pack/plugin/esql/src/main/java/.../planner/PlannerUtils.java`** —
   `main` independently changed `localPlan`: it **added a trailing `DatasetConfigResolver` parameter**
   (and reworded javadoc about per-node split stamping). The feature **added a `Map<String,
   ExternalSourceFactory> sourceFactories` parameter**. Resolution: keep `main`'s new signature/behavior
   and **re-thread `sourceFactories` into both `localPlan` overloads** alongside `main`'s
   `DatasetConfigResolver`. Pass `sourceFactories` into `new ExternalOptimizerContext(formatReaderRegistry,
   sourceFactories)`. Keep the feature's javadoc paragraph describing `sourceFactories`.
   **Also note (not in the conflict markers, but in the same file):** `main` changed the nearby
   `LocalPlanResult` record to **3 fields** `(PhysicalPlan, String, boolean approximationApplied)` and
   wires `physicalOptimizer.approximationApplied()`; the feature branch still has the 2-field record. The
   feature never touched `LocalPlanResult`, so git keeps `main`'s 3-field version automatically — **do not
   clobber it** while resolving the adjacent `localPlan` conflict, and keep all `approximationApplied`
   plumbing (`ComputeService` on `main` reads it on the non-external path).

3. **`x-pack/plugin/esql/src/main/java/.../plugin/ComputeService.java`** —
   import conflict (`ExternalSourceFactory` from the feature vs `ExternalPlanningIo` from `main`): keep
   **both** imports. `main` added memory-charging logic (`chargeResolvedExternalSources`, `chargePhase2`,
   `ExternalPlanningReservation`) near where the feature added the `sourceFactories()` accessor. Keep
   **both**: retain `main`'s new methods and re-add
   `Map<String, ExternalSourceFactory> sourceFactories() { return operatorFactoryRegistry == null ? null :
   operatorFactoryRegistry.sourceFactories(); }`, and pass `sourceFactories()` into the `localPlan` call
   (which now also takes `main`'s `DatasetConfigResolver`).

4. **`x-pack/plugin/esql/src/main/java/.../plugin/DataNodeComputeHandler.java`** —
   `main` wrapped the `localPlan` call in a `try/catch` that rebinds datasets to the node's own state
   (`DatasetConfigResolver.forProduction(projectResolver.getProjectMetadata(state))`) and handles
   "cluster state behind coordinator" refusals. The feature only added `computeService.sourceFactories()`
   as an argument. Resolution: keep **all** of `main`'s try/catch + rebind + refusal logic, and insert
   `computeService.sourceFactories()` into `main`'s `localPlan` argument list (which now also passes
   `DatasetConfigResolver`). Argument order must match the resolved §2 signature.

5. **`x-pack/plugin/esql/src/main/java/.../optimizer/rules/physical/local/PushFiltersToSource.java`** —
   `main` refactored pushdown resolution: it introduced `resolveFormatReader(sourceType, ctx)` returning a
   `FormatReader` and reads both `filterPushdownSupport()` **and the new `dropsRowsUnderPushedFilter()`**
   off it (new import `ErrorPolicy` on `main`). The feature replaced resolution with a **by-source-type**
   lookup that first consults the connector `sourceFactories` map
   (`factory instanceof ConnectorFactory → factory.filterPushdownSupport()`) and falls back to the format
   registry. Resolution: **merge both intents.** Precise structure to aim for (verified against both
   branches):
   1. **connector path first** — look up `sourceType` in `external().sourceFactories()`; if it is a
      `ConnectorFactory`, use its `filterPushdownSupport()`. The connector path has **no `FormatReader`**,
      so `main`'s `dropsRowsUnderPushedFilter()`/`ErrorPolicy` guard does **not** apply here (this matches
      the feature's prior JDBC behavior).
   2. **else file path** — resolve a `FormatReader` via **`main`'s `resolveFormatReader(sourceType, ctx)`**;
      do **NOT** reintroduce the feature's old `resolveFormatName(config, sourcePath)` — `main` deliberately
      moved to resolving by `sourceType`. Then read `filterPushdownSupport()` AND apply `main`'s
      `dropsRowsUnderPushedFilter()`/`ErrorPolicy` guard **only when that `FormatReader` is non-null**.
   Keep the feature's imports (`ConnectorFactory`, `ExternalSourceFactory`, `ExternalOptimizerContext`)
   **and** `main`'s (`ErrorPolicy`). This is the most delicate conflict — re-read `main`'s refactored
   method fully before editing, and cross-check against the connector-pushdown tests
   (`JdbcPushdownWiringTests`, `CountPushdownWiringTests`, and esql's own `PushFiltersToSource` tests).

## 6. Core edits that **auto-merge cleanly** — verify they survived

These feature edits did not textually conflict, but **textually clean ≠ semantically verified** — confirm
each is present and intact post-merge (a bad auto-merge, or a careless re-paste, could drop or duplicate
them), then rely on the compile gates in §11:

- **`QueryRequest.java`** — the `pushedFilter` record component + the two convenience constructors +
  `withBlockFactory` threading `pushedFilter`. (Verified present in the dry-run merge.)
- **`DataSourceModule.java`** — the `filterPushdownSupport()` override on the lazy `LazyConnectorFactory`
  bridge (delegates to `resolveDelegate().filterPushdownSupport()`). Note `main`'s `DataSourceModule` now
  has a **second** bridge returning `TableCatalog`; the override belongs on the `ConnectorFactory` bridge
  only. (Verified present.)
- **`OperatorFactoryRegistry.java`** — threading `context.pushedFilter()` + `FormatReader.NO_LIMIT` into
  the `QueryRequest` constructor, and the `FormatReader` import.
- **`AsyncConnectorSourceOperatorFactory.java`** — `closeConnectorQuietly()` must `catch (Exception)`
  (not just `IOException`) and the `import java.io.IOException` removal. NB `main` changed this class
  substantially elsewhere (e.g. `Limiter`/`observedLimiter` plumbing), so the auto-merge touched more than
  this method. `main` still catches only `IOException` in `closeConnectorQuietly`, so the feature's change
  must win the merge — **confirm** it did; if the merge reverted it to `IOException`, re-apply the
  `catch (Exception)` version. This was the fix for the node-crash on connector close.
- **`ExternalOptimizerContext.java`** — the `sourceFactories` map field + the single-arg convenience
  constructor + `NONE = new ExternalOptimizerContext(null, null)`.
- **Root `build.gradle`** — the feature adds `':x-pack:plugin:esql-datasource-jdbc'` to the
  `checkBuildLogic` project list. Confirm this entry is present after the merge (it is a feature-only
  one-line add, so it should auto-merge, but verify — a missing entry silently skips build-logic checks for
  the module).
- **`FlightConnector.java` (grpc)** — see §8; in the dry-run the null-guard auto-merged in. **Assert it is
  present** rather than blindly re-adding it (re-adding without reading risks duplicate logic).

## 7. SPI drift / compile risks (module side)

The ~96 module files are new (no textual conflict) but compile against the drifted SPI. The drift is NOT
uniformly "additive/low-risk" — there is at least one **deterministic compile break** (verified):

- **CRITICAL — `JdbcStorageObject` will not compile.** `main`'s `StorageObject` added two **abstract**
  methods with **no default**: `StorageIdentity storageIdentity();` and
  `void attachMetrics(ExternalSourceMetrics metrics, String scheme);`. The feature's private
  `JdbcStorageObject` (inner class in `JdbcStorageProvider.java`) implements only `newStream`/`length`/
  `lastModified`/`exists`/`path` and will fail to compile. **Fix proactively (do not wait for gate 1):**
  mirror the sibling `FlightStorageObject` on `main` — add a private `StorageIdentity` singleton returned
  from `storageIdentity()` and a no-op (or metrics-forwarding) `attachMetrics(...)`. Check whether
  `StorageProvider`/`StorageProviderFactory` (also grew) gained similarly-required methods and apply the
  same treatment to `JdbcStorageProvider`.
- **Interfaces the module implements that gained only default/static members** (compile-safe, but mind
  behavior):
  - `ConnectorFactory` — new `default testConnection(...)`.
  - `DataSourcePlugin` — new `default testConnectionSchemes()` and `default datasourceSecretSettingNames()`.
  - `ConfigKeyValidator` — new static helpers (`withoutFrameworkKeys`, `isFrameworkKey`).
  - **PORT REQUIREMENT (not a follow-up):** `main`'s `DataSourcePlugin` filters audit bodies by
    `datasourceSecretSettingNames()` at startup. `JdbcDataSourcePlugin` does **not** override it, so JDBC
    secret settings (password/credentials) risk leaking into audit output. Override
    `datasourceSecretSettingNames()` (and likely `testConnectionSchemes()`) with the JDBC secret keys.
    Unit/IT coverage may not catch REST audit redaction, so verify deliberately.
- **Types with deletions** — `StoragePath` (−6), `StorageProviderFactory` (−1). `ExternalSourceSettings`
  changed a lot (−59/+468) but JDBC only touches `MAX_CONCURRENT_REQUESTS.get()` in `JdbcRuntimeConfig`,
  so impact is likely nil — still, **let the compiler confirm** (§11 gate 1) and adapt any call sites.
- **Test-infra API drift** — the ITs extend `main`'s `AbstractExternalDataSourceIT` /
  `AbstractEsqlIntegTestCase` and use `TestDataSourcePlugin` / `HttpDataSourcePlugin`. These evolved on
  `main`; the JDBC ITs (`AbstractJdbcDatabaseIT`, `JdbcDatasetIT`, `H2JdbcIT`, `PostgresJdbcIT`, the `qa/`
  fixtures) may need signature/override adjustments. Compile the `internalClusterTest` source set
  explicitly to surface these.

## 8. grpc module — re-apply the null-guard

`main`'s `x-pack/plugin/esql-datasource-grpc/.../FlightConnector.java` **does not** have the null-client
guard, but it is a feature-only change so the merge auto-combined it in the dry-run. **Assert** the 7-line
guard is present at the top of `asCloseable(FlightClient client)`: `if (client == null) return;` (no-op
close when a client was never established); only re-apply if the merge somehow dropped it. This prevents
the NPE on the query-failure teardown path. Confirm the earlier grpc regression root cause (the `splitProvider()`
override on the lazy bridge) is **absent** — `main`'s `DataSourceModule` has no such override, so nothing
to remove; just ensure you do not reintroduce it.

## 9. Build hooks & versions checklist (`esql-datasource-jdbc/build.gradle`)

Bring the module's `build.gradle` verbatim, then re-validate against `main`:
- Plugins: `elasticsearch.internal-es-plugin`, `internal-cluster-test`, `publish`, `esql-release`
  (+ `subprojects { apply 'elasticsearch.esql-release' }`).
- `esplugin { classname = '…jdbc.JdbcDataSourcePlugin'; extendedPlugins = ['x-pack-esql'] }`.
- Local `versions << ['hadoop': '3.4.3']`.
- Dependencies: `com.zaxxer:HikariCP:5.1.0` **with `exclude group:'org.slf4j', module:'slf4j-api'`** (the
  jar-hell fix) + `compileOnly "org.slf4j:slf4j-api:${versions.slf4j}"`; test/IT-only drivers
  `com.h2database:h2:2.2.224`, `com.amazon.redshift:redshift-jdbc42:2.1.0.30`,
  `org.postgresql:postgresql:42.7.3`; `org.testcontainers:*:1.20.4` (pinned, **not** `${versions.testcontainer}`
  which is 2.0.2 on main); `parquet-hadoop-bundle:${versions.parquet}`, `hadoop-common` /
  `hadoop-mapreduce-client-core` `${versions.hadoop}` (transitive=false); test deps on
  `xpackModule('esql')`+`esql-core`+`core` testArtifacts, `esql` `internalClusterTest` artifact,
  `esql:qa:testFixtures`, `esql-datasource-http`, `encryption:spi`.
- `thirdPartyAudit { ignoreMissingClasses(...) }` for HikariCP's optional metrics/Hibernate/javassist refs.
- `internalClusterTest` task: system properties
  `es.esql_external_datasources_local_feature_flag_enabled=true`,
  `es.project_encryption_key_feature_flag_enabled=true`,
  `es.esql_external_datasources_jdbc_feature_flag_enabled=true`, `api.version=1.44`,
  `jvmArgs '-XX:-OmitStackTraceInFastThrow'`, and the `PostgresPerformanceIT` opt-in `doFirst` filter.
- `internalClusterTestTestingConventions { baseClass 'org.elasticsearch.test.ESTestCase' }`.

**Re-validate on `main`:** the version catalog is **`build-tools-internal/version.properties`** (there is
**no** `gradle/versions.toml` in this repo). Confirm it still defines `slf4j` (2.0.18), `parquet` (1.18.1),
`testcontainer` (2.0.2) — all present at time of writing; `hadoop` is pinned locally in the module
(`3.4.3`), matching esql's own IT classpath. Confirm the `xpackModule(...)` helper and the
`internal-es-plugin`/`esql-release` plugin ids are unchanged. If the slf4j version moved, revisit the
jar-hell exclusion rationale (x-pack-core ships slf4j-api 2.0.x; HikariCP's transitive 1.7.x must stay
excluded).

## 10. `gradle/verification-metadata.xml`

Do not hand-merge the 60 added lines. Take `main`'s file at the conflict, then regenerate for the new
dependencies:
```
./gradlew --write-verification-metadata sha256 \
  :x-pack:plugin:esql-datasource-jdbc:dependencies \
  :x-pack:plugin:esql-datasource-jdbc:resolveAllDependencies
```
(or the repo's standard regeneration task). Review the diff so only the JDBC module's jars — HikariCP,
h2, redshift-jdbc42, postgresql, testcontainers 1.20.4 and their transitives — are added.

## 11. Verification gates — run these, in order, and fix failures before advancing

Release-build runs need `-Dbuild.snapshot=false -Dlicense.key=x-pack/license-tools/src/test/resources/public.key`.

1. **Compile (fastest breakage signal) — include TEST sources of esql, since the `localPlan` arity change
   breaks esql's own tests (e.g. `CountPushdownWiringTests`) before gate 4:**
   - `./gradlew :x-pack:plugin:esql:compileJava :x-pack:plugin:esql:compileTestJava`
   - `./gradlew :x-pack:plugin:esql-datasource-jdbc:compileJava :x-pack:plugin:esql-datasource-jdbc:compileTestJava :x-pack:plugin:esql-datasource-jdbc:compileInternalClusterTestJava`
   - `./gradlew :x-pack:plugin:esql-datasource-grpc:compileJava`
2. **Precommit (jar-hell, thirdPartyAudit, forbiddenApis, licenseHeaders, verification-metadata):**
   - `./gradlew :x-pack:plugin:esql-datasource-jdbc:precommit`
   - This is where the slf4j jar-hell fix and the verification-metadata regen are validated.
3. **JDBC unit tests — snapshot and release:**
   - `./gradlew :x-pack:plugin:esql-datasource-jdbc:test`
   - `./gradlew :x-pack:plugin:esql-datasource-jdbc:test -Dbuild.snapshot=false -Dlicense.key=…public.key`
     (the `assumeJdbcFlagEnabled()` guards must make flag-gated tests **skip**, not fail, in release).
4. **esql core tests affected by the pushdown edits:**
   - `./gradlew :x-pack:plugin:esql:test --tests '*PushFiltersToSource*' --tests '*CountPushdownWiring*' --tests '*QueryRequest*'`
     then a broader `:x-pack:plugin:esql:test` if those pass.
5. **JDBC internalClusterTest (H2 / Docker-less first):**
   - `./gradlew :x-pack:plugin:esql-datasource-jdbc:internalClusterTest --tests '*H2JdbcIT' --tests '*JdbcDatasetIT' --tests '*JdbcRestResponseContractIT' --tests '*JdbcSchemaCacheBypassIT'`
   - Postgres ITs (`PostgresJdbcIT`, parity ITs) require Docker; run if the runner has Docker, else note
     they are skipped by `assumeDockerIsAvailable()`.
6. **grpc regression check (the previously-failing suite):**
   - `./gradlew :x-pack:plugin:esql-datasource-grpc:javaRestTest --tests '*FlightFormatSpecIT'` — confirm
     no "Connection is closed" / NPE after re-adding the null-guard. **Note:** that module's `javaRestTest`
     is `enabled = buildParams.snapshotBuild`, so run this gate as a **snapshot** build (the release-build
     flags in the header do NOT apply here — it simply won't run under `-Dbuild.snapshot=false`).
7. **Pre-existing upstream flaky to be aware of (NOT ours):** `MatchFunctionIT` has an intermittent
   circuit-breaker leak ("Request breaker not reset to 0", ~1048 bytes) that reproduces on pure `main`
   too. Do not attribute it to this port; if it surfaces in CI, it is the runtime-MATCH path, not JDBC.

## 12. Known risks & watch items

- **`PushFiltersToSource` (conflict 5)** is the highest-risk merge — reconcile `main`'s
  `dropsRowsUnderPushedFilter()` file path with the feature's connector-factory path; validate with the
  pushdown wiring tests.
- **`localPlan` signature** now carries **two** independently-added trailing params
  (`sourceFactories` from the feature, `DatasetConfigResolver` from `main`). Fix all overloads and both
  call sites (`ComputeService`, `DataNodeComputeHandler`) consistently or you get a cascade of
  arity/type errors.
- **`StorageObject` abstract-method break (CRITICAL, §7)** — `JdbcStorageObject` needs `storageIdentity()`
  + `attachMetrics()`; fix proactively, do not rely on gate 1 to "discover" it.
- **SPI deletions** (`StoragePath`/`StorageProviderFactory`/`ExternalSourceSettings`) may break module call
  sites — rely on gate 1.
- **New SPI default methods** (`datasourceSecretSettingNames`, `testConnectionSchemes`) — override for
  correct JDBC secret/test-connection behavior (secret redaction is user-visible, not just cosmetic);
  verify via ITs/REST audit, not just compile.
- **testcontainers 1.20.4 vs main 2.0.2** — the module intentionally pins 1.20.4 and uses
  `:test:fixtures:testcontainer-utils` (which api-exports 2.0.2). Run the Postgres ITs at least once and
  watch for API/binary incompatibility between the utils and the pinned 1.20.4.
- **Test-infra drift** in `AbstractExternalDataSourceIT` et al. may require IT adjustments.
- **Entitlements/service-loader** mechanism is unchanged — keep `plugin-metadata/entitlement-policy.yaml`
  (`outbound_network`, `manage_threads`, `create_class_loader`) and the `META-INF/services/
  …datasources.spi.DataSourcePlugin` file (`…jdbc.JdbcDataSourcePlugin`).
- **Changelog:** `docs/changelog/154127.yaml` is a NEW file (feature-branch only); it carries over as-is.
  Just confirm its fields still match `main`'s `ChangelogEntry` schema.
- **Feature flag** `esql_external_datasources_jdbc` (snapshot-on/release-off) — unchanged; the ITs enable
  it explicitly via the build.gradle system property.

## 13. Definition of done

- `esql/ds-jdbc-oct-26` merges current `main` with all 5 conflicts resolved, the clean core edits + root
  `build.gradle` `checkBuildLogic` entry + grpc null-guard confirmed, and the `JdbcStorageObject`
  `storageIdentity()`/`attachMetrics()` compile fix applied.
- `datasourceSecretSettingNames()` overridden (secret redaction) and verified.
- Gates 1–4 green; gate 5 green for the H2/Docker-less ITs (Postgres ITs green where Docker is available);
  gate 6 green as a snapshot build.
- Precommit green (jar-hell, thirdPartyAudit, verification-metadata regenerated).
- Commits authored/committed as Costin Leau, unsigned, `--no-verify`.
- Original `esql/ds-jdbc` untouched.
