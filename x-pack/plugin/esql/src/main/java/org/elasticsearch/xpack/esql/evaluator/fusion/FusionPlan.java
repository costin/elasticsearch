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
 * The typed, behaviour-neutral carrier of everything needed to stitch and bind ONE fused expression tree — produced by
 * {@link FusionPlanner} and consumed by {@link FusedExpressionEvaluatorFactory}. It collapses what used to be an
 * eight-argument positional list threaded through {@code tryCreate}/{@code tryCreateFusedOrNull} (and duplicated into
 * the adaptive lambda) into one record, so a caller cannot silently mis-order or drop an argument.
 *
 * <p>It holds the derived {@link FusionSignature} — the single source of the ordered positional contract (constants in
 * emit order, warning-source slots) — alongside the esql-side bindings the {@link org.elasticsearch.compute.operator.fusion.Stitcher}
 * cannot derive from the tree alone: the input {@code channels}, the per-input {@link ElementKind}s, the output element,
 * whether any kernel is overflow-checked, the per-warning-source {@link Source}s, and the cache {@code shape} key.
 * {@link #constants()} reads the constant order straight from the signature so it never diverges from what the emitter bakes in.
 */
record FusionPlan(
    FusionSignature signature,
    FusionNode tree,
    int[] channels,
    ElementKind[] inputElements,
    ElementKind outputElement,
    boolean overflowChecked,
    Source[] warningSources,
    String shape,
    /**
     * When non-empty, the {@code jdk.incubator.vector.VectorOperators.Comparison} constant name for a SIMD-eligible
     * boolean comparison of two same-primitive columns (B2) — set by the planner from the comparison {@code
     * Expression}. Empty means "no SIMD variant" (the scalar plain-vector path is used).
     */
    String vectorComparison
) {
    /** The embedded constants in the Stitcher's canonical emit order — read from the signature (single source of truth). */
    List<FusionNode.Constant> constants() {
        return signature.constantsInEmitOrder();
    }
}
