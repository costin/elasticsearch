# Iteration 4 report
Code HEAD: c1474931f0e (H1-H16 complete; H15 doc-only, H16 in H13 commit)   Evidence HEAD: (pending)

Delivery: direct push to `esql/fix/compute-codegen-followup`, no PR (operator directive).
Driven by the orchestrator; each production change implemented test-first by a subagent,
reviewed by a fresh subagent, nits applied, then committed by the orchestrator.

## Items
| Item | Status (done/partial/deviated) | Commits | Tests added (class#method) | Fails-before-fix shown (y/n) | Notes / deviation |
|---|---|---|---|---|---|
| H1 | done | 2a7e47987f9 | FusionBytesRefAliasingTests#{testEqualsLeftOfOtherColumn,testNotEqualsRightOfOtherColumn,testEqualsLeftLeftOfTwoColumns(coverage),testEqualsTwoColumnsFilterEval,testNotEqualsTwoColumnsFilterEval,testEqualsTrimOfOtherColumnFilterEval,testFusedClassHasDistinctSparePerBytesRefColumn}; BytesRefInputFusionDifferentialTests#testEqualsLeftOfOtherColumnFuses; MappingEncodingAwareTests flipped to fuse+match | y | Per-column BytesRef spare (one per input index) in BlockDfsEmitter + both FilterEvalEmitter helpers; varargs gather unchanged. Deleted nested-mapping-only planner refusal (now redundant). Deviations: FilterEval cannot stitch LEFT/RIGHT (@Fixed scratch const), so filter-eval guards use k1==k2 / k1!=k2 / k1==TRIM(k2) analogs; LEFT(k1)==LEFT(k2) kept coverage-only (does not alias pre-fix). |
| H2 | done | 25fd1cabcaf | StitcherHugeMethodGuardTests#{testMaxMethodCodeLengthReturnsLargestOfThreeMethods,testTinyLimitRefuses*(8 paths),testLogicalVectorMaxMethodCodeLengthSeesFusedInterface,testCheckedVectorMaxMethodCodeLengthIsLargestOfFour,testDeepLogicalVectorLargestMethodBetween325And8000,testDeepFilterEvalLargestMethodBetween325And8000}; FusionHugeMethodGuardTests#{testTinyLimit*FallsBackUnfused*,testDeepInBudget*Fuses*} | y | maxMethodCodeLength = max Code len over all methods; serializeWithBudgetGuard enforces on all 8 paths (enforce flag deleted); block compiles before vector try so block-too-big -> outer catch -> unfused. Rename prose DontCompileHugeMethods->HugeMethodLimit. No differential retunes. Added @After budget-reset net (reviewer nit). |
| H3 | done | 9622eed87db | FusionSpecDifferentialTests#{testBakedDivisorCapBoundsClassesPerShape,testDoubleDivisorIsTrailingParameterNotImmediate,testDoubleNonFiniteWithNonZeroDivisorMatchesUnfused}; StitcherVectorFusionTests#{testBakedIntDivisorIsImmediateOnCheckedPath,testBakedLongDivisorIsImmediateOnBlockPath,testBakedIntDivisorIsImmediateOnBlockPath}; ArithmeticFactoryFusionAwareTests#testBakedDivModDescriptorEqualsGeneratedFactories (replaced Longs-only tautology) | y | Bake int/long only (double -> trailing runtime param); cap 8 distinct baked values per un-baked shape via post-build un-bake rewrite + planner-static registry (bounded by MAX_ENTRIES, synchronized admission, resetForTests). Hot-path early-out: boolean flag, allocation-free when no baked divisor. All 6 process* descriptors == generated. |
| H4 | done | a9b5e13ad09 | MappingEncodingAwareTests#{testKeywordEqualsLiteralSparseDictionaryDoesNotTakeEncodingAwarePath,testKeywordEqualsLiteralOrdinalBlockWithNullsMatchesUnfused,testTrimToLowerDenseOrdinalPreservesOrdinalEncoding} | y | Dictionary path only when isDense() || dictSize<=positionCount; else per-row block fall-through. -ea seam lastEncodingAwareDictPositions. KEYWORD_EQ_PLAIN cell added to FusionFollowupBenchmark (smoke batched into H13). Ordinal-output rewrap (TRIM(TO_LOWER(k))) keeps OrdinalBytesRefVector. |
| H5 | done | 1e87238ec71 | FusionAwareGenerationTests#testFusableOverflowUncheckedWarnExceptionsEmitsWarns; MappingEncodingAwareTests#{testLengthConcatIsNotEncodingAware,testLengthConcatUnusedHugeDictionaryEntryDoesNotThrow}; FusionDescriptorTests#testConvenienceConstructorsDeriveWarnsAndDefaultDictionarySafe | y | Added descriptor fields warns (generator from warnExceptions regardless of overflowChecked) + dictionarySafe (false for CONCAT/SPACE); encodingAware requires !warns && dictionarySafe reading only flags; hasWarningKernel keys on warns. Regenerated 69 FUSION_DESCRIPTOR literals. Accepted deviation: enabled CONCAT(col,literal) fusion (fresh array slot, no shared spare) so the behaviour test is non-vacuous; >1MB row propagates same exception as unfused. Follow-ups (open qs): compile-time guard for overflowChecked=false+warnExceptions; dictionarySafe classification test. |
| H6 | done | c01679591fd | FusionVerifyTests#{testNonUtf8BytesRefMismatchReportsHexAndClosesBlocks,testDumpFailureStillClosesBothBlocksAndSaysDumpFailed,testNumericConstantLostEncodingAndConstantBeatsOrdinal}; FusionSettingsVerifyTests + FusionVerifyTests assumeTrue(isVerifyEnabled); FusionDumpTests dual-sink delivery; FusionSpecDifferentialTests ordinal-BytesRef shapes; FusionDifferentialTestCase once-per-page encoding | y | BytesRef printed as hex (BytesRef.toString) so non-UTF-8 output no longer trips Lucene utf8 assert; both blocks closed in finally, dump failure folded into message; encoding check covers numeric Constant*Vector and accepts constant-vs-ordinal; BYTES_BY_SHAPE LRU<=256; FusionDump delivers to thread capture AND external sink; verify-on tests skip (not fail) under -Desql.fusion.verify=false. 4 reviewer nits applied (prod-tolerance unit assertion, 2-arg describeMismatch javadoc, survivor-never-throws invariant comment, NULLS_AND_MULTIVALUE note). |
| H7 | done | 89a065fdb7c | FusionVerifyTests#testThrownKernelExceptionCarriesFusedFrameInfo; StitcherDebugInfoTests#testOversizedSourceFileAndKernelMapAreCappedAndStillDefine | y | Suppressed FusedFrameInfo(shape, KERNEL_MAP) attached at both fused factories' per-page catch/rethrow (RuntimeException with writableStackTrace=false, suppression off; cold-path only), so default traces (no -XX:+ShowHiddenFrames) name shape+kernel class#method. KERNEL_MAP resolved once per factory via revealDirect on block handle. SourceFile+KERNEL_MAP capped at 4KB on UTF-8 boundary so oversized signatures can't make ASM throw. Stale comments in FusedClassDebugInfo + Stitcher corrected. 2 reviewer nits applied (dead keep<0 clamp -> assert; trailing catch(Throwable) also attaches frame info). |
| H8 | done | 6e3e4a8f5fe | FusionFallbackHardeningTests (ExactlyOnceWarnExpectation x5); FusedClassCacheTests#testCacheHitDoesNotCallFindStatic + through-production stitch-fail test; FusionVerifyTests#testVerifyPageNullPathLabelsQuestionMark | y | G7 count WARN == 1 over 3 plans (was at-least-once); G5 drive FusionCompilationService.cached (deleted type=null 2-arg compile* overloads); G5/E7 resolve MethodHandle+asSpreader once, store on CachedFused so cache hit skips findStatic/asSpreader (PENDING_RESOLVED thread-local, cleared in wrapStitchFailures finally); E7 18-arg ctor -> FusedDispatch record (hot-path fields copied in ctor, no per-page indirection); verify label ? when -ea off; VectorHandles renamed primary/primaryInterface/raw/rawInterface; G9 javadoc corrected (no C2 fold of instance final). planCachedThenGet benchmark deferred to H13. 1 reviewer doc nit applied, 2 refactor nits skipped. |
| H9 | done | 3e6d6d8dd9c | FusionTelemetryCoverageTests (four-category + testDeclinedFilterEvalCountsAsOneTopLevel + sysprop-not-setting + testMapExpressionChildDoesNotInvokeRawFactory; 11 tests) | y | Coverage counted once per top-level expr (EVAL/WHERE/FilterEval pair) via FusionCoverage; FUSED/ISLAND/FRAGMENT/NONE + first-refusal reason; sysprop-only esql.fusion.coverage_report (registered Setting removed); zero production impact when off (plan-time volatile read short-circuit, ThreadLocal cleared in finally, classify wrapped in catch). Full CsvIT: FUSED 2.9% (down from F0 18.7%) \u2014 stated plainly; pre-H9 probe was not comparable. See Coverage section. 2 reviewer doc nits applied (recordFused test-only hazard, refusedAtPlan derived note); perf nit skipped. |
| H10 | done (MAP) | 333f1202b3f | LogicalFusionDifferentialTests#{testDatetimeGteLteRangeFuses, testNotDensePathPicksInterfaceVsBlock} | y (map test); Not test is coverage (passed pre-change) | DECISION: MAP DATETIME+DATE_NANOS to LONG for comparisons ONLY (not an open question). Same-type unfused GTE/LTE/GT/LT/EQ/NEQ bind *LongsEvaluator / @Fusable processLongs(JJ) (GreaterThanOrEqual.evaluatorMap + siblings), so fused==unfused byte-identical. Scope: matches()/buildConstant comparison path only (single caller buildComparisonOperand); elementOf unchanged so arithmetic over dates still refuses. Mixed DATETIME-vs-DATE_NANOS uses non-FusionAware NanosMillis/MillisNanos kernels -> still refuses. H9 evidence: GTE/DATETIME absent from top-10 because first-refusal of And(GTE,LTE) is "And" (GTE is FusionAware); ~386 of that shape sits just under Bucket=505; shape is in csv-spec (PromQL withTimestampFilter). Dense parity test straddles [t1,t2] incl boundaries. |
| H11 | done | 5879c12a57e | FusionClassDumpTests (E1 dump, 33 shapes); removed dead-path tests (testMappingBlockLoop*, testTinyLimitRefusesMapping) | n/a (pure refactor; E1 byte-identical is the proof) | Dedup: FusedBuilderEmit (prologue/epilogue), single this::intercept, test seams call emitters directly (8 forwarders removed), FusedMethodCodeLengths extracted. Deleted dead mapping path (compileMappingBlockLoop, Path.MAPPING_BLOCK, FusionPlan.mapping, factory/cache/planner branches) \u2014 reviewer verified unreachable in production (lone TO_LOWER stays unfused per D4; mapping_append_suffix was a test-only fixture). Stitcher 1482->1199 (<=1200); four-file total 4034->3363 (target <=3846). E1 byte-identical across pre-H1/H1/H2/H3/pre-H11/H11 (md5 7cbf6fa..., 33 shapes), reviewer independently reproduced. 2 reviewer nits (param count, redundant null guard) skipped as non-blocking. |
| H12 | done (TO_VERSION deviated->refused) | b157ced1448 | FusionAwareGenerationTests#{testFusableConvertNonBytesRefInputIsCompileError, testFusableConvertThreadLocalFixedIsCompileError, testFusableConvertJitConstantFixedIsCompileError, testFusableConvertNonBytesRefReturnIsCompileError} | y | Generator rejects non-BytesRef return (was silently generated), non-BytesRef input, and THREAD_LOCAL/jitConstant @Fixed \u2014 each asserts the specific message. Fusable admission runs before the generic trailing-arg check. mappingFixedArgTypes uses kernel class loader. TO_VERSION refused (deviation from plan's 'eligible'): encodeVersion throws IAE on a legal >=128-digit keyword with no warnExceptions, and generated converters hardcode dictionarySafe=true, so encoding-aware fusion would throw on an unreferenced dict entry; reviewer confirmed refusal justified + no real kernel regressed (only ChangeCase is @Fusable, returns BytesRef). 1 doc nit applied (dictionarySafe proxy caveat). Converter table below. |
| H13 | done | 3f2d8bbbfd1 | n/a (benchmark; smoke only) | n/a | Moved AND_CMP_GTE_LTE_LONG, NOT_AND_CMP_LONG, LONG_DIV_CONST, ROUND3 from SlowPathFusionBenchmark.Case to FusionFollowupBenchmark (SlowPath.Case == 96205ababfea's 14 constants, reviewer-verified; TO_LOWER_* fusesToday overrides stay). Added FusionStrategyBenchmark.planMiss (H16) + planCachedThenGet (H8, @Param n=1,4,16 nested state so run/planCached not multiplied) + confirmed KEYWORD_EQ_PLAIN (H4). Benchmark-only FusionSettings.resetClassCacheForBenchmarks hook. Followup setup asserts fusesToday consistency both ways. All smoke G3 (-f1 -wi1 -w1s -i1 -r1s -foe true, all params): PASS, no exceptions. 1 doc nit applied. |
| H14 | done | c1474931f0e | ModTests (Int/Long MIN_VALUE % -1 binary+ByConstant == 0, no warnings); MappingEncodingAwareTests (IP/VERSION literal does NOT fuse) | y (item 13 IP/VERSION guard) | 16 small fixes, all still applied at HEAD. Behavioral: item 13 buildConstant requires DataType.isString so IP/VERSION BytesRef literals stop fusing as keyword constants (red lines captured); item 1 Mod MIN%-1 clause was comment-only (kernel already lhs%rhs; tests added). Cleanups: dropped isBaked filters (constantsInEmitOrder already excludes; one assert); nested-mapping MV own counter; allExactArrayVectors only in PLAIN/CHECKED; get delegates to getForTests; dropped unfalsifiable FusedClassCache WARN expectations + reflection test + unused ctor; CodeBlock in FusionAwareGen; Releasables import; LoopSpec.checked assert; comments (static counters, ignoringWarnings, fail-fast). Reviewer GO (items 2/8/13 confirmed byte/behavior-safe); 3 nits not applied (item-10 deletion is plan-mandated; 15/6 acceptable as-is). |
| H15 | done | (evidence branch) | n/a (report consolidation) | n/a | FINAL_ITER4.md filled for all items H1-H16; FINAL.md + PERF_RUN.md carry SUPERSEDED banners; stale FINAL_FOLLOWUP open-questions A2/E6/E7 struck through -> RESOLVED (via H8/H11/H8) + iteration-4 WARN-once/G7 correction note; round C reviewer numbers recorded in "Measured by the reviewer (round C)". |
| H16 | done | 3f2d8bbbfd1 (with H13) | n/a (benchmark; smoke only) | n/a | FusionStrategyBenchmark.planMiss added (resets class cache per invocation then builds factory -> measures cold stitch miss; AverageTime). H16 'Do not' constraints respected: no hand-emitted stack frames, no disabled verification, no JDK ClassFile API. Smoked (strategy-h13.txt): 28 cells (7 shapes x 4 strategies), no exceptions. Human profiles with async-profiler (no timing read here). |

## Gates
- Final *fusion* gate: at HEAD c1474931f0e BUILD SUCCESSFUL 4m37s, FUSION_GATE_EXIT=0 (gen:test + gen:spotless + compute:test + esql:test --tests *fusion* + compute/esql spotless). Log: /tmp/final-fusion-gate.log
- CsvIT verify on (after H1, H2, H5, final):
  - after H1: BUILD SUCCESSFUL (4m47s), exit 0, verify on (-ea default). Log: T4/csvit-h1.log
  - after H2: BUILD SUCCESSFUL (2m44s), exit 0, verify on. Log: T4/csvit-h2.log
  - after H5: BUILD SUCCESSFUL (2m52s), exit 0, verify on. Log: T4/csvit-h5.log
  - final (HEAD c1474931f0e): BUILD SUCCESSFUL (3m2s), CSVIT_EXIT=0, 0 failures, verify on. Log: /tmp/final-csvit.log
- Suite with -Desql.fusion.verify=false: 251 tests, 0 failures, 5 skipped (H6; verify-on hard-asserts now assumeTrue-skip). Full fusion gate at H6 HEAD c01679591fd: BUILD SUCCESSFUL 4m20s, exit 0.

## Coverage (H9) vs F0
Full CsvIT (ALL csv-spec: 7657 tests, 252 skipped, 0 failures, 4m50s) with
-Dtests.jvm.argline=-Desql.fusion.coverage_report=true. 34523 top-level expressions.
Counted ONCE per top-level expression (EVAL/WHERE/FilterEval pair; declined FilterEval = 1, not 3).
Category defs (addendum X3 not in tree; implemented in FusionCoverage.java):
FUSED=top-level produced a fused factory; ISLAND=not fused but a proper descendant would fuse under the same planner gates; FRAGMENT=not fused, no island, depth>=2 fusable-operator skeleton that still refused; NONE=nothing fusable at depth>=2.
Mechanism: sysprop-only esql.fusion.coverage_report (Setting removed; it clobbered the sysprop at node start). Raw: T4/h9-coverage.txt.

| Category | F0 | H9 (now) |
|---|---|---|
| FUSED | 18.7 % | **2.9 % (1008)** |
| ISLAND | 2.9 % | 0.6 % (222) |
| FRAGMENT | ~1.3 % | 2.3 % (806) |
| NONE | 68 % | 94.1 % (32487) |

Identity: 1008+222+806+32487 = 34523. stitch-failures=0, runtime-fallbacks=0.

**FUSED did not rise (2.9% vs F0 18.7%).** The pre-H9 probe was not comparable (counted every toEvaluator call: declined FilterEval=3, lone kernels counted; single aggregate); its 10.8% subset / 3.7% reviewer-full-run were not F0 buckets. H9 uses F0's four categories on the full corpus, once per top-level.

Top 10 first-refusal reasons: depth<2=12585, Div=5858, MvSlice=4558, Case=1758, PackDimension=878, ToLong=802, UnpackDimension=756, IsNotNull=652, ToDouble=574, Bucket=505.

## Converters (H12)
Every @ConvertEvaluator whose first process arg is BytesRef. Admitted = @Fusable today and planner can type it. Only ChangeCase is currently @Fusable.
| Converter | In -> Out | Admitted? | Reason |
|---|---|---|---|
| ChangeCase.process (TO_LOWER/TO_UPPER) | KEYWORD/TEXT -> BytesRef | admitted | No warn, never throws, SINGLETON @Fixed (Locale, Case). Nested only (D4). |
| ToVersion.FromString | KEYWORD/TEXT -> VERSION | refused | encodeVersion throws IAE on >=128-digit group of a legal keyword (throw-not-warn over dict); elementOf has no VERSION. |
| ToString.FromIP | IP -> KEYWORD | refused | elementOf/matches have no IP; input not KEYWORD/TEXT. |
| ToString.FromVersion | VERSION -> KEYWORD | refused | elementOf/matches have no VERSION. |
| ToString.From{Geo,Cartesian}{Point,Shape} | geo -> KEYWORD | refused | input not KEYWORD/TEXT; no spatial in elementOf. |
| ToString.FromHistogram | histogram -> KEYWORD | refused | warnExceptions=IAE. |
| UrlEncode / UrlEncodeComponent | KEYWORD/TEXT -> KEYWORD | refused | THREAD_LOCAL @Fixed scratch (generator rejects). |
| UrlDecode | KEYWORD/TEXT -> KEYWORD | refused | warnExceptions=IAE. |
| ParseIp / TO_IP | KEYWORD/TEXT -> IP | refused | warnExceptions=IAE + THREAD_LOCAL scratch; IP not in elementOf. |
| ToBoolean.FromString | KEYWORD/TEXT -> boolean | refused | non-BytesRef return (now a generator error). |
| To{Double,Integer,Long,UnsignedLong}.FromString | KEYWORD/TEXT -> numeric | refused | warnExceptions + non-BytesRef return. |
| To{Datetime,DateNanos}.FromString | KEYWORD/TEXT -> long | refused | warnExceptions + non-BytesRef return. |
| ToDateRange.FromString | KEYWORD/TEXT -> LongRange | refused | warnExceptions + non-BytesRef return. |
| To{Geo,Cartesian}{Point,Shape}.FromString | KEYWORD/TEXT -> geo | refused | warnExceptions=IAE; not in elementOf. |
| To{Geohash,Geohex,Geotile}.FromString | KEYWORD/TEXT -> long | refused | warnExceptions + non-BytesRef return. |
| StX/StY From{Cartesian,Geo}WKB | WKB -> double | refused | warnExceptions + non-BytesRef return + spatial not in elementOf. |
| ToTDigest.FromHistogram | histogram -> TDigest | refused | warnExceptions + THREAD_LOCAL @Fixed + non-BytesRef return. |
(ToBase64/FromBase64 are @Evaluator, not @ConvertEvaluator.)

## E1 (H11)
Corpus: FusionClassDumpTests, 33 shapes (TE structural corpus minus the dead-path-only
mapping_append_suffix, which lost its producer when the mapping emit path was deleted).
All six dumps share md5 7cbf6fa3520c30dd271ded1ca9b0dd8a (byte-identical throughout).
| Step | Commit | Changed shapes | Hash list file |
|---|---|---|---|
| pre-H1 | dec0810a47a | baseline | T4/e1/pre-h1.txt |
| H1 | 2a7e47987f9 | byte-identical (aliasing fix is eval-time spare, not fused-class bytes) | T4/e1/h1.txt |
| H2 | 25fd1cabcaf | byte-identical (huge-method guard refuses; in-budget TE classes unchanged) | T4/e1/h2.txt |
| H3 | 9622eed87db | byte-identical (TE bakedDiv already emitted bounded baked form) | T4/e1/h3.txt |
| pre-H11 | 333f1202b3f | baseline for H11 | T4/e1/pre-h11.txt |
| H11 | 5879c12a57e | byte-identical (pure refactor; REQUIRED) | T4/e1/h11.txt |

## Line counts (H11)
| File | before | after |
|---|---|---|
| Stitcher.java | 1482 | 1199 (<=1200) |
| VectorLoopEmitter.java | 1049 | 1013 |
| BlockLoopEmitter.java | 1015 | 701 |
| FilterEvalEmitter.java | 488 | 450 |
| four-file total | 4034 | 3363 (E6 baseline 3996; target <=3846) |
New helpers not in total: FusedBuilderEmit 82, FusedMethodCodeLengths 139.

## Benchmark cells added or moved (smoke tables in T4/smoke/)
Smoke protocol: -f 1 -wi 1 -w 1s -i 1 -r 1s -foe true, all @Params. No timings read (human runs timed on dedicated host).
| Cell | Benchmark | Action | Smoke file | Result |
|---|---|---|---|---|
| AND_CMP_GTE_LTE_LONG | FusionFollowupBenchmark | moved from SlowPath (fusesToday=true) | followup-h13.txt | PASS |
| NOT_AND_CMP_LONG | FusionFollowupBenchmark | moved from SlowPath (fusesToday=true) | followup-h13.txt | PASS |
| LONG_DIV_CONST | FusionFollowupBenchmark | moved from SlowPath (fusesToday=true) | followup-h13.txt | PASS |
| ROUND3 | FusionFollowupBenchmark | moved from SlowPath (fusesToday=false) | followup-h13.txt | PASS |
| KEYWORD_EQ_PLAIN | FusionFollowupBenchmark | added in H4, smoked here | followup-h13.txt | PASS |
| planMiss | FusionStrategyBenchmark | added (H16) | strategy-h13.txt | PASS (28 cells) |
| planCachedThenGet | FusionStrategyBenchmark | added (H8), n=1,4,16 | strategy-h13.txt | PASS (84 cells) |
| (SlowPath.Case restored) | SlowPathFusionBenchmark | 4 cases removed -> back to 96205ababfea set | slowpath-h13.txt | PASS (28 cells) |

## Measured by the reviewer (round C)
Measured 2026-11-01 on a c7i.metal-24xl. tip / stabilize-final control / round-B HEAD.
- E3-logical accepted: AND_CMP 1.04 (NONE) / 0.99 (TYPES) ns/row vs <=1.10.
- D3 accepted: EQ_TO_LOWER_ORDINAL 1.98, LENGTH_TO_LOWER_ORDINAL 2.37 ns/row (unfused 8.12 / 18.28); <=1.5 target was reviewer error (TO_LOWER ~115 ns/dict entry). Plain cell 109 fused vs 119 unfused.
- F1 accepted: LONG_DIV_CONST 2.00 fused vs 4.14 unfused.
- E4' accepted on perf: DEEP_LOGICAL 0.52x unfused; DEEP_ARITH matches old split. Correctness gaps -> H2.
- E6/E7 perf-neutral: all 11 shared slow-path shapes within +/-1% of round B.
- C4 closed, no sampling: warm CsvIT 211s verify-on vs 206s off (~2%).
- G9: ADD_MUL_SUB 4% slower than control (target 3%) -> H8.
- Plan time warm cache: +30% on checked-arith shapes (17.3 vs 13.2 us) -> H8 + H16.
- G8 stays out: full CsvIT shows zero stitch failures across 26,113 attempts.

## Deviations
H12: plan said "Try TO_VERSION from string (eligible per the review)"; implemented the closest safe version = REFUSE. Why: ToVersion.fromKeyword -> VersionEncoder.encodeVersion throws IllegalArgumentException on a legal keyword with a >=128-digit group, with NO warnExceptions; generated @ConvertEvaluator+@Fusable descriptors hardcode dictionarySafe=true, so the encoding-aware path would run encodeVersion over an unreferenced dictionary entry and throw where the unfused chain would not. elementOf/matches also have no VERSION type. Evidence: reviewer independently traced the chain and confirmed the refusal is correct and unmitigated by the H5 dictionary-safety guard (which reads only the descriptor flag). Recorded as refused with reason in the converter table.
H1: FilterEval LEFT/RIGHT shapes unstitchable (@Fixed scratch const "unsupported fused constant element [L]"); used k1==k2/k1!=k2/k1==TRIM(k2) two-column analogs for the filter-eval guards. LEFT(k1)==LEFT(k2) retained as coverage-only (each LEFT writes its own scratch, so it does not reproduce the aliasing pre-fix).

## Open questions
(tracked per item)
- H10 DATETIME GTE/LTE: RESOLVED (mapped to LONG for comparisons; not open).
- H5 follow-ups: compile-time guard for overflowChecked=false+warnExceptions; dictionarySafe classification test (not blocking).
