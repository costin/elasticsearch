/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

/**
 * Implemented by {@code *Evaluator.Factory} classes whose kernel {@code process} method was
 * annotated with {@link org.elasticsearch.compute.ann.Fusable}. The stitcher uses this to look
 * up the kernel's bytecode template at plan time.
 */
public interface FusionAware {
    FusionDescriptor fusionDescriptor();
}
