# ES|QL fusion: path to main — evidence

Base: `esql/fix/compute-codegen-followup` at `c1474931f0e6` (iteration 4).
Scope of this session: **Phase D, P0, P1** (P2 benchmarks and P3 PR series are handled by a separate agent).
Delivery: D and P0 commits on `esql/fix/compute-codegen-followup`; P1 rebase onto current `main` lands on
branch `esql/fusion-main`. No PRs created this session.

Driven by the orchestrator; each production change implemented test-first by a subagent, reviewed by a
fresh subagent, nits applied, then committed by the orchestrator (`Costin Leau`, 50/72, no trailers).

## Baselines at c1474931f0e6
- Emitter line counts (D.0 table):

| File | Lines |
|---|---|
| Stitcher.java | 1193 |
| VectorLoopEmitter.java | 1015 |
| BlockLoopEmitter.java | 701 |
| BlockDfsEmitter.java | 451 |
| FilterEvalEmitter.java | 450 |
| FusionDescriptor.java | 170 |
| FusedBuilderEmit.java | 82 |
| FusionSimd.java | 80 |
| **Total** | **4142** |

- D.8 dup scan (window 6): 23 regions. Raw: `dupscan-baseline.txt`. Largest: BlockLoop 218-237 ~ 365-383 (20),
  FusionDescriptor 79-93 ~ 106-116 (15), BlockLoop 123-137 ~ 298-312 (15), VectorLoop 293-305 ~ 778-785 (13).
- E1 pre-D (33 shapes): `e1/pre-dd.txt`.

## Phase D
Target: eight files + new helpers total <= 3350 lines; D.8 window-6 scan reports no region >= 8 lines across the
emitters (modulo declared leftovers); every commit E1 byte-identical to the previous except D.1 (SIMD shapes
removed) and D.5a (declared reorder). Fusion gate green per commit; full CsvIT verify-on at phase end.

| # | Commit | Hash | E1 | Scan | Gate |
|---|---|---|---|---|---|
| D.0a extend E1 corpus | done | 42a1e77a6be | new baseline: 46 shapes, `e1/D0a.txt` md5 76d44677b9bf6c8be8530809327630f2 (33 pre-existing byte-identical, 13 new) | 23 regions (test-only; emitters untouched) | fusion gate BUILD SUCCESSFUL 4m52s exit 0 |
| D.1 delete SIMD | done | 68bf1e55cab | `e1/D1.txt` 43 shapes; all byte-identical to D0a (only simd_long/double/int_gt removed) | 23 regions (SIMD-tail pair gone; line shifts net same count; raw `dupscan-d1.txt`) | fusion gate BUILD SUCCESSFUL 5m31s exit 0 |
| D.2 delete Stitcher forwarders | done | 33e9553bf28 | `e1/D2.txt` 43 shapes byte-identical to D1 | 23 regions (dupscan-d2.txt) | fusion gate BUILD SUCCESSFUL 5m31s exit 0 |
| D.3 one FusionDescriptor ctor (I7) | pending | | identical | | |
| D.4 helpers | pending | | identical | | |
| D.5a move pre-loop inits | pending | | declared diff | | |
| D.5b builder position loop | pending | | identical | | |
| D.5c PositionLoop header/footer | pending | | identical | | |
| D.7 comment pass | pending | | identical | | |

## Phase P0
| Item | Status | Commit | Notes |
|---|---|---|---|
| P0.1 correctness (I4,I2,I3,I8,Stitcher.compile) | pending | | |
| P0.2 FeatureFlag esql_fusion | pending | | |
| P0.3 settings cleanup | pending | | |
| P0.4 hot-path invokeExact/interface (I6) | pending | | |
| P0.5 precommit fixes | pending | | |
| P0.6 unit suites (ModTests toString) | pending | | |
| P0.7 hygiene | pending | | |

## Phase P1
- Rebase notes: pending (branch `esql/fusion-main`).
- `allowNonFinite` decision: pending.
- Kernel contract table: `rebase.md` (pending).
- Gates after rebase: pending.

## iter5 item map
| iter5 | Lands in | Status |
|---|---|---|
| I1 coverage probe | deferred (evidence only, not PR series); correct FINAL_ITER4 "FUSED did not rise" | pending |
| I2 cache pinning | P0.1 | pending |
| I3 divisor-tuple cap | P0.1 | pending |
| I4 filter-eval L WARN | P0.1 (first) | pending |
| I5 finish dedup + E1 corpus | Phase D (corpus D.0a done; fold D.5) | corpus done; H1 reason corrected in FINAL_ITER4.md |
| I6 filter-eval spreader/spare | P0.4 | pending |
| I7 explicit dictionarySafe | D.3 | pending |
| I8 small fixes | P0.1/P0.5/P0.7 | pending |
| I9 cold planMiss | P2 (out of session) | n/a |
