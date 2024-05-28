/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.RangeFieldQuery;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;

public class LuceneScoringTests {

    public static void main(String[] args) throws IOException {
        // Create an in-memory index for demonstration
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter writer = new IndexWriter(directory, config);

        writeDoc(writer, "productA", "a cheap product", 100);
        writeDoc(writer,"productB", "a common product", 250);
        writeDoc(writer, "productC", "a high-price product", 500);

        writer.close();

        // Create an IndexSearcher to perform search operations
        IndexReader reader = DirectoryReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);

        // Construct the query using a RangeQuery
        Query query = IntField.newRangeQuery("price", 100, 300);

        // Search the index and retrieve the top hits
        TopDocs topDocs = searcher.search(query, 10, Sort.INDEXORDER, true);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            System.out.println("Score: " + scoreDoc.score + ", Title: " + doc.get("title") + ", Price: " + doc.get("price"));
        }

        reader.close();
    }

    private static void writeDoc(IndexWriter writer, String title, String description, int price) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("title", title, Field.Store.YES));
        doc.add(new TextField("description", description, Field.Store.YES));
        doc.add(new IntField("price", price, Field.Store.YES));
        writer.addDocument(doc);
    }
}
