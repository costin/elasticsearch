# ES|QL fusion stabilization — performance hand-off (§9)

Performance was **deliberately not gated** in the stabilization loop: it ran on a shared,
noisy cloud host, so the loop gated on correctness only (tests + structural bytecode tests +
benchmark *smoke* runs whose setup asserts fused == unfused). The timings below must be measured
**separately, on a quiet dedicated host**, by a human or a separate agent. The smoke JSONs under
`bench-results/T*/smoke/` are correctness artifacts only — **never report their timings**.

## SHAs

- `T0_SHA` (reference / harness-in-tree): `75b9aad906ceee991c7d4e5cb250e16c701d21da`
- **Final HEAD** (all tasks applied): `23f5e82b2bbc37198ca3e1352dd01a8a1f1a3434`
  - Task 1 `73172cba9c3`, Task 2 `d334ed8e949`, Task 3 `70c536d4fa7`, Task 4 `54ab96829ad`,
    Task 5 `507163d24ac`, Task 6 `23f5e82b2bb`.
- Branch: `esql/fix/compute-codegen-stabilize-bf28`.

## DONE tasks and the perf measures each is expected to move (from §3.2 / §7)

| Task | Change | Expected measures | Reference (Task 0, dedicated host) |
|---|---|---|---|
| 1 | Per-page encoding guard for fused TO_LOWER/TO_UPPER (keep ordinal/constant fast path) | **M1** (≈426× → ≤ 1.5), **M2** unchanged (≈1.03), **M7** no-regress | TO_LOWER_ORDINAL fused/unfused 426 (laptop) |
| 2 | Dense logical vector path (raw/interface reads, `boolean[]` output, no 3VL on dense pages) | **M3** (≤ 1.0), **M4** (≤ 1.3), **M5** (≤ 1.0), **M7** | AND_CMP_LONG SPLICED TYPES 13.43/14.81 vs handwritten 0.94–1.11 ns/row |
| 3 | Raw-array dense output for checked/plain vector arithmetic (fusedRaw-first, abandon-to-builder on overflow) | **M7**, **M9** (≤ 1.2), **M10** (≤ 1.15) | Xeon ADD_MUL_SUB_LONG SPLICED 3.53/7.33 (NONE/TYPES) vs HANDWRITTEN_RAW 2.90/2.37 |
| 4 | Per-input specialisation: all-interface vector variant for CONST_COL/BIGARRAY inputs | **M6** (≤ 0.9), **M7** | CONST_COL/BIGARRAY_ADD_MUL_LONG fused/unfused 0.56/0.71 clean (laptop); polluted from §9 |
| 5 | Negative stitch cache + log-once | none (robustness / log-noise) | — |
| 6 | Release exact breaker reservation on eval close | none (breaker-accounting correctness) | — |

M7 = no regression (within +10% of T0 **measured on the same host**) on ADD_MUL_SUB_LONG and
CHAIN8_LONG SPLICED (NONE, TYPES) and AND_CMP_LONG SPLICED NONE. M8 is now correctness (C1–C5).

## Exact §9 commands — DO NOT run in the loop; run on the dedicated host

Quiet bare-metal-like instance (c7i.4xlarge x86 / c7g.4xlarge ARM), one JMH process at a time,
pinned (`taskset`), nothing else running. JDK 25, `-Xms4g -Xmx4g -XX:+AlwaysPreTouch
-XX:ActiveProcessorCount=8`.

1. Check out `T0_SHA`, build, run both commands into `bench-results/perf/T0/`.
2. Check out the final HEAD (`23f5e82b2bb`), build, run both into `bench-results/perf/final/`.
3. Compare + compute M1–M10.

```
./gradlew -p benchmarks installDist -q

# <dir> = bench-results/perf/T0  (on T0_SHA)  then  bench-results/perf/final  (on final HEAD)
./bench.sh FusionStrategyBenchmark.run -p shape=ADD_MUL_SUB_LONG,CHAIN8_LONG,AND_CMP_LONG \
  -p strategy=UNFUSED,SPLICED,HANDWRITTEN,HANDWRITTEN_RAW -p pollute=NONE,TYPES \
  -f 3 -wi 3 -w 1s -i 3 -r 1s -foe true -rf json -rff <dir>/strategy.json -o <dir>/strategy.txt

./bench.sh SlowPathFusionBenchmark.run \
  -p shape=TO_LOWER_ORDINAL,TO_LOWER_PLAIN,OR_CMP_LONG,AND_CMP_DOUBLE,CONST_COL_ADD_MUL_LONG,BIGARRAY_ADD_MUL_LONG \
  -p fusion=true,false -p pollute=false,true \
  -f 3 -wi 3 -w 1s -i 3 -r 1s -foe true -rf json -rff <dir>/slowpath.json -o <dir>/slowpath.txt

python3 -I bench-results/compare.py --base bench-results/perf/T0 --new bench-results/perf/final \
  --md bench-results/perf/compare.md
```

Settings notes: a measure passes if the ratio of means meets the threshold and the 99.9% error
intervals don't make the opposite conclusion obvious; if an error exceeds 10% of its score, rerun
that cell with `-f 5`. M7 compares final vs T0 **from the same host**, never against the Task 0
reference tables. Optional extra: C1-only (`-jvmArgsAppend -XX:TieredStopAtLevel=1`) for the
strategy command to show the logical-path C1 behaviour (Task 0 Xeon: SPLICED AND 25 vs unfused 20).

About 30 min per checkout.
