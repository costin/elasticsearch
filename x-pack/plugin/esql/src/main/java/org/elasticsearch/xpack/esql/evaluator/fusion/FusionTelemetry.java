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
 * Process-wide counters for expression-fusion stitching failures (acceptance criterion #5).
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
     * Number of adaptive evaluators that crossed their row threshold and successfully switched from the unfused chain
     * to a stitched fused evaluator (Stage-6 {@code esql.fusion.adaptive.min_rows}). Diagnostic side-channel only.
     */
    private static final LongAdder ADAPTIVE_SWITCHES = new LongAdder();

    /**
     * Number of adaptive evaluators that crossed their row threshold but whose deferred stitch failed, so they stayed
     * on the unfused chain for the rest of their life. Diagnostic side-channel only.
     */
    private static final LongAdder ADAPTIVE_ABANDONED = new LongAdder();

    /**
     * Number of shapes that fused on the block path but degraded to block-only because the vector fast path could not
     * be built (e.g. the plain-vector huge-method guard refused a deep tree). Diagnostic side-channel only.
     */
    private static final LongAdder VECTOR_DEGRADED = new LongAdder();

    /**
     * Opt-in coverage probe (F4). Default {@code false}: every {@code recordAttempt}/{@code recordFused}/
     * {@code recordRefusedAtPlan}/{@code recordRuntimeFallback} call early-returns, so production pays nothing.
     * Latched from {@code esql.fusion.coverage_report} at node start (and honored from the matching system property
     * so a CsvIT {@code -D} takes effect). {@link #recordFailure} stays unconditional — it is the criterion #5
     * stitch-failure counter, not a coverage bucket.
     */
    static volatile boolean coverageEnabled = false;

    /** Fusion-build attempts that passed the kill-switch / A/B sample (one per {@code maybeFuse*} call that proceeded). */
    private static final LongAdder ATTEMPTS = new LongAdder();

    /** Attempts that produced a stitched fused factory (eager return, FilterEval {@code fused()==true}, or adaptive resolve). */
    private static final LongAdder FUSED = new LongAdder();

    /** Attempts that returned {@code null} at plan time after the attempt was counted (ineligible tree / depth / mapping). */
    private static final LongAdder REFUSED_AT_PLAN = new LongAdder();

    /**
     * Eval-time unfused fallbacks (LinkageError latch, {@code fusionDisabled}, nested-mapping MV). Counted once per
     * evaluator instance, not per page.
     */
    private static final LongAdder RUNTIME_FALLBACK = new LongAdder();

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
        // did before F4 (aggregate + per-shape). A non-null path is a plan-time site that lost fusion.
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

    /** Records one adaptive evaluator successfully switching to its stitched fused evaluator after the row threshold. */
    public static void recordAdaptiveSwitch() {
        ADAPTIVE_SWITCHES.increment();
    }

    /** Records one adaptive evaluator whose deferred stitch failed, so it stayed unfused. */
    public static void recordAdaptiveAbandoned() {
        ADAPTIVE_ABANDONED.increment();
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

    /** Number of adaptive unfused-to-fused switches recorded. */
    public static long adaptiveSwitches() {
        return ADAPTIVE_SWITCHES.sum();
    }

    /** Number of adaptive evaluators that crossed the threshold but abandoned fusion after a failed stitch. */
    public static long adaptiveAbandoned() {
        return ADAPTIVE_ABANDONED.sum();
    }

    /** An immutable snapshot of the per-shape counters, for diagnostics or assertions. */
    public static Map<String, Long> snapshot() {
        return FAILURES_BY_SHAPE.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }

    /** Records one fusion-build attempt. No-op when {@link #coverageEnabled} is false. */
    public static void recordAttempt() {
        if (coverageEnabled == false) {
            return;
        }
        ATTEMPTS.increment();
    }

    /** Records one attempt that produced a stitched fused factory. No-op when coverage is off. */
    public static void recordFused() {
        if (coverageEnabled == false) {
            return;
        }
        FUSED.increment();
    }

    /** Records one attempt refused at plan time after it was counted. No-op when coverage is off. */
    public static void recordRefusedAtPlan() {
        if (coverageEnabled == false) {
            return;
        }
        REFUSED_AT_PLAN.increment();
    }

    /**
     * Records one eval-time unfused fallback (LinkageError latch, nested-mapping MV). Callers must invoke this at most
     * once per evaluator instance. No-op when coverage is off.
     */
    public static void recordRuntimeFallback() {
        if (coverageEnabled == false) {
            return;
        }
        RUNTIME_FALLBACK.increment();
    }

    /** Coverage attempts recorded while the probe was on. */
    public static long attempts() {
        return ATTEMPTS.sum();
    }

    /** Coverage fused successes recorded while the probe was on. */
    public static long fused() {
        return FUSED.sum();
    }

    /** Coverage plan-time refusals recorded while the probe was on. */
    public static long refusedAtPlan() {
        return REFUSED_AT_PLAN.sum();
    }

    /** Coverage runtime fallbacks recorded while the probe was on. */
    public static long runtimeFallbacks() {
        return RUNTIME_FALLBACK.sum();
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
     * Human-readable coverage summary: attempts, fused, refused-at-plan, plan-time stitch-failures (plus
     * per-{@link FusedClassCache.Path} breakdown and {@link #snapshot() top shapes}), runtime-fallbacks, and the
     * fused/attempt percentage. {@code stitch-failures=} is the coverage plan-time counter, not
     * {@link #totalFailures()} — eval-time LinkageError after a successful fuse is a runtime-fallback. Safe to call
     * with coverage off (all zeros).
     */
    public static String coverageReport() {
        long attempts = ATTEMPTS.sum();
        long fused = FUSED.sum();
        long refused = REFUSED_AT_PLAN.sum();
        long stitch = PLAN_TIME_STITCH.sum();
        long runtime = RUNTIME_FALLBACK.sum();
        String percent = attempts == 0L ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0d * fused / attempts);
        StringBuilder out = new StringBuilder();
        out.append("fusion coverage: attempts=")
            .append(attempts)
            .append(" fused=")
            .append(fused)
            .append(" refused-at-plan=")
            .append(refused)
            .append(" stitch-failures=")
            .append(stitch)
            .append(" runtime-fallbacks=")
            .append(runtime)
            .append(" fused/attempt=")
            .append(percent);
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

    /** Test-only: clears all counters so an assertion can observe a failure it triggered in isolation. */
    static void resetForTests() {
        FAILURES_BY_SHAPE.clear();
        FAILURES_BY_PATH.clear();
        TOTAL_FAILURES.reset();
        ADAPTIVE_SWITCHES.reset();
        ADAPTIVE_ABANDONED.reset();
        VECTOR_DEGRADED.reset();
        ATTEMPTS.reset();
        FUSED.reset();
        REFUSED_AT_PLAN.reset();
        RUNTIME_FALLBACK.reset();
        PLAN_TIME_STITCH.reset();
    }
}
