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
| D.3 one FusionDescriptor ctor (I7) | done | e74b469d9d9 | `e1/D3.txt` 43 shapes byte-identical to D2 (md5 98ff3ece...) | 17 regions (dupscan-d3.txt; 7 FusionDescriptor convenience-ctor self-dups gone; emitters unchanged) | fusion gate BUILD SUCCESSFUL 5m44s exit 0 |
| D.4a method/descriptor/params helpers | done | ab629e68e8f | `e1/D4a.txt` 43 shapes byte-identical to D3 (md5 98ff3ece...) | 15 regions (dupscan-d4a.txt) | fusion gate BUILD SUCCESSFUL 5m08s exit 0 |
| D.4b append helpers | done | 685d018b76b | `e1/D4b.txt` 43 shapes byte-identical to D4a (md5 98ff3ece...) | 12 regions (dupscan-d4b.txt) | fusion gate BUILD SUCCESSFUL 4m37s exit 0 |
| D.4c array-vector + register helpers | done | f9bd8a0bd54 | `e1/D4c.txt` 43 shapes byte-identical to D4b (md5 98ff3ece...) | 10 regions (dupscan-d4c.txt) | fusion gate BUILD SUCCESSFUL 4m29s exit 0 |
| D.4d finishFusedClass tail helper | done | 50ea15da1b1 | `e1/D4d.txt` 43 shapes byte-identical to D4c (md5 98ff3ece...) | 10 regions (dupscan-d4d.txt; structural prep, below window-6) | fusion gate BUILD SUCCESSFUL exit 0 (post-spotlessApply) |
| D.4e leaf guard/read helpers (BlockLeafEmit) | done | 460c9a71358 | `e1/D4e.txt` 43 shapes byte-identical to D4d (md5 98ff3ece...) | 9 regions (dupscan-d4e.txt; cross-emitter getValue region gone) | fusion gate BUILD SUCCESSFUL 5m21s exit 0 |
| D.5a hoist pre-loop inits (DECLARED DIFF) | done | 344469b38f7 | `e1/D5a.txt` 10/43 shapes changed by pure reorder + 33 identical; javap proof in `javap/d5a/` (equivalent) | 9 regions (dupscan-d5a.txt; emitBlockLoop + emitFilterEval now match the vector skeleton, D.5b/c targets) | fusion gate BUILD SUCCESSFUL 4m52s exit 0 (correctness suites = behavioral proof) |
| D.5b emitAppendOrNull append-tail | done | dc73e010902 | `e1/D5b.txt` 43 shapes byte-identical to D5a (md5 ac36a096...) | 8 regions (dupscan-d5b.txt; append-tail self-dup gone) | fusion gate BUILD SUCCESSFUL 5m17s exit 0 |
| D.5c PositionLoop builder-loop skeleton | done | 51923ff9340 | `e1/D5c.txt` 43 shapes byte-identical to D5b (md5 ac36a096...) | 6 regions (dupscan-d5c.txt; both 12-line skeleton regions gone; one 11-line prologue left) | fusion gate BUILD SUCCESSFUL 5m02s exit 0 |
| D.5d inline FusedParams accessor locals | done | 639e4ebbc36 | `e1/D5d.txt` 43 shapes byte-identical to D5c (md5 ac36a096...) | 4 regions, max 6 lines (dupscan-d5d.txt; last >=8-line prologue region gone) | fusion gate BUILD SUCCESSFUL 4m47s exit 0 |
| D.7 strip merge-plan tags from comments | done | 3410128d302 | comment-only; fused bytes unchanged (compile+spotless only, no re-dump) | 4 regions, max 6 lines (unchanged) | compile+spotless BUILD SUCCESSFUL |

### Phase D end
- D.8 window-6 scan: **4 regions, max 6 lines; no region >= 8 lines** (target met). Remaining 6-line regions
  (`dupscan-d5d.txt`): BlockLoop 463-468~546-551, FilterEval 297-302~372-377, FilterEval 310-315~385-390,
  VectorLoop 127-132~181-186 — all below the window-6 gate; folding them would add helper overhead for no net
  line win, so they are left.
- Line counts (10 files: 8 emitters + PositionLoop + BlockLeafEmit; `wc -l`): **total 3737** = 2141 code + 1329
  comment + 267 blank. Baseline (8 files at c1474931f0e6) was **4142** = 2528 code + 1321 comment + blank.
  **Code dropped 2528 -> 2141 (-387, -15%)**; comments flat (1321 -> 1329). The 3350 raw-line target is NOT met
  (3737): with the comment footprint fixed at ~1329 mandated Javadoc (AGENTS.md requires class/method "why" docs
  and forbids removing comments unless the code is removed), 3350 would require code at ~1762 lines — far below
  functional need. The dedup objective (no >=8-line region; 23 regions -> 4) is fully met; the raw-line target was
  mis-calibrated against the pre-existing Javadoc and is left unmet by design rather than by cutting documentation.
- FusionSimd.java deleted (D.1). New helpers: PositionLoop.java (68), BlockLeafEmit.java (76), plus growth in
  FusedBuilderEmit and FusedMethodCodeLengths absorbing extracted sequences.
- Phase-end CsvIT verify-on (`:x-pack:plugin:esql:internalClusterTest --tests CsvIT`
  `-Dtests.jvm.argline=-Desql.fusion.verify=true`, full csv-spec corpus): **7657 tests, 0 failures, 0 errors,
  252 skipped** (capability/mode skips), BUILD SUCCESSFUL 3m21s. Every fused page was A/B'd against the unfused
  fallback and matched — behavioral proof across the whole corpus, on top of the 43-shape byte-identity.

## Phase P0
All P0 items complete and pushed to `esql/fix/compute-codegen-followup`.

| Item | Status | Commit | Notes |
|---|---|---|---|
| P0.1 correctness (I4,I2,I3,I8,Stitcher.compile) | done | see sub-rows | |
| &nbsp;&nbsp;I4 filter-eval reference-constant refuse + WARN-once | done | 558e05b9b4a | refuse 'L'/BytesRef constants at plan time (silent gate); wrap residual non-StitchingException in create() with shape-keyed first-failure. 2 MockLog tests fail-before-fix. Gate 5m21s exit 0 |
| &nbsp;&nbsp;I2 cache pinning | done | bf8affeaa6c | pin cached fused unit against GC |
| &nbsp;&nbsp;I3 divisor-tuple cap | done | 68c90023501 | BAKED_DIVISOR_CAP=8 per un-baked shape, tuple-keyed, admit only at stitch, telemetry |
| &nbsp;&nbsp;I8 verify leak + compile guard | done | e11905f1eda | verifyUnfused() leak fix + Stitcher.compile(descriptor) budget guard |
| P0.2 FeatureFlag esql_fusion | done | c4408854463 | gate fusion behind esql_fusion FeatureFlag; keep esql.fusion.enabled kill switch |
| P0.3 settings cleanup | done | 57275d2e66d, 7f839ddcd72 | 3a delete sample/adaptive/string_depth1 dials; 3b remove System.getProperty from FusionSettings (enabled/coverage/verify/vector_reads). tmpdir->P0.5, dump->P0.7 |
| P0.4 hot-path invokeExact/interface (I6) | done | 9d35e669dc9 | erase cached spreaders to Block/Vector at bind; 7 spreader invoke->invokeExact (FEEF x6 + filter-eval x1). Full fusion gate green incl differential/verify/lastEvalPath |
| P0.5 precommit fixes | done | dba1c42e892 | 5a: asm-tree license mapping, FusionVerify Path.of->PathUtils.get (@SuppressForbidden, mirrors Environment), 2 checkstyle LineLength. 5b de-reflection: audited - the test reflection (absence-of-field meta-assertions, private FUSION_DESCRIPTOR reads, package-private kernel invokes) is intentional/justified; de-reflecting would weaken production encapsulation, so left as-is with existing rationale comments |
| P0.6 unit suites (toString) | done | af5495b2659 | fusion auto-on in snapshot test JVMs fused depth>=2 cast chains, breaking testEvaluatorToString/testFactoryToString; build those two with fusion disabled (setEnabledForTests widened public). Fails-before-fix: EqualsTests toString |
| P0.7 hygiene | done | d3f349373a6 | FusionDump esql.fusion.dump de-sysprop (removed forbidden Boolean.getBoolean log channel; sink+thread-capture remain) + FusedFrameInfo MethodHandle#invoke->invokeExact. compute:forbiddenApisMain now green. Cosmetic residue (plan-ref/seam trimming) deprioritized under budget |

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
