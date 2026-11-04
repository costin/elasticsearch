# P1.B/C — Rebase of the fusion stack onto `main`

## Scope

The four fusion commits were squashed on the old base `0bd981523a66` (P1.A) and
then rebased onto fetched `main` `174890c2e3a` (P1.B). This file records the
kernel-contract drift that the rebase had to absorb (`@Fusable` `@Evaluator`
descriptors, base vs main) plus every non-trivial conflict resolution. The four
commits keep their identity after the rebase:

1. `[ESQL] Fusion metadata: @Fusable and descriptors`
2. `[ESQL] Fusion stitcher and bytecode emitters`
3. `[ESQL] Fusion planner for EVAL expressions`
4. `[ESQL] Fuse WHERE combined with a following EVAL`

The merge base of the stack is `main` `174890c2e3a`; the P1.A squash base was
`0bd981523a66`.

## Kernel contract table — `@Fusable` `@Evaluator` descriptors, base vs main

`main` introduced the PromQL lenient double path: every `@Fusable` expression
that now implements `org.elasticsearch.xpack.esql.capabilities.NonFiniteSupport`
grew a trailing `@Fixed(includeInToString = false) boolean allowNonFinite`
parameter on its scalar **double** kernel. The integer/long kernels of the same
operators are unchanged (integer arithmetic cannot produce a non-finite value).
The trailing boolean is a per-expression flag, not one of `exp.children()`, so
the planner bakes it as a bytecode immediate and appends it as the kernel's last
operand (see "allowNonFinite baking" below).

| `@Fusable` function | kernel (`@Evaluator`) | base descriptor | main descriptor |
|---|---|---|---|
| `Add`  | `processDoubles` | `(DD)D`  | `(DDZ)D` |
| `Sub`  | `processDoubles` | `(DD)D`  | `(DDZ)D` |
| `Mul`  | `processDoubles` | `(DD)D`  | `(DDZ)D` |
| `Div`  | `processDoubles` | `(DD)D`  | `(DDZ)D` |
| `Mod`  | `processDoubles` | `(DD)D`  | `(DDZ)D` |
| `Log`  | `process` (1-arg) | `(D)D`  | `(DZ)D`  |
| `Log`  | `process` (2-arg, base) | `(DD)D` | `(DDZ)D` |
| `Log10`| `process` | `(D)D` | `(DZ)D` |
| `Pow`  | `process` | `(DD)D` | `(DDZ)D` |
| `Sqrt` | `process` | `(D)D` | `(DZ)D` |
| `Acos` | `process` | `(D)D` | `(DZ)D` |
| `Acosh`| `process` | `(D)D` | `(DZ)D` |
| `Asin` | `process` | `(D)D` | `(DZ)D` |
| `Atanh`| `process` | `(D)D` | `(DZ)D` |
| `Cosh` | `process` | `(D)D` | `(DZ)D` |
| `Sinh` | `process` | `(D)D` | `(DZ)D` |

Unchanged (no `NonFiniteSupport`, no descriptor drift): `Abs`, `Atan`, `Atan2`,
`Cast`, `Cbrt`, `Ceil`, `Cos`, `Exp`, `Floor`, `Hypot`, `Neg`, `Signum`, `Sin`,
`Tan`, `Tanh`, `ChangeCase`, `Length`, `Concat`, `Space`, and the comparisons
`Equals`/`NotEquals`/`GreaterThan`/`GreaterThanOrEqual`/`LessThan`/`LessThanOrEqual`.
Integer/long/unsigned_long arithmetic kernels (`processInts`/`processLongs`/…)
are also unchanged.

### Type / symbol moves on `main`

* New interface `…capabilities.NonFiniteSupport` with `boolean allowNonFinite()`,
  `readNonFinite(StreamInput, TransportVersion)`, `toStrictVariant()`, and the
  `ESQL_PROMQL_NON_FINITE_ARITHMETIC` transport version. Implemented by `Add`,
  `Sub`, `Mul`, `Div`, `Mod` and the math functions above.
* New `*NonFiniteDoublesByConstant` evaluators (`DivNonFiniteDoublesByConstant`,
  `ModNonFiniteDoublesByConstant`) selected by the constant-RHS fast path when
  `allowNonFinite` is set; the strict path keeps `*DoublesByConstant`.
* The 4-arg `Add`/`Sub` and 3-arg `Mul`/`Mod` and 4-arg `Div` constructors all
  default `allowNonFinite = false`, preserving ES|QL's strict divide-by-zero /
  finite-only contract outside PromQL.

## Non-trivial conflict resolutions

All resolutions keep `main`'s behaviour and re-apply the fusion hooks; the
fused-vs-unfused differential suites guard every semantic claim below.

### 1. `Div.processDoubles` — two returns → single terminal return (commit 1)

`main` wrote `Div.processDoubles` with an early `return lhs / rhs;` for the
`allowNonFinite` branch plus a final `return NumericUtils.asFiniteNumber(lhs /
rhs);` — **two** terminal returns. The stitcher inliner requires exactly one
terminal return (multiple/early returns are extra loop exits the fused loop
cannot honour), so the double `Div` kernel could not be inlined and fell back to
the unfused chain.

Resolution: rewrite the body to a single terminal return while preserving
behaviour **exactly** (including the distinct `"/ by zero"` message for a zero
divisor vs `asFiniteNumber`'s "not a finite double number" for an overflow):

```java
if (allowNonFinite == false && rhs == 0.0) {
    throw new ArithmeticException("/ by zero");
}
return allowNonFinite ? lhs / rhs : NumericUtils.asFiniteNumber(lhs / rhs);
```

This matches the single-return guard-throw shape every sibling `NonFiniteSupport`
kernel already uses on `main` (`Add`/`Sub`/`Mul` ternary; `Mod`/`Log`/`Pow`/
`Sqrt`/`Acos`/… a guard that *throws* when `allowNonFinite == false` then one
`return`). Div's two-return form was the sole outlier. Guarded by
`FusionSpecDifferentialTests` (`testDoubleDivisorIsTrailingParameterNotImmediate`,
`testDoubleNonFiniteWithNonZeroDivisorMatchesUnfused`, `testConstantDivisorDiv…`)
and the new `testDoubleArithmeticAllowNonFiniteMatchesUnfused`.

### 2. `allowNonFinite` baked as a trailing `Z` constant (commits 2 + 3)

* `FusionNode.Constant.baked(value, element)` now accepts element `'Z'` (a
  Boolean backing a trailing `@Fixed boolean`). `Stitcher.pushBakedImmediate`
  pushes it as `iconst 0/1`; `elementSortMatches` maps `'Z'` → `Type.BOOLEAN`.
* `FusionPlanner`: for any `NonFiniteSupport` kernel whose descriptor ends in
  `Z`, the leading numeric args drive homogeneity/arity (trailing `Z` excluded)
  and the flag is appended as `FusionNode.Constant.baked(allowNonFinite(), 'Z')`.
  It enters the cache **shape key** as `#C:Z:true` / `#C:Z:false`, so the strict
  and non-finite variants never share a fused class (guarded by the new
  `testAllowNonFiniteVariantsResolveToDistinctClasses`).
* `bakedDivModDescriptor` DOUBLE case `(DD)D` → `(DDZ)D` so the constant-RHS fast
  path (whose unfused factory is the non-`FusionAware` `*DoublesByConstant`)
  stitches `process*` with the baked divisor **and** baked flag.
* Decision per P1.C: the flag is baked as a planner constant and fusion is **not**
  refused when it is `true`.

### 3. ASM 9.11.1 `ComputeLimits` "Too many operations" (commit 2)

`main` pulled in a newer ASM whose `COMPUTE_FRAMES` frame computation enforces an
operation budget (`org.objectweb.asm.ComputeLimits` → `LimitExceededException:
Too many operations`). A deep, branch-heavy 3VL logical nest exhausts that budget
during `classNode.accept(writer)` — before our explicit 8000-byte huge-method
guard can measure the method — so the raw `LimitExceededException` leaked out of
`serializeWithBudgetGuard` (the end-to-end and `StitcherBudgetTests` logical
over-limit tests failed; the straight-line plain-vector path still trips the byte
guard first, so it was unaffected).

Resolution: `Stitcher.serializeWithBudgetGuard` catches `LimitExceededException`
and re-surfaces it as the same huge-method `StitchingException` (the fused method
is too large/branch-heavy to even serialize, so it would never JIT either). The
refusal stays a deterministic stitch failure → unfused fallback + negative cache,
and the WARN is actionable instead of an opaque ASM internal. `assertBudgetRefusal`
and `testOverLimitLogicalRefusesToFuseAndFallsBackEndToEnd` accept both the
measured-size and the frame-budget form of the refusal.

### 4. `StackOverflowError` catch removed — plan-time depth cap (commit 3)

`main` added a forbidden-API rule banning `catch (StackOverflowError)` ("track
recursion depth manually instead"). `FusedClassCache.load` used to negatively
cache a `StackOverflowError`. Resolution: drop the SOE catch (it now falls to the
`VirtualMachineError` branch → transient `TransientStitchError`, uncached +
retried) and add a plan-time `FusionPlanner.MAX_FUSION_TREE_DEPTH = 500` cap that
refuses any tree whose depth would risk overflowing the emit walk, so the stack
can never overflow in the first place. `FusedClassCacheTests` updated:
`testStackOverflowErrorIsTransientAndRetries`.

### 5. Warnings routed to the per-driver `DriverContext` sink (commit 3 tests)

`main` routes an evaluator's warnings to its `DriverContext`'s per-driver sink
(`WarningsMode.COLLECT`; `warnings()` requires a terminal `finish()`), not the
thread context. The differential harness read warnings from the thread context.
Resolution: `FusionDifferentialTestCase` now evaluates each factory on its own
fresh `DriverContext` (`evalCapturing`/`freshDriverContext`/`warningsOf`) and the
former `drainWarnings()` is removed; every call site was converted. Keeps the
fused and unfused warning lists independent for the ratified subset invariant.

### 6. Cross-module descriptor tests (commit 3 tests)

`StitcherCrossModuleTests`, `StitcherOverflowCrossModuleTests`,
`StitcherBlockCrossModuleTests` and `ArithmeticFactoryFusionAwareTests` hard-coded
the real `Add.processDoubles` contract; updated to `(DDZ)D` + a baked `Z` child +
a `boolean`-param oracle + a random/false `allowNonFinite` at invocation.

### 7. Structural rebase conflicts (resolved keeping `main`)

`EvalMapper`, `LocalExecutionPlanner`, `EsqlPlugin`, and the per-operator kernel
files (`Add`/`Sub`/`Mul`/`Div`/`Mod` and the math functions) conflicted because
`main` edited the same regions (the `allowNonFinite` wiring and evaluator-factory
signatures). Each was resolved by taking `main`'s code and re-applying the fusion
hook on top (the `@Fusable` annotation, the `FusionPlanner.maybeFuse` entry point
in `EvalMapper`, the fusion compilation-service wiring in `EsqlPlugin`/
`LocalExecutionPlanner`), never reverting a `main` behaviour change.
