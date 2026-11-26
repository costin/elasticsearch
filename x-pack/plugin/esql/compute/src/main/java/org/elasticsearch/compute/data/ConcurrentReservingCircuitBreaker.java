/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.core.Releasable;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe sibling of {@link LocalCircuitBreaker} for memory charged off a driver's run loop, such as the
 * I/O completion threads and parse workers of one external-source operator.
 * <p>
 * Every charge to the shared breaker is a CAS on contended atomics plus a parent check that may read the real
 * heap usage; with many producer threads that costs microseconds per block. This breaker instead takes a chunk
 * from the shared breaker at a time and serves later charges and releases from that local reserve with a single
 * CAS, so the shared breaker is touched about once per chunk. It must be scoped to one operator: a single
 * instance shared by every producer on the node just moves the contention onto its own reserve.
 * <p>
 * Accounting rules:
 * <ul>
 *     <li>The shared breaker's usage is always the bytes really held plus the reserve. It may over-count by the
 *     reserve, never under-count.</li>
 *     <li>The reserve is only touched after the shared check has passed, so a trip leaves no local state behind.</li>
 *     <li>Releases refill the reserve. Once it grows past {@code 2 × chunk} it is trimmed back to {@code chunk};
 *     the gap between the two keeps a charge/release pair from bouncing to the shared breaker.</li>
 *     <li>Near the wrapped breaker's limit the refill tapers to a fraction of the remaining headroom, and below
 *     {@link #MIN_TAPERED_REFILL_BYTES} charges go through exactly, so the reserve never trips a charge that would
 *     fit on its own.</li>
 *     <li>{@link #close()} returns the reserve. Every later call passes straight through to the wrapped breaker, so
 *     a straggling charge or release from a producer thread is still accounted exactly.</li>
 * </ul>
 */
public final class ConcurrentReservingCircuitBreaker implements CircuitBreaker, Releasable {
    /**
     * Below this tapered refill size the reserve stops refilling and charges go through exactly: a tiny refill
     * saves nothing while still holding headroom that a real allocation might need.
     */
    static final long MIN_TAPERED_REFILL_BYTES = 16 * 1024;
    /**
     * The tapered refill is at most {@code 1/64} of the headroom left after the charge.
     */
    private static final int HEADROOM_SHIFT = 6;
    /**
     * Wrappers of consecutive operators are often allocated back to back, so their reserves would share a cache
     * line and every CAS would invalidate the neighbour's. The reserve sits in the middle of an array padded by
     * {@code 128} bytes on each side, which also covers adjacent-line prefetching.
     */
    private static final int PADDING_LONGS = 16;
    private static final int RESERVE = PADDING_LONGS;

    private final CircuitBreaker breaker;
    private final long chunkBytes;
    private final long trimAboveBytes;
    private final AtomicLongArray reserve = new AtomicLongArray(2 * PADDING_LONGS + 1);
    private volatile boolean closed;

    /**
     * Sizes the chunk from the same {@code esql.block_factory.local_breaker.*} settings as {@link LocalCircuitBreaker}.
     */
    public ConcurrentReservingCircuitBreaker(CircuitBreaker breaker, LocalCircuitBreaker.SizeSettings settings) {
        this(breaker, settings.overReservedBytes());
    }

    /**
     * @param breaker    the thread-safe breaker to reserve from
     * @param chunkBytes how much to reserve at a time; {@code 0} charges every call exactly
     */
    public ConcurrentReservingCircuitBreaker(CircuitBreaker breaker, long chunkBytes) {
        if (breaker instanceof LocalCircuitBreaker) {
            throw new IllegalArgumentException("must wrap a thread-safe breaker, got [" + breaker + "]");
        }
        if (chunkBytes < 0) {
            throw new IllegalArgumentException("chunk must be >= 0, got [" + chunkBytes + "]");
        }
        this.breaker = breaker;
        this.chunkBytes = chunkBytes;
        this.trimAboveBytes = 2 * chunkBytes;
    }

    @Override
    public void addEstimateBytesAndMaybeBreak(long bytes, String label) throws CircuitBreakingException {
        if (bytes <= 0 || closed) {
            breaker.addEstimateBytesAndMaybeBreak(bytes, label);
            return;
        }
        if (tryTake(bytes)) {
            return;
        }
        long refill = refillBytes(bytes);
        // Throws before anything changes locally. A reserve smaller than bytes stays where it is: taking it would
        // need a compensating give-back on a trip.
        breaker.addEstimateBytesAndMaybeBreak(bytes + refill, label);
        if (refill > 0) {
            giveBack(reserve.addAndGet(RESERVE, refill));
        }
    }

    @Override
    public void addWithoutBreaking(long bytes) {
        if (closed) {
            breaker.addWithoutBreaking(bytes);
        } else if (bytes > 0) {
            if (tryTake(bytes) == false) {
                breaker.addWithoutBreaking(bytes);
            }
        } else if (bytes < 0) {
            giveBack(reserve.addAndGet(RESERVE, -bytes));
        }
    }

    /**
     * Serves {@code bytes} from the reserve if it covers them.
     */
    private boolean tryTake(long bytes) {
        long current;
        while ((current = reserve.get(RESERVE)) >= bytes) {
            if (reserve.compareAndSet(RESERVE, current, current - bytes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How much to reserve on top of a {@code bytes} charge: a full chunk while there is plenty of headroom, then a
     * fraction of the headroom left after the charge, then nothing. Reads only the wrapped breaker, never the real
     * memory behind its parent.
     */
    private long refillBytes(long bytes) {
        long limit = breaker.getLimit();
        if (limit < 0) {
            return chunkBytes; // no limit
        }
        long headroom = limit - breaker.getUsed() - bytes;
        if (headroom <= 0) {
            return 0;
        }
        long tapered = Math.min(chunkBytes, headroom >> HEADROOM_SHIFT);
        if (tapered < chunkBytes && tapered < MIN_TAPERED_REFILL_BYTES) {
            return 0;
        }
        return tapered;
    }

    /**
     * Trims the reserve to {@code chunk} once it exceeds {@code 2 × chunk}, or empties it when closed concurrently.
     * Close flips {@link #closed} before it empties the reserve, so whoever adds to the reserve after that sees the
     * flag here and returns what it added.
     */
    private void giveBack(long current) {
        while (true) {
            long keep;
            if (closed) {
                keep = 0;
            } else if (current > trimAboveBytes) {
                keep = chunkBytes;
            } else {
                return;
            }
            if (current <= keep) {
                return;
            }
            if (reserve.compareAndSet(RESERVE, current, keep)) {
                breaker.addWithoutBreaking(keep - current);
                return;
            }
            current = reserve.get(RESERVE);
        }
    }

    /**
     * Returns the reserve to the wrapped breaker. Safe to call from any thread, more than once, and while other
     * threads still charge or release: those calls then pass straight through.
     */
    @Override
    public void close() {
        closed = true;
        long r = reserve.getAndSet(RESERVE, 0);
        if (r > 0) {
            breaker.addWithoutBreaking(-r);
        }
    }

    public CircuitBreaker parentBreaker() {
        return breaker;
    }

    // for tests
    long reservedBytes() {
        return reserve.get(RESERVE);
    }

    @Override
    public void circuitBreak(String fieldName, long bytesNeeded) {
        breaker.circuitBreak(fieldName, bytesNeeded);
    }

    @Override
    public long getUsed() {
        return breaker.getUsed();
    }

    @Override
    public long getLimit() {
        return breaker.getLimit();
    }

    @Override
    public double getOverhead() {
        return breaker.getOverhead();
    }

    @Override
    public long getTrippedCount() {
        return breaker.getTrippedCount();
    }

    @Override
    public String getName() {
        return breaker.getName();
    }

    @Override
    public Durability getDurability() {
        return breaker.getDurability();
    }

    @Override
    public void setLimitAndOverhead(long limit, double overhead) {
        breaker.setLimitAndOverhead(limit, overhead);
    }

    @Override
    public String toString() {
        return "ConcurrentReservingCircuitBreaker[" + reserve.get(RESERVE) + "/" + chunkBytes + "]";
    }
}
