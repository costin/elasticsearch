/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.ann;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a kernel {@code process} method as eligible for bytecode fusion. The annotation
 * processor emits {@code FusionAware} metadata on the generated {@code *Evaluator.Factory}
 * so the stitcher can locate the kernel's compiled bytecode template at plan time.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Fusable {
    /**
     * Whether this kernel can throw {@code ArithmeticException} on overflow (precludes SIMD vectorization).
     */
    boolean overflowChecked() default false;
}
