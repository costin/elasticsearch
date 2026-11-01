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
 * @param variadic              whether the kernel's LAST parameter is an array that gathers a variable number of
 *                              operands (e.g. {@code CONCAT}'s {@code BytesRef[] values}). When {@code true} the tree
 *                              supplies the fixed leading operands followed by the array's elements as extra children,
 *                              and the stitcher builds the array per row from those trailing children.
 * @param mapping               whether this is a multi-value mapping convert kernel ({@code @ConvertEvaluator}).
 *                              A lone converter is refused at plan time (D4). Nested under a single-value parent
 *                              it splices as a unary kernel on the ordinary block path. Generated {@code @Evaluator}
 *                              descriptors and hand-written string kernels default this to {@code false}; only the
 *                              convert generator emits {@code true}.
 * @param warns                 whether the kernel declares a {@code warnExceptions} type, independent of
 *                              {@code overflowChecked}. The encoding-aware ordinal path refuses any tree containing
 *                              a warning-capable kernel: evaluating a dictionary can warn for entries no row
 *                              references, in a different order.
 * @param dictionarySafe        whether evaluating this kernel over an unreferenced dictionary entry cannot throw
 *                              an exception that is not a declared warn-exception. {@code false} for kernels that
 *                              throw on input content (hand-built {@code CONCAT} past 1MB, {@code SPACE} on a
 *                              negative/too-long count). Generated {@code @Evaluator}/{@code @ConvertEvaluator}
 *                              kernels are {@code true}: any throw they make is a declared {@code warnExceptions}
 *                              entry (caught as a warning, encoded in {@code warns}) or they do not throw.
 */
public record FusionDescriptor(
    Class<?> kernelClass,
    String kernelMethod,
    String kernelType,
    boolean overflowChecked,
    boolean allNullsIsNull,
    String overflowExceptionType,
    boolean variadic,
    boolean mapping,
    boolean warns,
    boolean dictionarySafe
) {
    /**
     * Convenience constructor for callers and tests that predate the explicit {@link #overflowExceptionType}
     * component. Every fusable overflow-checked kernel in scope throws {@link ArithmeticException} (long/int
     * {@code Math.*Exact}, {@code /}-by-zero, and the {@code double} {@code NumericUtils.asFiniteNumber}
     * NaN/Inf guard), which is exactly what the annotation processor derives from the kernel's
     * {@code @Evaluator.warnExceptions}. This overload reproduces that mapping so a descriptor built by hand
     * stays consistent with the generated one: {@code java.lang.ArithmeticException} when overflow-checked,
     * empty otherwise. Not variadic, not mapping. {@code warns} follows overflow-checked (the derived
     * ArithmeticException is a warn-exception); {@code dictionarySafe} defaults to {@code true} (the throw
     * is caught as a warning).
     */
    public FusionDescriptor(Class<?> kernelClass, String kernelMethod, String kernelType, boolean overflowChecked, boolean allNullsIsNull) {
        this(
            kernelClass,
            kernelMethod,
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowChecked ? "java.lang.ArithmeticException" : "",
            false,
            false,
            overflowChecked,
            true
        );
    }

    /**
     * Convenience constructor (the shape the annotation processor used to emit for {@code @Evaluator} kernels):
     * explicit overflow type, not variadic, not mapping. {@code warns} is derived from a non-empty overflow
     * exception type; {@code dictionarySafe} defaults to {@code true}.
     */
    public FusionDescriptor(
        Class<?> kernelClass,
        String kernelMethod,
        String kernelType,
        boolean overflowChecked,
        boolean allNullsIsNull,
        String overflowExceptionType
    ) {
        this(
            kernelClass,
            kernelMethod,
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowExceptionType,
            false,
            false,
            hasExceptionType(overflowExceptionType),
            true
        );
    }

    /** Convenience constructor: explicit overflow type and variadic flag, not mapping. */
    public FusionDescriptor(
        Class<?> kernelClass,
        String kernelMethod,
        String kernelType,
        boolean overflowChecked,
        boolean allNullsIsNull,
        String overflowExceptionType,
        boolean variadic
    ) {
        this(
            kernelClass,
            kernelMethod,
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowExceptionType,
            variadic,
            false,
            hasExceptionType(overflowExceptionType),
            true
        );
    }

    /**
     * Convenience constructor: explicit overflow type, variadic, and mapping. {@code warns} is derived from a
     * non-empty overflow exception type; {@code dictionarySafe} defaults to {@code true}.
     */
    public FusionDescriptor(
        Class<?> kernelClass,
        String kernelMethod,
        String kernelType,
        boolean overflowChecked,
        boolean allNullsIsNull,
        String overflowExceptionType,
        boolean variadic,
        boolean mapping
    ) {
        this(
            kernelClass,
            kernelMethod,
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowExceptionType,
            variadic,
            mapping,
            hasExceptionType(overflowExceptionType),
            true
        );
    }

    /** Whether this kernel declares an overflow exception the stitcher must catch around its spliced body. */
    public boolean hasOverflowException() {
        return hasExceptionType(overflowExceptionType);
    }

    private static boolean hasExceptionType(String overflowExceptionType) {
        return overflowExceptionType != null && overflowExceptionType.isEmpty() == false;
    }
}
