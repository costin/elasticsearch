/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.compute.data.ConcurrentReservingCircuitBreaker;
import org.elasticsearch.compute.data.LocalCircuitBreaker;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerMetrics;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.search.scheduling.QueryScheduler;
import org.elasticsearch.tasks.TaskId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost of an external-source producer thread charging a block and releasing it, as the thread count grows. Run it
 * with {@code -t 1}, {@code -t 12} and {@code -t 32}.
 * <ul>
 *     <li>{@code shared}: every thread charges the query breaker, as producers did before they had a reserve.</li>
 *     <li>{@code perOperator}: each thread charges its own {@link ConcurrentReservingCircuitBreaker}, as each
 *     source operator's producers do.</li>
 *     <li>{@code oneForAll}: every thread charges one {@link ConcurrentReservingCircuitBreaker}; shows why the reserve
 *     must be scoped to an operator.</li>
 *     <li>{@code local}: each thread charges its own {@link LocalCircuitBreaker}, the single-threaded lower bound.</li>
 * </ul>
 */
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ProducerBreakerBenchmark {

    static {
        BenchmarkLogging.configure();
    }

    private static final String LABEL = "<esql_block_factory>";
    /** The {@code over_reserved} default for heaps over 8GB. */
    private static final long CHUNK = 512 * 1024;
    private static final long MAX_LOCAL = 4 * 1024 * 1024;

    @Param({ "true" })
    public boolean realMemory;

    @Param({ "shared", "perOperator", "oneForAll", "local" })
    public String path;

    @Param({ "16384" })
    public long bytes;

    private QueryScheduler.QueryPart part;
    private CircuitBreaker query;
    private ConcurrentReservingCircuitBreaker oneForAll;

    @Setup(Level.Trial)
    public void setup() {
        Settings settings = Settings.builder().put("indices.breaker.total.use_real_memory", realMemory).build();
        HierarchyCircuitBreakerService service = new HierarchyCircuitBreakerService(
            CircuitBreakerMetrics.NOOP,
            settings,
            List.of(),
            new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
        );
        QueryScheduler scheduler = new QueryScheduler(System::nanoTime, service.getBreaker(CircuitBreaker.REQUEST));
        part = scheduler.part(new TaskId("coordinator", 1), 1);
        query = part.breaker();
        oneForAll = new ConcurrentReservingCircuitBreaker(query, CHUNK);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oneForAll.close();
        part.close();
    }

    /**
     * The breaker one benchmark thread charges.
     */
    @State(Scope.Thread)
    public static class PerThread {
        CircuitBreaker breaker;
        /** The breaker this thread created and must close, if any. */
        Releasable owned;

        @Setup(Level.Iteration)
        public void setup(ProducerBreakerBenchmark b) {
            switch (b.path) {
                case "shared" -> breaker = b.query;
                case "perOperator" -> {
                    ConcurrentReservingCircuitBreaker reserving = new ConcurrentReservingCircuitBreaker(b.query, CHUNK);
                    breaker = reserving;
                    owned = reserving;
                }
                case "oneForAll" -> breaker = b.oneForAll;
                case "local" -> {
                    LocalCircuitBreaker local = new LocalCircuitBreaker(b.query, CHUNK, MAX_LOCAL);
                    breaker = local;
                    owned = local;
                }
                default -> throw new IllegalArgumentException("unknown path [" + b.path + "]");
            }
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            Releasables.close(owned);
            owned = null;
        }
    }

    /**
     * Charges a block and releases it through the same breaker, as a producer block does when the driver closes it.
     */
    @Benchmark
    public void chargeAndRelease(PerThread t) {
        t.breaker.addEstimateBytesAndMaybeBreak(bytes, LABEL);
        t.breaker.addWithoutBreaking(-bytes);
    }

    /**
     * Charges a block and releases it on the query breaker, isolating the charge side.
     */
    @Benchmark
    public void chargeOnly(PerThread t) {
        t.breaker.addEstimateBytesAndMaybeBreak(bytes, LABEL);
        query.addWithoutBreaking(-bytes);
    }
}
