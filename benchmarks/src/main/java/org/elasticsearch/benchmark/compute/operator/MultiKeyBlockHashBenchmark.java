/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntArrayBlock;
import org.elasticsearch.compute.data.IntBigArrayBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.Page;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
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
 * SPIKE-only micro-benchmark (Task X/10, esql/codegen-stitching-iter). Compares the raw multi-key
 * {@code STATS ... BY a, b} ingestion (i.e. {@link BlockHash#add}) cost of a hand-written
 * <b>type-specialized</b> composite-key hash (the structured {@code LongLongBlockHash}) against the
 * <b>generic</b> {@code PackedValuesBlockHash} over the same all-vector, no-null, single-value pages.
 * <p>
 * This measures the ceiling of any runtime-codegen win for numeric composite keys: runtime codegen could
 * at best reproduce a hand-written specialization, so {@code specialized_ll} vs {@code packed_ll} bounds
 * the recoverable gap over the already-vectorized generic path. {@code packed_ii} shows the generic path
 * on a combo (INT,INT) that has no specialization today.
 */
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class MultiKeyBlockHashBenchmark {
    private static final int ROWS = 8 * 1024;

    static {
        Utils.configureBenchmarkLogging();
    }

    private static final BlockFactory blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("bench"))
        .build();

    /**
     * Discards ords without closing them (the {@link BlockHash} retains ownership), touching one value so
     * the JIT can't dead-code the add loop.
     */
    private static final class NoopAddInput implements GroupingAggregatorFunction.AddInput {
        long sink;

        @Override
        public void add(int positionOffset, IntArrayBlock groupIds) {
            sink += groupIds.getPositionCount();
        }

        @Override
        public void add(int positionOffset, IntBigArrayBlock groupIds) {
            sink += groupIds.getPositionCount();
        }

        @Override
        public void add(int positionOffset, IntVector groupIds) {
            sink += groupIds.getPositionCount();
        }

        @Override
        public void close() {}
    }

    /**
     * impl selects the (type-combo, implementation) pair:
     * <ul>
     *   <li>{@code specialized_ll} — (LONG,LONG) via the hand-written structured {@code LongLongBlockHash}</li>
     *   <li>{@code packed_ll} — (LONG,LONG) via the generic {@code PackedValuesBlockHash}</li>
     *   <li>{@code packed_ii} — (INT,INT) via the generic {@code PackedValuesBlockHash} (no specialization exists)</li>
     * </ul>
     */
    @Param({ "specialized_ll", "packed_ll", "packed_ii" })
    public String impl;

    /** Approximate number of distinct composite groups (controls hash-table occupancy / collisions). */
    @Param({ "16", "1024", "1000000" })
    public int cardinality;

    private Page page;
    private NoopAddInput addInput;

    @Setup
    public void setup() {
        addInput = new NoopAddInput();
        boolean ints = impl.equals("packed_ii");
        // Spread cardinality across two columns: each column carries ~sqrt(cardinality) distinct values.
        int perColumn = Math.max(1, (int) Math.round(Math.sqrt(cardinality)));
        if (ints) {
            int[] c1 = new int[ROWS];
            int[] c2 = new int[ROWS];
            for (int i = 0; i < ROWS; i++) {
                c1[i] = i % perColumn;
                c2[i] = (i / perColumn) % perColumn;
            }
            page = new Page(blockFactory.newIntArrayVector(c1, ROWS).asBlock(), blockFactory.newIntArrayVector(c2, ROWS).asBlock());
        } else {
            long[] c1 = new long[ROWS];
            long[] c2 = new long[ROWS];
            for (int i = 0; i < ROWS; i++) {
                c1[i] = i % perColumn;
                c2[i] = (i / perColumn) % perColumn;
            }
            page = new Page(blockFactory.newLongArrayVector(c1, ROWS).asBlock(), blockFactory.newLongArrayVector(c2, ROWS).asBlock());
        }
    }

    @TearDown
    public void tearDown() {
        page.releaseBlocks();
    }

    private BlockHash newBlockHash() {
        return switch (impl) {
            case "specialized_ll" -> BlockHash.build(
                List.of(new BlockHash.GroupSpec(0, ElementType.LONG), new BlockHash.GroupSpec(1, ElementType.LONG)),
                blockFactory,
                ROWS,
                true // allowBrokenOptimizations -> routes (LONG,LONG) to the specialized LongLongBlockHash
            );
            case "packed_ll" -> BlockHash.buildPackedValuesBlockHash(
                List.of(new BlockHash.GroupSpec(0, ElementType.LONG), new BlockHash.GroupSpec(1, ElementType.LONG)),
                blockFactory,
                ROWS
            );
            case "packed_ii" -> BlockHash.buildPackedValuesBlockHash(
                List.of(new BlockHash.GroupSpec(0, ElementType.INT), new BlockHash.GroupSpec(1, ElementType.INT)),
                blockFactory,
                ROWS
            );
            default -> throw new IllegalArgumentException("unknown impl [" + impl + "]");
        };
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long add() {
        try (BlockHash hash = newBlockHash()) {
            hash.add(page, addInput);
        }
        return addInput.sink;
    }
}
