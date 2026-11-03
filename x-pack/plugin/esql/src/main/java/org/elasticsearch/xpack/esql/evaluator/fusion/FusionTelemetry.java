/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for expression-fusion stitching failures.
 *
 * <p>When {@link FusionPlanner} tries to stitch a fusable expression subtree and the stitch cannot be verified,
 * linked, or defined — surfacing as a {@link org.elasticsearch.compute.operator.fusion.Stitcher.StitchingException}
 * or, defensively, a {@link LinkageError} at first evaluation — the query must never break: the planner logs a
 * warning, falls back to the unfused evaluator chain, and records the failure here keyed by the expression
 * <em>shape</em> (the operator tree + kernel set, independent of the concrete columns bound to it). Keying by shape
 * lets an operator distinguish a single pathological expression shape that always fails to stitch from a broad
 * regression across many shapes, without the counter's cardinality growing with query volume.
 *
 * <p>The counters are a diagnostic side-channel only; production evaluation never reads them. They live on the cold
 * plan-time path (one increment per failed stitch, not per row), so a {@link ConcurrentHashMap} of {@link LongAdder}s
 * is more than fast enough and is safe under the concurrent planning that happens across shards.
 *
 * <p>Shape cardinality is naturally bounded by the number of distinct expression shapes, but a pathological workload
 * (e.g. machine-generated queries) could in principle produce unboundedly many shapes. To keep this static map from
 * growing without limit the per-shape map is capped at {@link #MAX_TRACKED_SHAPES}; once full, further <em>new</em>
 * shapes are still counted in the {@link #totalFailures() aggregate} but are not tracked individually.
 */
public final class FusionTelemetry {

    private FusionTelemetry() {}

    /**
     * Upper bound on distinct shapes tracked individually, so the static map cannot grow without limit under a
     * pathological (e.g. generated-query) workload. Diagnostics only, so a coarse cap is fine.
     */
    static final int MAX_TRACKED_SHAPES = 1024;

    /** Per-shape failure counts, capped at {@link #MAX_TRACKED_SHAPES} distinct keys. */
    private static final ConcurrentHashMap<String, LongAdder> FAILURES_BY_SHAPE = new ConcurrentHashMap<>();

    /** Total stitch failures across all shapes (including shapes dropped by the cap), so the aggregate is exact. */
    private static final LongAdder TOTAL_FAILURES = new LongAdder();

    /**
     * Number of shapes that fused on the block path but degraded to block-only because the vector fast path could not
     * be built (e.g. the plain-vector huge-method guard refused a deep tree). Diagnostic side-channel only.
     */
    private static final LongAdder VECTOR_DEGRADED = new LongAdder();

    /**
     * Number of plans that could not bake a divisor tuple because the per-shape tuple registry outer map was already
     * at {@link FusionPlanner#BAKED_DIVISOR_SHAPE_CAP}. Diagnostic side-channel only; not coverage-gated.
     */
    private static final LongAdder BAKING_DISABLED_REGISTRY_FULL = new LongAdder();

    /**
     * Opt-in coverage probe. Default {@code false}: every coverage {@code record*} call early-returns, so
     * production pays nothing. Latched from the {@link FusionSettings} constant default (off) and flipped only via
     * {@link FusionSettings#setCoverageReportEnabledForTests}; not a node Setting. {@link #recordFailure} stays
     * unconditional — it is the stitch-failure counter, not a coverage bucket.
     */
    static volatile boolean coverageEnabled = false;

    /**
     * Coverage category of one top-level expression. See {@link FusionCoverage} for the definitions.
     */
    public enum Category {
        FUSED,
        ISLAND,
        FRAGMENT,
        NONE
    }

    /** Nesting of {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} entry points so a declined FilterEval counts once. */
    private static final ThreadLocal<Integer> TOP_LEVEL_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** Top-level expressions classified while the probe was on (one per EVAL/WHERE/FilterEval pair). */
    private static final LongAdder ATTEMPTS = new LongAdder();

    /** Top-level expressions whose factory is fused (eager, or FilterEval {@code fused()==true}). */
    private static final LongAdder FUSED = new LongAdder();

    /** Top-level expressions that contain a fusable descendant island (partial codegen out of scope). */
    private static final LongAdder ISLAND = new LongAdder();

    /** Top-level expressions with a depth≥2 fusable-operator skeleton that still refused. */
    private static final LongAdder FRAGMENT = new LongAdder();

    /** Top-level expressions with nothing fusable at depth≥2 (lone kernel / unfusable root). */
    private static final LongAdder NONE = new LongAdder();

    /** First-refusal reasons (simple class name, or {@code depth<2}) for non-{@link Category#FUSED} top-level expressions. */
    private static final ConcurrentHashMap<String, LongAdder> REFUSALS = new ConcurrentHashMap<>();

    /**
     * Eval-time unfused fallbacks (LinkageError latch, {@code fusionDisabled}). Counted once per evaluator instance,
     * not per page. Nested-mapping MV uses {@link #NESTED_MAPPING_MV_FALLBACK}.
     */
    private static final LongAdder RUNTIME_FALLBACK = new LongAdder();

    /**
     * Eval-time nested-mapping multi-value/null fallbacks (a non-vector page keeps unfused MV semantics). Counted
     * once per evaluator instance, not per page.
     */
    private static final LongAdder NESTED_MAPPING_MV_FALLBACK = new LongAdder();

    /**
     * Coverage-only: plan-time stitch failures that lost fusion (the {@code recordFailure(shape, path)} sites). Drives
     * {@link #coverageReport()}'s {@code stitch-failures=} line so an eval-time {@link LinkageError} after a successful
     * fuse counts only in {@link #RUNTIME_FALLBACK}, keeping {@code fused + refused + stitch == attempts}.
     */
    private static final LongAdder PLAN_TIME_STITCH = new LongAdder();

    /** Stitch failures classified by {@link FusedClassCache.Path} so FILTER_EVAL_BLOCK is visible in the coverage report. */
    private static final ConcurrentHashMap<FusedClassCache.Path, LongAdder> FAILURES_BY_PATH = new ConcurrentHashMap<>();

    /**
     * Records one stitching failure for {@code shape} (the {@link FusionPlanner} shape signature). The aggregate is
     * always incremented; the per-shape counter is incremented for an already-tracked shape, or for a new shape only
     * while the map is below {@link #MAX_TRACKED_SHAPES} — beyond the cap a new shape counts only in the aggregate.
     */
    public static void recordFailure(String shape) {
        recordFailure(shape, null);
    }

    /**
     * As {@link #recordFailure(String)}, additionally classifying the failure by {@code path} when non-null so the
     * coverage report can surface {@link FusedClassCache.Path#FILTER_EVAL_BLOCK} (and every other emit path) without a
     * new stitch-failure enum. Eval-time LinkageError sites pass {@code null} — they have no compiling path in scope.
     */
    public static void recordFailure(String shape, FusedClassCache.Path path) {
        TOTAL_FAILURES.increment();
        LongAdder existing = FAILURES_BY_SHAPE.get(shape);
        if (existing != null) {
            existing.increment();
        } else if (FAILURES_BY_SHAPE.size() < MAX_TRACKED_SHAPES) {
            // Guard the size check with computeIfAbsent so concurrent inserts cannot grow the map materially past the cap.
            FAILURES_BY_SHAPE.computeIfAbsent(shape, unused -> new LongAdder()).increment();
        }
        // Path map + plan-time stitch bucket are coverage-only. With the probe off, recordFailure does exactly what it
        // did without the coverage probe (aggregate + per-shape). A non-null path is a plan-time site that lost fusion.
        if (coverageEnabled && path != null) {
            FAILURES_BY_PATH.computeIfAbsent(path, unused -> new LongAdder()).increment();
            PLAN_TIME_STITCH.increment();
        }
    }

    /** Number of stitch failures recorded for {@code shape}. */
    public static long failuresForShape(String shape) {
        LongAdder counter = FAILURES_BY_SHAPE.get(shape);
        return counter == null ? 0L : counter.sum();
    }

    /** Total stitch failures recorded across every shape. */
    public static long totalFailures() {
        return TOTAL_FAILURES.sum();
    }

    /**
     * Records one shape whose block path fused but whose vector fast path could not be built (most commonly the
     * plain-vector huge-method guard refusing a deep tree), so it degraded to block-only fusion instead of falling all
     * the way back to the unfused chain.
     */
    public static void recordVectorDegraded(String shape) {
        VECTOR_DEGRADED.increment();
    }

    /** Number of shapes that degraded to block-only fusion after a vector-fast-path stitch failure. */
    public static long vectorDegraded() {
        return VECTOR_DEGRADED.sum();
    }

    /**
     * Records one plan that could not bake a divisor tuple because the per-shape tuple registry outer map was already
     * at {@link FusionPlanner#BAKED_DIVISOR_SHAPE_CAP}.
     */
    public static void recordBakingDisabledRegistryFull() {
        BAKING_DISABLED_REGISTRY_FULL.increment();
    }

    /** Number of plans that skipped baking because the tuple registry outer map was full. */
    public static long bakingDisabledRegistryFull() {
        return BAKING_DISABLED_REGISTRY_FULL.sum();
    }

    /** An immutable snapshot of the per-shape counters, for diagnostics or assertions. */
    public static Map<String, Long> snapshot() {
        return FAILURES_BY_SHAPE.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }

    /** Pushes one EvalMapper top-level entry. Coverage-only; must pair with {@link #exitTopLevel()}. */
    public static void enterTopLevel() {
        TOP_LEVEL_DEPTH.set(TOP_LEVEL_DEPTH.get() + 1);
    }

    /** Pops one EvalMapper top-level entry. */
    public static void exitTopLevel() {
        int depth = TOP_LEVEL_DEPTH.get() - 1;
        if (depth <= 0) {
            TOP_LEVEL_DEPTH.remove();
        } else {
            TOP_LEVEL_DEPTH.set(depth);
        }
    }

    /** Records one fusion-build attempt. No-op when {@link #coverageEnabled} is false. Kept for unit-test direct calls. */
    public static void recordAttempt() {
        if (coverageEnabled == false) {
            return;
        }
        ATTEMPTS.increment();
    }

    /**
     * TEST-ONLY. Increments {@link Category#FUSED} without {@link #ATTEMPTS} — must never be used for real counting.
     * {@link #recordTopLevel} is the sole real counting entry point. Calling this outside the coverage-off no-op test
     * would break the {@code FUSED+ISLAND+FRAGMENT+NONE==ATTEMPTS} accounting identity. No-op when coverage is off.
     */
    public static void recordFused() {
        if (coverageEnabled == false) {
            return;
        }
        FUSED.increment();
    }

    /**
     * Records one top-level expression's coverage category. Nested {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper}
     * calls (the two {@code toEvaluator} fallbacks of a declined FilterEval) are ignored so the pair counts once.
     * No-op when coverage is off.
     */
    public static void recordTopLevel(Category category, String refusalReason) {
        if (coverageEnabled == false || TOP_LEVEL_DEPTH.get() != 1) {
            return;
        }
        ATTEMPTS.increment();
        switch (category) {
            case FUSED -> FUSED.increment();
            case ISLAND -> ISLAND.increment();
            case FRAGMENT -> FRAGMENT.increment();
            case NONE -> NONE.increment();
        }
        if (category != Category.FUSED && refusalReason != null && refusalReason.isEmpty() == false) {
            REFUSALS.computeIfAbsent(refusalReason, unused -> new LongAdder()).increment();
        }
    }

    /**
     * Records one eval-time unfused fallback (LinkageError latch). Callers must invoke this at most once per evaluator
     * instance. No-op when coverage is off. Nested-mapping MV uses {@link #recordNestedMappingMvFallback()}.
     */
    public static void recordRuntimeFallback() {
        if (coverageEnabled == false) {
            return;
        }
        RUNTIME_FALLBACK.increment();
    }

    /**
     * Records one nested-mapping MV/null fallback. Callers must invoke this at most once per evaluator instance.
     * No-op when coverage is off.
     */
    public static void recordNestedMappingMvFallback() {
        if (coverageEnabled == false) {
            return;
        }
        NESTED_MAPPING_MV_FALLBACK.increment();
    }

    /** Coverage top-level expressions recorded while the probe was on. */
    public static long attempts() {
        return ATTEMPTS.sum();
    }

    /** Coverage {@link Category#FUSED} top-level expressions. */
    public static long fused() {
        return FUSED.sum();
    }

    /** Coverage {@link Category#ISLAND} top-level expressions. */
    public static long island() {
        return ISLAND.sum();
    }

    /** Coverage {@link Category#FRAGMENT} top-level expressions. */
    public static long fragment() {
        return FRAGMENT.sum();
    }

    /** Coverage {@link Category#NONE} top-level expressions. */
    public static long none() {
        return NONE.sum();
    }

    /**
     * Derived as island+fragment+none (every top-level attempt that did not FUSE); retained under this name for
     * back-compat of existing assertions.
     */
    public static long refusedAtPlan() {
        return ISLAND.sum() + FRAGMENT.sum() + NONE.sum();
    }

    /** First-refusal count for {@code reason}, or {@code 0}. */
    public static long refusalsFor(String reason) {
        LongAdder counter = REFUSALS.get(reason);
        return counter == null ? 0L : counter.sum();
    }

    /** Coverage runtime fallbacks recorded while the probe was on. */
    public static long runtimeFallbacks() {
        return RUNTIME_FALLBACK.sum();
    }

    /** Coverage nested-mapping MV fallbacks recorded while the probe was on. */
    public static long nestedMappingMvFallbacks() {
        return NESTED_MAPPING_MV_FALLBACK.sum();
    }

    /** Coverage plan-time stitch failures (the report's {@code stitch-failures=} line). Not eval-time LinkageError. */
    public static long planTimeStitchFailures() {
        return PLAN_TIME_STITCH.sum();
    }

    /** Stitch failures recorded against {@code path}; {@code 0} if none. */
    public static long failuresForPath(FusedClassCache.Path path) {
        LongAdder counter = FAILURES_BY_PATH.get(path);
        return counter == null ? 0L : counter.sum();
    }

    /**
     * Human-readable coverage summary: category counts (FUSED/ISLAND/FRAGMENT/NONE) over top-level expressions,
     * top-10 first-refusal reasons, plan-time stitch-failures (plus per-{@link FusedClassCache.Path} breakdown and
     * {@link #snapshot() top shapes}), runtime-fallbacks, and nested-mapping-mv-fallbacks. {@code stitch-failures=}
     * is the coverage plan-time counter, not {@link #totalFailures()} — eval-time LinkageError after a successful fuse
     * is a runtime-fallback. Nested-mapping MV pages are counted separately.
     * Safe to call with coverage off (all zeros).
     */
    public static String coverageReport() {
        long attempts = ATTEMPTS.sum();
        long fused = FUSED.sum();
        long island = ISLAND.sum();
        long fragment = FRAGMENT.sum();
        long none = NONE.sum();
        long stitch = PLAN_TIME_STITCH.sum();
        long runtime = RUNTIME_FALLBACK.sum();
        long nestedMv = NESTED_MAPPING_MV_FALLBACK.sum();
        String fusedPct = percent(fused, attempts);
        String islandPct = percent(island, attempts);
        String fragmentPct = percent(fragment, attempts);
        String nonePct = percent(none, attempts);
        StringBuilder out = new StringBuilder();
        out.append("fusion coverage: expressions=")
            .append(attempts)
            .append(" FUSED=")
            .append(fused)
            .append(" (")
            .append(fusedPct)
            .append(")")
            .append(" ISLAND=")
            .append(island)
            .append(" (")
            .append(islandPct)
            .append(")")
            .append(" FRAGMENT=")
            .append(fragment)
            .append(" (")
            .append(fragmentPct)
            .append(")")
            .append(" NONE=")
            .append(none)
            .append(" (")
            .append(nonePct)
            .append(")")
            .append(" stitch-failures=")
            .append(stitch)
            .append(" runtime-fallbacks=")
            .append(runtime)
            .append(" nested-mapping-mv-fallbacks=")
            .append(nestedMv);
        List<Map.Entry<String, Long>> refusals = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : REFUSALS.entrySet()) {
            refusals.add(Map.entry(e.getKey(), e.getValue().sum()));
        }
        refusals.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        int reasonLimit = Math.min(10, refusals.size());
        if (reasonLimit > 0) {
            out.append("\n  top refusal reasons: ");
            for (int i = 0; i < reasonLimit; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                Map.Entry<String, Long> e = refusals.get(i);
                out.append(e.getKey()).append("=").append(e.getValue());
            }
        }
        List<String> byPath = new ArrayList<>();
        for (FusedClassCache.Path path : FusedClassCache.Path.values()) {
            long count = failuresForPath(path);
            if (count > 0L) {
                byPath.add(path.name() + "=" + count);
            }
        }
        if (byPath.isEmpty() == false) {
            out.append("\n  stitch-failures by path: ").append(String.join(", ", byPath));
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>(snapshot().entrySet());
        top.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        int limit = Math.min(8, top.size());
        if (limit > 0) {
            out.append("\n  top failed shapes: ");
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                Map.Entry<String, Long> e = top.get(i);
                out.append(e.getKey()).append("=").append(e.getValue());
            }
        }
        return out.toString();
    }

    private static String percent(long count, long total) {
        return total == 0L ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0d * count / total);
    }

    /** Test-only: clears all counters so an assertion can observe a failure it triggered in isolation. */
    static void resetForTests() {
        FAILURES_BY_SHAPE.clear();
        FAILURES_BY_PATH.clear();
        REFUSALS.clear();
        TOP_LEVEL_DEPTH.remove();
        TOTAL_FAILURES.reset();
        VECTOR_DEGRADED.reset();
        BAKING_DISABLED_REGISTRY_FULL.reset();
        ATTEMPTS.reset();
        FUSED.reset();
        ISLAND.reset();
        FRAGMENT.reset();
        NONE.reset();
        RUNTIME_FALLBACK.reset();
        NESTED_MAPPING_MV_FALLBACK.reset();
        PLAN_TIME_STITCH.reset();
    }
}
