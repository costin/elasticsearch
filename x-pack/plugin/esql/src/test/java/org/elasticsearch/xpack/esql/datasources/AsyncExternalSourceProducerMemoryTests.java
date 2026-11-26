/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.ConcurrentReservingCircuitBreaker;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LocalCircuitBreaker;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.CloseableIterator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.datasources.spi.AbstractTestStorageObject;
import org.elasticsearch.xpack.esql.datasources.spi.ExternalSplit;
import org.elasticsearch.xpack.esql.datasources.spi.FormatReadContext;
import org.elasticsearch.xpack.esql.datasources.spi.FormatReader;
import org.elasticsearch.xpack.esql.datasources.spi.NoConfigFormatReader;
import org.elasticsearch.xpack.esql.datasources.spi.PassThroughRowPositionStrategy;
import org.elasticsearch.xpack.esql.datasources.spi.RowPositionStrategy;
import org.elasticsearch.xpack.esql.datasources.spi.SourceMetadata;
import org.elasticsearch.xpack.esql.datasources.spi.StorageChildren;
import org.elasticsearch.xpack.esql.datasources.spi.StorageObject;
import org.elasticsearch.xpack.esql.datasources.spi.StoragePath;
import org.elasticsearch.xpack.esql.datasources.spi.StorageProvider;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Producer threads of each external-source operator charge a {@link ConcurrentReservingCircuitBreaker} of their own.
 * These tests drive several operators over one shared breaker with real {@link DriverContext}s and check that every
 * reserve is returned, whether the read completes or the operators are closed while their producers are mid-read.
 */
public class AsyncExternalSourceProducerMemoryTests extends ESTestCase {
    private static final int POSITIONS_PER_PAGE = 1024;

    public void testBreakerBalancedAfterCompletedMultiSplitRead() throws Exception {
        runMultiSplitRead(false);
    }

    public void testBreakerBalancedAfterCancelledMultiSplitRead() throws Exception {
        runMultiSplitRead(true);
    }

    private void runMultiSplitRead(boolean cancel) throws Exception {
        CircuitBreaker breaker = newLimitedBreaker(ByteSizeValue.ofMb(512));
        BlockFactory root = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE).breaker(breaker).build();
        int splitCount = between(4, 16);
        int pagesPerSplit = between(2, 20);
        List<ExternalSplit> splits = new ArrayList<>();
        for (int i = 0; i < splitCount; i++) {
            splits.add(new FileSplit("test", StoragePath.of("s3://bucket/f" + i + ".parquet"), 0, 100, "parquet", Map.of(), Map.of()));
        }
        int drivers = between(1, 4);
        // When cancelling, every producer parks on its second page until the operators are closed.
        CountDownLatch closed = new CountDownLatch(cancel ? 1 : 0);
        CountDownLatch parked = new CountDownLatch(cancel ? drivers : 0);
        AllocatingReader reader = new AllocatingReader(null, pagesPerSplit, parked, closed);
        ExecutorService pool = Executors.newFixedThreadPool(4, EsExecutors.daemonThreadFactory("test", "producer-memory"));
        List<LocalCircuitBreaker> localBreakers = new ArrayList<>();
        List<DriverContext> contexts = new ArrayList<>();
        List<SourceOperator> operators = new ArrayList<>();
        try {
            AsyncExternalSourceOperatorFactory factory = AsyncExternalSourceOperatorFactory.builder(
                new StubStorageProvider(),
                reader,
                StoragePath.of("s3://bucket/f0.parquet"),
                attributes(),
                100,
                1,
                pool
            ).sliceQueue(new ExternalSliceQueue(splits)).build();
            LocalCircuitBreaker.SizeSettings sizes = new LocalCircuitBreaker.SizeSettings(4096, 65536);
            for (int d = 0; d < drivers; d++) {
                LocalCircuitBreaker local = new LocalCircuitBreaker(breaker, sizes.overReservedBytes(), sizes.maxOverReservedBytes());
                localBreakers.add(local);
                DriverContext ctx = new DriverContext(BigArrays.NON_RECYCLING_INSTANCE, root.newChildFactory(local), sizes);
                contexts.add(ctx);
                operators.add(factory.get(ctx));
            }

            if (cancel) {
                safeAwait(parked);
                // Poll at most one page per operator, then close them while their producers are still reading.
                for (SourceOperator op : operators) {
                    Page page = op.getOutput();
                    if (page != null) {
                        page.releaseBlocks();
                    }
                    op.close();
                }
                closed.countDown();
            } else {
                int rows = 0;
                for (SourceOperator op : operators) {
                    rows += drain(op);
                    op.close();
                }
                assertThat(rows, equalTo(splitCount * pagesPerSplit * POSITIONS_PER_PAGE));
            }

            // As the driver does: finish the context, then wait for the producers to stop.
            for (DriverContext ctx : contexts) {
                ctx.finish();
                PlainActionFuture<Void> done = new PlainActionFuture<>();
                ctx.waitForAsyncActions(done);
                done.actionGet(30, TimeUnit.SECONDS);
            }
            assertFalse("every producer must bind a factory", reader.boundFactories.isEmpty());
            for (BlockFactory bound : reader.boundFactories) {
                assertThat(bound.breaker(), instanceOf(ConcurrentReservingCircuitBreaker.class));
                assertThat(((ConcurrentReservingCircuitBreaker) bound.breaker()).parentBreaker(), sameInstance(breaker));
            }
            // Each operator has its own reserve. A fast operator can take every split before the others start, unless
            // the producers park on their first split.
            if (cancel) {
                assertThat("one reserving breaker per operator", reader.boundBreakers().size(), equalTo(drivers));
            } else {
                assertThat(reader.boundBreakers().size(), lessThanOrEqualTo(drivers));
            }
            for (LocalCircuitBreaker local : localBreakers) {
                local.close();
            }
            localBreakers.clear();
            assertThat(breaker.getUsed(), equalTo(0L));
        } finally {
            closed.countDown();
            for (LocalCircuitBreaker local : localBreakers) {
                local.close();
            }
            terminate(pool);
        }
    }

    private static int drain(SourceOperator operator) {
        int rows = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            Page page = operator.getOutput();
            if (page != null) {
                rows += page.getPositionCount();
                page.releaseBlocks();
                continue;
            }
            if (operator.isFinished()) {
                return rows;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out draining operator");
            }
            PlainActionFuture<Void> unblocked = new PlainActionFuture<>();
            operator.isBlocked().listener().addListener(unblocked);
            unblocked.actionGet(30, TimeUnit.SECONDS);
        }
    }

    private static List<Attribute> attributes() {
        return List.of(
            new FieldAttribute(
                Source.EMPTY,
                "value",
                new EsField("value", DataType.INTEGER, Map.of(), false, EsField.TimeSeriesFieldType.NONE)
            )
        );
    }

    /**
     * Builds every page from the factory bound by {@link #withBlockFactory}, as the real readers do, so the pages are
     * charged to the operator's reserve. Counts down {@code parked} and parks on {@code beforeSecondPage} so a test can close the operator mid-read.
     */
    private static final class AllocatingReader implements NoConfigFormatReader {
        private final BlockFactory blockFactory;
        private final int pagesPerSplit;
        private final CountDownLatch parked;
        private final CountDownLatch beforeSecondPage;
        private final Set<BlockFactory> boundFactories;

        AllocatingReader(@Nullable BlockFactory blockFactory, int pagesPerSplit, CountDownLatch parked, CountDownLatch beforeSecondPage) {
            this(blockFactory, pagesPerSplit, parked, beforeSecondPage, ConcurrentHashMap.newKeySet());
        }

        private AllocatingReader(
            @Nullable BlockFactory blockFactory,
            int pagesPerSplit,
            CountDownLatch parked,
            CountDownLatch beforeSecondPage,
            Set<BlockFactory> boundFactories
        ) {
            this.blockFactory = blockFactory;
            this.pagesPerSplit = pagesPerSplit;
            this.parked = parked;
            this.beforeSecondPage = beforeSecondPage;
            this.boundFactories = boundFactories;
        }

        Set<CircuitBreaker> boundBreakers() {
            Set<CircuitBreaker> breakers = ConcurrentHashMap.newKeySet();
            for (BlockFactory factory : boundFactories) {
                breakers.add(factory.breaker());
            }
            return breakers;
        }

        @Override
        public FormatReader withBlockFactory(@Nullable BlockFactory factory) {
            if (factory == null || factory == blockFactory) {
                return this;
            }
            boundFactories.add(factory);
            return new AllocatingReader(factory, pagesPerSplit, parked, beforeSecondPage, boundFactories);
        }

        @Override
        public SourceMetadata metadata(StorageObject object) {
            return null;
        }

        @Override
        public CloseableIterator<Page> read(StorageObject object, FormatReadContext context) {
            Objects.requireNonNull(blockFactory, "the operator must bind a producer factory before reading");
            return new CloseableIterator<>() {
                private int emitted;

                @Override
                public boolean hasNext() {
                    return emitted < pagesPerSplit;
                }

                @Override
                public Page next() {
                    if (emitted >= pagesPerSplit) {
                        throw new NoSuchElementException();
                    }
                    if (emitted == 1 && beforeSecondPage.getCount() > 0) {
                        parked.countDown();
                        safeAwait(beforeSecondPage);
                    }
                    emitted++;
                    try (IntVector.FixedBuilder builder = blockFactory.newIntVectorFixedBuilder(POSITIONS_PER_PAGE)) {
                        for (int i = 0; i < POSITIONS_PER_PAGE; i++) {
                            builder.appendInt(i);
                        }
                        return new Page(builder.build().asBlock());
                    }
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public String formatName() {
            return "allocating";
        }

        @Override
        public List<String> fileExtensions() {
            return List.of(".parquet");
        }

        @Override
        public RowPositionStrategy rowPositionStrategy() {
            return PassThroughRowPositionStrategy.INSTANCE;
        }

        @Override
        public void close() {}
    }

    private static final class StubStorageProvider implements StorageProvider {
        @Override
        public StorageChildren listChildren(StoragePath prefix, int limit) {
            return null;
        }

        @Override
        public StorageObject newObject(StoragePath path) {
            return new StubStorageObject(path);
        }

        @Override
        public StorageObject newObject(StoragePath path, long length) {
            return new StubStorageObject(path);
        }

        @Override
        public StorageObject newObject(StoragePath path, long length, Instant lastModified) {
            return new StubStorageObject(path);
        }

        @Override
        public StorageIterator listObjects(StoragePath prefix, boolean recursive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(StoragePath path) {
            return true;
        }

        @Override
        public List<String> supportedSchemes() {
            return List.of("s3");
        }

        @Override
        public void close() {}
    }

    private static final class StubStorageObject extends AbstractTestStorageObject {
        private final StoragePath path;

        StubStorageObject(StoragePath path) {
            this.path = path;
        }

        @Override
        public InputStream newStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream newStream(long position, long length) {
            return InputStream.nullInputStream();
        }

        @Override
        public long length() {
            return 0;
        }

        @Override
        public Instant lastModified() {
            return Instant.EPOCH;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public StoragePath path() {
            return path;
        }
    }
}
