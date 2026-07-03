/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The node-level runtime controls for expression fusion: the kill switch (acceptance criterion #3) plus the Stage-6
 * rollout dials — an <b>adaptive</b> row threshold and an <b>A/B sampling</b> fraction.
 *
 * <p>{@code esql.fusion.enabled} (default {@code true}) is a {@link Setting.Property#NodeScope} setting — it is read
 * once at node start and is <b>not</b> dynamically updatable, so a node either fuses for its whole lifetime or never
 * does. When disabled, {@link FusionPlanner#maybeFuse} returns {@code null} for every expression, so
 * {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the ordinary unfused factory tree unchanged and
 * the {@link org.elasticsearch.compute.operator.fusion.Stitcher} is never invoked.
 *
 * <p>Two further node-scoped dials shape <em>how</em> a fusable expression is served (both fully additive — the
 * defaults reproduce the pre-Stage-6 eager, always-on behaviour):
 * <ul>
 *   <li>{@code esql.fusion.adaptive.min_rows} (default {@code 0}) — operator-level tiered compilation. When {@code > 0},
 *       {@link FusionPlanner} does <b>not</b> stitch at plan time; it returns an {@link AdaptiveFusionEvaluatorFactory}
 *       that starts on the unfused chain and only stitches + switches once an evaluator has seen at least this many
 *       rows, so cold/tiny queries never pay the stitch cost. {@code 0} keeps the eager plan-time stitch.</li>
 *   <li>{@code esql.fusion.sample} (default {@code 1.0}) — A/B sampling. The fraction of fusable expression subtrees on
 *       which fusion is actually attempted; the rest run unfused. {@code 1.0} always attempts (pre-Stage-6 behaviour),
 *       {@code 0.0} never does, and an intermediate value gathers real-world signal without a full rollout.</li>
 * </ul>
 *
 * <p>Because {@code EvalMapper}/{@code FusionPlanner} are static utilities reached from many call sites that have no
 * {@link Settings} in hand, the resolved values are latched into {@code volatile} fields that
 * {@link org.elasticsearch.xpack.esql.plugin.EsqlPlugin} sets once from the node settings at component creation via
 * {@link #initFromNodeSettings(Settings)}. The initial values also honor the matching system properties so a JVM-level
 * flag (e.g. {@code -Desql.fusion.enabled=false}) takes effect in unit tests and tooling that never build a plugin.
 */
public final class FusionSettings {

    private FusionSettings() {}

    /** {@code esql.fusion.enabled}: node-scoped, non-dynamic; default {@code true}. Registered by {@code EsqlPlugin}. */
    public static final Setting<Boolean> FUSION_ENABLED_SETTING = Setting.boolSetting(
        "esql.fusion.enabled",
        true,
        Setting.Property.NodeScope
    );

    /**
     * A warmup-informed <b>recommended</b> value for {@link #FUSION_ADAPTIVE_MIN_ROWS_SETTING} when a deployment opts
     * into adaptive mode — <b>not</b> the hard default (which stays {@code 0}/eager; see below). It matches Trino's
     * {@code PageProcessor.MAX_BATCH_SIZE} (8×1024), i.e. one compiled batch worth of rows.
     *
     * <p>Rationale: the fused method runs once per page with the row loop inside it, so its JIT back-edge count equals
     * the rows it processes, and a fresh per-shape hidden class starts interpreted and needs ~15k–40k rows (HotSpot JDK
     * 21 {@code Tier4CompileThreshold=15000}/{@code Tier4BackEdgeThreshold=40000}) to reach C2. Eager stitching
     * therefore gives the <em>most</em> warmup runway; the adaptive threshold's real purpose is only to skip the stitch
     * on trivially small queries while switching early enough to leave the rest of a large query as warmup runway.
     * {@code 8192} skips sub-page queries without meaningfully delaying warmup. Our own {@code FusionBenchmark} (warmed,
     * steady-state) refines this later.
     */
    public static final int RECOMMENDED_ADAPTIVE_MIN_ROWS = 8 * 1024;

    /**
     * {@code esql.fusion.adaptive.min_rows}: node-scoped, non-dynamic; default {@code 0} (eager plan-time stitch, which
     * is also warmup-optimal — see {@link #RECOMMENDED_ADAPTIVE_MIN_ROWS}). When {@code > 0}, a fusable subtree is
     * served adaptively — unfused until an evaluator has seen this many rows, then stitched and switched;
     * {@link #RECOMMENDED_ADAPTIVE_MIN_ROWS} is the suggested starting value for that mode. Bounded at
     * {@link Integer#MAX_VALUE}.
     */
    public static final Setting<Integer> FUSION_ADAPTIVE_MIN_ROWS_SETTING = Setting.intSetting(
        "esql.fusion.adaptive.min_rows",
        0,
        0,
        Setting.Property.NodeScope
    );

    /**
     * {@code esql.fusion.sample}: node-scoped, non-dynamic; default {@code 1.0} (always attempt). The A/B fraction of
     * fusable subtrees on which fusion is attempted; the remainder run unfused.
     */
    public static final Setting<Double> FUSION_SAMPLE_SETTING = Setting.doubleSetting(
        "esql.fusion.sample",
        1.0,
        0.0,
        1.0,
        Setting.Property.NodeScope
    );

    /**
     * The effective switch. Written once at node start (or by a test hook) and read on the cold plan-time path, so a
     * plain {@code volatile} is sufficient — there is no per-row cost and no need for stronger ordering.
     */
    private static volatile boolean enabled = initialEnabledDefault();

    /** The effective adaptive row threshold (0 = eager). Cold-path read; {@code volatile} is sufficient. */
    private static volatile long adaptiveMinRows = initialLongProperty("esql.fusion.adaptive.min_rows", 0L);

    /** The effective A/B sampling fraction in {@code [0.0, 1.0]} (1.0 = always). Cold-path read. */
    private static volatile double sampleFraction = initialDoubleProperty("esql.fusion.sample", 1.0);

    private static boolean initialEnabledDefault() {
        // Honor -Desql.fusion.enabled before any plugin wiring so JVM-flagged runs (tests, benchmarks) behave.
        String property = System.getProperty("esql.fusion.enabled");
        return property == null ? true : Boolean.parseBoolean(property);
    }

    private static long initialLongProperty(String name, long fallback) {
        String property = System.getProperty(name);
        return property == null ? fallback : Long.parseLong(property);
    }

    private static double initialDoubleProperty(String name, double fallback) {
        String property = System.getProperty(name);
        return property == null ? fallback : Double.parseDouble(property);
    }

    /** Latches all fusion dials from the node {@link Settings}. Called once by {@code EsqlPlugin#createComponents}. */
    public static void initFromNodeSettings(Settings settings) {
        enabled = FUSION_ENABLED_SETTING.get(settings);
        adaptiveMinRows = FUSION_ADAPTIVE_MIN_ROWS_SETTING.get(settings);
        sampleFraction = FUSION_SAMPLE_SETTING.get(settings);
    }

    /** Whether fusion may be attempted on this node. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * The adaptive row threshold: {@code 0} means stitch eagerly at plan time; {@code > 0} means serve the subtree
     * unfused until an evaluator has seen this many rows, then stitch and switch.
     */
    public static long adaptiveMinRows() {
        return adaptiveMinRows;
    }

    /**
     * The A/B decision for one fusable subtree: {@code true} if fusion should be attempted. A fraction of {@code >= 1.0}
     * always attempts and {@code <= 0.0} never does; otherwise a per-subtree random draw decides, so enabling on (say)
     * 10% of subtrees gathers signal without a full rollout. The draw is on the cold plan-time path only.
     */
    public static boolean shouldAttemptFusion() {
        double fraction = sampleFraction;
        if (fraction >= 1.0) {
            return true;
        }
        if (fraction <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < fraction;
    }

    /** Test-only: flips the switch so a test can assert both the fused and unfused wiring; restore in a finally. */
    static void setEnabledForTests(boolean value) {
        enabled = value;
    }

    /** Test-only: sets the adaptive row threshold; restore in a finally. */
    static void setAdaptiveMinRowsForTests(long value) {
        adaptiveMinRows = value;
    }

    /** Test-only: sets the A/B sampling fraction; restore in a finally. */
    static void setSampleFractionForTests(double value) {
        sampleFraction = value;
    }

    /**
     * Benchmark/test-only PUBLIC hook that flips the same switch as {@link #setEnabledForTests}, exposed so the JMH
     * {@code FusionBenchmark} (in the separate {@code :benchmarks} module, which cannot see the package-private test
     * hook) can build a fused and an unfused evaluator for the same expression in-process and measure both. This is
     * <b>never</b> called in production — the effective value is otherwise only ever written once at node start by
     * {@link #initFromNodeSettings(Settings)}.
     */
    public static void setEnabledForBenchmarks(boolean value) {
        enabled = value;
    }
}
