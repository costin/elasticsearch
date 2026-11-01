# Iteration 4 — per-item gate output (T4/CHECK.md)

## H1 — fused BytesRef column aliasing (commit 2a7e47987f9)
Fusion gate (implementer run): BUILD SUCCESSFUL
- :x-pack:plugin:esql:compute:gen:test — 0 failures
- :x-pack:plugin:esql:compute:test (*fusion*) — 100 fusion tests, 0 failures
- :x-pack:plugin:esql:test --tests '*fusion*' — 234 tests, 0 failures
- FusionBytesRefAliasingTests — 7 tests, 0 failures
- :compute:spotlessJavaCheck / :esql:spotlessJavaCheck — pass

CsvIT (verify on, -ea default): BUILD SUCCESSFUL in 4m47s, exit 0. (full log: T4/csvit-h1.log)

Fails-before-fix captured (see commit body 2a7e47987f9):
- testEqualsLeftOfOtherColumn: fused vs unfused mismatch, path=BLOCK, firstDiff@0 fused=false unfused=true
- testNotEqualsRightOfOtherColumn: fused vs unfused mismatch, path=BLOCK, firstDiff@0 fused=true unfused=false
- testFusedClassHasDistinctSparePerBytesRefColumn: expected 2 spares, was 1
- testEqualsTwoColumnsFilterEval / testNotEqualsTwoColumnsFilterEval / testEqualsTrimOfOtherColumnFilterEval: FILTER_EVAL survivor-count mismatch

## H2 — huge-method guard on every path (commit 25fd1cabcaf)
Fusion gate (implementer): BUILD SUCCESSFUL in 5m11s
- :compute:gen:test 11/0/0; :compute:test 10532/0/202; :esql:test '*fusion*' 239/0/0; spotless pass
Orchestrator re-ran both new H2 test classes + spotless after @After nit: green.
CsvIT (verify on): BUILD SUCCESSFUL in 2m44s, exit 0 (log: T4/csvit-h2.log)
Fails-before-fix (commit body 25fd1cabcaf): testTinyLimitRefusesLogicalVector, testTinyLimitRefusesCheckedVector (no StitchingException pre-H2); testTinyLimitArithmeticFallsBackUnfusedAndMatches (was CHECKED_VECTOR).

## H3 — bound baked Div/Mod divisor classes (commit 9622eed87db)
Fusion gate (implementer): all green — :compute:gen:test 11/0/0; :compute:test 10535/0/202; :esql:test '*fusion*' 7899/0/252; spotless pass.
Reviewer GO (no blockers); nits applied: hot-path early-out (boolean flag on PlanContext, allocation-free on no-baked path), javadoc indent, rewriteOverCap -> sealed pattern switch. Post-nit targeted tests green (StitcherVectorFusionTests 8/0, FusionSpecDifferentialTests 39/0, ArithmeticFactoryFusionAwareTests 7/0).
No CsvIT (not an H1/H2/H5/final gate).
Fails-before-fix (commit body): testBakedDivisorCapBoundsClassesPerShape (20 classes > cap+1), testDoubleDivisorIsTrailingParameterNotImmediate (double was LDC2_W).

## H4 — skip dictionary path on sparse ordinal pages (commit a9b5e13ad09)
Fusion gate (implementer): BUILD SUCCESSFUL 4m46s (compute:gen/compute/esql *fusion* + spotless + :benchmarks:compileJava). Reviewer GO (no blockers); 3 cosmetic comment nits applied, spotless green.
No CsvIT (not an H1/H2/H5/final gate).
Guard: ordinals.isDense() || dictionarySize <= positionCount; else fall through to per-row block path. -ea seam lastEncodingAwareDictPositions.
Fails-before-fix (commit body): testKeywordEqualsLiteralSparseDictionaryDoesNotTakeEncodingAwarePath (10000 dict evals > 10 rows).

## H5 — dictionary-safe kernels (commit 1e87238ec71)
Fusion gate (implementer): BUILD SUCCESSFUL — :compute:gen:test 12/0/0; :compute:test 10536/0/202; :esql:test '*fusion*' 247/0/0; spotless pass. Reviewer GO (ACCEPT CONCAT deviation, no blockers).
CsvIT (verify on): BUILD SUCCESSFUL in 2m52s, exit 0 (log: T4/csvit-h5.log)
Fails-before-fix (commit body): testFusableOverflowUncheckedWarnExceptionsEmitsWarns; testLengthConcatIsNotEncodingAware (was true); testLengthConcatUnusedHugeDictionaryEntryDoesNotThrow.

## H6 — safe verify failure path (commit c01679591fd)
Full fusion gate (orchestrator): BUILD SUCCESSFUL in 4m20s, GATE_EXIT=0
- :compute:gen:test, :compute:test, :esql:test --tests '*fusion*', both spotlessJavaCheck — all green.
Suite under -Desql.fusion.verify=false (implementer): 251 tests, 0 failures, 5 skipped (verify-on hard-asserts now assumeTrue-skip as required by §0 Done-when).
Reviewer GO (no blockers); 4 nits applied: unit assertion locking production tolerance (describeMismatch(arrayLong,constLong,false)==null), test-only 2-arg describeMismatch javadoc, describeSurvivorMismatch-never-throws invariant comment, NULLS_AND_MULTIVALUE once-per-page note.
No full CsvIT (H6 is verify-path/test only; not an H1/H2/H5/final gate).
Fails-before-fix (commit body, red seed 9EA5B429838878A5):
- testNonUtf8BytesRefMismatchReportsHexAndClosesBlocks: expected "fused vs unfused mismatch" but was "b = 0xff" (Lucene utf8 assert hid mismatch).
- testDumpFailureStillClosesBothBlocksAndSaysDumpFailed: expected "dump failed" but got normal mismatch+dumped-to message.
- testNumericConstantLostEncodingAndConstantBeatsOrdinal: expected "constant-encoded" but was null.

## H7 — name fused shape+kernels in stack traces (commit 89a065fdb7c)
Full fusion gate (orchestrator): BUILD SUCCESSFUL in 4m30s, GATE_EXIT=0
- :compute:gen:test, :compute:test, :esql:test --tests '*fusion*', both spotlessJavaCheck — all green.
Reviewer GO (no blockers, GO with 3 optional nits); applied 2: dropped dead capUtf8 keep<0 clamp (assert), trailing catch(Throwable) now also attaches FusedFrameInfo for symmetry. Skipped optional kernelMapOf TRACE-log nit.
No CsvIT (H7 is cold-path diagnostic/test only; not an H1/H2/H5/final gate).
FusedFrameInfo = RuntimeException super(msg,null,false,false) (no stack trace, suppression off); attached via addSuppressed at both factories' per-page catch/rethrow. KERNEL_MAP resolved once per factory via revealDirect on block handle. SourceFile+KERNEL_MAP capped at 4KB on UTF-8 boundary.
Fails-before-fix (commit body):
- testThrownKernelExceptionCarriesFusedFrameInfo: suppressed count expected >0 but was 0.
- testOversizedSourceFileAndKernelMapAreCappedAndStillDefine: SourceFile UTF-8 bytes expected <=4096 but was 102503.

## H8 — cache fused handles + tighten diagnostics (commit 6e3e4a8f5fe)
Full fusion gate (implementer): BUILD SUCCESSFUL in 5m23s. Comment-only reviewer nit applied after (stitchAndResolve/PENDING_RESOLVED side-channel javadoc); spotless re-run green. Orchestrator confirming full gate at committed HEAD 6e3e4a8f5fe: BUILD SUCCESSFUL in 4m11s, GATE_EXIT=0.
Reviewer GO (no blockers; highest-risk handle-caching + ctor-record refactor scrutinized correct). 3 optional nits; applied 1 (doc), skipped 2 refactors.
No CsvIT (not an H1/H2/H5/final gate).
Sub-deliverables: G7 exactly-once WARN (ExactlyOnceWarnExpectation, 3 plans, true equality); G5 through-production (FusionCompilationService.cached, 2-arg type=null overloads deleted->UOE); G5/E7 resolve-once cache (MethodHandle+asSpreader on CachedFused, cache hit skips findStatic/asSpreader via PENDING_RESOLVED thread-local); E7 18-arg ctor -> FusedDispatch record (hot path unchanged); labels path==null?"?":BLOCK; VectorHandles renamed primary/primaryInterface/raw/rawInterface; G9 javadoc fixed.
Fails-before-fix (commit body):
- G7 double-WARN probe: expected exactly 1 WARN, was 2.
- testVerifyPageNullPathLabelsQuestionMark: expected "path=?" but was "...path=BLOCK...".
- testCacheHitDoesNotCallFindStatic: expected findStatic count 10 but was 15.
- G5 through-production (skip stitchAndResolve probe): expected exactly 1 WARN, was 3.

## H9 — F0-comparable coverage probe (commit 3e6d6d8dd9c)
Full fusion gate (implementer): BUILD SUCCESSFUL 5m12s (FusionTelemetryCoverageTests 11/0). Comment-only reviewer nits applied after (recordFused hazard javadoc, refusedAtPlan derived note); spotless re-run green. Orchestrator confirming full gate at HEAD 3e6d6d8dd9c: BUILD SUCCESSFUL in 5m13s, GATE_EXIT=0.
Reviewer GO (no blockers; zero-production-impact-when-off, ThreadLocal nest, categorization exhaustiveness, Setting removal all verified). 3 nits; applied 2 doc, skipped perf.
Coverage mechanism: sysprop-only esql.fusion.coverage_report; registered Setting removed. Counted once per top-level expr.
Full CsvIT (ALL csv-spec, coverage on): 7657 tests, 252 skipped, 0 failures, 4m50s. 34523 top-level exprs: FUSED 2.9% (1008), ISLAND 0.6% (222), FRAGMENT 2.3% (806), NONE 94.1% (32487); identity holds; stitch-failures=0, runtime-fallbacks=0. FUSED did NOT rise vs F0 18.7% (pre-H9 probe not comparable). Raw: T4/h9-coverage.txt.
Top refusals: depth<2=12585, Div=5858, MvSlice=4558, Case=1758, PackDimension=878, ToLong=802, UnpackDimension=756, IsNotNull=652, ToDouble=574, Bucket=505.
Fails-before-fix (commit body): testDeclinedFilterEvalCountsAsOneTopLevel expected <1L> but was <3L>.

## H10 — fuse DATETIME range comparisons as long (commit 333f1202b3f)
Full fusion gate (implementer): BUILD SUCCESSFUL 4m41s. 2 reviewer nits applied (dense parity page straddles [t1,t2] incl boundaries; buildConstant comparison-only comment); targeted LogicalFusionDifferentialTests + spotless re-run green. Orchestrator confirming full gate at HEAD 333f1202b3f: BUILD SUCCESSFUL in 5m16s, GATE_EXIT=0.
Reviewer GO (no blockers; scope/value-identity/mixed-type-refusal all verified against GreaterThanOrEqual.evaluatorMap + EsqlBinaryComparison.toEvaluator). 2 nits applied.
DECISION: MAP DATETIME+DATE_NANOS to LONG for comparisons only (evidence: same-type date comparisons bind the long kernels; mixed-type still refuses; elementOf/arithmetic unchanged).
No CsvIT (not an H1/H2/H5/final gate).
Fails-before-fix (commit body): testDatetimeGteLteRangeFuses expected FusedExpressionEvaluatorFactory but was BooleanLogicExpressionEvaluator wrapping the Longs evaluators. testNotDensePathPicksInterfaceVsBlock is coverage (passed pre-change).

## H11 — dedup emitters + drop dead mapping path (commit 5879c12a57e)
Full fusion gate (implementer): BUILD SUCCESSFUL 5m5s. Orchestrator confirming full gate at HEAD 5879c12a57e: BUILD SUCCESSFUL in 4m35s, GATE_EXIT=0.
Reviewer GO (no blockers; independently REPRODUCED E1 md5 7cbf6fa... 33 shapes; dead-path-unreachable proof traced through planOrNull depth guard; bytecode-identical prologue/epilogue extraction verified instruction-by-instruction). 2 nits skipped (non-blocking: param count, redundant null guard).
Pure refactor \u2014 no fails-before-fix; E1 byte-identical is the proof.
Line counts: Stitcher 1482->1199 (<=1200); four-file total 4034->3363 (target <=3846, 633 below E6 baseline 3996).
Dead mapping path deleted (compileMappingBlockLoop/Path.MAPPING_BLOCK/FusionPlan.mapping/factory+cache+planner branches); lone TO_LOWER stays unfused per D4; nested mapping unchanged on ordinary block path.
E1: all six dumps (pre-H1/H1/H2/H3/pre-H11/H11) md5 7cbf6fa3520c30dd271ded1ca9b0dd8a, byte-identical. Files T4/e1/{pre-h1,h1,h2,h3,pre-h11,h11}.txt.
No CsvIT (not an H1/H2/H5/final gate).

## H12 — reject unfusable converters at generation time (commit b157ced1448)
Full gate (implementer): BUILD SUCCESSFUL 5m41s. NOTE: standard gate omitted :compute:gen:spotlessJavaCheck; the doc-nit step found the gen test file needed reformatting (assertHasError wrapping), spotlessApply fixed it, both gen+compute spotless now pass. Orchestrator confirming gate (now INCLUDING :compute:gen:spotlessJavaCheck) at HEAD b157ced1448: BUILD SUCCESSFUL in 4m28s, GATE_EXIT=0.
Reviewer GO (no blockers; TO_VERSION refusal definitively justified by independent trace; non-BytesRef-return rejection breaks no real kernel \u2014 only ChangeCase is @Fusable and returns BytesRef). 1 doc nit applied (dictionarySafe proxy caveat).
DEVIATION: TO_VERSION refused (plan said "eligible"); recorded with reason. Converter table in FINAL_ITER4.md.
No CsvIT (not an H1/H2/H5/final gate).
Fails-before-fix (commit body): testFusableConvertNonBytesRefReturnIsCompileError (got none); testFusableConvertJitConstantFixedIsCompileError (got generic "fixed function args supported after the first").

## H9 — F4 coverage probe comparable to F0 (uncommitted)
Once per top-level expression; FUSED/ISLAND/FRAGMENT/NONE; first-refusal reason;
sysprop-only `esql.fusion.coverage_report` (Setting removed). Gradle:
`-Dtests.jvm.argline=-Desql.fusion.coverage_report=true`.
Fails-before-fix: `testDeclinedFilterEvalCountsAsOneTopLevel` Expected: is <1L> but: was <3L>.
Full CsvIT (7657 tests, 0 failures): FUSED=1008 (2.9%) ISLAND=222 (0.6%) FRAGMENT=806 (2.3%) NONE=32487 (94.1%).
**FUSED did not rise** vs F0 18.7%. Table: `FINAL_ITER4.md`, raw: `T4/h9-coverage.txt`.
