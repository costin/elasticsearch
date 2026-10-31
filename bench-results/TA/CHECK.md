# Phase A post-phase check (follow-up iteration 2)

- **Code branch:** `esql/fix/compute-codegen-followup`
- **Phase A HEAD:** `50258d8aa18` — "Compare IntBlock output in fusion benchmark"
- **Base:** `esql/compute-codegen-stabilize` tip `454347f117f` (deviation D0 from plan §0's `23f5e82b2bbc`: based off the stabilization branch so the breaker-reservation fix is included; documented in IMPLEMENTATION_PLAN.md).

## Commits (task -> commit)

| Task | Commit | Summary |
|---|---|---|
| A1 | `a751c9a8fe9` | Negative-cache all fused stitch failure kinds (RuntimeException / ReflectiveOperationException / LinkageError wrapped as StitchingException; only VirtualMachineError stays uncached and retries). WARN once per shape. |
| A2 | `acfee35d83d` | Move the plan-time stitch-failure WARN to the factory; WARN once only when the shape falls back to the unfused chain; DEBUG for vector/SIMD/logical-vector degrade-to-block and every repeat. Message names the path. |
| A3 | (no commit) | "Exactly once" asserted as exactly once — absorbed by A1/A2: every first WARN is paired with an UnseenEventExpectation on the second plan, at both the cache and the factory; the cache no longer WARNs. |
| A4 | `3af03f633e3` | Reset the fused class cache in `FusionDifferentialTestCase` `@Before`/`@After` (and `FusionFallbackHardeningTests` from A1) to stop cross-test cache leakage. |
| A5 | `9056a8f1597` | End-to-end negative-cache degrade test through the production factory + real class cache: one stitch, one negative hit on replan, block-only (VectorStrategy.NONE) both times, output == unfused. Package-private `injectFailureForPathForTests` hook (inert in production). |
| A6 | `864564478a2` (+ hardening `66f701a3c60`) | Broaden structural-test owner filters via new `ForbiddenHotPathOwners` helper (`isBuilderOwner`/`isBlockOwner`); gate INVOKEVIRTUAL as well as INVOKEINTERFACE. Hardening: also scan the PLAIN ALL-RAW `fused` and the split `compute()` body; Javadoc corrected. Each filter proven fail-first by injecting a typed Block/Builder call, then reverting (probe not committed). |
| A7 | (note only) | Commit message of `70c536d4fa77` (from the stabilization branch) violates 50/72 — to be fixed at squash time, per plan. No history rewrite now. |
| A9 | `96205ababfe` (+ smoke fix `50258d8aa18`) | Two benchmark/test-only JVM dials (NOT node settings): `esql.fusion.vector_reads=auto|interface` (interface forces ALL-INTERFACE over exact `*ArrayVector`, read once per dense page) and `esql.fusion.max_method_bytecodes` (default 325, overrides the fused-method inlining/split budget). New `FusionFollowupBenchmark` (split, EQ/LENGTH over TO_LOWER on ordinal + plain) with its own Case enum. A unit test per dial proves the path/split flip. Smoke fix: added an `IntBlock` arm to `SlowPathFusionBenchmark.assertSameOutput` so the LENGTH (INT) case compares. |

## Reviewer passes
- A2, A6, A9 each reviewed (cursor-grok-4.6-high). All verdicts NON-BLOCKING. A9's two polish items (Javadoc wording, `Method` import) folded into `96205ababfe`. A6's three method-coverage findings addressed by the hardening commit.
- Open questions recorded in IMPLEMENTATION_PLAN.md (A2 post-stitch findStatic-miss not negative-cached — should-never-happen contract bug, deferred).

## Gates (per task, committed HEAD)
- `:x-pack:plugin:esql:compute:test` fusion suite: 86 tests / 16 classes, 0 failures (incl. new `StitcherMaxMethodBytecodesTests`, broadened `StitcherOverflowTests`/`StitcherArithmeticVectorInterfaceTests`/`StitcherLogicalVectorFusionTests`).
- `:x-pack:plugin:esql:test --tests '*fusion*'`: 158 tests, 0 failures (incl. new `FusionVectorReadsHookTests`, `FusionNegativeCacheEndToEndTests`).
- `:benchmarks:compileJava`: success. `spotlessJavaCheck` (compute / esql / benchmarks): clean.

## A8 — CsvIT fusion on/off evidence (plan A8)
Run on Phase A HEAD `66f701a3c60` via `:x-pack:plugin:esql:internalClusterTest --tests "org.elasticsearch.xpack.esql.CsvIT"` (counts from `build/test-results/internalClusterTest/TEST-org.elasticsearch.xpack.esql.CsvIT.xml`).

| Run | Command delta | tests | failures | errors | skipped | gradle |
|---|---|---|---|---|---|---|
| Fusion ON (default) | _(none)_ | 7657 | 0 | 0 | 252 | BUILD SUCCESSFUL in 2m 42s |
| Fusion OFF | `-Dtests.jvm.argline="-Desql.fusion.enabled=false" -Dtests.timestamp=<ts>` | 7657 | 0 | 0 | 252 | BUILD SUCCESSFUL in 2m 46s |

Identical test/skip counts and zero failures both ways: the `esql.fusion.enabled` kill switch (criterion #3) produces the same CSV-spec correctness whether fusion is on or off. (Logs: `bench-results/TA/csvit/fusion-on.log`, `fusion-off.log` — git-excluded; summary above is the committed evidence.)

## Benchmark smoke (plan §0 boundary; correctness only, timings discarded)
`-f 1 -wi 0 -i 1 -r 200ms -foe false`, JSON+txt under `bench-results/TA/smoke/` (git-excluded).

| Benchmark | params | cells | failures | note |
|---|---|---|---|---|
| FusionStrategyBenchmark | `pollute=NONE,TYPES` | all | 0 | no AssertionError/exception |
| SlowPathFusionBenchmark | `pollute=false,true` | all | 0 | no AssertionError/exception |
| FusionFollowupBenchmark | defaults (shape×fusion) | 8 | 0 | all 4 shapes present; `split` fuses + splits into `compute()` |

- The initial FusionFollowupBenchmark smoke surfaced a benchmark-harness gap: `LENGTH_TO_LOWER_ORDINAL` returns INT and `SlowPathFusionBenchmark.assertSameOutput` lacked an `IntBlock` arm, throwing "unexpected block types" for both fusion flags (outputs were identical). Fixed in `50258d8aa18` (added the `IntBlock` arm); re-run is clean (8 cells, 0 failures). This is why the boundary smoke exists.
- Timings are never reported as results (noisy host; perf is §2, run by the human on dedicated hardware).

**VERDICT: PASS** — Phase A HEAD `50258d8aa18`. All fusion unit/structural suites green; CsvIT on==off (7657/0/252); boundary smoke clean on all three benchmarks.
