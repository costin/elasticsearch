/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.FusionSignature;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;

import java.util.List;

/**
 * The typed carrier for a fused WHERE-predicate + EVAL-projection pair — the filter-eval counterpart of
 * {@link FusionPlan}. Produced by {@link FusionPlanner#maybeFuseFilterEval} and consumed by
 * {@link FusedFilterEvalEvaluatorFactory#create}, it collapses what was a fourteen-argument positional list into one
 * record so the predicate/projection sides cannot be mis-ordered or mixed up.
 *
 * <p>It holds the two derived {@link FusionSignature}s — the single source of each side's constant emit order — plus
 * the esql-side bindings the stitcher can't derive from the trees alone: the per-side input channels, the per-input
 * {@link ElementKind}s, the projection output element, the combined per-warning-source {@link Source}s (predicate
 * sources first, then projection), and the cache {@code shape}. {@link #predicateConstants()}/
 * {@link #projectionConstants()} read straight from the signatures so they never diverge from what the emitter bakes in.
 */
record FilterEvalPlan(
    FusionSignature predicateSignature,
    FusionSignature projectionSignature,
    FusionNode predicateTree,
    FusionNode projectionTree,
    int[] predicateChannels,
    int[] projectionChannels,
    ElementKind[] predicateInputElements,
    ElementKind[] projectionInputElements,
    ElementKind projectionOutputElement,
    Source[] warningSources,
    String shape
) {
    /** The predicate's embedded constants in the Stitcher's canonical emit order (from its signature). */
    List<FusionNode.Constant> predicateConstants() {
        return predicateSignature.constantsInEmitOrder();
    }

    /** The projection's embedded constants in the Stitcher's canonical emit order (from its signature). */
    List<FusionNode.Constant> projectionConstants() {
        return projectionSignature.constantsInEmitOrder();
    }
}
