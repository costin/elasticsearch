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
 */
public record FusionDescriptor(
    Class<?> kernelClass,
    String kernelMethod,
    String kernelType,
    boolean overflowChecked,
    boolean allNullsIsNull
) {}
