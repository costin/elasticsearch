/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasource.parquet;

import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.datasources.spi.AggregatePushdownSupport;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;

import java.util.List;

/**
 * Parquet-specific implementation of AggregatePushdownSupport.
 * <p>
 * Supports pushdown of COUNT(*), MIN, and MAX aggregates.
 * The actual aggregate values are extracted from Parquet row-group statistics
 * during format reader execution (when we have access to the file), not during
 * optimization. This class just indicates that these aggregate types are pushable.
 * <p>
 * The pushedHint returned by pushAggregates() is a marker object that tells
 * the format reader to compute aggregates from statistics instead of reading data.
 */
public class ParquetAggregatePushdownSupport implements AggregatePushdownSupport {

    @Override
    public Pushability canPushAggregates(List<Expression> aggregates, List<Expression> filters) {
        // Check if all aggregates are pushable types (COUNT, MIN, MAX)
        for (Expression agg : aggregates) {
            if (!(agg instanceof Count) && !(agg instanceof Min) && !(agg instanceof Max)) {
                return Pushability.NO;
            }
        }

        // All aggregates are pushable types
        return Pushability.YES;
    }

    @Override
    public AggregatePushdownResult pushAggregates(List<Expression> aggregates, List<Expression> filters) {
        // Create a marker hint that tells the format reader to compute aggregates
        ParquetAggregatePushdownHint hint = new ParquetAggregatePushdownHint();

        // All aggregates are marked for pushdown; actual extraction happens during read()
        return new AggregatePushdownResult(hint, List.of());
    }

    /**
     * Opaque marker hint for Parquet aggregate pushdown.
     * Signals to ParquetFormatReader.read() that it should compute aggregates from
     * statistics and return synthetic pages instead of reading actual data.
     */
    public static class ParquetAggregatePushdownHint {
        // Marker-only hint; actual aggregate extraction happens during read()
    }
}
