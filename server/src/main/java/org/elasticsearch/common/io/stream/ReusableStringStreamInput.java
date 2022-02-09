/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.io.stream;

import org.apache.lucene.util.CharsRef;
import org.elasticsearch.common.util.StringInstanceDeduplicator;

public class ReusableStringStreamInput extends FilterStreamInput {

    private final StringInstanceDeduplicator deduplicator;

    public ReusableStringStreamInput(StreamInput delegate, StringInstanceDeduplicator deduplicator) {
        super(delegate);
        this.deduplicator = deduplicator;
    }

    @Override
    protected String asString(CharsRef charRef) {
        return deduplicator.dedup(charRef);
    }
}
