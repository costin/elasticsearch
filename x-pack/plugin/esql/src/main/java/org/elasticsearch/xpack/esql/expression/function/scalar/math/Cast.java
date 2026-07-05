/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.math;

import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.ann.Fusable;
import org.elasticsearch.compute.expression.ConstantEvaluators;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import static org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter.intToUnsignedLong;
import static org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter.longToUnsignedLong;
import static org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter.unsignedLongToDouble;

public class Cast {
    /**
     * Build the evaluator supplier to cast {@code in} from {@code current} to {@code required}.
     */
    public static ExpressionEvaluator.Factory cast(Source source, DataType current, DataType required, ExpressionEvaluator.Factory in) {
        if (current == required) {
            return in;
        }
        if (current == DataType.NULL || required == DataType.NULL) {
            return ConstantEvaluators.CONSTANT_NULL_FACTORY;
        }
        if (required == DataType.DOUBLE) {
            if (current == DataType.LONG) {
                return new CastLongToDoubleEvaluator.Factory(source, in);
            }
            if (current == DataType.INTEGER) {
                return new CastIntToDoubleEvaluator.Factory(source, in);
            }
            if (current == DataType.UNSIGNED_LONG) {
                return new CastUnsignedLongToDoubleEvaluator.Factory(source, in);
            }
            throw cantCast(current, required);
        }
        if (required == DataType.UNSIGNED_LONG) {
            if (current == DataType.LONG) {
                return new CastLongToUnsignedLongEvaluator.Factory(source, in);
            }
            if (current == DataType.INTEGER) {
                return new CastIntToUnsignedLongEvaluator.Factory(source, in);
            }
        }
        if (required == DataType.LONG) {
            if (current == DataType.INTEGER) {
                return new CastIntToLongEvaluator.Factory(source, in);
            }
            throw cantCast(current, required);
        }
        throw cantCast(current, required);
    }

    private static EsqlIllegalArgumentException cantCast(DataType current, DataType required) {
        return new EsqlIllegalArgumentException("can’t process [" + current.typeName() + " -> " + required.typeName() + "]");
    }

    // Cast vs TO_DOUBLE / TO_LONG / ... (clarification): this Cast is the planner-inserted IMPLICIT numeric coercion
    // used by commonType (e.g. `double + int` becomes Add(double, castIntToDouble(int))). Its kernels are pure,
    // single-valued primitive widenings with NO multi-value handling and NO warnings — they are only inserted where the
    // type system guarantees a single-valued numeric operand. The user-facing TO_DOUBLE/TO_LONG/... convert FUNCTIONS
    // (AbstractConvertFunction, @ConvertEvaluator) are a DIFFERENT family: multi-value-aware and warn-capable (e.g.
    // TO_DOUBLE(keyword) can fail-to-null with a Warning). They are numerically identical for int/long->double but are
    // NOT interchangeable — a convert function deliberately does NOT reuse these Cast evaluators (see the "would be a
    // candidate, but not MV'd" notes in ToDouble.java). Fusion must therefore never swap one for the other; the
    // mixed-type differential sweeps (FusionSpecDifferentialTests) prove the fused Cast path matches the unfused Cast
    // chain in values, nulls and warnings.
    //
    // @Fusable: the numeric widening casts ESQL inserts via commonType (e.g. double + int => Add(double,
    // castIntToDouble(int))) are pure primitive conversions with no overflow, so they fuse as intermediate kernels
    // whose return element differs from their argument element — the seam that lets a mixed-type tree fuse end to end.
    @Fusable
    @Evaluator(extraName = "IntToLong")
    static long castIntToLong(int v) {
        return v;
    }

    @Fusable
    @Evaluator(extraName = "IntToDouble")
    static double castIntToDouble(int v) {
        return v;
    }

    @Fusable
    @Evaluator(extraName = "LongToDouble")
    static double castLongToDouble(long v) {
        return v;
    }

    @Evaluator(extraName = "UnsignedLongToDouble")
    static double castUnsignedLongToDouble(long v) {
        return unsignedLongToDouble(v);
    }

    @Evaluator(extraName = "IntToUnsignedLong")
    static long castIntToUnsignedLong(int v) {
        return intToUnsignedLong(v);
    }

    @Evaluator(extraName = "LongToUnsignedLong")
    // TODO: catch-to-null in evaluator?
    static long castLongToUnsignedLong(long v) {
        return longToUnsignedLong(v, false);
    }
}
