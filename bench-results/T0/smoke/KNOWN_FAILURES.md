# T0 smoke known failures

Start-up smoke (§4.3) run on the cloud host at the start of the loop.

- `FusionStrategyBenchmark.run` (pollute=NONE,TYPES): 56/56 cells present, no AssertionError/exception. Every strategy's output equals unfused and SPLICED fused.
- `SlowPathFusionBenchmark.run` (pollute=false,true): 56/56 cells present, no AssertionError/exception. Every case's fused output equals unfused.

No known failing cells. Later per-task smoke checks must not add new failing cells.
