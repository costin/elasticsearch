/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.Assertions;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.plugin.EsqlPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * C1: {@code esql.fusion.verify} is a test/CI-only system property (default {@link Assertions#ENABLED}),
 * not a node {@link Setting}. Test JVMs run with {@code -ea} so verify is ON unless
 * {@code -Desql.fusion.verify=false}.
 */
public class FusionSettingsVerifyTests extends ESTestCase {

    public void testVerifyDefaultsToAssertionsEnabledWhenPropertyUnset() {
        assertThat(FusionSettings.resolveVerifyEnabled(null, true), is(true));
        assertThat(FusionSettings.resolveVerifyEnabled(null, false), is(false));
    }

    public void testVerifyPropertyFalseDisablesEvenWhenAssertionsOn() {
        assertThat(FusionSettings.resolveVerifyEnabled("false", true), is(false));
        assertThat(FusionSettings.resolveVerifyEnabled("false", false), is(false));
    }

    public void testVerifyPropertyTrueEnablesEvenWhenAssertionsOff() {
        assertThat(FusionSettings.resolveVerifyEnabled("true", false), is(true));
        assertThat(FusionSettings.resolveVerifyEnabled("true", true), is(true));
    }

    public void testIsVerifyEnabledMatchesAssertionsInThisTestJvm() {
        // Test JVMs run with -ea and do not set -Desql.fusion.verify, so the latched flag equals Assertions.ENABLED.
        assertThat(FusionSettings.isVerifyEnabled(), is(Assertions.ENABLED));
        assertThat("ES tests run with -ea, so verify is on by default", FusionSettings.isVerifyEnabled(), is(true));
    }

    public void testVerifyIsNotANodeSetting() throws Exception {
        for (Field field : FusionSettings.class.getDeclaredFields()) {
            if (Setting.class.isAssignableFrom(field.getType()) && Modifier.isStatic(field.getModifiers())) {
                assertThat(
                    "esql.fusion.verify must not be registered as a Setting; found " + field.getName(),
                    field.getName().toLowerCase(Locale.ROOT).contains("verify"),
                    is(false)
                );
            }
        }
        for (Setting<?> setting : new EsqlPlugin().getSettings()) {
            assertThat("EsqlPlugin must not register esql.fusion.verify", setting.getKey().equals("esql.fusion.verify"), is(false));
        }
        assertThat(FusionSettings.FUSION_ENABLED_SETTING, notNullValue());
    }
}
