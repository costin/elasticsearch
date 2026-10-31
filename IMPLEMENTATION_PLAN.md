# ES|QL fusion follow-up (iteration 2) — implementation plan

Orchestrator-owned tracking doc. Git-excluded. The authoritative specs are the
uploaded plan + addendum + iteration-3 notes:
- base: `esql-fusion-followup-plan_a42f.md`
- addendum 1: `esql-fusion-followup-addendum_b688.md` (supersedes `_b9d8`/`_23d3`)
- iteration-3: `esql-fusion-followup-iter3_580e.md` — NEW work + fixes + round-B
  decisions. Its §B REPLACES the main plan's §8 ("filled in by the human"), so
  the previously-deferred items are now decided (see Scope). Its §G lists fixes
  to already-delivered work.

## Branch / lineage

- Code branch: `esql/fix/compute-codegen-followup`, based on
  `esql/compute-codegen-stabilize` (`454347f117f`).
- Evidence branch: `esql/fix/compute-codegen-followup-evidence` (markdown only).
- Direct push, no PR (per operator directive).
- **Deviation D0:** plan §0 says base off `23f5e82b2bbc`. Based off the clean
  `esql/compute-codegen-stabilize` (`454347f117f`) instead — identical fusion
  code, minus the unrelated cloud-dev-env AGENTS.md lines and the cherry-picked
  stabilization lineage. Chosen for clean history after the operator restructured
  the branches. Harness commit on this lineage: `fd85ec1f9cf` (== `75b9aad906c`).

## Roles / loop (plan §0, unchanged)

Orchestrator (no production code) + implementer + reviewer (`/java-code-reviewer`),
both on `cursor-grok-4.6-high`. Test-first; max 3 attempts/task then BLOCKED;
commits 50/72, author+committer "Costin Leau <costin@users.noreply.github.com>",
no Co-Authored-By / AI trailers; never `--no-verify`; never gate on timings.

## Scope (after iteration-3 §B decisions)

Phase A is DONE + pushed (code `50258d8aa18`, evidence `c87de31d30e`).

iteration-3 §0 execution order (remaining), adapted because Phase C was already
started when iter-3 arrived (operator said: finish the current task, then
incorporate):
1. **§G fixes** — G1, G5, G7, G9 (G2/G6/G4 already delivered by Phase A's later
   commits; G3 is a process rule, G8 is explicitly not-this-iteration).
2. **E3-logical** — logical vector path INTERFACE-only; delete `LOGICAL_VECTOR`'s
   RAW method + dispatch + structural-test RAW expectations. Acceptance: AND_CMP
   NONE & TYPES ≤ 1.10 ns/row (human re-measures).
3. **Phase C** — C1+C2 (verify mode; IN FLIGHT), C3 (debug info), C4 (gate +
   CsvIT wall time + generator encoding coverage). Unchanged from main plan.
4. **D1 -> D2(X2) -> D3(GO)+F3 -> D4.** (F1 uses X2's `fusionFixedArgs()`, so
   F-phase follows D2.) D3 acceptance: EQ & LENGTH over TO_LOWER on ordinals ≤ 1.5
   ns/row fused, plain cell unchanged (perf deferred to the benchmark round).
   **ORDER SWAP (agent, engineering grounds): D3 is executed before D4, not after
   as iter3 §0 lists.** D4 removes the depth-1 mapping exemption so lone TO_LOWER
   stays unfused; with buildMapping still root-only before D3, that would make ALL
   mapping fusion dead and FORCE deleting D1's `MappingEncodingAwareTests` + D2's
   isFused assertions, only to re-add them in D3. The plan §5 D4 text ("After
   D1/D3…", "parity coverage moves to D2's tests") is only satisfiable with D3
   present. Running D3 first reaches the identical end-state with monotonically
   growing coverage: D3 adds the depth-≥2 chain path (lone TO_LOWER still fuses via
   the exemption, chains now fuse too); D4 then deletes the exemption so only chains
   fuse. No dead-code/broken-test window.
5. **F1 (by-constant Div/Mod), F2 (logical GTE/LTE + Not), F4 (coverage probe).**
6. **E4' (revised GO)** — delete the `compute()` split + its tests + the
   `max_method_bytecodes` split branch; replace the 325 enforce budget with a
   single 8000-byte huge-method guard (`DontCompileHugeMethods`) on every path
   that enforces today (plain split, logical block, filter-eval). Lifts the 325
   refusals that drop deep logical / filter-eval trees.
7. **E6/E7** — Stitcher split, after steps 2 and 6 delete their code.

### SCOPE CHANGE (operator, 2026-10-31): no timing runs for the rest of this iteration

All perf measurements move to a separate benchmark round the human runs on a
dedicated host after this iteration. Do NOT wait for, run, or gate on any
timing number. Benchmark work is limited to (1) adding the cells each item
asks for and (2) a G3 smoke of every new/changed cell
(`-f 1 -wi 1 -w 1s -i 1 -r 1s -foe true`, all params) that only checks setup
assertions pass and fused == unfused — numbers are neither read nor reported.
Still in scope (correctness, not timing): E1 class-dump diff before/after
E6/E7, and the F4 coverage-probe CsvIT run.

**Deferred to the benchmark round** (each skipped measurement + its plan
acceptance threshold; must be echoed in FINAL.md under this exact heading):
- **C4 CsvIT wall-time, verify on vs off** — threshold: if overhead > 15%,
  add page-sampling (first N pages/evaluator then 1-in-16). Skipped; no
  sampling added (verify checks every page).
- **G9 vector_reads static-final re-measure** — threshold: B2 auto cells
  within 3% of control (no per-page volatile regression).
- **E3-logical (logical vector INTERFACE-only)** — threshold: AND_CMP NONE &
  TYPES ≤ 1.10 ns/row.
- **D3 encoding-aware nested mapping** — threshold: EQ & LENGTH over TO_LOWER
  on ordinals ≤ 1.5 ns/row fused; plain keyword cell unchanged.
- **F1 by-constant Div/Mod** — threshold: fused `LONG_DIV_CONST` ((a*b)/1000)
  & `ROUND3` (ROUND(d*1.1,3)) not slower than unfused (jitConstant divisor
  baked as LDC/ICONST → C2 multiply-and-shift, several× cheaper than idiv).
- **E4' huge-method guard** — threshold: one cell of the deepest logical shape,
  fused vs unfused, fused not slower (split buys 0.2%; 325 budget protects
  nothing measurable).

§B decisions (replace main plan §8):
- **E2 (single-pass nulls mask): DROPPED** (B1 — per-page rerun cost, but fused
  still ~2x unfused on overflow pages; the design made no-overflow pages 34%
  faster).
- **E3-logical: GO** (B2 — logical RAW is the slow path; INTERFACE −22% on AND_CMP).
  Arithmetic keeps RAW (RAW wins 18% under type pollution).
- **E4': GO, revised** (B3 — split buys 0.2%; 325 budget protects nothing measurable).
- **E5 (direct invocation): DROPPED** (B4 — ratio moves because unfused speeds up
  on small pages, not a fused per-page cost).
- **D3 + F3: GO** (B6 — unfused `TO_LOWER(k)=="x"` on ordinals is 8.1 ns/row, 4x
  the threshold).
- **B5: closed** (C1-only AND regression gone: 26.4 -> 3.0 ns/row).
- Partial codegen / islands / cross-EVAL inlining: still out of scope (F0).
- Out-of-branch note (human): `TO_LOWER` on plain keyword is ~119 ns/row; an ASCII
  fast path in `ChangeCase` is a separate PR, not this branch.

## Task status

| Task | Status | SHA | Notes |
|---|---|---|---|
| A1 negative-cache all failure kinds + WARN once | Done | a751c9a8fe9 | reviewer clean; adaptive-switch + warn-once tests added |
| A2 WARN text/level matches consequence | Done | acfee35d83d | single WARN site logPlanTimeStitchFailure; filter-eval covered |
| A3 assert exactly-once WARN | Done (no commit) | - | absorbed by A1/A2: every first WARN paired with Unseen on 2nd plan at cache+factory; cache no longer WARNs |

### Open questions / follow-ups
- A2: a post-stitch `findStatic` signature miss (ReflectiveOperationException) is not negative-cached, so it WARNs on every plan rather than once. Should-never-happen generated-code contract bug; deferred rather than caching lookup failures. (reviewer A2, non-blocking)
- A6-hardening (queued, run single-threaded after A9 lands to avoid gradle contention on the compute test suite): reviewer flagged 3 non-blocking method-coverage holes (not owner-matching, so outside A6's literal "broaden the filters" scope): (1) StitcherArithmeticVectorInterfaceTests only scans `fusedInterface`, not the ALL-RAW `fused`, so a typed block/builder bounce in `fused` still slips (PLAIN only; CHECKED fusedRaw is covered by StitcherOverflowTests); (2) StitcherOverflowTests split test walks only the `fusedRaw` shell, not the lifted `compute()` body where per-row work lives; (3) the Arithmetic test class Javadoc overstates builder-forbidding coverage vs what the visitor actually scans. Fix by extending the visitors (and matching the Javadoc) once A9 is committed.
| A4 resetForTests in @Before | Done | 3af03f633e3 | FusionFallbackHardeningTests reset in A1; added to FusionDifferentialTestCase |
| A5 negative cache end-to-end via factory | Done | 9056a8f1597 | package-private injectFailureForPathForTests hook; FusionNegativeCacheEndToEndTests |
| A6 broaden structural owner filters | Done | 864564478a2 (+ hardening 66f701a3c60) | new ForbiddenHotPathOwners helper (isBuilderOwner/isBlockOwner); Arithmetic/Logical/Overflow tests broadened + INVOKEVIRTUAL gated; hardening scans PLAIN fused + split compute() body + Javadoc fix (reviewer 3 non-blocking findings) |
| A7 (commit-msg of 70c536d4 — fix at squash) | Note only | | record in FINAL |
| A8 CsvIT fusion on/off evidence | Done | (evidence c87de31d30e) | ON==OFF: 7657 tests / 0 fail / 252 skip on HEAD 50258d8aa18; summary in bench-results/TA/CHECK.md on evidence branch. (iter-3 G4: reviewer independently confirmed identical numbers.) |
| A9 bench hooks + FusionFollowupBenchmark | Done | 96205ababfe | vector_reads (FusionSettings+allExactArrayVectors) + max_method_bytecodes (Stitcher); declaresMethod/fusedClassHasMethod; 2 unit tests + FusionFollowupBenchmark; reviewer non-blocking (2 polish fixes applied) |
| C1 verify flag | Done | f603d158f62 | static-final VERIFY_ENABLED=Assertions.ENABLED; not a Setting; FusionBenchmarkGuard on all 5 fusion JMH @Setup; FusionSettingsVerifyTests. Reviewer: no blocking |
| C2 A/B compare in eval + filter eval | Done | e3ffe7a97b1 | FusionVerify (typed compare, doubleToLongBits, ordinal/BytesRef-const encoding, survivor-index compare, dump); DriverContext.ignoringWarnings; FusionDump thread-local capture. Gates: compute 10507/0, *fusion* 170/0. Reviewer: no blocking; 8 nits all applied |
| C3 debug info in generated code | Done | 7129f91cad5 | SourceFile + LineNumberTable (line=kernel index, post-order) + public static final String KERNEL_MAP ConstantValue on every fused class, via new FusedClassDebugInfo + Stitcher.spliceKernelBody. Class metadata only (no hot-loop opcodes, signatures/dispatch unchanged, budget still reads code_length). New StitcherDebugInfoTests (9, all paths, CheckClassAdapter + code_length-not-inflated). Gates: compute 10517/0, *fusion* 175/0, spotless clean. Reviewer non-blocking (2 nits applied: ofDescriptor/indexOf depth-1 caveat doc; stronger code-length assert) |
| C4 gate build + CsvIT wall time + coverage | Done | 9b3ff130815 | (a) Build-gating: already satisfied by C1 default (VERIFY_ENABLED=Assertions.ENABLED; FusionSettingsVerifyTests asserts verify ON under -ea), so existing test/internalClusterTest(CsvIT)/spec runs are the gate — no new gradle task. (b) CsvIT wall-time verify on vs off: DEFERRED to benchmark round (operator scope change 2026-10-31; no sampling added — verify checks every page). (c) Generator encoding coverage: extended FusionDifferentialTestCase (BigArray long/int/double + Constant into PLAIN_VECTOR) and BytesRefInputFusionDifferentialTests (OrdinalBytesRefVector) across the sweep; coverage matrix + residual gaps (D1 encoding-aware mapping; no BytesRefBigArrayVector type) in subagent report. Gate *fusion* 175/0, spotless clean |
| D1 generic encoding-aware mapping | Done | 9fc7295549d | plan-time encodingAware (1 BytesRef column, no warn kernel); eval runs fused chain once over ordinal dict (rewrap OrdinalBytesRefVector / gather primitive) or once for constant; Task-1 give-up removed. Reviewer non-blocking; ref-count neutral (breaker zero), handle sig confirmed. MappingEncodingGuardTests→MappingEncodingAwareTests (give-up→fusion-preserves-encoding). *fusion* 179/0 |
| D2 (X2) converters via annotations | Done | 436a84288bc1 | @Fusable now honoured on @ConvertEvaluator: shared FusionAwareGen helper emits FusionAware + FUSION_DESCRIPTOR(mapping=true) + fusionFixedArgs() on the convert Factory, byte-identical when @Fusable absent. FusionDescriptor gains `boolean mapping` (last component; 6/7-arg convenience ctors default false). FusionAware gains default fusionFixedArgs(). Planner routes on descriptor.mapping() via generic buildMapping (guard isAssignableFrom not ==, since ToLower/ToUpper extend ChangeCase; fixed-arg declared types parsed from kernelType to dodge enum-constant subclass). Deleted CHANGE_CASE_DESCRIPTOR + ChangeCase branch; @Fusable on ChangeCase.process. Fused bytecode unchanged (regen descriptor == old hand-written). **Admitted:** ChangeCase (TO_LOWER/TO_UPPER). **Refused:** UrlEncode/UrlEncodeComponent (THREAD_LOCAL @Fixed), UrlDecode (warnExceptions), ToVersionFromString (encodeVersion throws IAE on ≥128-digit valid keyword — throw-not-warn over dict), ToStringFromIP/FromVersion (non-KEYWORD input + can throw), other parse-y TO_* (warnExceptions/non-BytesRef). **D1-review throw-not-warn finding addressed:** admission rejects warnExceptions AND the per-converter audit excludes any converter that can throw on a valid-typed value over the dictionary (only ChangeCase's toUpper/toLower, which never throw, admitted). FusionAwareGenerationTests 6/6 (incl. byte-identical invariant + warnExceptions compile error), FusionDescriptorTests 8/8, compute *fusion* 100/0, esql *fusion* 180/0 |
| D4 remove depth-1 mapping exemption | Done | 8fc15245222 | Removed `ctx.mapping == false` from the depth-1 refusal guard so a lone TO_LOWER/TO_UPPER (always depth-1) stays unfused; a converter fuses only as a nested mapping kernel in a depth>=2 chain (D3). KEPT `ctx.mapping` (still set in buildMapping root branch, still read by `stringDepth1` to exclude a lone converter from the string-depth-1 opt-in), `plan.mapping()`, the `if (plan.mapping())`→`compileMappingBlockLoop` branch, and the Stitcher method (dead-but-valid: plan.mapping() now always false in production since a root mapping is always depth-1 and refused before a plan is built — Phase E prunes). DELETED the now-dead OUTER + INNER `FusedExpressionEvaluatorFactory` `mapping` fields + ctor params + all construction-site args. Flipped lone TO_LOWER/TO_UPPER fusion tests to assert non-fusion (value/warning parity vs unfused kept); D2 descriptor assertion relocated to the `LENGTH(TO_LOWER(k))` chain; renamed the misleading test methods; deleted unused `assertEncodingNoWeaker`. SlowPathFusionBenchmark TO_LOWER_ORDINAL/TO_LOWER_PLAIN now fusesToday()==false. Reviewer no-blocker (3 nits applied). esql *fusion* 200/0, compute *fusion* 100/0 |
| F1 by-constant Div/Mod fuse | Done | 9af28192aca | Bake a Div/Mod constant DIVISOR into the fused bytecode as an immediate (LDC/LCONST/ICONST/DCONST) so C2 strength-reduces the division, instead of a runtime-param LLOAD→idiv. KERNEL BODY STAYS the @Fusable `process*` (zero guard + overflowChecked try/catch), NOT `*ByConstant` — so div-by-zero (baked 0 → C2 folds `if(0==0)throw` → warn+null) and MIN/-1 overflow stay byte-identical to unfused; FusionVerify cross-checks. SELECTIVE baking: only the rhs (2nd) Literal of a Div/Mod kernel bakes (int/long/double; unsigned_long stays unfused — no @Fusable processUnsignedLongs); all other constants (comparisons, Add/Mul addends, lhs) stay runtime params and keep sharing a class. New `FusionNode.Constant.baked` flag (excluded from constantsInEmitOrder so no slot/param/MethodType/marshal, does not shift following non-baked slots); Stitcher pushBakedImmediate/pushLong/pushDouble at every emit site (vector/block/split-compute/comparison-helper/FilterEval) w/ elementSortMatches assert; appendShape adds the VALUE for baked (`#C:J:1000` vs `#C:J`) so x/1000 and x/500 get distinct classes. Since EsqlArithmeticOperation.toEvaluator returns the non-FusionAware `*ByConstant` factory for a foldable non-zero rhs, FusionPlanner.bakedDivModDescriptor SYNTHESIZES the `process*` descriptor (6-arg ctor, .equals the generated Div/Mod form). Mod.processInts/Longs/Doubles gained @Fusable(overflowChecked=true) (regenerated Mod*Evaluator confirmed byte-identical via clean regen). **ROUND fusion DEFERRED** (Round is non-@Fusable delegating expr; baking decimals yields no strength-reduction) — ROUND3 cell added as unfused baseline (fusesToday()==false). Bench LONG_DIV_CONST (fusesToday true) + ROUND3 G3-smoked (fused==unfused; numbers deferred). Reviewer no-blocker (4 nits applied, foldable()-recovery nit skipped: optimizer folds 2+3→5 pre-eval so Literal-rhs is the correct contract). compute *fusion*/*Stitcher* 102/0, esql *fusion* 207/0, gen 11/0. Perf acceptance (LONG_DIV_CONST/ROUND3 not slower than unfused) deferred to the benchmark round. |
| F2 logical GTE/LTE + Not | Done | F2a 5f29c3a5532 + F2b 880716d228d | **F2a (tests only, no prod change):** GTE/LTE already fuse (GreaterThanOrEqual/LessThanOrEqual @Fusable; simdComparison maps GTE→GE/LTE→LE; buildComparisonOperand descriptor-generic). Added deterministic >=/<= under AND/OR on both 3VL block + dense 2VL vector paths, an equal-operand boundary (a>=a true / a>a false catches a GT/GTE swap), and bench AND_CMP_GTE_LTE_LONG. **F2b (Not node):** ESQL's BooleanSimplification.maybeSimplifyNegatable returns null (no canonicalize, no De Morgan) so NOT(a>5) reaches fusion intact and previously did not fuse (NotEvaluator wrapper). New sealed `FusionNode.Not(child)` — child must be a boolean subtree (comparison Kernel w/ kernelType ending )Z, Logical, or nested Not; bare boolean column refuses; ctor-guarded). 3VL block path: flip 0↔1 keep-null (IF_ICMPEQ 2 → keep 2, else IXOR 1 — NOT 1-x/unguarded x^1); dense 2VL vector path: IXOR 1 (null/MV pages fall to block path). Not carries no descriptor, adds no warning slot (warnings stay on child kernels). depth(Not)=1+child so NOT(cmp) is depth-2 and fuses. Threaded through every node-kind site via `isBooleanLogic` helper (build/buildLogicalOperand/buildNot/depth/containsNestedMapping/countInputLeaves/hasWarningKernel/appendShape; FusionSignature.walk; Stitcher StackKernelEmitter/LogicalDfsEmitter.emitNot/assignComparisonHelpers/logicalInputType/rootReturnType/block+vector root guards/FilterEval predicate; FusedExpressionEvaluatorFactory routing; FusionCompilationService.rootElement; FusedClassDebugInfo). FilterEval fuses a Not predicate (survivor t!=1 → NOT null drops; Pred.NOT_GT in the null/MV sweep). Reviewer no-blocker (3 non-blockers + nits applied: distinct-source warning test, boolean-kernel ctor guard, FilterEval null/MV Not coverage, structural keep-2 branch assert, stale javadocs). Bench NOT_AND_CMP_LONG G3-smoked. compute *fusion*/*Stitcher* 105/0, esql *fusion*/*Logical* 753/0, gen 11/0. |
| F4 permanent coverage probe + re-run | Done | 151e3d06614 | Opt-in fusion coverage probe extending the existing FusionTelemetry (criterion #5 failure registry). New `esql.fusion.coverage_report` NodeScope setting (default false, latches FusionTelemetry.coverageEnabled at node start; also honors -Desql.fusion.coverage_report for test JVMs). Coverage buckets recordAttempt/recordFused/recordRefusedAtPlan/recordRuntimeFallback early-return when off (zero cost; recordFailure/TOTAL_FAILURES stay unconditional, FAILURES_BY_PATH gated). Plan-time accounting identity fused+refused-at-plan+(plan-time)stitch==attempts; recordAttempt after kill-switch/sample; adaptive recordFused once-per-factory in resolveFused; runtime-fallback once-per-evaluator-instance (incl. nested-mapping MV fallback). coverageReport() classifies stitch failures by FusedClassCache.Path; logged once at EsqlPlugin.close() when on (+ CsvIT forwards the -D and triggers it via teardown). Reviewer no-blocker (eager-path accounting confirmed; 4 non-blockers + 2 nits applied: adaptive once-per-factory, report stitch=plan-time-only, path-map gated, single log). **CsvIT coverage run GREEN** (math/comparison/boolean/stats/string/eval/where = 942 tests / 0 fail): `attempts=1604 fused=174 refused-at-plan=1430 stitch-failures=0 runtime-fallbacks=0 fused/attempt=10.8%`. **ZERO FILTER_EVAL_BLOCK stitch failures → G8 confirmed not-this-iteration (evidence-backed).** The 1430 refusals are depth-1/ineligible gates, not bugs. esql *fusion* 228/0, compute *fusion* 105/0. |
| D3 (GO per iter-3 §B/B6) + F3 rides on it | Done | a87c0097b928 | Nested mapping under a single-value parent: buildMapping admits a mapping kernel non-root when parent consumes BYTES_REF (LENGTH/Equals); root mapping still → compileMappingBlockLoop, nested → ordinary block path (plan.mapping stays false). Eval: nested-mapping tree with ANY non-vector input → UNFUSED_FALLBACK (arity-agnostic after reviewer blocker); vector/ordinal/constant → fused + D1 encoding-aware (runs chain once over dict, gathers int/boolean by ordinal). **F3:** @Fusable on Equals/NotEquals processBytesRef; buildConstant BytesRef reference-constant; depth-1 encoding-aware exemption for lone `col ==/!= literal` (and literal-on-left). **Reviewer BLOCKER fixed:** multi-column nested mapping (`k1 == TO_LOWER(k2)`) refused at plan time (shared BytesRef spare would alias → wrong dense values; MV warning mis-attribution) — `containsNestedMapping(tree) && inputElements.length>1 → null`; fail-first proven via FusionVerify. FilterEval also refuses nested OR root mapping (no per-page fallback there). Bench EQ_TO_LOWER_*/LENGTH_TO_LOWER_ORDINAL now fusesToday + G3 smoke (fused==unfused; numbers deferred to bench round). esql *fusion* 200/0, compute *fusion* 100/0, gen 11/0. Perf acceptance (EQ/LENGTH ordinals ≤1.5 ns/row) deferred to the benchmark round. |
| E3-logical (GO per iter-3 §B/B2) | Done | 60b0753ad46 | logical vector INTERFACE-only; deleted LOGICAL_VECTOR RAW emit (Stitcher emitLogicalVectorMethod raw branch) + factory dispatch (vectorHandleInterface=null, evalLogicalVector always INTERFACE) + RAW structural/differential expectations. Arithmetic RAW untouched (reviewer confirmed). Smoke: AND_CMP NONE 0.993 / TYPES 1.037 ns/op (<=1.10). Gates: compute 10508/0, *fusion* 175/0. Reviewer non-blocking |
| E4' (revised GO per iter-3 §B/B3) | Done | 24f12460e9f | Deleted the compute() split (emitVectorLoopSplit/emitPlainVectorSplitMethod/emitVectorComputeHelper/emitCheckedVectorRawSplit) + max_method_bytecodes split branch + all split tests; DEFAULT_MAX_FUSED_METHOD_BYTECODES 325→8000 (DontCompileHugeMethods — the "never JIT-compiled above this" limit, not FreqInlineSize). Plain-vector now enforces its inline body directly (single serializeWithBudgetGuard(...,"plain-vector loop",true)); checked-vector returns inline (enforce=false unchanged); enforce=true set = plain-vector/logical-block/filter-eval; message reworded "inlining budget"→"huge-method limit" (keeps [<n> bytecodes] token). Deleted dead FusionPlanner.fusedClassHasMethod + FusedExpressionEvaluatorFactory.declaresMethod. Empirical crossings: plain-vector left-ADD chain refuses at depth 352 (8008 bytecodes); balanced-AND refuses at depth 8 (256 leaves, 15836). Bench: Case.split→DEEP_ARITH (depth 24, fuses inline), added DEEP_LOGICAL (balanced AND, depth 6). Reviewer 1 BLOCKER (1500-comparison differential test passed via KERNEL_MAP UTF-8 overflow not the 8000 guard → switched to 120 NOT-wrapped comparisons = 11455 bytecodes, KERNEL_MAP 11589 chars <65535; JVM 255-param MethodType limit forbids ~250 plain comparisons) + 6 nits all applied. compute *fusion* 100/0, esql *fusion* 226/0, benchmarks compileJava + spotless green. Perf acceptance deferred to bench round. |
| E6 Stitcher split | Done | 6b73a3be812 | Split Stitcher.java (3883→1442) into package-private VectorLoopEmitter (1037: plain/checked/SIMD/logical-vector), BlockLoopEmitter (1013: block/mapping/logical-block + cmpN helpers) and FilterEvalEmitter (487: filter+eval pred/projAppend). Stitcher keeps the compile* façade + defineOrThrow + budget/serializeWithBudgetGuard + frameComputingWriter + shared static helpers. Vector emitters unified behind one `record LoopSpec(Reads,Output,boolean checked,Element outElement)`; logical-vector routes through the array-vector loop with outElement=BOOLEAN (rootReturnType maps Logical/Not→boolean, StackKernelEmitter already walks them); checked-vector BUILDER keeps its own skeleton (LoopSpec-dispatched, not fused into the array loop — correctly not forced). Added committed SIMD test seam `simdVectorLoopBytecode`. **≥30% target not met (combined +2.5%)**: the ~400 duplicated logical-vector lines the target assumed were already shared via StackKernelEmitter by E2/E3, so there was nothing left to delete; Stitcher ITSELF is −63%. No new emitter file >~1200; Stitcher 1442 is javadoc-dominated (compile* docs). **E1 BYTE-IDENTICAL**: 34-shape class-byte dump identical before/after (empty `diff -r`), incl. after the LoopSpec merge + nits. Harness `FusionClassDumpTests` (uncommitted; copy in git-ignored bench-results/TE/) + SIMD seam. Reviewer no blocker (8 nits applied: visibility, @FunctionalInterface, class javadocs, vectorMethodName BLOCK/raw hardening, shared BlockLoopEmitter instance, dead constantType deletion). compute *fusion* + esql *fusion* green, spotless clean. |
| E7 factory refactor | Done | dec0810a47a | FusedExpressionEvaluatorFactory 1509→1468. (1) Three telescoping ctors → one taking a small `record VectorHandles(plain,plainInterface,raw,rawInterface)` (static factories none()/plain()/plainInterface()/checked()); 6 construction sites remapped with nulls preserved exactly (logical-vector keeps interfaceHandle in the plain slot). (2) asSpreader handles built ONCE per factory (immutable, shared across drivers) instead of per-driver; each evaluator still allocates its own per-page scratch Object[]; shared `invokeArgConstantCount` keeps the baked-skip count identical to resolveConstantValues/constantParamTypes (argCount formulas unchanged). (3) `lastEvalPath` writes → `assert recordPath(...)` (new private helper returning true) so the per-page path bookkeeping is off the production hot path and survives only under -ea (tests + verify). No production reader; verify-without-ea leaves the dump label "BLOCK" (documented, CI uses -ea). Does NOT touch Stitcher → E1 dump trivially byte-identical (confirmed empty). Reviewer no blocker (2 javadoc nits applied). esql *fusion* + compute *fusion* green, spotless clean. |
| E1 (safety net for E6/E7) | Done (uncommitted harness) | | 34-shape class-byte dump corpus covering every surviving compile entry point (plain/checked/block/logical-block/logical-vector/mapping/SIMD/filter-eval) × element types (int/long/double, boolean-root, BytesRef, cast-bridged mixed-width, runtime+baked constant, AND/OR/Not, filter+eval). fusedClassName is deterministic (pkg + fixed simpleName, no counter) → re-run on same HEAD byte-identical (two-run diff empty). Byte-identical gate held across E6 (move + LoopSpec merge) and E7. Harness `FusionClassDumpTests` NOT committed to the code branch; copy + README in git-ignored bench-results/TE/. |
| E2/E5/E8 | Dropped/n-a (iter-3 §B) | | E2+E5 dropped; E8 not revived |

### iteration-3 §G fixes — ALL DONE, pushed e3ffe7a97b1..1ca2b34e82e (final gate: *fusion* green + spotless clean). G9 perf re-measure (B2 auto within 3% of control) DEFERRED to operator bench host — dev VM too noisy for a 3% acceptance; functional no-per-page-volatile verified by unit tests.
| G# | Status | SHA | Notes |
|---|---|---|---|
| G1 FusionFollowupBenchmark.split asserts split matches effective budget | Done | 1ca2b34e82e | public Stitcher.DEFAULT_MAX_FUSED_METHOD_BYTECODES + maxFusedMethodBytecodes(); assert compute() present iff budget<=default. Ran split cell both arms: default 11.25 ns/op (compute present), budget=8000 9.81 ns/op (compute absent), both -foe true green |
| G2 IntBlock arm in assertSameOutput | Done | 50258d8aa18 | delivered as Phase A benchmark smoke fix |
| G3 run every new benchmark cell before pushing | Process | - | `-f 1 -wi 1 -w 1s -i 1 -r 1s -foe true`, paste table into CHECK/commit |
| G4 A8 evidence on evidence branch | Done | c87de31d30e | CsvIT on/off; put final numbers in FINAL.md |
| G5 post-stitch findStatic WARNs every plan (A2 regression) | Done | ba03a96b360 | Option 1: FusedClassCache.stitchAndResolve resolves expected methods inside cache.get; service MethodType overloads; FusedClassCompiler defaulted overloads (test doubles stay 2-arg). Test testFactoryFindStaticMissWarnsOnceThenDebug (fail-before/pass-after). Gates: *fusion* 171/0, compute 10507/0 |
| G6 split structural test scans compute() | Done | 66f701a3c60 | delivered as A6-hardening |
| G7 small fixes bundle | Done | 00b439d7422 | causeClassName in reconstructed exc; wrap StackOverflowError as stitch failure (keep only OOM/InternalError transient); WarnsOnce count/Unseen; rename FusedClassCacheTests methods + drop dup isFirstFailure; fix Stitcher comments l.67/164/3808; rename MAX_FUSED_METHOD_BYTECODES + reject<=0 + doc enforce effect; isVectorReadsInterface package-private + restore-prev-in-tests + reject unknown value; split switch case 2 + throwing default; doc eviction-race double-WARN in exactly-once javadoc |
| G8 filter-eval fallback loses separate fusion | Not this iter (evidence-backed) | | F4 CsvIT coverage probe (151e3d06614) showed ZERO FILTER_EVAL_BLOCK stitch failures across the math/comparison/boolean/stats/string/eval/where corpus (942 tests), so the condition to fix it ("only if F4 probe shows filter-eval stitch failures") is not met. Deferred. |
| G9 vector_reads hook static-final (not per-page volatile) | Done | 3a4fba265ce | VECTOR_READS_INTERFACE static-final (reject unknown); accessor package-private; snapshot into final evaluator field (no per-page volatile); getForTests forces both modes; +reflection guard. Gates: *fusion* 174/0. Perf re-measure (B2 auto within 3%) deferred to boundary smoke |

## Per-task gates (plan §0)

- `./gradlew :x-pack:plugin:esql:compute:test :x-pack:plugin:esql:test --tests '*fusion*'`
  plus `spotlessJavaCheck` on touched projects.
- Smoke (correctness only) run at phase boundaries into `bench-results/T<phase>/smoke/`:
  - `./bench.sh FusionStrategyBenchmark.run -p pollute=NONE,TYPES -f 1 -wi 0 -i 1 -r 200ms -foe false ...`
  - `./bench.sh SlowPathFusionBenchmark.run -p pollute=false,true -f 1 -wi 0 -i 1 -r 200ms -foe false ...`
  - plus `FusionFollowupBenchmark` once it exists. No new failing cell vs prior.
