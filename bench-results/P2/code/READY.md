READY: base 251d667d2d77, tip a9de22c68949, all gates green

Four commits on `upstream/main` (`251d667d2d77`):

1. `cb2df865d7e2` `[ESQL] Fusion metadata: @Fusable and descriptors`
2. `85eca33263ed` `[ESQL] Fusion stitcher and bytecode emitters`
3. `b4ca3a7e9bee` `[ESQL] Fusion planner for EVAL expressions`
4. `a9de22c68949` `[ESQL] Fuse WHERE combined with a following EVAL`

Code branch: `esql/fix/fusion-main-bf28` (not pushed).

## Gates

| command | exit | notes | log |
|---|---|---|---|
| fusion tests (`compute:gen:test` `compute:test` `esql:test --tests *fusion*`) | 0 | C12, 4m; XML `esql/build/test-results/test` 259/0/0; compute 114/1/0; gen 18/0/0 | `C12-test.log` |
| esql+compute+gen+benchmarks precommit | 0 | C12, 1m 45s | `C12-precommit.log` |
| CsvIT fusion on | 0 | 10891 tests, 184 skipped, 0 failures (131s) | `C12-csvit-on.log` |
| CsvIT fusion off | 0 | `-Desql.fusion.enabled=false`; 10891/184/0 (180s) | `C12-csvit-off.log` |
| dump corpus vs MERGE/e1/P1.txt | 0 | 43/43 identical | `C12-dump.log` / `dump.txt` |
| `:benchmarks:test --tests '*Fusion*'` | 0 | 2 tests, 0 skip, 0 fail | `C12-bench-test.log` |
| `EsqlActionIT.testProfileShowsFusedEval -Dtests.iters=20` | 0 | 20/0/0 (23.6s) | `C12-profile-it.log` |
| C1 compileTestJava (gen, compute, esql) | 0 | detached `cb2df865d7e2` | `C12-C1-compile.log` |
| C2 compileTestJava | 0 | detached `85eca33263ed` | `C12-C2-compile.log` |
| C3 compileTestJava | 0 | detached `b4ca3a7e9bee` | `C12-C3-compile.log` |
| C9 truncated one-liners vs d89fef | 0 | awk list empty | — |
| C9 leftover-tag grep | 0 | after `iteration=` reword in `EvalMapperFusionWiringTests` | — |
| C5 plan-tag regex on added Java | 0 | | — |
| adaptive-fusion wording | 0 | `git grep` NONE | — |

`esql.fusion.enabled` is the node-scope kill switch (`FusionSettings`). `CsvIT` copies `System.getProperty("esql.fusion.enabled")` into node settings and sets `esql.fusion.cache.min_sightings=1`.

## Test counts (`x-pack/plugin/esql/build/test-results`)

| suite | tests | skipped | failures | errors |
|---|---|---|---|---|
| `test/` (`*fusion*` unit) | 259 | 0 | 0 | 0 |
| `internalClusterTest/` CsvIT on | 10891 | 184 | 0 | 0 |
| `internalClusterTest/` CsvIT off | 10891 | 184 | 0 | 0 |
| `internalClusterTest/` profile IT (20 iters) | 20 | 0 | 0 | 0 |

Companion fusion XML from the C12-test run (compute XML later overwritten by dump): compute 114/1/0, gen 18/0/0. Benchmarks Fusion 2/0/0.
