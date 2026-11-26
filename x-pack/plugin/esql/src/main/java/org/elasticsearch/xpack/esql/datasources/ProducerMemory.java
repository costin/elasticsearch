/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.ConcurrentReservingCircuitBreaker;
import org.elasticsearch.compute.data.LocalCircuitBreaker;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;

/**
 * The memory scope of one external-source operator's producers: the {@link BlockFactory} its I/O and parser
 * threads build blocks with, backed by a {@link ConcurrentReservingCircuitBreaker} of its own so those threads
 * charge a local reserve instead of contending on the query's shared breaker for every block.
 * <p>
 * The reserve is returned once both the operator is closed and the producer has finished, whichever comes last.
 * Either can come first: a driver that stops early closes the operator while the producer winds down, and a
 * producer that finishes normally completes before the driver closes the operator. Both holds must be released
 * before the driver completes so that its breaker is balanced when it reports; the operator is closed before
 * the driver waits for its async actions, and the producer releases its hold before it removes its async action.
 * A charge or release that still arrives after the reserve is returned passes straight through to the shared
 * breaker, so a straggling producer thread cannot leak.
 */
final class ProducerMemory {
    @Nullable
    private final BlockFactory blockFactory;
    @Nullable
    private final ConcurrentReservingCircuitBreaker breaker;
    private final Releasable operatorHold;
    private final Releasable producerHold;

    private ProducerMemory(@Nullable BlockFactory blockFactory, @Nullable ConcurrentReservingCircuitBreaker breaker) {
        this.blockFactory = blockFactory;
        this.breaker = breaker;
        RefCounted holds = AbstractRefCounted.of(() -> Releasables.close(breaker));
        holds.mustIncRef();
        this.operatorHold = Releasables.releaseOnce(holds::decRef);
        this.producerHold = Releasables.releaseOnce(holds::decRef);
    }

    /**
     * Opens the producer scope for the operator of {@code driverContext}. The reserve wraps the driver's root
     * (thread-safe) breaker and is sized like the driver's {@link LocalCircuitBreaker}. A context without a block
     * factory (only reachable from mocks) gets a scope without one.
     */
    static ProducerMemory open(DriverContext driverContext) {
        BlockFactory root = AsyncExternalSourceOperatorFactory.producerBlockFactory(driverContext);
        if (root == null) {
            return new ProducerMemory(null, null);
        }
        LocalCircuitBreaker.SizeSettings settings = driverContext.localBreakerSettings();
        if (settings == null) {
            settings = LocalCircuitBreaker.SizeSettings.DEFAULT_SETTINGS;
        }
        ConcurrentReservingCircuitBreaker breaker = new ConcurrentReservingCircuitBreaker(root.breaker(), settings);
        return new ProducerMemory(root.newProducerFactory(breaker), breaker);
    }

    /**
     * The factory producer threads allocate from, or {@code null} when the driver context has none.
     */
    @Nullable
    BlockFactory blockFactory() {
        return blockFactory;
    }

    /**
     * The breaker producer threads charge, or {@code null} when the driver context has no block factory.
     */
    @Nullable
    CircuitBreaker breaker() {
        return breaker;
    }

    /**
     * Released by the operator's close. Idempotent.
     */
    void releaseOperatorHold() {
        operatorHold.close();
    }

    /**
     * Released when the producer has finished, just before it removes its async action. Idempotent.
     */
    void releaseProducerHold() {
        producerHold.close();
    }
}
