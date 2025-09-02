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
import org.apache.lucene.index.LeafReaderContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Bulk vector loader that performs optimized I/O operations to load multiple vectors
 * simultaneously using parallel random access when possible.
 */
public class DirectIOVectorBatchLoader {
    
    private static final int PARALLEL_BATCH_SIZE = 8; // Vectors per parallel batch
    private static final ForkJoinPool LOADER_POOL = ForkJoinPool.commonPool();

    /**
     * Loads vectors for multiple document IDs using parallel processing when enabled.
     */
    public Map<Integer, float[]> loadSegmentVectors(int[] docIds, LeafReaderContext context, String field) throws IOException {
        // Feature flag for parallel loading
        if (BulkVectorProcessingSettings.PARALLEL_VECTOR_LOADING) {
            return loadSegmentVectorsParallel(docIds, context, field);
        }
        
        // Keep existing sequential implementation as fallback
        return loadSegmentVectorsSequential(docIds, context, field);
    }

    /**
     * Parallel vector loading implementation - POC version
     */
    private Map<Integer, float[]> loadSegmentVectorsParallel(int[] docIds, LeafReaderContext context, String field) throws IOException {
        FloatVectorValues vectorValues = context.reader().getFloatVectorValues(field);
        if (vectorValues == null) {
            throw new IllegalArgumentException("No float vector values found for field: " + field);
        }

        // Phase 1: Build docId -> ordinal mapping (optimized for target docs only)
        Map<Integer, Integer> docToOrdinal = buildDocToOrdinalMapping(vectorValues, docIds);
        
        // Phase 2: Group docIds into parallel batches
        List<List<Integer>> batches = createBatches(new ArrayList<>(docToOrdinal.keySet()), PARALLEL_BATCH_SIZE);
        
        // Phase 3: Parallel vector loading
        List<CompletableFuture<Map<Integer, float[]>>> futures = batches.stream()
            .map(batch -> CompletableFuture.supplyAsync(() -> {
                try {
                    return loadVectorBatch(vectorValues, batch, docToOrdinal);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load vector batch", e);
                }
            }, LOADER_POOL))
            .toList();
        
        // Phase 4: Combine results
        Map<Integer, float[]> combinedResult = new HashMap<>();
        try {
            for (CompletableFuture<Map<Integer, float[]>> future : futures) {
                combinedResult.putAll(future.get());
            }
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Parallel vector loading failed", e);
        }
        
        return combinedResult;
    }

    /**
     * Core parallel loading logic - each thread processes a batch independently
     */
    private Map<Integer, float[]> loadVectorBatch(
            FloatVectorValues vectorValues,
            List<Integer> docIdBatch,
            Map<Integer, Integer> docToOrdinal) throws IOException {
        
        Map<Integer, float[]> batchResult = new HashMap<>();
        
        // Each thread works with the SAME vectorValues instance
        // OffHeapFloatVectorValues.vectorValue(ordinal) is thread-safe for reads
        for (Integer docId : docIdBatch) {
            Integer ordinal = docToOrdinal.get(docId);
            if (ordinal != null) {
                // RANDOM ACCESS by ordinal - this is the key optimization
                float[] vector = vectorValues.vectorValue(ordinal);
                batchResult.put(docId, vector);
            }
        }
        
        return batchResult;
    }

    /**
     * Optimized docId -> ordinal mapping that only processes target documents
     */
    private Map<Integer, Integer> buildDocToOrdinalMapping(
            FloatVectorValues vectorValues, 
            int[] targetDocIds) throws IOException {
        
        Map<Integer, Integer> docToOrdinal = new HashMap<>();
        Set<Integer> targetDocSet = Arrays.stream(targetDocIds).boxed()
            .collect(Collectors.toSet());
        
        KnnVectorValues.DocIndexIterator iterator = vectorValues.iterator();
        for (int docId = iterator.nextDoc(); docId != KnnVectorValues.DocIndexIterator.NO_MORE_DOCS; docId = iterator.nextDoc()) {
            if (targetDocSet.contains(docId)) {  // Only map docs we actually need
                docToOrdinal.put(docId, iterator.index());
                
                // Early termination when all target docs found
                if (docToOrdinal.size() == targetDocSet.size()) {
                    break;
                }
            }
        }
        
        return docToOrdinal;
    }

    /**
     * Sequential implementation - existing logic preserved as fallback
     */
    private Map<Integer, float[]> loadSegmentVectorsSequential(int[] docIds, LeafReaderContext context, String field) throws IOException {
        Map<Integer, float[]> vectorCache = new HashMap<>();

        // Get vector values for the field
        FloatVectorValues vectorValues = context.reader().getFloatVectorValues(field);
        if (vectorValues == null) {
            throw new IllegalArgumentException("No float vector values found for field: " + field);
        }

        // Build a lookup of available documents
        Map<Integer, Integer> docToIndex = new HashMap<>();
        KnnVectorValues.DocIndexIterator iterator = vectorValues.iterator();
        for (int docId = iterator.nextDoc(); docId != KnnVectorValues.DocIndexIterator.NO_MORE_DOCS; docId = iterator.nextDoc()) {
            docToIndex.put(docId, iterator.index());
        }

        // Load vectors for requested documents
        for (int docId : docIds) {
            Integer vectorIndex = docToIndex.get(docId);
            if (vectorIndex != null) {
                float[] vector = vectorValues.vectorValue(vectorIndex);
                if (vector != null) {
                    vectorCache.put(docId, vector);
                }
            }
        }

        return vectorCache;
    }

    /**
     * Utility method to split items into batches for parallel processing
     */
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
    public float[] loadSingleVector(int docId, LeafReaderContext context, String field) throws IOException {
        FloatVectorValues vectorValues = context.reader().getFloatVectorValues(field);
        if (vectorValues == null) {
            throw new IllegalArgumentException("No float vector values found for field: " + field);
        }

        KnnVectorValues.DocIndexIterator iterator = vectorValues.iterator();
        for (int currentDoc = iterator.nextDoc(); currentDoc != KnnVectorValues.DocIndexIterator.NO_MORE_DOCS; currentDoc = iterator
            .nextDoc()) {
            if (currentDoc == docId) {
                float[] vector = vectorValues.vectorValue(iterator.index());
                return vector != null ? vector : null;
            }
        }

        throw new IllegalArgumentException("Document " + docId + " not found in vector values");
    }
}
