/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.VectorScorer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.search.vectors.DirectIOVectorBatchLoader;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark comparing serial vs parallel vector loading performance.
 * Tests the DirectIOVectorBatchLoader with different configurations to measure
 * the performance impact of parallel vector loading during reranking operations.
 */
@Fork(value = 1, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(1)
@State(Scope.Thread)
public class VectorLoadingBenchmark {

    public static final int OPERATIONS = 100;

    static {
        LogConfigurator.configureESLogging();
    }

    @Param({ "512" })
    private int vectorDims;

    //@Param({ "5", "10", "25", "50", "75", "100" })
    @Param({ "50"})
    private int documentsToLoad;

    //@Param({ "75", "100", "150" })
    @Param({ "150"})
    private int ioLatencyMs;

    @Param({ "5000" })
    private int totalDocuments;

    private static final String VECTOR_FIELD = "vector";
    private Directory directory;
    private DirectoryReader reader;
    private LeafReaderContext leafContext;
    private ScoreDoc[] scoreDocs;
    private float[] queryVector;
    private Path tempDir;

    @Setup
    public void setup() throws IOException {
        tempDir = createTempDir();
        directory = new NIOFSDirectory(tempDir);
        createTestIndex();

        reader = DirectoryReader.open(directory);
        leafContext = reader.leaves().get(0);

        queryVector = randomVector(vectorDims);

        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), documentsToLoad);
        scoreDocs = topDocs.scoreDocs;
    }

    @TearDown
    public void tearDown() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    @Benchmark
    public void serialVectorLoading(Blackhole blackhole) throws IOException {
        var loader = new AccessibleDirectIOVectorBatchLoader();
        var supplier = s3LikeFloatVectorValues();

        for (int i = 0; i < OPERATIONS; i++) {
            int[] docIds = new int[scoreDocs.length];
            for (int j = 0; j < scoreDocs.length; j++) {
                docIds[j] = scoreDocs[j].doc;
            }

            Map<Integer, float[]> vectors = loader.loadSegmentVectorsSerial(docIds, supplier);
            blackhole.consume(vectors);
        }
    }

    @Benchmark
    public void parallelVectorLoading(Blackhole blackhole) throws IOException {
        var loader = new AccessibleDirectIOVectorBatchLoader();
        var supplier = s3LikeFloatVectorValues();

        for (int i = 0; i < OPERATIONS; i++) {
            int[] docIds = new int[scoreDocs.length];
            for (int j = 0; j < scoreDocs.length; j++) {
                docIds[j] = scoreDocs[j].doc;
            }

            Map<Integer, float[]> vectors = loader.loadSegmentVectorsParallel(docIds, supplier);
            blackhole.consume(vectors);
        }
    }

    private void createTestIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        config.setRAMBufferSizeMB(8);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            Random random = new Random(42); // Fixed seed for reproducible results

            for (int i = 0; i < totalDocuments; i++) {
                Document doc = new Document();
                float[] vector = randomVector(vectorDims, random);
                doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);

                if (i % 1000 == 0) {
                    writer.commit();
                }
            }
            writer.commit();
            writer.flush();
        }
    }

    private float[] randomVector(int dimensions) {
        return randomVector(dimensions, new Random());
    }

    private float[] randomVector(int dimensions, Random random) {
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextFloat() * 2.0f - 1.0f;
        }

        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    private Path createTempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("vector-loading-bench");
    }

    private static class AccessibleDirectIOVectorBatchLoader extends DirectIOVectorBatchLoader {
        @Override
        public Map<Integer, float[]> loadSegmentVectorsParallel(
            int[] docIds,
            CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier
        ) throws IOException {
            return super.loadSegmentVectorsParallel(docIds, vectorValuesSupplier);
        }

        @Override
        public Map<Integer, float[]> loadSegmentVectorsSerial(
            int[] docIds,
            CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier
        ) throws IOException {
            return super.loadSegmentVectorsSerial(docIds, vectorValuesSupplier);
        }
    }

    private CheckedSupplier<FloatVectorValues, IOException> s3LikeFloatVectorValues() throws IOException {
        final FloatVectorValues delegate = leafContext.reader().getFloatVectorValues(VECTOR_FIELD);
        return () -> new SlowFloatVectorValues(delegate, ioLatencyMs);
    }

    private static class SlowFloatVectorValues extends FloatVectorValues {
        private final FloatVectorValues delegate;
        private final long ioLatency;

        SlowFloatVectorValues(FloatVectorValues vectorValues, long ioLatency) {
            this.delegate = vectorValues;
            this.ioLatency = ioLatency;
        }

        @Override
        public float[] vectorValue(int i) throws IOException {
            try {
                Thread.sleep(Duration.of(ioLatency, ChronoUnit.MILLIS));
            } catch (InterruptedException ex) {}
            return delegate.vectorValue(i);
        }

        @Override
        public VectorScorer scorer(float[] target) throws IOException {
            return delegate.scorer(target);
        }

        @Override
        public VectorEncoding getEncoding() {
            return delegate.getEncoding();
        }

        @Override
        public int dimension() {
            return delegate.dimension();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public FloatVectorValues copy() throws IOException {
            return new SlowFloatVectorValues(delegate.copy(), ioLatency);
        }

        @Override
        public KnnVectorValues.DocIndexIterator iterator() {
            return delegate.iterator();
        }
    }
}
