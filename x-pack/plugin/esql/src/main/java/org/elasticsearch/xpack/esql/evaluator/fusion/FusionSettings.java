/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.FeatureFlag;
import org.elasticsearch.core.Assertions;

/**
 * The node-level runtime controls for expression fusion: the kill switch (acceptance criterion #3) and the
 * {@code esql_fusion} {@link FeatureFlag}.
 *
 * <p>{@code esql.fusion.enabled} (default {@code true}) is a {@link Setting.Property#NodeScope},
 * {@link Setting.Property#Dynamic} setting — it is read at node start and refreshed live (via {@link #updateEnabled})
 * whenever the cluster setting changes, so an operator can turn fusion off (or back on) on a running node. The
 * {@code esql_fusion} {@link FeatureFlag} sits in front of this kill switch: fusion is attempted only when
 * both the flag and the setting are on. When either is off, {@link FusionPlanner#maybeFuse} returns {@code null} for
 * every expression, so {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the ordinary unfused factory
 * tree unchanged and the {@link org.elasticsearch.compute.operator.fusion.Stitcher} is never invoked.
 *
 * <p>Because {@code EvalMapper}/{@code FusionPlanner} are static utilities reached from many call sites that have no
 * {@link Settings} in hand, the resolved values are latched into {@code volatile} fields that
 * {@link org.elasticsearch.xpack.esql.plugin.EsqlPlugin} seeds from the node settings at component creation via
 * {@link #initFromNodeSettings(Settings)} and keeps current through a dynamic-setting consumer bound to
 * {@link #updateEnabled}. Tests and benchmarks that never build a plugin flip the same fields via
 * {@link #setEnabledForTests} / {@link #setEnabledForBenchmarks}.
 *
 * <p>Verify defaults to {@link Assertions#ENABLED} (on under {@code -ea} test JVMs, off in production / JMH where
 * the {@code static final} branch folds away). It is <b>not</b> a node {@link Setting} and is <b>not</b> registered
 * in {@code EsqlPlugin}. The in-process OFF hook is {@code FusionVerify.skipForTests}.
 *
 * <p>Vector reads default to {@code auto} (ALL-RAW, {@code false}). The ALL-INTERFACE path is reachable only via the
 * per-evaluator {@link FusedExpressionEvaluatorFactory#getForTests} seam. This is <b>not</b> a node {@link Setting};
 * production / JMH pay zero cost — the dispatch branch is a constant the C2 JIT folds away.
 *
 * <p>The coverage-report probe defaults off and is <b>not</b> a node {@link Setting}. Tests flip it in-process via
 * {@link #setCoverageReportEnabledForTests}, which latches both this flag and {@link FusionTelemetry#coverageEnabled}.
 * {@code EsqlPlugin#close()} logs {@link FusionTelemetry#coverageReport()} when the probe is on.
 */
public final class FusionSettings {

    private FusionSettings() {}

    /**
     * Build-level gate for expression fusion (compiled bytecode). On in snapshot builds and tests, off in release
     * unless {@code -Des.esql_fusion_feature_flag_enabled=true}. Removed after rollout.
     */
    public static final FeatureFlag FUSION_FEATURE_FLAG = new FeatureFlag("esql_fusion");

    /** {@code esql.fusion.enabled}: node-scoped, dynamic; default {@code true}. Registered by {@code EsqlPlugin}. */
    public static final Setting<Boolean> FUSION_ENABLED_SETTING = Setting.boolSetting(
        "esql.fusion.enabled",
        true,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    /**
     * The effective switch. Default {@code true}; the effective value is seeded from the node Setting by
     * {@link #initFromNodeSettings}, refreshed on dynamic cluster-setting updates via {@link #updateEnabled} (or
     * flipped by a test/benchmark hook). Read on the cold plan-time path, so a plain {@code volatile} is sufficient
     * — there is no per-row cost and no need for stronger ordering.
     */
    private static volatile boolean enabled = true;

    /**
     * Default {@code auto} (ALL-RAW, {@code false}). <b>Not</b> a node {@link Setting}. WHY {@code static final}
     * (not {@code volatile}): production dispatch must be a C2-foldable constant so the default costs nothing per
     * dense page. The ALL-INTERFACE path is reachable only via the per-evaluator
     * {@link FusedExpressionEvaluatorFactory#getForTests} seam, not a mutable override of this flag.
     */
    private static final boolean VECTOR_READS_INTERFACE = false;

    /**
     * The effective coverage-report opt-in (default off). Not a {@link Setting}; flipped only via
     * {@link #setCoverageReportEnabledForTests}, which also latches {@link FusionTelemetry#coverageEnabled} so the
     * counters stay zero-cost when this is false. Volatile rather than {@code static final} so unit tests can flip
     * it in-process; the probe is plan-time only, so a C2-foldable constant is not required.
     */
    private static volatile boolean coverageReportEnabled = false;

    /**
     * Verify-mode A/B. <b>Not</b> a node {@link Setting} — do not register it in {@code EsqlPlugin}. Default is
     * {@link Assertions#ENABLED} (on under {@code -ea} test JVMs, off in production / JMH where the
     * {@code static final} branch folds away). Tests that need the comparison off in-process use
     * {@code FusionVerify.skipForTests} (inside the already-dead production branch), not a mutable override of this
     * flag. It is no longer a system property.
     */
    private static final boolean VERIFY_ENABLED = Assertions.ENABLED;

    /** Seeds all fusion dials from the node {@link Settings}. Called once by {@code EsqlPlugin#createComponents}. */
    public static void initFromNodeSettings(Settings settings) {
        enabled = FUSION_ENABLED_SETTING.get(settings);
        // coverage_report and verify are non-Setting diagnostics (constant defaults; flipped only via test hooks).
    }

    /**
     * Dynamic-setting consumer for {@link #FUSION_ENABLED_SETTING}: {@code EsqlPlugin} binds this to the cluster
     * settings so an operator can toggle {@code esql.fusion.enabled} on a running node. New plans pick up the change
     * on the next plan-time read; already-compiled fused classes are unaffected (they are only reached when a plan
     * fuses).
     */
    public static void updateEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Whether eval should treat exact {@code *ArrayVector} inputs as non-exact and take ALL-INTERFACE.
     * Default {@code false} ({@code auto} / ALL-RAW). The ALL-INTERFACE path is reachable only via
     * {@link FusedExpressionEvaluatorFactory#getForTests}{@code (ctx, true)}; this accessor returns the constant
     * default and is still read by {@link FusedExpressionEvaluatorFactory#get}.
     */
    static boolean isVectorReadsInterface() {
        return VECTOR_READS_INTERFACE;
    }

    /**
     * Whether fusion may be attempted on this node. True only when the {@code esql_fusion} feature flag
     * <b>and</b> the {@code esql.fusion.enabled} kill switch are both on.
     */
    public static boolean isEnabled() {
        return effectiveEnabled(FUSION_FEATURE_FLAG.isEnabled(), enabled);
    }

    /** Fusion is active only when the build-level feature flag AND the node kill switch are both on. */
    static boolean effectiveEnabled(boolean featureFlagEnabled, boolean killSwitchEnabled) {
        return featureFlagEnabled && killSwitchEnabled;
    }

    /**
     * Whether fused evaluators must A/B each page against the unfused fallback and fail on any difference.
     * Defaults to {@link Assertions#ENABLED} (on under {@code -ea} test JVMs, off in production / JMH where the
     * branch folds away). The in-process OFF hook is {@code FusionVerify.skipForTests}; it is no longer a system
     * property.
     */
    public static boolean isVerifyEnabled() {
        return VERIFY_ENABLED;
    }

    /**
     * Test-only: flips the switch so a test can assert both the fused and unfused wiring; restore in a finally.
     * Also used cross-package by the scalar-function toString tests to pin the canonical unfused evaluator.
     */
    public static void setEnabledForTests(boolean value) {
        enabled = value;
    }

    /** Whether the opt-in fusion coverage probe is on. Default {@code false} (zero cost). */
    public static boolean isCoverageReportEnabled() {
        return coverageReportEnabled;
    }

    /** Test-only: flips the coverage probe and the {@link FusionTelemetry#coverageEnabled} latch together; restore in a finally. */
    static void setCoverageReportEnabledForTests(boolean value) {
        coverageReportEnabled = value;
        FusionTelemetry.coverageEnabled = value;
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

    /**
     * Benchmark-only PUBLIC hook that resets the process-wide fused-class cache state (drops all cached classes,
     * zeroes telemetry counters, and clears baked-divisor/test hooks) so a JMH cell
     * ({@code FusionStrategyBenchmark.planMiss}) can measure a cold stitch. The {@code :benchmarks} module cannot
     * see the package-private {@code FusedClassCache#resetForTests}. Never called in production.
     */
    public static void resetClassCacheForBenchmarks() {
        FusionPlanner.classCache().resetForTests();
    }
}
