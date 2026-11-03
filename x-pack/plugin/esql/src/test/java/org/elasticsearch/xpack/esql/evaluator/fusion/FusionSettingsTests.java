/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.is;

/** fusion is active only when the {@code esql_fusion} feature flag AND the {@code esql.fusion.enabled} */
public class FusionSettingsTests extends ESTestCase {

    public void testEffectiveEnabledRequiresFlagAndKillSwitch() {
        assertThat(FusionSettings.effectiveEnabled(true, true), is(true));
        assertThat(FusionSettings.effectiveEnabled(true, false), is(false));
        assertThat(FusionSettings.effectiveEnabled(false, true), is(false));
        assertThat(FusionSettings.effectiveEnabled(false, false), is(false));
        assertThat(FusionSettings.FUSION_FEATURE_FLAG.isEnabled(), is(true));
    }
}
