/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.util;

import java.nio.CharBuffer;

/**
 * Light deduplicator for reducing String instances using best effort.
 * Aims for efficiency and minimal object/garbage creation while maintaining correctness.
 *
 * The class does not use any locking or synchronization however in a multi-threaded environment should behave correct.
 */
public final class StringInstanceDeduplicator {
    private final String[] strings;
    private final int mask;

    public StringInstanceDeduplicator(int capacity) {
        var n = Math.max(1, Integer.highestOneBit(capacity - 1) << 1); // next power of 2
        strings = new String[n];
        mask = n - 1;
    }

    public String dedup(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        int h = hash(cs) & mask;
        h &= mask;
        var hit = strings[h];
        if (hit != null && hit.contentEquals(cs)) {
            return hit;
        }
        hit = cs.toString();
        strings[h] = hit;
        return hit;
    }

    private int hash(CharSequence cs) {
        // known implementations that implement hashcode
        if (cs instanceof String || cs instanceof CharBuffer) {
            return cs.hashCode();
        }
        int h = 0;
        for (int i = 0, l = cs.length(); i < l; i++) {
            h = 31 * h + cs.charAt(i);
        }
        return h;
    }
}
