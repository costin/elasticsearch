/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.tree.MethodNode;

import java.util.function.Consumer;

/**
 * The same emitter graph {@link Stitcher}'s constructor builds, so tests can dump fused class bytes without
 * going through Stitcher's deleted {@code *Bytecode} forwarders.
 *
 * <p>Stitcher wires {@code this::intercept}, and {@code intercept} is a no-op unless a test sets
 * {@code hostMethodInterceptor}. Dump/budget tests never set that field, so a no-op {@code mn -> {}} is
 * the same interceptor the Stitcher actually runs — the MethodNode is not mutated, so class bytes stay
 * identical (proven by the E1 dump vs {@code D1.txt}).
 *
 * <p>{@link FilterEvalEmitter} shares the {@link BlockLoopEmitter} instance, matching Stitcher.
 */
final class FusionTestEmitters {

    final TemplateRegistry templates;
    final VectorLoopEmitter vectors;
    final BlockLoopEmitter blocks;
    final FilterEvalEmitter filterEval;

    FusionTestEmitters() {
        this(new TemplateRegistry());
    }

    FusionTestEmitters(TemplateRegistry templates) {
        this.templates = templates;
        Consumer<MethodNode> intercept = mn -> {};
        this.vectors = new VectorLoopEmitter(templates, intercept);
        this.blocks = new BlockLoopEmitter(templates, intercept);
        this.filterEval = new FilterEvalEmitter(templates, intercept, this.blocks);
    }
}
