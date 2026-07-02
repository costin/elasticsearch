/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

/**
 * Runtime introspection handle for a fusable kernel. The stitcher uses this to locate the
 * kernel class, method name, and JVM method descriptor when loading the kernel's bytecode
 * template from {@code .class} resources at plan time.
 *
 * @param kernelClass          the class declaring the kernel {@code process} method
 * @param kernelMethod         the kernel method's simple name
 * @param kernelType           the kernel method's JVM descriptor (e.g. {@code (JJ)J})
 * @param overflowChecked      whether the kernel can throw on overflow (precludes SIMD vectorization)
 * @param allNullsIsNull       whether an all-null input yields a null result
 * @param overflowExceptionType the fully-qualified name of the exception the kernel throws on overflow
 *                              (e.g. {@code java.lang.ArithmeticException}), or the empty string when the
 *                              kernel is not overflow-checked. The stitcher uses this as the exact type to
 *                              catch around a spliced kernel body so an overflowing position becomes a null
 *                              with a registered warning, mirroring the unfused evaluator (binding rule #3).
 */
public record FusionDescriptor(
    Class<?> kernelClass,
    String kernelMethod,
    String kernelType,
    boolean overflowChecked,
    boolean allNullsIsNull,
    String overflowExceptionType
) {
    /**
     * Convenience constructor for callers and tests that predate the explicit {@link #overflowExceptionType}
     * component. Every fusable overflow-checked kernel in scope throws {@link ArithmeticException} (long/int
     * {@code Math.*Exact}, {@code /}-by-zero, and the {@code double} {@code NumericUtils.asFiniteNumber}
     * NaN/Inf guard), which is exactly what the annotation processor derives from the kernel's
     * {@code @Evaluator.warnExceptions}. This overload reproduces that mapping so a descriptor built by hand
     * stays consistent with the generated one: {@code java.lang.ArithmeticException} when overflow-checked,
     * empty otherwise.
     */
    public FusionDescriptor(Class<?> kernelClass, String kernelMethod, String kernelType, boolean overflowChecked, boolean allNullsIsNull) {
        this(
            kernelClass,
            kernelMethod,
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowChecked ? "java.lang.ArithmeticException" : ""
        );
    }

    /** Whether this kernel declares an overflow exception the stitcher must catch around its spliced body. */
    public boolean hasOverflowException() {
        return overflowExceptionType != null && overflowExceptionType.isEmpty() == false;
    }
}
