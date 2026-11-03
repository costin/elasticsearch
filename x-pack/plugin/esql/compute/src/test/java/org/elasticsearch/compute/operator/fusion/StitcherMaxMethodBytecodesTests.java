/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Test-only {@code esql.fusion.max_method_bytecodes} hook: a non-positive huge-method guard is rejected
 * and leaves the live huge-method limit unchanged.
 */
public class StitcherMaxMethodBytecodesTests extends ESTestCase {

    public void testMaxFusedMethodBytecodesRejectsNonPositive() {
        int previous = Stitcher.maxFusedMethodBytecodes;
        try {
            IllegalArgumentException zero = expectThrows(IllegalArgumentException.class, () -> Stitcher.maxFusedMethodBytecodesForTests(0));
            assertThat(zero.getMessage(), containsString("0"));
            IllegalArgumentException neg = expectThrows(IllegalArgumentException.class, () -> Stitcher.maxFusedMethodBytecodesForTests(-1));
            assertThat(neg.getMessage(), containsString("-1"));
            assertThat("a rejected set must leave the budget unchanged", Stitcher.maxFusedMethodBytecodes, is(previous));
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
        }
    }
}
