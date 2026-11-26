/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.LimitedBreaker;
import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ConcurrentReservingCircuitBreakerTests extends ESTestCase {

    public void testServesFromReserve() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        breaker.addEstimateBytesAndMaybeBreak(100, "test");
        assertThat(parent.getUsed(), equalTo(1100L));
        breaker.addEstimateBytesAndMaybeBreak(900, "test");
        assertThat(parent.getUsed(), equalTo(1100L));
        // 100 left in the reserve: 101 refills with bytes + chunk
        breaker.addEstimateBytesAndMaybeBreak(101, "test");
        assertThat(parent.getUsed(), equalTo(2201L));
        assertThat(breaker.reservedBytes(), equalTo(1100L));
        // release on the parent, as a block that moved to another factory does
        parent.addWithoutBreaking(-1101);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testChunkFromSizeSettings() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        Settings settings = Settings.builder().put(BlockFactory.LOCAL_BREAKER_OVER_RESERVED_SIZE_SETTING, "4kb").build();
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(
            parent,
            new LocalCircuitBreaker.SizeSettings(settings)
        );
        breaker.addEstimateBytesAndMaybeBreak(1, "test");
        assertThat(parent.getUsed(), equalTo(4097L));
        breaker.addWithoutBreaking(-1);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testReleaseTrimsWithHysteresis() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        parent.addEstimateBytesAndMaybeBreak(10_000, "elsewhere");
        // up to 2 x chunk stays in the reserve
        breaker.addWithoutBreaking(-2000);
        assertThat(breaker.reservedBytes(), equalTo(2000L));
        assertThat(parent.getUsed(), equalTo(10_000L));
        // past 2 x chunk, it is trimmed back down to chunk
        breaker.addWithoutBreaking(-1);
        assertThat(breaker.reservedBytes(), equalTo(1000L));
        assertThat(parent.getUsed(), equalTo(8999L));
        // a large release is trimmed in one step
        breaker.addWithoutBreaking(-7999);
        assertThat(breaker.reservedBytes(), equalTo(1000L));
        assertThat(parent.getUsed(), equalTo(1000L));
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testChargeReleaseCycleStaysLocal() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        breaker.addEstimateBytesAndMaybeBreak(1000, "test");
        long used = parent.getUsed();
        for (int i = 0; i < 100; i++) {
            breaker.addWithoutBreaking(-1000);
            breaker.addEstimateBytesAndMaybeBreak(1000, "test");
            assertThat(parent.getUsed(), equalTo(used));
        }
        breaker.addWithoutBreaking(-1000);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testTripLeavesNothingCharged() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofBytes(1500));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        expectThrows(CircuitBreakingException.class, () -> breaker.addEstimateBytesAndMaybeBreak(600, "test"));
        assertThat(parent.getUsed(), equalTo(0L));
        assertThat(breaker.reservedBytes(), equalTo(0L));
        breaker.close();
    }

    public void testTaperRefillsAFractionOfHeadroom() {
        LimitReportingBreaker parent = new LimitReportingBreaker(ByteSizeValue.ofMb(10));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, ByteSizeValue.ofMb(1).getBytes());
        breaker.addEstimateBytesAndMaybeBreak(1024, "test");
        long expectedRefill = (ByteSizeValue.ofMb(10).getBytes() - 1024) >> 6;
        assertThat(breaker.reservedBytes(), equalTo(expectedRefill));
        assertThat(parent.getUsed(), equalTo(1024 + expectedRefill));
        breaker.addWithoutBreaking(-1024);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testTaperChargesExactNearLimit() {
        long limit = ByteSizeValue.ofMb(10).getBytes();
        LimitReportingBreaker parent = new LimitReportingBreaker(ByteSizeValue.ofBytes(limit));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, ByteSizeValue.ofKb(512).getBytes());
        long headroom = ByteSizeValue.ofKb(100).getBytes();
        parent.addEstimateBytesAndMaybeBreak(limit - headroom, "elsewhere");

        breaker.addEstimateBytesAndMaybeBreak(headroom / 2, "test");
        assertThat("charged exactly", parent.getUsed(), equalTo(limit - headroom / 2));
        assertThat(breaker.reservedBytes(), equalTo(0L));

        // exactly the rest of the headroom still fits: the reserve must not trip it early
        breaker.addEstimateBytesAndMaybeBreak(headroom / 2, "test");
        assertThat(parent.getUsed(), equalTo(limit));
        assertThat(breaker.reservedBytes(), equalTo(0L));

        expectThrows(CircuitBreakingException.class, () -> breaker.addEstimateBytesAndMaybeBreak(1, "test"));
        assertThat(parent.getUsed(), equalTo(limit));

        breaker.addWithoutBreaking(-headroom);
        parent.addWithoutBreaking(-(limit - headroom));
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    /**
     * A charge that fits in the wrapped breaker's headroom never trips because of the refill on top of it.
     */
    public void testNeverTripsAChargeThatFits() {
        long limit = randomLongBetween(1, ByteSizeValue.ofMb(64).getBytes());
        LimitReportingBreaker parent = new LimitReportingBreaker(ByteSizeValue.ofBytes(limit));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, randomLongBetween(0, limit));
        long elsewhere = randomLongBetween(0, limit - 1);
        parent.addEstimateBytesAndMaybeBreak(elsewhere, "elsewhere");
        long bytes = randomLongBetween(1, limit - elsewhere);
        breaker.addEstimateBytesAndMaybeBreak(bytes, "test");
        assertThat(parent.getUsed(), lessThanOrEqualTo(limit));
        breaker.addWithoutBreaking(-bytes);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(elsewhere));
        parent.addWithoutBreaking(-elsewhere);
    }

    public void testPositiveAddWithoutBreakingConsumesReserve() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        breaker.addEstimateBytesAndMaybeBreak(100, "test");
        assertThat(parent.getUsed(), equalTo(1100L));
        breaker.addWithoutBreaking(500);
        assertThat(breaker.reservedBytes(), equalTo(500L));
        assertThat(parent.getUsed(), equalTo(1100L));
        // not covered by the reserve: passes through and leaves the reserve alone
        breaker.addWithoutBreaking(501);
        assertThat(breaker.reservedBytes(), equalTo(500L));
        assertThat(parent.getUsed(), equalTo(1601L));
        breaker.addWithoutBreaking(-1101);
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testAfterClosePassesThrough() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        breaker.addEstimateBytesAndMaybeBreak(100, "test");
        breaker.close();
        assertThat(parent.getUsed(), equalTo(100L));
        breaker.addEstimateBytesAndMaybeBreak(50, "test");
        assertThat(parent.getUsed(), equalTo(150L));
        breaker.addWithoutBreaking(25);
        assertThat(parent.getUsed(), equalTo(175L));
        breaker.addWithoutBreaking(-175);
        assertThat(parent.getUsed(), equalTo(0L));
        assertThat(breaker.reservedBytes(), equalTo(0L));
    }

    public void testReleaseAfterClose() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, 1000);
        breaker.addEstimateBytesAndMaybeBreak(300, "test");
        breaker.close();
        assertThat(parent.getUsed(), equalTo(300L));
        breaker.addWithoutBreaking(-300);
        assertThat(breaker.reservedBytes(), equalTo(0L));
        assertThat(parent.getUsed(), equalTo(0L));
        // closing again is a no-op
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    public void testRejectsLocalCircuitBreaker() {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofMb(100));
        LocalCircuitBreaker local = new LocalCircuitBreaker(parent, 1000, 1000);
        expectThrows(IllegalArgumentException.class, () -> new ConcurrentReservingCircuitBreaker(local, 1000));
        local.close();
    }

    public void testConcurrentChargeAndRelease() throws Exception {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofGb(1));
        long chunk = between(0, 512 * 1024);
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, chunk);
        runChargersAndRelease(parent, breaker, null);
        assertThat(breaker.reservedBytes(), lessThanOrEqualTo(2 * chunk));
        breaker.close();
        assertThat(parent.getUsed(), equalTo(0L));
    }

    /**
     * Threads charge and release random sizes while the breaker closes under them. The close and the racing
     * releases must return the reserve exactly once, so the wrapped breaker ends at exactly zero without a
     * second close.
     */
    public void testCloseRacesChargeAndRelease() throws Exception {
        CircuitBreaker parent = newLimitedBreaker(ByteSizeValue.ofGb(1));
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(parent, between(1, 512 * 1024));
        runChargersAndRelease(parent, breaker, breaker::close);
        assertThat(breaker.reservedBytes(), equalTo(0L));
        assertThat(parent.getUsed(), equalTo(0L));
    }

    private void runChargersAndRelease(CircuitBreaker parent, ConcurrentReservingCircuitBreaker breaker, Runnable midway) throws Exception {
        int threads = between(2, 8);
        int iters = between(1000, 20_000);
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        CountDownLatch started = new CountDownLatch(threads);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                safeAwait(barrier);
                for (int i = 0; i < iters; i++) {
                    long bytes = randomLongBetween(1, 32 * 1024);
                    breaker.addEstimateBytesAndMaybeBreak(bytes, "test");
                    switch (between(0, 2)) {
                        case 0 -> breaker.addWithoutBreaking(-bytes);
                        case 1 -> parent.addWithoutBreaking(-bytes);
                        case 2 -> {
                            // re-charge without breaking, then release both
                            breaker.addWithoutBreaking(bytes);
                            breaker.addWithoutBreaking(-2 * bytes);
                        }
                        default -> throw new AssertionError("unexpected");
                    }
                    if (i == iters / 2) {
                        started.countDown();
                    }
                }
            });
            ts[t].start();
        }
        safeAwait(barrier);
        if (midway != null) {
            safeAwait(started);
            midway.run();
        }
        for (Thread t : ts) {
            t.join();
        }
    }

    /**
     * {@link LimitedBreaker} reports no limit; the taper needs one.
     */
    private static class LimitReportingBreaker extends LimitedBreaker {
        private final long limit;

        LimitReportingBreaker(ByteSizeValue max) {
            super("test", max);
            this.limit = max.getBytes();
        }

        @Override
        public long getLimit() {
            return limit;
        }
    }
}
