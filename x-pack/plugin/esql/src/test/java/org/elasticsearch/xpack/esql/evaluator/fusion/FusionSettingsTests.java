/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.is;

/**
 * P0.2: fusion is active only when the {@code esql_fusion} feature flag AND the {@code esql.fusion.enabled}
 * kill switch are both on.
 */
public class FusionSettingsTests extends ESTestCase {

    public void testEffectiveEnabledRequiresFlagAndKillSwitch() {
        assertThat(FusionSettings.effectiveEnabled(true, true), is(true));
        assertThat(FusionSettings.effectiveEnabled(true, false), is(false));
        assertThat(FusionSettings.effectiveEnabled(false, true), is(false));
        assertThat(FusionSettings.effectiveEnabled(false, false), is(false));
        // ES unit tests run on snapshot builds, so fusion stays enabled by default (guards against an accidental always-off gate).
        assertThat(FusionSettings.FUSION_FEATURE_FLAG.isEnabled(), is(true));
    }
}
