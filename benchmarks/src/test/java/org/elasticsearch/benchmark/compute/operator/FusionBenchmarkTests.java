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
 * Fast guard for {@link FusionBenchmark}. Drives the benchmark's static self-test, which — for every
 * {@code (expression × fusion)} combination — builds the evaluator through the real plan-time path and asserts that
 * fusion actually happens when enabled and that the fused output equals the unfused output (and both match the
 * expected values). Runs in seconds so the benchmark cannot bit-rot.
 */
public class FusionBenchmarkTests extends ESTestCase {
    public void test() {
        FusionBenchmark.selfTest();
    }
}
