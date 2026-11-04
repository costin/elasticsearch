# C1 rebase onto upstream/main

Date: 2026-11-04

## Before

- Fusion base: `174890c2e3a0` (`Update wolfi (versioned) (#164527)`)
- Fusion tip: `d89fef927d23`
- 4 commits:
  1. `f79e2bfddb42` `[ESQL] Fusion metadata: @Fusable and descriptors`
  2. `889fb67de492` `[ESQL] Fusion stitcher and bytecode emitters`
  3. `dab192af7fa6` `[ESQL] Fusion planner for EVAL expressions`
  4. `d89fef927d23` `[ESQL] Fuse WHERE combined with a following EVAL`

## Overlap (`git diff 174890c2e3a0 upstream/main --stat -- x-pack/plugin/esql`)

`upstream/main` was 31 commits ahead of fusion base (`502cbab322e2`).

21 files changed under `x-pack/plugin/esql` (668 insertions, 355 deletions). None of those files appear in the 4 fusion commits. Intersection of main-touched esql files vs fusion-touched files: empty.

Likely conflict files from the plan (`LocalExecutionPlanner`, `EvalMapper`, `EsqlPlugin`, `EvalOperator`, `@Fusable` kernels) were **not** among the 21.

No `@Fusable` kernel body changes on main in this window.

## Rebase

```
git fetch upstream main
git rebase upstream/main
```

Result: clean, 4/4, no conflicts.

## After

- New base: `502cbab322e2`
- New tip: `6ee7f77c415a`
- 4 commits:
  1. `311a99e1cfe7` `[ESQL] Fusion metadata: @Fusable and descriptors`
  2. `ddafd91f49f2` `[ESQL] Fusion stitcher and bytecode emitters`
  3. `13e640727c55` `[ESQL] Fusion planner for EVAL expressions`
  4. `6ee7f77c415a` `[ESQL] Fuse WHERE combined with a following EVAL`

## C1 gate

| command | exit | log |
|---|---|---|
| `./gradlew :x-pack:plugin:esql:compute:gen:test :x-pack:plugin:esql:compute:test :x-pack:plugin:esql:test --tests "*fusion*"` | 0 (9m 50s) | `C1-test.log` |
| `./gradlew :x-pack:plugin:esql:precommit :x-pack:plugin:esql:compute:precommit :x-pack:plugin:esql:compute:gen:precommit :benchmarks:precommit` | 0 (1m 29s) | `C1-precommit.log` |

## C8 rebase

`git fetch upstream main && git rebase upstream/main` — clean, 4/4.

New base: `dd00717db9f7` (`Mapper.Builder implements ToXContentFragment (#164463)`).
New tip after rebase: see READY.md.

