/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class LongArrayVectorRawValuesTests extends ESTestCase {

    CircuitBreakerService breakerService;
    CircuitBreaker breaker;
    BlockFactory blockFactory;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    public void testRawValuesReturnsStableBackingArray() {
        long[] values = new long[] { 10L, 20L, 30L };
        try (LongArrayVector vector = (LongArrayVector) blockFactory.newLongArrayVector(values, values.length)) {
            long[] raw1 = vector.rawValues();
            long[] raw2 = vector.rawValues();
            assertThat(raw1, sameInstance(raw2));
            assertThat(raw1.length, is(vector.getPositionCount()));
            for (int p = 0; p < vector.getPositionCount(); p++) {
                assertThat(raw1[p], is(vector.getLong(p)));
            }
        }
    }
}
