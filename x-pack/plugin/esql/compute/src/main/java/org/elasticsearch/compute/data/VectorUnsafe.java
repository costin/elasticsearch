/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

/**
 * INTERNAL — fusion codegen only. Public static seam that exposes the <b>live backing array</b> of a dense
 * {@code *ArrayVector} to the ES|QL expression-fusion stitcher.
 *
 * <p><b>Do not use.</b> The returned array is the vector's own storage, not a copy: it bypasses every null /
 * multi-value guard and length equals {@link Vector#getPositionCount()}. It is only safe for a caller that has
 * already verified the vector is a dense, no-null, single-valued {@code *ArrayVector} (as the fused vector fast
 * path does before dispatching). Mutating it corrupts the vector.
 *
 * <p><b>Why this exists.</b> {@code *ArrayVector.rawValues()} is package-private in {@code compute.data}. The
 * fused vector-loop hidden class must therefore either be defined into {@code compute.data} (via a
 * {@code privateLookupIn} teleport, which then cannot link non-{@code java.base} kernel callees such as
 * {@code NumericUtils}) or reach the backing array through an <b>exported public</b> seam. This forwarder is that
 * seam: it keeps {@code rawValues()} package-private while letting the stitcher define every fused class in the
 * <b>caller's</b> (esql-proper) module with a plain {@code INVOKESTATIC}, unifying the block and vector lookup
 * paths and letting the vector path link esql-proper/esql-core kernels. Flagged for ES API review — it is not a
 * user-facing API.
 */
public final class VectorUnsafe {

    private VectorUnsafe() {}

    /** The live {@code long[]} backing {@code v}. See the class contract: fusion codegen only, do not use. */
    public static long[] longs(LongArrayVector v) {
        return v.rawValues();
    }

    /** The live {@code int[]} backing {@code v}. See the class contract: fusion codegen only, do not use. */
    public static int[] ints(IntArrayVector v) {
        return v.rawValues();
    }

    /** The live {@code double[]} backing {@code v}. See the class contract: fusion codegen only, do not use. */
    public static double[] doubles(DoubleArrayVector v) {
        return v.rawValues();
    }

    /** The live {@code boolean[]} backing {@code v}. See the class contract: fusion codegen only, do not use. */
    public static boolean[] booleans(BooleanArrayVector v) {
        return v.rawValues();
    }
}
