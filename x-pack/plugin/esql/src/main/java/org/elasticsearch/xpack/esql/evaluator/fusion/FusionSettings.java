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
 * <p>{@code esql.fusion.enabled} (default {@code true}) is a {@link Setting.Property#NodeScope} setting — it is read
 * once at node start and is <b>not</b> dynamically updatable, so a node either fuses for its whole lifetime or never
 * does. The {@code esql_fusion} {@link FeatureFlag} sits in front of this kill switch: fusion is attempted only when
 * both the flag and the setting are on. When either is off, {@link FusionPlanner#maybeFuse} returns {@code null} for
 * every expression, so {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the ordinary unfused factory
 * tree unchanged and the {@link org.elasticsearch.compute.operator.fusion.Stitcher} is never invoked.
 *
 * <p>Because {@code EvalMapper}/{@code FusionPlanner} are static utilities reached from many call sites that have no
 * {@link Settings} in hand, the resolved values are latched into {@code volatile} fields that
 * {@link org.elasticsearch.xpack.esql.plugin.EsqlPlugin} sets once from the node settings at component creation via
 * {@link #initFromNodeSettings(Settings)}. The initial values also honor the matching system properties so a JVM-level
 * flag (e.g. {@code -Desql.fusion.enabled=false}) takes effect in unit tests and tooling that never build a plugin.
 *
 * <p>{@code esql.fusion.verify} is <b>not</b> a node {@link Setting} and is <b>not</b> registered in
 * {@code EsqlPlugin}. It is a test/CI-only system property that latches into a {@code static final} boolean (see
 * {@link #isVerifyEnabled()}): default {@link Assertions#ENABLED} so {@code -ea} test JVMs A/B every fused page
 * against the unfused evaluator, and production / JMH (no {@code -ea}) pay zero cost. {@code -Desql.fusion.verify=false}
 * disables the A/B even when assertions are on.
 *
 * <p>{@code esql.fusion.vector_reads} is likewise <b>not</b> a node {@link Setting}. It latches into a
 * {@code static final} boolean (see {@link #isVectorReadsInterface()}): {@code auto} (default) keeps ALL-RAW over
 * exact {@code *ArrayVector}s; {@code interface} forces ALL-INTERFACE. Unknown values throw at class init. Production
 * / JMH (unset) pay zero cost — the dispatch branch is a constant the C2 JIT folds away. JMH sets the system property
 * per fork rather than calling the accessor.
 *
 * <p>{@code esql.fusion.coverage_report} is <b>not</b> a node {@link Setting} and is <b>not</b> registered in
 * {@code EsqlPlugin} — same shape as {@code esql.fusion.verify}. It is a test/CI-only system property that latches
 * into {@link FusionTelemetry#coverageEnabled} (default {@code false}). A registered Setting used to overwrite the
 * sysprop at node start (default {@code false}), so {@code -Desql.fusion.coverage_report=true} was dead on a real
 * node. Gradle forwards only {@code tests.*} into the test JVM, so a CsvIT coverage run must use
 * {@code -Dtests.jvm.argline=-Desql.fusion.coverage_report=true}. {@code EsqlPlugin#close()} logs
 * {@link FusionTelemetry#coverageReport()} when the probe is on.
 */
public final class FusionSettings {

    private FusionSettings() {}

    /**
     * Build-level gate for expression fusion (compiled bytecode). On in snapshot builds and tests, off in release
     * unless {@code -Des.esql_fusion_feature_flag_enabled=true}. Removed after rollout.
     */
    public static final FeatureFlag FUSION_FEATURE_FLAG = new FeatureFlag("esql_fusion");

    /** {@code esql.fusion.enabled}: node-scoped, non-dynamic; default {@code true}. Registered by {@code EsqlPlugin}. */
    public static final Setting<Boolean> FUSION_ENABLED_SETTING = Setting.boolSetting(
        "esql.fusion.enabled",
        true,
        Setting.Property.NodeScope
    );

    /**
     * The effective switch. Written once at node start (or by a test hook) and read on the cold plan-time path, so a
     * plain {@code volatile} is sufficient — there is no per-row cost and no need for stronger ordering.
     */
    private static volatile boolean enabled = initialEnabledDefault();

    /**
     * Benchmark/test-only {@code esql.fusion.vector_reads}. <b>Not</b> a node {@link Setting}. WHY {@code static final}
     * (not {@code volatile}): production dispatch must be a C2-foldable constant so an unset hook costs nothing per
     * dense page. A9's per-page volatile read was the only hot-path difference vs. the stabilize tip. JMH sets
     * {@code -Desql.fusion.vector_reads} per fork (class-init of this class in that fork); tests that need both
     * modes in one JVM use {@link FusedExpressionEvaluatorFactory#getForTests}, not a mutable override of this flag.
     */
    private static final boolean VECTOR_READS_INTERFACE = resolveVectorReadsInterface(System.getProperty("esql.fusion.vector_reads"));

    /**
     * The effective coverage-report opt-in (default off). Sysprop-only (not a {@link Setting}); also latches
     * {@link FusionTelemetry#coverageEnabled} so the counters stay zero-cost when this is false. Volatile rather
     * than {@code static final} so unit tests can flip it in-process via {@link #setCoverageReportEnabledForTests};
     * the probe is plan-time only, so a C2-foldable constant is not required.
     */
    private static volatile boolean coverageReportEnabled = initialCoverageReportDefault();

    /**
     * Test/CI-only {@code esql.fusion.verify}. <b>Not</b> a node {@link Setting} — do not register it in
     * {@code EsqlPlugin}. Read once into a {@code static final} so the fused-eval {@code if (isVerifyEnabled())}
     * branch is a single constant the JIT folds away in production (no {@code -ea}) and in JMH (no {@code -ea}).
     * Default is {@link Assertions#ENABLED}; {@code -Desql.fusion.verify=false} disables even when {@code -ea} is on.
     * Tests that need the comparison off in-process use {@code FusionVerify.skipForTests} (inside the already-dead
     * production branch), not a mutable override of this flag.
     */
    private static final boolean VERIFY_ENABLED = resolveVerifyEnabled(System.getProperty("esql.fusion.verify"), Assertions.ENABLED);

    private static boolean initialEnabledDefault() {
        // Honor -Desql.fusion.enabled before any plugin wiring so JVM-flagged runs (tests, benchmarks) behave.
        String property = System.getProperty("esql.fusion.enabled");
        return property == null ? true : Boolean.parseBoolean(property);
    }

    private static boolean initialCoverageReportDefault() {
        // Honor -Desql.fusion.coverage_report before any plugin wiring so CsvIT / unit tests that never build a node
        // still flip the probe. Default false: production pays nothing. Not overwritten by initFromNodeSettings —
        // a Setting used to clobber this to false at node start.
        boolean value = Boolean.parseBoolean(System.getProperty("esql.fusion.coverage_report", "false"));
        FusionTelemetry.coverageEnabled = value;
        return value;
    }

    /** Latches all fusion dials from the node {@link Settings}. Called once by {@code EsqlPlugin#createComponents}. */
    public static void initFromNodeSettings(Settings settings) {
        enabled = FUSION_ENABLED_SETTING.get(settings);
        // coverage_report is sysprop-only (like verify); do not clobber the class-init latch from a node Setting.
    }

    /**
     * Whether eval should treat exact {@code *ArrayVector} inputs as non-exact and take ALL-INTERFACE.
     * Benchmark/test-only; default {@code false} ({@code auto}). WHY package-private: production dispatch snapshots
     * this into a {@code final} evaluator field at construction; JMH sets {@code -Desql.fusion.vector_reads} per
     * fork (this {@code static final} reads it at class init) and must not reach across packages to toggle a runtime
     * flag. WHY unknown values are rejected at {@link #resolveVectorReadsInterface}: a typo would silently keep RAW
     * and an INTERFACE bench/test cell would look like {@code auto}. That throw happens at class init, so a bad
     * {@code -Desql.fusion.vector_reads} becomes {@code ExceptionInInitializerError} and fails every EVAL plan —
     * intended fail-fast, not a silent fallback.
     */
    static boolean isVectorReadsInterface() {
        return VECTOR_READS_INTERFACE;
    }

    /**
     * Resolves {@code esql.fusion.vector_reads}: unset/{@code auto} → {@code false} (RAW); {@code interface} →
     * {@code true}. Anything else throws (and at class init that is {@code ExceptionInInitializerError} — intended
     * fail-fast). Package-private so unit tests can pin the matrix without reloading this class.
     */
    static boolean resolveVectorReadsInterface(String property) {
        String value = property == null ? "auto" : property;
        return switch (value) {
            case "auto" -> false;
            case "interface" -> true;
            default -> throw new IllegalArgumentException(
                "unknown esql.fusion.vector_reads [" + value + "]; expected [auto] or [interface]"
            );
        };
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
     * Test/CI-only; production reads a {@code static final false} and the JIT removes the branch.
     */
    public static boolean isVerifyEnabled() {
        return VERIFY_ENABLED;
    }

    /**
     * Resolves {@code esql.fusion.verify}: unset → {@code assertionsEnabled}; otherwise {@link Boolean#parseBoolean}.
     * Package-private so unit tests can pin the property/assertions matrix without reloading this class.
     */
    static boolean resolveVerifyEnabled(String property, boolean assertionsEnabled) {
        return property == null ? assertionsEnabled : Boolean.parseBoolean(property);
    }

    /** Test-only: flips the switch so a test can assert both the fused and unfused wiring; restore in a finally. */
    static void setEnabledForTests(boolean value) {
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
