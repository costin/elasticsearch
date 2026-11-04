READY: base dd00717db9f7, tip c19d06c755ba, all gates green

Four commits on `upstream/main` (`dd00717db9f7`):

1. `5a7c07209133` `[ESQL] Fusion metadata: @Fusable and descriptors`
2. `d3d7a315a34f` `[ESQL] Fusion stitcher and bytecode emitters`
3. `9e5938b32e77` `[ESQL] Fusion planner for EVAL expressions`
4. `c19d06c755ba` `[ESQL] Fuse WHERE combined with a following EVAL`

Code branch: `esql/fix/fusion-main-bf28` (not pushed).

## Gates

| command | exit | notes | log |
|---|---|---|---|
| fusion tests (`compute:gen:test` `compute:test` `esql:test --tests *fusion*`) | 0 | C8 | `C8-test.log` |
| esql+compute+gen+benchmarks precommit | 0 | C8 | `C8-precommit.log` |
| CsvIT fusion on | 0 | `CsvIT` default (min_sightings=1) | `C8-csvit-on.log` |
| CsvIT fusion off | 0 | `-Desql.fusion.enabled=false` | `C8-csvit-off.log` |
| dump corpus vs MERGE/e1/P1.txt | 0 | 43/43 identical | `C8-dump.log` / `dump.txt` |
| C1 compileTestJava (gen, compute, esql) | 0 | detached `5a7c07209133` | `C8-C1-compile.log` |
| C2 compileTestJava | 0 | detached `d3d7a315a34f` | `C8-C2-compile.log` |
| C3 compileTestJava | 0 | detached `9e5938b32e77` | `C8-C3-compile.log` |
| C5 plan-tag count on added Java | 0 | 157 → 0 | — |
| C6 JMH smoke FusionBenchmark | 0 | `-f 1 -wi 1 -i 1 -w 1s -r 1s` | `C6-jmh-smoke.log` |
| C7 dump 43/43 | 0 | | `C7-dump.log` |

`esql.fusion.enabled` is the node-scope kill switch (`FusionSettings`). `CsvIT` copies `System.getProperty("esql.fusion.enabled")` into node settings and sets `esql.fusion.cache.min_sightings=1`.

## C7 line count

C3 test lines +13554 → **+11775**. Case count 255 `public void test*` held. 9k cap not reached without dropping cases (comments/`//` banners only).
