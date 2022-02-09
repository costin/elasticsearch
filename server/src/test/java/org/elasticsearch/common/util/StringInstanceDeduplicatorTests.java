/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.util;

import org.elasticsearch.test.ESTestCase;

public class StringInstanceDeduplicatorTests extends ESTestCase {

    public void testDedupWithHashCollision() {
        var prefix = randomAlphaOfLength(10);
        // see https://shipilev.net/#string-catechism
        var left = prefix + "Aa";
        var right = prefix + "BB";

        assertNotEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());

        var dedup = new StringInstanceDeduplicator(128);
        assertSame(left, dedup.dedup(left));
        assertSame(right, dedup.dedup(right));
    }

    public void testDedup() {
        var strings = randomArray(100, 200, s -> new String[s], () -> randomRealisticUnicodeOfCodepointLengthBetween(1, 50));
        var dedup = new StringInstanceDeduplicator(strings.length / 4);

        // do multiple passes for testing the cache
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < strings.length; j++) {
                var reuse = dedup.dedup(strings[j]);
                assertEquals(reuse, strings[j]);
                assertSame(reuse, dedup.dedup(reuse));
            }
        }
    }
}
