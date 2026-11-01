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
