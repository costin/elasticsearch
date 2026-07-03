/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.test.ESTestCase;

/**
 * Fast guard for {@link FusionOperatorBenchmark}. Drives the macro benchmark's static self-test, which — for every
 * {@code (expression × fusion)} combination — builds the evaluator through the real plan-time path, runs it through a
 * real {@link org.elasticsearch.compute.operator.EvalOperator}, and asserts fusion happens when enabled and the
 * appended result block matches the expected values. Runs in seconds so the macro benchmark cannot bit-rot.
 */
public class FusionOperatorBenchmarkTests extends ESTestCase {
    public void test() {
        FusionOperatorBenchmark.selfTest();
    }
}
