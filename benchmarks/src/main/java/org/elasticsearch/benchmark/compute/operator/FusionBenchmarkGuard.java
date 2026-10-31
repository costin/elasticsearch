/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;

/**
 * JMH must never pay verify-mode A/B. Default JMH has no {@code -ea} so {@link FusionSettings#isVerifyEnabled()}
 * is false; this fails fast if someone runs with {@code -Desql.fusion.verify=true} and would otherwise silently
 * double-evaluate every page.
 */
final class FusionBenchmarkGuard {
    private FusionBenchmarkGuard() {}

    static void assertVerifyDisabled() {
        if (FusionSettings.isVerifyEnabled()) {
            throw new IllegalStateException(
                "esql.fusion.verify must be off in JMH (Assertions.ENABLED is false and the property is unset); "
                    + "A/B comparison of fused vs unfused on every page would distort timings"
            );
        }
    }
}
