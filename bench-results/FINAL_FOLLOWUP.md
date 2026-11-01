# ES|QL expression fusion — follow-up iteration: final report

- **Code branch:** `esql/fix/compute-codegen-followup` (based on `esql/compute-codegen-stabilize` `454347f117f`; deviation D0 in `IMPLEMENTATION_PLAN.md`).
- **Evidence branch:** `esql/fix/compute-codegen-followup-evidence` (markdown only).
- **Final HEAD:** `dec0810a47a`.
- **Delivery:** direct push to `origin/esql/fix/compute-codegen-followup`, no PR (operator directive).
- **Loop policy:** gated on correctness only. Per the operator scope change (2026-10-31) there are NO timing runs this iteration — all perf measurements move to a dedicated benchmark host the human runs after this iteration. Benchmark work here was limited to (1) adding the cells each item asks for and (2) a G3 correctness smoke of every new/changed cell (`-f 1 -wi 1 -w 1s -i 1 -r 1s -foe true`, all params) that only checks setup assertions pass and fused == unfused. Numbers were neither read nor reported.

## Phase / commit map

| Phase | Commits | Summary |
|---|---|---|
| A (fallback hardening + bench hooks) | code `50258d8aa18` (8 commits), evidence `c87de31d30e` | A1–A6 negative-cache/WARN-once/structural owners, A8 CsvIT on==off, A9 bench hooks + `FusionFollowupBenchmark`; A6-hardening `66f701a3c60`. |
| §G fixes (iter-3) | `e3ffe7a97b1..1ca2b34e82e` | G5 `ba03a96b360`, G7 `00b439d7422`, G9 `3a4fba265ce`, G1 `1ca2b34e82e` (G2/G4/G6 landed in A). |
| E3-logical | `60b0753ad46` | logical vector INTERFACE-only; deleted LOGICAL_VECTOR RAW emit + dispatch + RAW structural expectations (arithmetic RAW untouched). |
| C (verify + debug info) | C1 `f603d158f62`, C2 `e3ffe7a97b1`, C3 `7129f91cad5`, C4 `9b3ff130815` | `esql.fusion.verify` A/B compare; SourceFile/LineNumberTable/KERNEL_MAP debug metadata; build-gating via C1 default; generator encoding coverage. |
| D (mapping + converters) | D1 `9fc7295549d`, D2 `436a84288bc`, D3+F3 `a87c0097b92`, D4 `8fc15245222` | encoding-aware BytesRef mapping; `@Fusable` on `@ConvertEvaluator` (ChangeCase); nested mapping under single-value parent + keyword Equals; removed depth-1 mapping exemption. |
| F (constants + logic + probe) | F1 `9af28192aca`, F2a `5f29c3a5532`, F2b `880716d228d`, F4 `151e3d06614` | baked Div/Mod constant divisor (C2 strength-reduce); GTE/LTE coverage; `FusionNode.Not` (3VL flip); opt-in `esql.fusion.coverage_report` probe. |
| E (huge-method guard + Stitcher split) | E4' `24f12460e9f`, E6 `6b73a3be812`, E7 `dec0810a47a` | deleted the compute() split, single 8000-byte DontCompileHugeMethods guard; split `Stitcher.java` into per-loop emitters; slimmed the evaluator factory. |

## Final gate (correctness, on `dec0810a47a`)

`./gradlew :x-pack:plugin:esql:compute:test :x-pack:plugin:esql:test --tests "*fusion*" :x-pack:plugin:esql:compute:spotlessJavaCheck :x-pack:plugin:esql:spotlessJavaCheck` → **BUILD SUCCESSFUL** (5m15s, exit 0). Per-task `*fusion*` gates were green at every commit; per-task counts are in `IMPLEMENTATION_PLAN.md`.

### CsvIT (full ES|QL REST corpus)
- **A8 (fusion on vs off, Phase A HEAD `50258d8aa18`):** both **7657 tests / 0 failures / 252 skipped** (pre-existing `muted-tests.yml`). Reviewer independently confirmed identical numbers (G4).
- **F4 coverage probe (`esql.fusion.coverage_report`, math/comparison/boolean/stats/string/eval/where = 942 tests / 0 fail):** `attempts=1604 fused=174 refused-at-plan=1430 stitch-failures=0 runtime-fallbacks=0`, **fused/attempt = 10.8%**, and **ZERO `FILTER_EVAL_BLOCK` stitch failures**. The 1430 refusals are depth-1/ineligible plan-time gates, not bugs.

### E1 byte-identical proof (safety net for E6/E7)
A 34-shape class-byte dump corpus covering every surviving compile entry point (plain / checked / block / logical-block / logical-vector / mapping / SIMD / filter-eval) × element types was dumped before and after. `diff -r` was **EMPTY** after the E6 pure move, after the E6 LoopSpec merge, after the E6 review nits, and after E7. `fusedClassName` is deterministic (package + fixed per-path name, no counter), so re-running on a fixed HEAD is itself byte-identical (two-run diff empty). The dump harness (`FusionClassDumpTests`) is NOT committed to the code branch; a copy + README live in `bench-results/TE/`.

## Deferred to the benchmark round

Each item below is a measurement the operator runs on the dedicated host, with the plan acceptance threshold it must meet. No number was produced this iteration.

- **C4 CsvIT wall-time, verify on vs off** — threshold: if verify overhead > 15%, add page-sampling (first N pages/evaluator then 1-in-16). Status: no sampling added — verify checks every page; the on/off wall-time delta is unmeasured.
- **G9 `vector_reads` static-final re-measure** — threshold: B2 auto cells within 3% of control (no per-page volatile regression). Functional no-per-page-volatile behavior is unit-tested; the 3% perf check is deferred (dev VM too noisy for a 3% bar).
- **E3-logical (logical vector INTERFACE-only)** — threshold: `AND_CMP` NONE & TYPES ≤ 1.10 ns/row. (A boundary smoke saw 0.993 / 1.037 but is not an accepted measurement.)
- **D3 encoding-aware nested mapping** — threshold: `EQ_TO_LOWER_*` & `LENGTH_TO_LOWER_ORDINAL` over ordinals ≤ 1.5 ns/row fused; the plain keyword cell unchanged.
- **F1 by-constant Div/Mod** — threshold: fused `LONG_DIV_CONST` ((a*b)/1000) & `ROUND3` (ROUND(d*1.1,3)) not slower than unfused (the `jitConstant` divisor baked as LDC/ICONST lets C2 strength-reduce to multiply-and-shift, several× cheaper than `idiv`).
- **E4' huge-method guard** — threshold: one cell of the deepest logical shape (`DEEP_LOGICAL`), fused vs unfused, fused not slower (the deleted split bought ~0.2%; the old 325 budget protected nothing measurable).
- **New F2 cells (correctness-smoked, perf not individually gated):** `AND_CMP_GTE_LTE_LONG` (F2a) and `NOT_AND_CMP_LONG` (F2b) — included so the GTE/LTE and NOT paths are measurable on the bench host.

## Decisions (iteration-3 §B + agent engineering calls)

- **E2 (single-pass nulls mask): DROPPED** (B1 — fused still ~2× unfused on overflow pages; the design only helped no-overflow pages).
- **E3-logical: GO** (B2 — logical RAW was the slow path; INTERFACE −22% on AND_CMP). Arithmetic keeps RAW (RAW wins ~18% under type pollution).
- **E4': GO, revised** (B3) — delivered as the single 8000-byte DontCompileHugeMethods guard (not the 325 FreqInlineSize split).
- **E5 (direct invocation): DROPPED** (B4 — the ratio moved because unfused sped up on small pages, not a fused per-page cost).
- **D3 + F3: GO** (B6 — unfused `TO_LOWER(k)=="x"` on ordinals was 8.1 ns/row, 4× the threshold).
- **ROUND fusion: DEFERRED** (F1) — `Round` is a non-`@Fusable` delegating expression; baking decimals yields no strength-reduction. `ROUND3` added as an unfused baseline cell.
- **G8 (filter-eval fallback loses separate fusion): NOT THIS ITERATION — evidence-backed.** The F4 CsvIT coverage probe showed ZERO `FILTER_EVAL_BLOCK` stitch failures across 942 tests, so the condition to fix it ("only if the F4 probe shows filter-eval stitch failures") is not met.
- **E6 "≥30% emitter-line cut": NOT MET (combined +2.5%), accepted.** The ~400 duplicated logical-vector lines that target assumed were already shared via `StackKernelEmitter` by E2/E3, so there was nothing left to delete. `Stitcher.java` itself dropped −63% (3883→1442); the merge still unified the 4 vector emitters behind one `LoopSpec`. No new emitter file exceeds ~1200 lines; `Stitcher` at 1442 is javadoc-dominated (the public `compile*` docs).
- **E6 merge fidelity:** plain / checked-raw / logical-vector merged into one `LoopSpec` array-vector emitter; the checked-vector BUILDER kept its own skeleton (a byte-identical merge was not possible, so it was correctly left as a LoopSpec-dispatched method rather than forced).
- **Partial codegen / islands / cross-EVAL inlining (F0): still out of scope.**
- **A7 commit-message note:** the stabilize-branch commit `70c536d4` carried a sub-par message; the fix is to be applied at squash time (recorded here per plan, no action on this branch).

## Open questions / follow-ups

- **All perf numbers (M-series + the deferred thresholds above) are pending the dedicated-host benchmark round.** None were produced here by design.
- A2: ~~a post-stitch `findStatic` signature miss WARNs on every plan rather than once~~ — RESOLVED in iteration 4 (H8/G5): the resolved handle is cached on the class-cache unit and the test drives through `FusionCompilationService.cached`; the fallback now WARNs exactly once (`FusionFallbackHardeningTests` ExactlyOnceWarnExpectation).
- E6: ~~`Stitcher.java` remains 1442 lines~~ — RESOLVED in iteration 4 (H11): Stitcher is 1199 lines, four-file emitter total 3363 (dedup via FusedBuilderEmit, dead mapping path deleted). E1 byte-identical.
- E7: ~~verify ON without `-ea` falls back to the "BLOCK" label~~ — RESOLVED in iteration 4 (H8): the verify-path label reads `?` when the path is unknown, not `BLOCK`.
- NOTE (iteration 4 correction): earlier phase-A notes implying "WARN-once / G7 done" were inaccurate — G7 was only at-least-once until H8 made it exactly-once over three plans. See FINAL_ITER4.md H8.
