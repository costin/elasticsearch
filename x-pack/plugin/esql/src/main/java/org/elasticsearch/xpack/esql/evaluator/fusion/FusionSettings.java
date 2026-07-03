/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;

/**
 * The node-level runtime kill switch for expression fusion (acceptance criterion #3).
 *
 * <p>{@code esql.fusion.enabled} (default {@code true}) is a {@link Setting.Property#NodeScope} setting — it is read
 * once at node start and is <b>not</b> dynamically updatable, so a node either fuses for its whole lifetime or never
 * does. When disabled, {@link FusionPlanner#maybeFuse} returns {@code null} for every expression, so
 * {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the ordinary unfused factory tree unchanged and
 * the {@link org.elasticsearch.compute.operator.fusion.Stitcher} is never invoked.
 *
 * <p>Because {@code EvalMapper}/{@code FusionPlanner} are static utilities reached from many call sites that have no
 * {@link Settings} in hand, the resolved value is latched into a {@code volatile} flag that
 * {@link org.elasticsearch.xpack.esql.plugin.EsqlPlugin} sets once from the node settings at component creation via
 * {@link #initFromNodeSettings(Settings)}. The initial value also honors the {@code esql.fusion.enabled} system
 * property so a JVM-level flag (e.g. {@code -Desql.fusion.enabled=false}) takes effect in unit tests and tooling that
 * never build a plugin.
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
     * The effective switch. Written once at node start (or by a test hook) and read on the cold plan-time path, so a
     * plain {@code volatile} is sufficient — there is no per-row cost and no need for stronger ordering.
     */
    private static volatile boolean enabled = initialDefault();

    private static boolean initialDefault() {
        // Honor -Desql.fusion.enabled before any plugin wiring so JVM-flagged runs (tests, benchmarks) behave.
        String property = System.getProperty("esql.fusion.enabled");
        return property == null ? true : Boolean.parseBoolean(property);
    }

    /** Latches the switch from the node {@link Settings}. Called once by {@code EsqlPlugin#createComponents}. */
    public static void initFromNodeSettings(Settings settings) {
        enabled = FUSION_ENABLED_SETTING.get(settings);
    }

    /** Whether fusion may be attempted on this node. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Test-only: flips the switch so a test can assert both the fused and unfused wiring; restore in a finally. */
    static void setEnabledForTests(boolean value) {
        enabled = value;
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
