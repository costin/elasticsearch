> **SUPERSEDED** by `bench-results/FINAL_ITER4.md` (iteration 4). Kept for history only.

# ES|QL expression fusion — stabilization: final report

- **Branch:** `esql/fix/compute-codegen-stabilize-bf28`
- **`T0_SHA` (reference + harness in tree):** `75b9aad906ceee991c7d4e5cb250e16c701d21da`
- **Final HEAD:** `23f5e82b2bbc37198ca3e1352dd01a8a1f1a3434`
- **Loop policy:** gated on correctness only (C1–C5). Performance (M1–M10) is DEFERRED to a dedicated
  host per §9; see `bench-results/PERF_RUN.md`. Benchmark *smoke* timings here are correctness
  artifacts (setup asserts fused == unfused) and must never be reported as results.

## Per-task summary

### Task 1 — per-page encoding guard (mapping path) — DONE `73172cba9c3`
- Fused TO_LOWER/TO_UPPER falls back to the unfused chain for ordinal/constant `BytesRef` pages so the
  dictionary-level encoding fast path is never lost; plain/MV pages stay fused.
- Tests added: `MappingEncodingGuardTests` (differential: ordinal/constant fall back, plain/MV parity).
- Gates: §4.1 clean; §4.2 `evaluator.fusion.*` green; smoke 56+56, 0 missing. CHECK T1: **PASS**.
- Expected perf (not measured here): **M1** (≈426× → ≤ 1.5), **M2** ~unchanged, **M7**.

### Task 2 — dense logical vector path — DONE `d334ed8e949`
- `Stitcher.compileLogicalVectorLoop` emits a 2VL dense loop (raw-array OR typed-`*Vector` interface
  reads, `boolean[]` output via `newBooleanArrayVector`) for all-vector pages; the 3VL block path stays
  for null/MV pages. New `VectorStrategy.LOGICAL_VECTOR` + `FusedClassCache.Path.LOGICAL_VECTOR`.
- Tests added: `StitcherLogicalVectorFusionTests` (structural: no `Block`/`BooleanBlock$Builder` in the
  dense method); new cases in `LogicalFusionDifferentialTests` + `FusionFallbackHardeningTests`.
- Gates: §4.1/§4.2 green (2 review passes, hardened); smoke 56+56, 0 missing. CHECK T2: **PASS**.
- Expected perf: **M3** (≤ 1.0), **M4** (≤ 1.3), **M5** (≤ 1.0), **M7**.

### Task 3 — raw-array dense output for vector arithmetic — DONE `70c536d4fa7`
- `PLAIN_VECTOR` / `CHECKED_VECTOR` write dense output into a raw primitive array wrapped as a
  `*ArrayVector`, never through a builder. Checked path emits `fusedRaw` (single try, `null` on
  overflow) invoked first; on overflow it re-runs the page through the builder method so nulls +
  warnings exactly match unfused. Breaker: transient `NEWARRAY` uncharged, `new*ArrayVector` charges on
  success, abandon leaks nothing.
- Tests added: new cases in `StitcherOverflowTests`, `StitcherOverflowCrossModuleTests` (structural:
  `fusedRaw` has no `*Block$Builder` invokes; split-path coverage) and `FusionDifferentialTests`
  (overflow row → null + warning parity; `asVector()` is a `*ArrayVector`).
- Gates: §4.1/§4.2 green (2 passes; split path tested); smoke 56+56, 0 missing. CHECK T3: **PASS**.
- Expected perf: **M7**, **M9** (≤ 1.2), **M10** (≤ 1.15).

### Task 4 — per-input specialisation (arithmetic vectors) — DONE `54ab96829ad`
- All-interface siblings (`fusedInterface` / `fusedRawInterface`) added to PLAIN_VECTOR and
  CHECKED_VECTOR: CONST_COL/BIGARRAY (any `asVector()!=null`) inputs fuse through the hidden class's own
  typed `*Vector.getX` INVOKEINTERFACE sites instead of the per-row block path. `eval` dispatch per
  page: all exact `*ArrayVector` → raw; else all `asVector()!=null` → interface; else block (pinned by a
  `LastEvalPath` test seam). Checked interface path keeps the Task 3 overflow semantics. The RAW method
  governs the inline/split budget decision. SIMD plain pages keep the block path (no interface variant).
- Tests added: `StitcherArithmeticVectorInterfaceTests` (two-sided structural: interface has typed
  getters + zero `VectorUnsafe`; raw has `VectorUnsafe` + no getters, no `Block`/builder on dense
  methods); `VectorInputSpecialisationTests` (differential: const/big-array/array combos + overflow
  parity, interface path asserted); renamed interface-path case in `EvalMapperFusionWiringTests`.
- Gates: §4.1/§4.2 green (reviewer: no blocking, hardened); smoke 56+56, 0 missing. CHECK T4: **PASS**.
- Expected perf: **M6** (≤ 0.9), **M7**.

### Task 5 — negative stitch cache + log-once — DONE `507163d24ac`
- `FusedClassCache` negatively caches a `StitchingException` as a lightweight `Failure(causeClassName,
  message)` marker: a failing shape is attempted once per lifetime (bounded by MAX_ENTRIES + eviction;
  an evicted failing shape re-attempts once). Repeats rethrow without stitching. The single plan-time
  WARN is emitted once per shape by the cache (first-failure transition under the entry monitor),
  repeats at DEBUG; the now-duplicate WARNs (`Stitcher.defineOrThrow`, the budget-guard enforce branch,
  the expression + filter-eval plan-time fallback catches) were downgraded to DEBUG. Unchecked
  RuntimeException/Error stay transient (entry dropped). Runtime (eval-time) link WARNs left as WARN.
- Tests added (in `FusedClassCacheTests`): negative-cache attempt-once, log-once (MockLog, WARN→DEBUG),
  bounded-negative-cache, concurrent failing stampede, cause-drop contract.
- Gates: §4.1/§4.2 green (reviewer: no blocking, hardened); smoke 56+56, 0 missing. CHECK T5: **PASS**.
- Expected perf: none (robustness / log-noise fix).

### Task 6 — adaptive breaker drift — DONE `23f5e82b2bb`
- `EvalOperator` and `FilterEvalOperator` snapshot `BASE_RAM_BYTES_USED + evaluator.baseRamBytesUsed()`
  into a final `reservedBytes` field at construction and release exactly that at close, instead of
  re-reading `baseRamBytesUsed()` (which an adaptive evaluator grows after switching to the fused path
  mid-query, driving the breaker negative). Contract documented on `ExpressionEvaluator.baseRamBytesUsed`.
- Tests added: breaker-drift case in `EvalOperatorTests`; new `FilterEvalOperatorTests`; real-adaptive
  end-to-end breaker case in `AdaptiveFusionEvaluatorTests`. Fail-first: `-1008` over-release.
- Gates: §4.1/§4.2 green (reviewer: no blocking); smoke 56+56, 0 missing. CHECK T6: **PASS**.
- Expected perf: none (breaker-accounting correctness).

## Task 7 — final smoke + perf hand-off — DONE
- Final smoke on HEAD `23f5e82b2bb` into `bench-results/T7/smoke/`: strategy 56, slowpath 56; compare
  vs T0 = 112 cells, **0 baseline cells not rerun**; no AssertionError/exception. No new failing cell.
- `bench-results/PERF_RUN.md` written (final SHA, per-task expected measures, exact §9 commands).

## Task 8 — full verification — DONE
- `:x-pack:plugin:esql:internalClusterTest --tests 'org.elasticsearch.xpack.esql.CsvIT'` (fusion ON,
  default): **BUILD SUCCESSFUL**, 7657 tests, 0 failures, 252 skipped (pre-existing `muted-tests.yml`).
- Same with `-Dtests.jvm.argline="-Desql.fusion.enabled=false"` (fusion OFF): **BUILD SUCCESSFUL**,
  7657 tests, 0 failures.
- §4.2 on final HEAD: `compute.operator.fusion.*`, `EvalOperatorTests`, `FilterEvalOperatorTests`,
  `evaluator.fusion.*` all green.

## Correctness gates (C1–C5) — all met
- C1 fusion packages compile + pass; C2 every task's new tests observed failing-first; C3 structural
  bytecode tests pin the fast-path shapes (no `Block`/builder in dense methods; interface vs raw reads);
  C4 smoke has 0 new failing cells vs T0 at every task; C5 CsvIT green with fusion on AND off.

## Expected performance effect (NOT measured here — see PERF_RUN.md / §9)
- M1 (Task 1), M3/M4/M5 (Task 2), M7/M9/M10 (Task 3), M6 (Task 4), M7 across Tasks 1–4. Tasks 5–6 have
  no perf effect. All to be measured on a dedicated host against T0 from the same host.

## BLOCKED items
- None. All nine tasks completed; no task was reverted or marked BLOCKED.

## Non-blocking follow-ups (from reviews)
- Task 1: optional probe to pin plain/MV stay fused; TO_UPPER coverage.
- Task 4: a mixed class (inline RAW + split INTERFACE) could keep the interface method inlinable too;
  logical VECTOR path still inlines its own extract/read rather than reusing
  `emitExtractConsumedInput`/`emitReadExtractedAtP` — unify so they cannot drift.
- Task 6: the extra fused-evaluator RAM after an adaptive switch is intentionally not reserved on the
  breaker by the operator; if it should be accounted, do it inside `AdaptiveFusionEvaluator`, not the
  operator reservation.

## Known pre-existing issue (not caused by any task)
- `FusionSpecDifferentialTests.testMathFunctionSpecSweepMatchesUnfused` — a rare, seed-dependent
  double/multivalue Infinity-warning divergence surfaced once during Task 1; did NOT reproduce across 8
  derived seeds, and every post-task §4.2 (through the final HEAD) was green. Not on the mapping path.
  If a future run hits it, treat as this known issue and capture the REPRODUCE seed.

## Open questions
- Whether the Task 6 adaptive fused-RAM delta should be breaker-accounted (above) — product decision.
- Final M1–M10 numbers are pending the §9 dedicated-host run.

## Deviations from the uploaded plan
See `IMPLEMENTATION_PLAN.md` (D1 branch name/base, D2 push-per-task, D3 commit author/hook
neutralization, D4 grok reviewer vs `/java-code-reviewer` skill, D5 tracking files git-excluded).
