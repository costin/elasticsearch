# Task 4 post-task check (§4.5)

- **HEAD:** `54ab96829adbe27c0cd3ef22c115160b566def5c` — "Specialise fused vector paths per input kind"
- **§4.1:** spotlessJavaCheck clean (esql + compute); esql + compute compile green.
- **§4.2 (committed HEAD):** `compute.operator.fusion.*` (incl. new StitcherArithmeticVectorInterfaceTests + Task 1-3 added cases), `EvalOperatorTests`, `evaluator.fusion.*` (incl. new VectorInputSpecialisationTests + renamed interface-path wiring test) all green. BUILD SUCCESSFUL.
- **§4.3 smoke (bench-results/T4/smoke):** strategy 56, slowpath 56; no AssertionError/exception in txt; compare vs T0 = 112 cells, **0 baseline cells not rerun**. 21 ">10%" flags are single-iteration noise (ignored per §4.3).
- **No new failing cell vs T0.**

## Scope recap
- Added ALL-INTERFACE siblings (`fusedInterface` / `fusedRawInterface`) to PLAIN_VECTOR and CHECKED_VECTOR so CONST_COL / BIGARRAY (any `asVector()!=null`) inputs fuse through the hidden class's own typed `*Vector.getX` INVOKEINTERFACE sites instead of falling to the per-row block path.
- `eval` dispatch per page: all inputs exact `*ArrayVector` -> RAW (VectorUnsafe); else all `asVector()!=null` -> INTERFACE; else BLOCK. Pinned by `LastEvalPath` test seam.
- Checked interface path preserves Task 3 fusedRaw-first / abandon-to-builder overflow semantics -> null + warning parity with unfused.
- RAW method governs the inline/split budget decision (hot path stays inline); interface fallback rides along.
- SIMD plain pages keep the block path (no interface SIMD variant) — documented deviation.

## Reviewer pass
- No blocking findings. Hardened: RAW-only inline/split gate (restores Task 3 inline guarantee for deep trees whose INTERFACE variant overflows the inline heuristic); two-sided structural assertions (interface has typed getters + zero VectorUnsafe; raw has VectorUnsafe + no getters); null builder-spreader guard in evalCheckedVector; renamed `testConstantVectorInputRoutesThroughInterfacePath` + pins `LastEvalPath.INTERFACE`; doc corrections; deleted unused ctor overload.
- Follow-up (non-blocking): a mixed class (inline RAW + split INTERFACE) could keep the interface method inlinable too; left simple.

## Expected perf effect (deferred to §9 — NOT measured here)
- **M6** CONST_COL_ADD_MUL_LONG / BIGARRAY_ADD_MUL_LONG, polluted: interface variant avoids the shared-accessor profile pollution and the per-row block path. Reference (clean laptop) fused/unfused 0.56 / 0.71; polluted numbers from §9.
- **M7** no-regression on ADD_MUL_SUB_LONG / CHAIN8_LONG SPLICED paths (RAW variant byte-identical; inline preserved).

**VERDICT: PASS**
