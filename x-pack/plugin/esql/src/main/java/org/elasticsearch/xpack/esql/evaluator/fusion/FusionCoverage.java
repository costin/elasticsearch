/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.evaluator.mapper.EvaluatorMapper;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.LTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.RTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Space;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mod;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.function.Function;

/**
 * H9 coverage classifier: one category per <em>top-level</em> expression, using the F0 buckets so a CsvIT run is
 * comparable with F0's FUSED 18.7% / ISLAND 2.9% / FRAGMENT ~1.3% / NONE 68%.
 *
 * <p>Addendum X3's original F0 prose is not in this tree ({@code FINAL_ITER4.md} was never committed; the plan only
 * records "Partial codegen / islands / cross-EVAL inlining: still out of scope (F0)"). The definitions used here,
 * matching that F0 decision and the named buckets:
 * <ul>
 *   <li>{@link FusionTelemetry.Category#FUSED} — the top-level expression produced a fused factory (eager
 *       {@link FusedExpressionEvaluatorFactory}, or a FilterEval factory with {@code fused()==true}).</li>
 *   <li>{@link FusionTelemetry.Category#ISLAND} — not fused, but a proper descendant <em>would</em> fuse under the
 *       same {@link FusionPlanner} gates (a fusable island under an unfusable parent; partial codegen is out of
 *       scope).</li>
 *   <li>{@link FusionTelemetry.Category#FRAGMENT} — not fused, no island, but the expression has a depth≥2 skeleton
 *       of fusable operators that still refused (policy / ineligible child / stitch failure).</li>
 *   <li>{@link FusionTelemetry.Category#NONE} — nothing fusable at depth≥2. Typical first-refusal {@code depth<2}
 *       (lone kernel) or the class of an unfusable root.</li>
 * </ul>
 *
 * <p>The first refusal reason is the simple class name of the first unfusable node in pre-order, or {@code depth<2}
 * when every operator is fusable but the tree is a lone kernel.
 */
public final class FusionCoverage {

    private FusionCoverage() {}

    public record Result(FusionTelemetry.Category category, String refusalReason) {}

    public static Result classify(
        Expression exp,
        Layout layout,
        Function<Expression, ExpressionEvaluator.Factory> rawFactory,
        ExpressionEvaluator.Factory fuseResult
    ) {
        if (fuseResult instanceof FusedExpressionEvaluatorFactory) {
            return new Result(FusionTelemetry.Category.FUSED, null);
        }
        return classifyUnfused(exp, layout, rawFactory);
    }

    public static Result classifyFilterEval(
        Expression predicate,
        Expression projection,
        Layout layout,
        Function<Expression, ExpressionEvaluator.Factory> rawFactory,
        FilterEvalEvaluator.Factory fuseResult
    ) {
        if (fuseResult instanceof FusedFilterEvalEvaluatorFactory fused && fused.fused()) {
            return new Result(FusionTelemetry.Category.FUSED, null);
        }
        if (fuseResult != null) {
            // Stitch failed after both sides planned: a fusable pair that did not produce a fused factory.
            return new Result(FusionTelemetry.Category.FRAGMENT, firstRefusalReason(predicate, rawFactory));
        }
        Result pred = classifyUnfused(predicate, layout, rawFactory);
        Result proj = classifyUnfused(projection, layout, rawFactory);
        FusionTelemetry.Category category;
        if (pred.category() == FusionTelemetry.Category.ISLAND
            || proj.category() == FusionTelemetry.Category.ISLAND
            || FusionPlanner.isFusableAtPlan(predicate, layout, rawFactory)
            || FusionPlanner.isFusableAtPlan(projection, layout, rawFactory)) {
            category = FusionTelemetry.Category.ISLAND;
        } else if (pred.category() == FusionTelemetry.Category.FRAGMENT || proj.category() == FusionTelemetry.Category.FRAGMENT) {
            category = FusionTelemetry.Category.FRAGMENT;
        } else {
            category = FusionTelemetry.Category.NONE;
        }
        String reason = pred.refusalReason() != null ? pred.refusalReason() : proj.refusalReason();
        return new Result(category, reason);
    }

    private static Result classifyUnfused(Expression exp, Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        String reason = firstRefusalReason(exp, rawFactory);
        if (hasFusableDescendant(exp, layout, rawFactory)) {
            return new Result(FusionTelemetry.Category.ISLAND, reason);
        }
        if (kernelSkeletonDepth(exp, rawFactory) >= 2) {
            return new Result(FusionTelemetry.Category.FRAGMENT, reason);
        }
        return new Result(FusionTelemetry.Category.NONE, reason);
    }

    private static boolean hasFusableDescendant(
        Expression exp,
        Layout layout,
        Function<Expression, ExpressionEvaluator.Factory> rawFactory
    ) {
        for (Expression child : exp.children()) {
            // planOrNull/build calls rawFactory; skip non-evaluator children (named-option maps, etc.).
            if (child instanceof EvaluatorMapper && FusionPlanner.isFusableAtPlan(child, layout, rawFactory)) {
                return true;
            }
            if (hasFusableDescendant(child, layout, rawFactory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Depth of the fusable-operator skeleton: each fusable operator adds 1; unfusable nodes are transparent (so a
     * wrapper is classified as an island first, never as a fragment of its inner tree).
     */
    static int kernelSkeletonDepth(Expression exp, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        int childMax = 0;
        for (Expression child : exp.children()) {
            childMax = Math.max(childMax, kernelSkeletonDepth(child, rawFactory));
        }
        if (isFusableOperator(exp, rawFactory)) {
            return 1 + childMax;
        }
        return childMax;
    }

    static String firstRefusalReason(Expression exp, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        String unfusable = firstUnfusableClass(exp, rawFactory);
        if (unfusable != null) {
            return unfusable;
        }
        if (kernelSkeletonDepth(exp, rawFactory) < 2) {
            return "depth<2";
        }
        return exp.getClass().getSimpleName();
    }

    private static String firstUnfusableClass(Expression exp, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        if (exp instanceof Attribute || exp instanceof Literal) {
            return null;
        }
        if (isFusableOperator(exp, rawFactory) == false) {
            return exp.getClass().getSimpleName();
        }
        for (Expression child : exp.children()) {
            String nested = firstUnfusableClass(child, rawFactory);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Whether {@code exp} is a fusable <em>operator</em> (a kernel, logical, Not, or admitted string function) —
     * independent of depth and of whether the whole tree actually plans. Used for skeleton depth and first-refusal,
     * not as a substitute for {@link FusionPlanner#isFusableAtPlan}.
     */
    static boolean isFusableOperator(Expression exp, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        if (exp instanceof Attribute || exp instanceof Literal) {
            return false;
        }
        if (exp instanceof And || exp instanceof Or || exp instanceof Not) {
            return true;
        }
        if (exp instanceof Left
            || exp instanceof Right
            || exp instanceof Trim
            || exp instanceof LTrim
            || exp instanceof RTrim
            || exp instanceof Space
            || exp instanceof Concat) {
            return true;
        }
        if ((exp instanceof Div || exp instanceof Mod) && exp.children().size() == 2 && exp.children().get(1) instanceof Literal) {
            return true;
        }
        // Named-option maps, literals-as-maps, and other non-evaluator children are not fusable operators.
        // Calling rawFactory on them throws QlIllegalArgumentException (Unsupported expression) and would fail CsvIT.
        if (exp instanceof EvaluatorMapper == false) {
            return false;
        }
        ExpressionEvaluator.Factory factory = rawFactory.apply(exp);
        return factory instanceof FusionAware;
    }
}
