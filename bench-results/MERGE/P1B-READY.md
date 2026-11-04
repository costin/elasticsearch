NOT READY: F2 (isolate WHERE+EVAL into commit 4), F3 (benchmark prune + test consolidation) and the F4 test-comment plan-tag scrub (157 refs remain, gate != 0) are not done; the landed review fixes (F1, F5, F6, F7) plus the F4 production-code hit are complete and every gate run below is green on base `174890c2e3a`.

# P1B fix-up readiness report

- **Code branch**: `esql/fix/fusion-main-bf28`, tip `d89fef927d2` (base `174890c2e3a` + 4 commits).
- **Evidence branch**: `esql/fix/compute-codegen-followup-evidence` (this file + `rebase.md` P1B section + raw logs under `bench-results/MERGE/p1b/`).
- **Base**: `174890c2e3a`. `origin/main` is `a42903a9e04`, an **ancestor** of the base (base is **151 commits ahead**), so the base is already the newest reachable `main`; the F8 rebase is a no-op and there is no upstream drift (see `rebase.md` → "P1B fix-up").
- Commits edited in place via `git commit --fixup` + `git rebase -i --autosquash` (non-interactive `GIT_SEQUENCE_EDITOR=:`). Result is exactly **4 commits**, author **Costin Leau**, no AI/Co-Authored-By trailers. No PR created.

## F1–F8 status

| Item | Commit(s) | Status | Notes |
|---|---|---|---|
| **F1** WHERE+EVAL scoring guard + fusion gate | 4 (`d89fef927d2`) | **done** | Extracted `filterNeedsScoring(FilterExec, LocalExecutionPlannerContext)` holding `main`'s exact unscored-path condition; used in both `planFilter` and the hook. Hook now enters only when `FusionSettings.isEnabled() && filterNeedsScoring(...) == false`, so with fusion off planning follows `main` exactly. Tests: `testCoordinatorScoringFilterThenSingleEvalIsNotFused` (coordinator-side scoring filter + 1-field eval → Filter+Score+Eval, not FilterEval) and `testFusionOffWhereThenSingleEvalPlansFilterThenEval` (fusion off → Filter→Eval, not FilterEval). Coordinator-side scoring IS reachable in the planner unit test (empty shard contexts + runtime-search condition), so the unit test covers F1(c); no CsvIT-side scoring case was added. |
| **F2** move all WHERE+EVAL code into commit 4 | — | **NOT done** | The WHERE+EVAL operator/emitter/planner code (`FilterEvalOperator`, `FilterEvalEmitter`, `FusionPlanner.maybeFuseFilterEval`, `FusedClassCache.Path.FILTER_EVAL_BLOCK`, filter-eval Stitcher/telemetry/coverage, `EvalMapper.toFilterEvalEvaluator`, their tests) still lives in commit 3, not commit 4. `git grep -i 'filterEval\|filter_eval\|FILTER_EVAL'` on commit 3 still has hits. Only the `planEval` hook + its tests are in commit 4. This is a large cross-commit code move and was not attempted to keep each commit compiling; dropping commit 4 today removes only the hook, not the whole feature. |
| **F3** benchmark prune + test consolidation | — | **NOT done** | Benchmarks: analyzed but not pruned — the keep set (`FusionBenchmark`, `FusionStrategyBenchmark`, `FusionStrategy`) transitively depends on `PocShape`, `SlowPathFusionBenchmark` and `FusionBenchmarkGuard`, so deleting those three requires first inlining the referenced cells into the kept classes (not a plain delete); `FusionOperatorBenchmark` is also benchmark-only. `planMiss` exists (`FusionStrategyBenchmark`), the cached-plan counterpart is `planCached`/`planCachedThenGet` (no method literally named `planHit`). Test consolidation (13.9k test lines in commit 3 → ~7k) not attempted. All benchmark files still compile (`benchmarks:precommit` green). |
| **F4** remove internal plan tags from added lines | 1 (`f79e2bfddb4`) partial | **partial** | The only **production-code** plan-tag reference (the iter-22 comment in `Pow.java`) was reworded to behaviour. The dump-list property is `esql.fusion.dump.list` (never contained "E1"); its harness wording still says "E1". **157** plan-tag references remain in **test** comments/messages (0 in `src/main`). The F4 gate prints **157**, not 0 (output below). |
| **F5** compile-time inlinable-kernel contract | 1 (`f79e2bfddb4`) | **done** | New `InlinableKernelValidator` (javac `com.sun.source` Tree API, isolated behind a narrowly-scoped `@SuppressForbidden` + `requires jdk.compiler` in `module-info`) rejects any `@Fusable` kernel whose body has >1 own `return` (lambda/anon-class returns excluded) with a clear error naming `Class#method`. Wired into `EvaluatorProcessor` for both `@Evaluator+@Fusable` and `@ConvertEvaluator+@Fusable`. Negative test `testFusableKernelWithMultipleReturnsIsCompileError` + counter-case `testFusableKernelLambdaReturnDoesNotCountAgainstContract`, both driving the **real** processor. |
| **F6** table-driven allowNonFinite math tests | 3 (`dab192af7fa`) | **done** | `testDoubleMathFunctionsAllowNonFiniteMatchUnfused`: table over {Sqrt, Log(1&2-arg), Log10, Pow, Acos, Asin, Acosh, Atanh, Cosh, Sinh} × {allowNonFinite false,true} × out-of-domain inputs, each nested one level under a `Mul` (depth-2, so it fuses). Differential asserts fused == unfused values/nulls/warnings; strict mode asserts the per-function domain-error warning, lenient mode asserts no warning. |
| **F7.1** cache-stats double count | 3 (`dab192af7fa`) | **done** | `containsShape`'s presence peek no longer inflates `Cache#stats()`: its hit/miss is tracked in separate `LongAdder`s and subtracted out of `hits()`/`misses()`/`stats()`. Test `testCachedPlanAddsExactlyOneHit` asserts one reused cached plan records exactly one hit. |
| **F7.2** ToEvaluator dedup | 3 (`dab192af7fa`) | **done** | `EvalMapper.Builder.buildUnfused` and the public `toEvaluatorContext` now share one anonymous `ToEvaluator` via a new `toEvaluatorContext(..., Function<Expression, Factory> childFactory)` overload; `buildUnfused` passes `this::unfused`, the memoized path passes the fusing `toEvaluator`. |
| **F7.3** breaker accounting | 3 (`dab192af7fa`) | **done** | Chose the **documented-bound** option (delta accounting is not implementable: no evaluator mutates `baseRamBytesUsed()` post-construction, so the adaptive-switch delta is 0 and there is no swap callback to re-reserve through; reserving a delta would unbalance `close()`). `EvalOperator` and `FilterEvalOperator` `reservedBytes` javadoc now states growth is bounded by the fused evaluator's shallow instance size — a fixed JVM object-layout constant on the order of a few hundred bytes, independent of page size, row count or cardinality. |
| **F8** rebase + gates + logs | — | **done** (base unchanged) | Base stays `174890c2e3a` (see above). Drift re-check appended to `rebase.md`. Gates run at the final tip and raw logs saved under `bench-results/MERGE/p1b/`; per-commit compiles for commits 1 and 3 and the tip; dump corpus regenerated and diffed (0 changed). Not run: full `:esql:test` / `:compute:test` headline counts (compute:test ran green *inside* the fusion gate; full esql:test is ~30 min and was skipped under budget) and the JMH smoke of `FusionBenchmark` (benchmarks compile via `benchmarks:precommit`, but no JMH fork was executed). |

## The 4 commits (tip-ordered, base `174890c2e3a`)

| # | Commit | Subject | main +lines | test +lines | bench +lines |
|---|---|---|---|---|---|
| 1 | `f79e2bfddb4` | [ESQL] Fusion metadata: @Fusable and descriptors | 1472 | 665 | 0 |
| 2 | `889fb67de49` | [ESQL] Fusion stitcher and bytecode emitters | 5581 | 6779 | 0 |
| 3 | `dab192af7fa` | [ESQL] Fusion planner for EVAL expressions | 9772 | 13954 | 2678 |
| 4 | `d89fef927d2` | [ESQL] Fuse WHERE combined with a following EVAL | 48 | 1067 | 0 |

(`+lines` = added lines in that commit, `git show --numstat`, split by `*/src/main/*`, `*/src/test/*`, `benchmarks/*`.)

## Gates (final tip `d89fef927d2`, base `174890c2e3a`)

| Gate | Command | Result | Log |
|---|---|---|---|
| Fusion gate | `./gradlew :…:compute:gen:test :…:compute:gen:spotlessJavaCheck :…:compute:test :…:esql:test --tests "*fusion*" :…:compute:spotlessJavaCheck :…:esql:spotlessJavaCheck` | **BUILD SUCCESSFUL 4m51s, exit 0**. esql `*fusion*` = 259 tests / 0 fail / 29 suites; gen:test = 18/0; compute:test green in-gate | `p1b/fusion-gate.log` |
| Four-project precommit | `./gradlew :…:esql:precommit :…:compute:precommit :…:compute:gen:precommit :benchmarks:precommit` | **BUILD SUCCESSFUL, exit 0** | `p1b/precommit.log` |
| Dump corpus (E1 → P1.txt) | `:…:compute:test --tests FusionClassDumpTests -Dtests.jvm.argline=-Desql.fusion.dump.list=…` | **43/43 byte-identical**, md5 `ac36a0964b7c59f2969f08bdc2907a2b` == `e1/P1.txt`, 0 changed | `p1b/dump.log`, `p1b/corpus-P1B.txt` |
| Per-commit compile — commit 1 | `:…:compute:gen:compileTestJava :…:esql:compileTestJava :…:compute:compileTestJava` @ commit 1 | **BUILD SUCCESSFUL, exit 0** | `p1b/c1-compile.log` |
| Per-commit compile — commit 3 | same @ commit 3 | **BUILD SUCCESSFUL, exit 0** | `p1b/c3-compile.log` |
| Per-commit compile — commit 4 (tip) | four-project precommit compiles the full tree | **BUILD SUCCESSFUL, exit 0** | `p1b/precommit.log` |
| CsvIT verify-on (fusion on, `-ea`, `min_sightings=1`) | `:…:esql:internalClusterTest --tests "org.elasticsearch.xpack.esql.CsvIT"` | **10889 tests / 0 fail / 0 err / 184 skipped, exit 0** | `p1b/csvit-on.log` |
| CsvIT fusion-off | same + `-Dtests.jvm.argline=-Desql.fusion.enabled=false` | **10889 tests / 0 fail / 0 err / 184 skipped, exit 0** | `p1b/csvit-off.log` |

Commit 2 was not re-compiled in isolation (unchanged by this fix-up and previously verified per-commit); its neighbours (1 and 3) and the tip compile cleanly.

### F4 plan-tag gate (must print 0 — currently **157**)

```
$ git diff main...HEAD -- '*.java' | grep '^+' | grep -v '^+++' \
    | grep -c -E '\biter[- ]?[0-9]+|\bS[0-9]\.[0-9]|\b(P[0-3]|D)\.[0-9][a-z]?\b|\b[HIEFG][0-9]{1,2}\b|criterion #|binding rule #|\bstage[- ][0-9]'
157
```

All 157 are in test comments/assertion messages (0 in `src/main` after the `Pow.java` fix). `main` is set locally to the base `174890c2e3a` so the literal `main...HEAD` command reproduces.

## P2 handoff

### JMH — build and run the kept benchmarks

```
# Build the benchmarks jar (Shadow/uber jar on the classpath):
./gradlew :benchmarks:jmhJar

# Run the two kept fusion benchmarks (1 fork, short smoke; drop -f/-wi/-i for full runs):
java -jar benchmarks/build/libs/elasticsearch-benchmarks-*-jmh.jar \
    'org.elasticsearch.benchmark.compute.operator.FusionBenchmark'        -f 1 -wi 3 -i 5
java -jar benchmarks/build/libs/elasticsearch-benchmarks-*-jmh.jar \
    'org.elasticsearch.benchmark.compute.operator.FusionStrategyBenchmark' -f 1 -wi 3 -i 5

# Cache plan hit vs miss cells (FusionStrategyBenchmark):
java -jar benchmarks/build/libs/elasticsearch-benchmarks-*-jmh.jar \
    'FusionStrategyBenchmark.(planCached|planMiss|planCachedThenGet)' -f 1 -wi 3 -i 5
```

`FusionBenchmark`/`FusionStrategyBenchmark` `@Setup` assert fused == unfused before timing, so a passing run is itself the A/B correctness check. (NB: there is no method literally named `planHit`; the plan-cache-hit cell is `planCached`/`planCachedThenGet`.)

### Toggle fusion on a running node for a Rally A/B

`esql.fusion.enabled` is a dynamic cluster setting gated behind the `esql_fusion` feature flag:

```
PUT _cluster/settings
{ "persistent": { "esql.fusion.enabled": true } }   # or false for the baseline arm
```

Run the same Rally track against each arm. (In test JVMs the feature flag is on; on a release build the `esql_fusion` flag must be enabled for the setting to take effect.)

### Settings (names / defaults)

| Setting | Default | Meaning |
|---|---|---|
| `esql.fusion.enabled` | `true` (kill switch; gated behind the `esql_fusion` feature flag) | master on/off for evaluator fusion; operator-dynamic |
| `esql.fusion.cache.max_bytes` | weight-bounded fused-class cache size (bytes) | cap on the process-wide fused-class cache |
| `esql.fusion.cache.min_sightings` | production default `2` (static/test default `1`) | admit a shape only after this many sightings (1 = fuse on first sight; the doorkeeper is the pre-admission filter) |

### Reading cache stats during a run

`FusedClassCache` exposes `hits()` / `misses()` (now counting one lookup per plan after F7.1) and `stats()`; these surface through the fusion telemetry. Watch the hit/miss ratio climb as hot shapes are admitted and the doorkeeper stops refusing repeat sightings.

## Not done / follow-ups

- **F2** — isolate all WHERE+EVAL code into commit 4 (currently in commit 3).
- **F3** — prune benchmarks (inline `PocShape`/`SlowPathFusionBenchmark`/`FusionBenchmarkGuard` cells into the kept classes, delete `FusionFollowupBenchmark`/`FusionOperatorBenchmark`) and consolidate commit-3 differential tests toward ~7k lines (preserving every shape and the 43/43 corpus).
- **F4** — scrub the remaining 157 plan-tag references from test comments/messages (and the "E1" harness wording) until the gate prints 0.
- **F8** — full `:esql:test`/`:compute:test` headline counts and a JMH smoke fork of `FusionBenchmark` were not executed under budget.
