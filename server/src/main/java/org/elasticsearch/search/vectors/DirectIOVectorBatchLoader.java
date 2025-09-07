/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.util.Maps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Bulk vector loader that performs optimized I/O operations to load multiple vectors
 * simultaneously using parallel random access when possible.
 */
public class DirectIOVectorBatchLoader {

    private static final int BATCH_PER_THREAD = 4;
    // TODO: hook into a dedicated thread pool or at least name the virtual threads
    private Executor vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public Map<Integer, float[]> loadSegmentVectors(int[] docIds, CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier)
        throws IOException {

        if (docIds.length >= BulkVectorProcessingSettings.MIN_BULK_PROCESSING_THRESHOLD) {
            return loadSegmentVectorsParallel(docIds, vectorValuesSupplier);
        } else {
            return loadSegmentVectorsSerial(docIds, vectorValuesSupplier);
        }
    }

    @SuppressWarnings("rawtypes")
    protected Map<Integer, float[]> loadSegmentVectorsParallel(
        int[] docIds,
        CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier
    ) throws IOException {
        FloatVectorValues vectorValues = vectorValuesSupplier.get();

        Map<Integer, Integer> docToOrdinal = buildDocToOrdinalMapping(vectorValues, docIds);
        List<List<Integer>> batches = createBatches(new ArrayList<>(docToOrdinal.keySet()), BATCH_PER_THREAD);

        List<CompletableFuture<Map<Integer, float[]>>> futures = new ArrayList<>();
        for (List<Integer> batch : batches) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    FloatVectorValues vv = vectorValuesSupplier.get();
                    return loadVectorBatch(vv, batch, docToOrdinal);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load vector batch", e);
                }
            }, vtExecutor));
        }

        CompletableFuture<Void> allComplete = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );


        Map<Integer, float[]> combinedResult = new HashMap<>();
        try {
            allComplete.get();
            for (CompletableFuture<Map<Integer, float[]>> future : futures) {
                var results = future.get();
                combinedResult.putAll(results);
            }
        } catch (Exception e) {
            throw ExceptionsHelper.convertToElastic(e);
        }

        return combinedResult;
    }

    protected Map<Integer, float[]> loadSegmentVectorsSerial(
        int[] docIds,
        CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier
    ) throws IOException {
        FloatVectorValues vectorValues = vectorValuesSupplier.get();

        Map<Integer, Integer> docToOrdinal = buildDocToOrdinalMapping(vectorValues, docIds);
        Map<Integer, float[]> result = new HashMap<>();

        // Load vectors sequentially
        for (int docId : docIds) {
            Integer ordinal = docToOrdinal.get(docId);
            if (ordinal != null) {
                // clone the vector since the reader reuses the array
                float[] vector = vectorValues.vectorValue(ordinal).clone();
                result.put(docId, vector);
            }
        }

        return result;
    }

    private Map<Integer, float[]> loadVectorBatch(
        FloatVectorValues vectorValues,
        List<Integer> docIdBatch,
        Map<Integer, Integer> docToOrdinal
    ) throws IOException {

        Map<Integer, float[]> batchResult = new HashMap<>();

        for (Integer docId : docIdBatch) {
            Integer ordinal = docToOrdinal.get(docId);
            if (ordinal != null) {
                // clone the vector since the reader reuses the array
                float[] vector = vectorValues.vectorValue(ordinal).clone();
                batchResult.put(docId, vector);
            }
        }

        return batchResult;
    }

    private Map<Integer, Integer> buildDocToOrdinalMapping(FloatVectorValues vectorValues, int[] targetDocIds) throws IOException {

        Map<Integer, Integer> docToOrdinal = Maps.newHashMapWithExpectedSize(targetDocIds.length);

        KnnVectorValues.DocIndexIterator iterator = vectorValues.iterator();
        for (int i = 0; i < targetDocIds.length; i++) {
            var next = iterator.advance(targetDocIds[i]);
            if (next == KnnVectorValues.DocIndexIterator.NO_MORE_DOCS || next != targetDocIds[i]) {
                break;
            }
            docToOrdinal.put(next, iterator.index());
        }
        return docToOrdinal;
    }

    private <T> List<List<T>> createBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return batches;
    }

    /**
     * TODO: look into removing this method
     */
    public float[] loadSingleVector(int docId, CheckedSupplier<FloatVectorValues, IOException> vectorValuesSupplier) throws IOException {
        FloatVectorValues vectorValues = vectorValuesSupplier.get();

        KnnVectorValues.DocIndexIterator iterator = vectorValues.iterator();
        var next = iterator.advance(docId);
        float[] result = null;
        if (next != KnnVectorValues.DocIndexIterator.NO_MORE_DOCS && next == docId) {
            var ordinal = iterator.index();
            result = vectorValues.vectorValue(ordinal).clone();
        }
        return result;
    }
}
