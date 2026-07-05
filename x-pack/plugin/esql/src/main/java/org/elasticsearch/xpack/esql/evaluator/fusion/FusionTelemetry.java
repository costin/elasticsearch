/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

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
     * be built (e.g. the plain-vector inlining budget refused a deep tree). Diagnostic side-channel only.
     */
    private static final LongAdder VECTOR_DEGRADED = new LongAdder();

    /**
     * Records one stitching failure for {@code shape} (the {@link FusionPlanner} shape signature). The aggregate is
     * always incremented; the per-shape counter is incremented for an already-tracked shape, or for a new shape only
     * while the map is below {@link #MAX_TRACKED_SHAPES} — beyond the cap a new shape counts only in the aggregate.
     */
    public static void recordFailure(String shape) {
        TOTAL_FAILURES.increment();
        LongAdder existing = FAILURES_BY_SHAPE.get(shape);
        if (existing != null) {
            existing.increment();
            return;
        }
        // Guard the size check with computeIfAbsent so concurrent inserts cannot grow the map materially past the cap.
        if (FAILURES_BY_SHAPE.size() < MAX_TRACKED_SHAPES) {
            FAILURES_BY_SHAPE.computeIfAbsent(shape, unused -> new LongAdder()).increment();
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
     * plain-vector inlining budget refusing a deep tree), so it degraded to block-only fusion instead of falling all
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

    /** Test-only: clears all counters so an assertion can observe a failure it triggered in isolation. */
    static void resetForTests() {
        FAILURES_BY_SHAPE.clear();
        TOTAL_FAILURES.reset();
        ADAPTIVE_SWITCHES.reset();
        ADAPTIVE_ABANDONED.reset();
        VECTOR_DEGRADED.reset();
    }
}
