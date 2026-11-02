# MERGE per-commit gate/review log

Gate command (fusion gate):
`./gradlew :x-pack:plugin:esql:compute:gen:test :x-pack:plugin:esql:compute:gen:spotlessJavaCheck :x-pack:plugin:esql:compute:test :x-pack:plugin:esql:test --tests "*fusion*" :x-pack:plugin:esql:compute:spotlessJavaCheck :x-pack:plugin:esql:spotlessJavaCheck`

Per D commit also: E1 hash list (byte-identical vs previous unless declared), D.8 window-6 scan output, spotlessApply.

## D.0a — extend fusion class-dump corpus (commit 42a1e77a6be)
Test-only (FusionClassDumpTests + StitcherBudgetTests/BodyExtractorTests fixtures); no src/main change. Also corrected the H1 E1 reason in FINAL_ITER4.md (iter5 I5): H1 emits one new BytesRef per column and DOES change fused bytes; the old 33-shape corpus just had no two-BytesRef-column shape.
Added 13 shapes (46 total): block_k1_eq_left_k2, filter_eval_k1_eq_k2, filter_eval_k1_eq_left_k2, block_double_div_const, checked_baked_div_mod, block_length_concat, block_length_to_lower, logical_block_not_and_or, filter_eval_not_and_mul_add, checked_add_mul_right, plain_int_add_gt, plain_cast_int_to_double_add, block_warn_only.
Shape-3 deviation: filter-eval LEFT uses a 2-arg no-scratch overload because FilterEvalEmitter's pushConstantArg cannot forward an 'L' constant (iter5 I4, fixed in P0.1); pred helper still has two BytesRef columns so H1 per-column spares are exercised inside the helper.
Reviewer (fresh): GO, no blockers. Verified descriptor flag positions (WARN_ONLY warns=true/overflowChecked=false; CONCAT variadic; TO_LOWER mapping), all 13 shapes exercise distinct paths (46 distinct hashes), fixtures meet BodyExtractor contract. 3 non-blocking nits (plain_int_add_gt uses checked addInt fixture on the plain path - pin still sound; WARN_ONLY reuses overflowExceptionType field; CONCAT fixture dictionarySafe default) - skipped, characterized as non-issues for the dump.
E1: 33 pre-existing shapes byte-identical vs pre-dd.txt; D0a.txt is the new baseline (md5 76d44677b9bf6c8be8530809327630f2).
Gate: fusion gate BUILD SUCCESSFUL 4m52s, GATE_D0A_EXIT=0. Emitter dup scan unchanged at 23 regions (test-only).
Additive corpus (coverage-only) - no fails-before-fix.

## D.1 — delete fusion SIMD path (commit 68bf1e55cab)
Pure deletion. Deleted FusionSimd.java + StitcherSimdFusionTests.java; edited 20 files (VectorLoopEmitter -208, Stitcher, FusionSettings drop FUSION_SIMD_SETTING+simdEnabled+accessors, EsqlPlugin unregister, CsvIT drop sysprop, FusionPlanner never emits SIMD plan, FusionPlan drop vectorComparison, FusedClassCache drop Path.SIMD_VECTOR, FEEF always PLAIN_VECTOR interface+raw, FusionCompilationService drop SIMD overloads, FusionDescriptor/Fusable/Add/Mul/Sub javadoc-only, pruned SIMD cases from StitcherHugeMethodGuardTests/ComparisonFusionDifferentialTests/FusionFallbackHardeningTests/BodyExtractorTests). No unrelated SIMD touched (vector scorers, WildcardLike, ByteMatchers, LongIntAdaptiveBlockHash untouched).
Reviewer (fresh): GO, no blockers. Verified scope (only fusion SIMD), completeness (0 matches repo-wide for FusionSimd/useSimd/vectorComparison/SIMD_VECTOR/esql.fusion.simd/etc.), collapse correctness (non-checked path == old non-SIMD else branch, FusionPlan 9-arg ctor updated), switch/enum hygiene (Path switch exhaustive, no default), test deletions sound. 2 nits = confirmations (no fusion-SIMD benchmark cell existed; javadoc accurate).
E1: 43 survivors byte-identical to D0a.txt; D1.txt baseline. Emitter dup scan 23 regions (dupscan-d1.txt). Eight-file total 4142->3843.
Gate: fusion gate BUILD SUCCESSFUL 5m31s, GATE_D1_EXIT=0.
Pure deletion with byte-identity net - no fails-before-fix.
