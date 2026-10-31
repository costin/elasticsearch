# Task 5 post-task check (§4.5)

- **HEAD:** `507163d24ac16e03d29d5be38a833cb3516d7a6a` — "Negatively cache fused stitch failures"
- **§4.1:** spotlessJavaCheck clean (esql + compute); compiles green.
- **§4.2 (committed HEAD):** `compute.operator.fusion.*` (85), `EvalOperatorTests`, `evaluator.fusion.*` (146, incl. new FusedClassCacheTests negative-cache/log-once/bounded/stampede cases + all Task 1-4 added cases) all green. BUILD SUCCESSFUL.
- **§4.3 smoke (bench-results/T5/smoke):** strategy 56, slowpath 56; no AssertionError/exception in txt; compare vs T0 = 112 cells, **0 baseline cells not rerun**. 16 ">10%" flags are single-iteration noise (ignored per §4.3).
- **No new failing cell vs T0.** (This task changes no evaluation output — only stitch-failure caching and log levels — so smoke parity is expected.)

## Scope recap
- `FusedClassCache` negatively caches a `StitchingException` as a lightweight `Failure(causeClassName, message)` marker: a failing shape is attempted once per node lifetime (bounded by MAX_ENTRIES + eviction; an evicted failing shape re-attempts once). Repeats rethrow a reconstructed StitchingException without invoking the stitcher. New `failures()`/`negativeHits()` counters.
- Single plan-time WARN now emitted once per shape by the cache (first-failure transition under the entry monitor); repeats at DEBUG. Downgraded the now-duplicate WARNs to DEBUG: `Stitcher.defineOrThrow`, the budget-guard enforce branch, the expression (`FusedExpressionEvaluatorFactory`) and filter-eval (`FusedFilterEvalEvaluatorFactory`) plan-time fallback catches. Unchecked RuntimeException/Error stay transient (entry dropped). Runtime (eval-time) link WARNs left as WARN.

## Reviewer pass
- No blocking findings. Hardened: removed the leftover filter-eval duplicate WARN; downgraded the budget-guard enforce WARN so the cache is the sole plan-time WARN (byte-size detail preserved via the StitchingException cause); negative-hit DEBUG now includes the stored cause class; added cause-drop contract assertions, a bounded-negative-cache test, and a concurrent failing-stampede test; doc fixes.
- Confirmed no compute fusion test asserted the budget WARN at WARN level.

## Expected perf effect (deferred to §9)
- None. Task 5 is a fallback-robustness / log-noise fix; it does not change any fused evaluation path or hot-loop bytecode.

**VERDICT: PASS**
