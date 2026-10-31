# Task 6 post-task check (§4.5)

- **HEAD:** `23f5e82b2bbc37198ca3e1352dd01a8a1f1a3434` — "Release exact breaker reservation on eval close"
- **§4.1:** spotlessJavaCheck clean (esql + compute); compiles green.
- **§4.2 (committed HEAD):** `compute.operator.fusion.*` (85), `EvalOperatorTests` (incl. new breaker-drift test), new `FilterEvalOperatorTests` (breaker-drift), `evaluator.fusion.*` (147, incl. new real-adaptive end-to-end breaker test + all Task 1-5 added cases) all green. BUILD SUCCESSFUL.
- **§4.3 smoke (bench-results/T6/smoke):** strategy 56, slowpath 56; no AssertionError/exception in txt; compare vs T0 = 112 cells, **0 baseline cells not rerun**. 15 ">10%" flags are single-iteration noise (ignored per §4.3).
- **No new failing cell vs T0.** (Task 6 changes only breaker accounting at operator close; no evaluation output changes.)

## Scope recap
- `EvalOperator` and `FilterEvalOperator` snapshot `BASE_RAM_BYTES_USED + evaluator.baseRamBytesUsed()` into a final `reservedBytes` field at construction and release exactly `-reservedBytes` at close, instead of re-reading `baseRamBytesUsed()` (which an adaptive evaluator grows after switching to the fused path mid-query, causing a negative breaker drift).
- Documented the snapshot contract on `ExpressionEvaluator.baseRamBytesUsed()`.

## Reviewer pass
- No blocking findings. NIT taken (contract javadoc). Non-blocking follow-up recorded (see below).

## Fail-first evidence
- `LimitedBreaker: total must be >= 0 but was [-1008]` at close (reserve BASE+16, old close released BASE+1024). Both new operator tests reproduced it before the fix.

## Expected perf effect (deferred to §9)
- None. Breaker-accounting correctness fix; no fused evaluation path or hot-loop bytecode changes.

**VERDICT: PASS**
