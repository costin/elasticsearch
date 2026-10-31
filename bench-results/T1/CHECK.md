# Task 1 post-task check (§4.5)

- **HEAD:** `73172cba9c336a8b4e465fb61b3e90a8be85dc39` — "Keep encoding fast path for fused TO_LOWER/UPPER"
- **§4.1:** spotlessJavaCheck clean; esql compileJava/compileTestJava + benchmarks compileJava green.
- **§4.2 (committed HEAD):** `compute.operator.fusion.*`, `EvalOperatorTests`, `evaluator.fusion.*` all green (BUILD SUCCESSFUL). Includes the new `MappingEncodingGuardTests` (4 tests).
- **§4.3 smoke (bench-results/T1/smoke):** strategy.json 56 cells, slowpath.json 56 cells; no AssertionError/exception in either .txt. `compare.py --base T0/smoke --new T1/smoke`: 112 cells, **0 baseline cells not rerun** (no missing cells). The 14 ">10%" flags are single-iteration timing noise — ignored per §4.3.
- **No new failing cell vs T0** (KNOWN_FAILURES.md still empty).

## Expected perf effect (deferred to §9 — NOT measured here)
- **M1** SlowPath TO_LOWER_ORDINAL fused/unfused ≤ 1.5: the guard defers ordinal/constant pages to the unfused evaluator, so the ~426x regression is expected to collapse to ≈1.0. (Correctness that the guard engages is proven: fused output is ordinal-encoded, which only the unfused fallback can produce.)
- **M2** TO_LOWER_PLAIN ≤ 1.10: plain input stays fused — unchanged.
- **M7** no regression on arithmetic/logical SPLICED paths: Task 1 touches only the mapping path, so other paths are untouched.

Note: the cold smoke TO_LOWER_ORDINAL fused number (~122 ns/row, 0 warmup / 1 iteration) is startup/measurement overhead of the fused wrapper deferring to a lazily-built fallback and is NOT meaningful; §4.3 timings are never reported as results.

**VERDICT: PASS**
