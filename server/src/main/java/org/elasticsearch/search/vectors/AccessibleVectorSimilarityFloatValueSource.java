/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.ScoreDoc;
import org.elasticsearch.index.mapper.vectors.VectorSimilarityFloatValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extended VectorSimilarityFloatValueSource that provides bulk vector loading
 * optimizations when processing multiple documents.
 */
class AccessibleVectorSimilarityFloatValueSource extends VectorSimilarityFloatValueSource {

    String field;
    float[] target;
    VectorSimilarityFunction vectorSimilarityFunction;
    private final ScoreDoc[] scoreDocs;

    AccessibleVectorSimilarityFloatValueSource(
        String field, 
        float[] target, 
        VectorSimilarityFunction vectorSimilarityFunction,
        ScoreDoc[] scoreDocs
    ) {
        super(field, target, vectorSimilarityFunction);
        this.field = field;
        this.target = target;
        this.vectorSimilarityFunction = vectorSimilarityFunction;
        this.scoreDocs = scoreDocs;
    }

    @Override
    public DoubleValues getValues(LeafReaderContext context, DoubleValues scores) throws IOException {
        // Check if bulk processing should be used
        if (scoreDocs != null && BulkVectorProcessingSettings.shouldUseBulkProcessing(scoreDocs.length)) {
            int[] segmentDocIds = extractSegmentDocuments(scoreDocs, context);
            
            if (segmentDocIds.length >= BulkVectorProcessingSettings.MIN_BULK_PROCESSING_THRESHOLD) {
                return createBulkVectorDoubleValues(context, scores, segmentDocIds);
            }
        }
        
        // Fallback to individual loading
        return super.getValues(context, scores);
    }

    /**
     * Creates bulk-optimized DoubleValues that precomputes similarities for all segment documents
     */
    private DoubleValues createBulkVectorDoubleValues(
            LeafReaderContext context, 
            DoubleValues scores, 
            int[] segmentDocIds) throws IOException {
        
        // Bulk load all vectors for this segment
        DirectIOVectorBatchLoader loader = new DirectIOVectorBatchLoader();
        Map<Integer, float[]> vectorCache = loader.loadSegmentVectors(segmentDocIds, context, field);
        
        // Precompute similarities
        Map<Integer, Float> similarityCache = new HashMap<>();
        for (Map.Entry<Integer, float[]> entry : vectorCache.entrySet()) {
            float similarity = vectorSimilarityFunction.compare(target, entry.getValue());
            similarityCache.put(entry.getKey(), similarity);
        }
        
        return new BulkVectorDoubleValues(scores, similarityCache);
    }

    /**
     * Extract segment-specific document IDs from global ScoreDoc array
     */
    private int[] extractSegmentDocuments(ScoreDoc[] scoreDocs, LeafReaderContext context) {
        List<Integer> segmentDocs = new ArrayList<>();
        int docBase = context.docBase;
        int maxDoc = docBase + context.reader().maxDoc();

        for (ScoreDoc scoreDoc : scoreDocs) {
            if (scoreDoc.doc >= docBase && scoreDoc.doc < maxDoc) {
                // Convert to segment-relative document ID
                segmentDocs.add(scoreDoc.doc - docBase);
            }
        }

        return segmentDocs.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * DoubleValues implementation that returns precomputed similarity scores
     */
    private static class BulkVectorDoubleValues extends DoubleValues {
        private final DoubleValues scores;
        private final Map<Integer, Float> similarityCache;

        BulkVectorDoubleValues(DoubleValues scores, Map<Integer, Float> similarityCache) {
            this.scores = scores;
            this.similarityCache = similarityCache;
        }

        @Override
        public double doubleValue() throws IOException {
            int docId = scores.docID();
            Float similarity = similarityCache.get(docId);
            return similarity != null ? similarity : 0.0;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            return scores.advanceExact(target);
        }

        @Override
        public int docID() {
            return scores.docID();
        }
    }

    public String field() {
        return field;
    }

    public float[] target() {
        return target;
    }

    public VectorSimilarityFunction similarityFunction() {
        return vectorSimilarityFunction;
    }

    public ScoreDoc[] scoreDocs() {
        return scoreDocs;
    }
}
