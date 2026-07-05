/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.ann;

import org.elasticsearch.test.ESTestCase;

/**
 * Smoke test that {@link Fusable} is on the compute classpath. The annotation uses
 * {@code SOURCE} retention, so compile-time reachability is the only runtime check.
 */
public class FusableAnnotationTests extends ESTestCase {

    public void testFusableAnnotationCompileSmoke() {
        assertTrue(FusableFixture.class.isAssignableFrom(FusableFixture.class));
    }

    static final class FusableFixture {
        @Fusable(overflowChecked = true)
        static long process(long lhs, long rhs) {
            return lhs + rhs;
        }
    }
}
