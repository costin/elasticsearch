# P0 follow-up JMH smoke (plan-time + hot-path invokeExact)

Host caveat: run on the shared Cursor cloud loop VM (memory-ballooned, noisy). These are **1-fork
smokes** (`-f 1 -wi 2 -w 1 -i 3 -r 1`, one `shape=ADD_MUL_LONG`, `strategy=SPLICED`, `pollute=NONE`),
so JMH's 99.9% CI is wide by construction. They establish that JMH runs here and record the magnitudes;
they are **not** a statistically resolved A/B. Correctness/equivalence of every commit is proven instead
by the per-commit fusion gate and the phase-end CsvIT verify-on (7657 tests, 0 failures, every fused page
A/B'd against the unfused fallback).

Same command and host before and after (only the checked-out commit differs). Raw JSON in this directory.

## Plan-time: `planCached` (planHit, warm class cache) and `planMiss` (cold cache)

| bench | before `2871861922e` (pre-ES-`Cache`) | tip `d1618bc5d2d` |
|---|---|---|
| planCached (planHit) | 9.498 ± 150.528 us/op | 4.863 ± 33.591 us/op |
| planMiss | 276.117 ± 775.350 us/op | 279.779 ± 816.970 us/op |

`2871861922e` ("Make esql.fusion.enabled a dynamic setting") predates the weight-bounded `org.elasticsearch
.common.cache.Cache` + doorkeeper (added in `9bd6ce21c1d`), so its `planCached` uses the old unlocked map.
The instruction anticipated a `planHit` **slowdown** at tip from `Cache.get`'s global LRU lock. On this host
that effect is **not resolvable**: the smoke error bars are 10–30× the means and the two CIs overlap by an
order of magnitude (the tip mean is even nominally lower). A clean measurement needs a quiet host with
multiple forks; the lock cost itself is real and documented in `FusedClassCache` (global LRU promotion on
every `get`), it just does not surface above this host's noise.

## Hot path: `run()` throughput (the invoke→invokeExact spreader change)

| bench | before `d3f349373a6^` = `af5495b2659` | tip `d1618bc5d2d` |
|---|---|---|
| run (per position) | 3.337 ± 58.017 ns/op | 1.553 ± 6.104 ns/op |

`d3f349373a6` ("P0.7 hygiene") carried a `MethodHandle#invoke`→`invokeExact` change; its parent
`af5495b2659` is the before. Again noise-dominated (error 4–17× the mean, CIs overlap), consistent with the
prior session's invokeExact A/B (commit `9d35e669dc9`, recorded in `FINAL.md` Phase P0) which was likewise
"indistinguishable within error". The change is kept because `invokeExact` is strictly the correct, lower-
overhead call once the spreaders are erased to `Block`/`Vector` (no runtime signature adaptation), not on the
strength of a host-resolvable microbenchmark delta.
