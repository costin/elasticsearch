/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.Constants;
import org.elasticsearch.test.ESTestCase;
import org.junit.BeforeClass;
import org.openjdk.jmh.annotations.Param;

import java.util.Arrays;

public class VectorLoadingBenchmarkTests extends ESTestCase {

    final int vectorDims;
    final int documentsToLoad;

    public VectorLoadingBenchmarkTests(int vectorDims, int documentsToLoad) {
        this.vectorDims = vectorDims;
        this.documentsToLoad = documentsToLoad;
    }

    @BeforeClass
    public static void skipWindows() {
        assumeFalse("doesn't work on windows yet", Constants.WINDOWS);
    }

    public void testSerialVectorLoadingSetup() throws Exception {
        var benchmark = new VectorLoadingBenchmark();
        benchmark.vectorDims = vectorDims;
        benchmark.documentsToLoad = documentsToLoad;
        benchmark.totalDocuments = 1000; // Smaller for tests
        
        try {
            benchmark.setup();
            assertNotNull("ScoreDocs should be initialized", benchmark.scoreDocs);
            assertEquals("Should load requested number of documents", documentsToLoad, benchmark.scoreDocs.length);
            assertNotNull("Query vector should be initialized", benchmark.queryVector);
            assertEquals("Query vector should have correct dimensions", vectorDims, benchmark.queryVector.length);
        } finally {
            benchmark.tearDown();
        }
    }

    public void testParallelVectorLoadingSetup() throws Exception {
        var benchmark = new VectorLoadingBenchmark();
        benchmark.vectorDims = vectorDims;
        benchmark.documentsToLoad = documentsToLoad;
        benchmark.totalDocuments = 1000; // Smaller for tests
        
        try {
            benchmark.setup();
            
            // Verify the benchmark can run without errors
            assertNotNull("Directory reader should be initialized", benchmark.reader);
            assertNotNull("Leaf context should be initialized", benchmark.leafContext);
            assertTrue("Should have documents to process", benchmark.scoreDocs.length > 0);
            
        } finally {
            benchmark.tearDown();
        }
    }

    public void testBenchmarkParameterConsistency() throws Exception {
        // Test that benchmark parameters are consistent with vector loading requirements
        var benchmark = new VectorLoadingBenchmark();
        benchmark.vectorDims = vectorDims;
        benchmark.documentsToLoad = documentsToLoad;
        benchmark.totalDocuments = Math.max(documentsToLoad * 2, 1000);
        
        try {
            benchmark.setup();
            
            // Verify parameter consistency
            assertTrue("Documents to load should be less than total documents", 
                benchmark.documentsToLoad <= benchmark.totalDocuments);
            assertTrue("Vector dimensions should be positive", benchmark.vectorDims > 0);
            assertTrue("Should have at least some documents", benchmark.documentsToLoad > 0);
            
        } finally {
            benchmark.tearDown();
        }
    }

    @ParametersFactory
    public static Iterable<Object[]> parametersFactory() {
        try {
            // Get vector dimensions parameter
            var vectorDimsField = VectorLoadingBenchmark.class.getField("vectorDims");
            var vectorDimsParams = vectorDimsField.getAnnotationsByType(Param.class)[0].value();
            
            // Get documents to load parameter  
            var documentsToLoadField = VectorLoadingBenchmark.class.getField("documentsToLoad");
            var documentsToLoadParams = documentsToLoadField.getAnnotationsByType(Param.class)[0].value();
            
            return () -> {
                var params = new java.util.ArrayList<Object[]>();
                for (String vectorDim : vectorDimsParams) {
                    for (String docCount : documentsToLoadParams) {
                        params.add(new Object[] { Integer.parseInt(vectorDim), Integer.parseInt(docCount) });
                    }
                }
                return params.iterator();
            };
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}