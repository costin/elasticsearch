/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.LoadFromPageEvaluator;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.compute.lucene.EmptyIndexedByShardId;
import org.elasticsearch.compute.lucene.IndexedByShardId;
import org.elasticsearch.compute.operator.FilterOperator;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.xpack.esql.core.QlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionCoverage;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionPlanner;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionSettings;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionTelemetry;
import org.elasticsearch.xpack.esql.evaluator.mapper.EvaluatorMapper;
import org.elasticsearch.xpack.esql.planner.EsPhysicalOperationProviders.ShardContext;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.elasticsearch.xpack.esql.planner.PlannerUtils;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

public final class EvalMapper {
    private EvalMapper() {}

    public static ExpressionEvaluator.Factory toEvaluator(FoldContext foldCtx, Expression exp, Layout layout) {
        return toEvaluator(foldCtx, exp, layout, EmptyIndexedByShardId.instance());
    }

    /**
     * Provides an ExpressionEvaluator factory to evaluate an expression that does not require shard contexts
     * but may reference functions (e.g. {@code TOP_SNIPPETS}) that need the node-level {@link AnalysisRegistry}.
     */
    public static ExpressionEvaluator.Factory toEvaluator(
        FoldContext foldCtx,
        Expression exp,
        Layout layout,
        @Nullable AnalysisRegistry analysisRegistry
    ) {
        return toEvaluator(foldCtx, exp, layout, EmptyIndexedByShardId.instance(), analysisRegistry);
    }

    /**
     * Provides an ExpressionEvaluator factory to evaluate an expression.
     *
     * @param foldCtx the fold context for folding expressions
     * @param exp the expression to generate an evaluator for
     * @param layout the mapping from attributes to channels
     * @param shardContexts the shard contexts, needed to generate queries for expressions that couldn't be pushed down to Lucene
     */
    public static ExpressionEvaluator.Factory toEvaluator(
        FoldContext foldCtx,
        Expression exp,
        Layout layout,
        IndexedByShardId<? extends ShardContext> shardContexts
    ) {
        return toEvaluator(foldCtx, exp, layout, shardContexts, null);
    }

    /**
     * Provides an ExpressionEvaluator factory to evaluate an expression.
     *
     * @param foldCtx the fold context for folding expressions
     * @param exp the expression to generate an evaluator for
     * @param layout the mapping from attributes to channels
     * @param shardContexts the shard contexts, needed to generate queries for expressions that couldn't be pushed down to Lucene
     * @param analysisRegistry the node-level analysis registry for resolving analyzers by name, may be {@code null}
     */
    public static ExpressionEvaluator.Factory toEvaluator(
        FoldContext foldCtx,
        Expression exp,
        Layout layout,
        IndexedByShardId<? extends ShardContext> shardContexts,
        @Nullable AnalysisRegistry analysisRegistry
    ) {
        return new Builder(foldCtx, layout, shardContexts, analysisRegistry).toEvaluator(exp, false);
    }

    /** {@link #toFilterEvaluator(FoldContext, Expression, Layout, IndexedByShardId, AnalysisRegistry)} without shard contexts. */
    public static ExpressionEvaluator.Factory toFilterEvaluator(FoldContext foldCtx, Expression condition, Layout layout) {
        return toFilterEvaluator(foldCtx, condition, layout, EmptyIndexedByShardId.instance(), null);
    }

    /**
     * Provides the factory for a WHERE {@code condition} that a {@link FilterOperator} consumes. Identical to
     * {@link #toEvaluator(FoldContext, Expression, Layout, IndexedByShardId, AnalysisRegistry)} except when the condition
     * fuses: its evaluators then also write the selected row positions directly ({@link PositionsEvaluator}), sparing
     * the filter an intermediate {@code BooleanBlock} and a second pass over it. The unfused factory is unchanged.
     */
    public static ExpressionEvaluator.Factory toFilterEvaluator(
        FoldContext foldCtx,
        Expression condition,
        Layout layout,
        IndexedByShardId<? extends ShardContext> shardContexts,
        @Nullable AnalysisRegistry analysisRegistry
    ) {
        return new Builder(foldCtx, layout, shardContexts, analysisRegistry).toEvaluator(condition, true);
    }

    /**
     * A single {@link #toEvaluator} invocation. Holds the per-call state — most importantly the {@code unfusedMemo},
     * which guarantees each expression node's generated factory is built <b>exactly once</b> even though fusion
     * inspects the same nodes it may fall back to.
     *
     * <h2>Why the memo matters (additive fold-budget invariant)</h2>
     * Building a generated factory is <b>not</b> side-effect-free: operators such as {@code EsqlArithmeticOperation}
     * constant-fold a foldable operand against the shared {@link FoldContext} budget while constructing their
     * evaluator. If fusion probed a node by building its factory and then a separate fall-through rebuilt it, every
     * foldable subexpression would fold twice — doubling plan-time fold-budget consumption and risking a spurious
     * {@code FoldTooMuchMemoryException} on queries that previously succeeded. Memoizing by expression identity keeps
     * total folds and factory constructions identical to the pre-fusion baseline whenever nothing fuses, and no worse
     * when something does.
     *
     * <h2>Where fusion is attempted</h2>
     * Fusion is attempted at {@code exp} (the expression a caller hands to {@link #toEvaluator} or
     * {@link #toFilterEvaluator}, e.g. an EVAL/WHERE value). If the maximal subtree fuses, the fused factory is
     * returned; otherwise the memoized unfused factory is returned unchanged. The unfused factory the
     * {@link FusionPlanner} reads for descriptor probing and passes as the
     * fusion fallback is the very same object returned when nothing fuses, so the invariant holds by construction.
     */
    private static final class Builder {
        private final FoldContext foldCtx;
        private final Layout layout;
        private final IndexedByShardId<? extends ShardContext> shardContexts;
        @Nullable
        private final AnalysisRegistry analysisRegistry;

        /** Identity-keyed cache of the <b>unfused</b> generated factory per expression node; each is built at most once. */
        private final Map<Expression, ExpressionEvaluator.Factory> unfusedMemo = new IdentityHashMap<>();

        Builder(
            FoldContext foldCtx,
            Layout layout,
            IndexedByShardId<? extends ShardContext> shardContexts,
            @Nullable AnalysisRegistry analysisRegistry
        ) {
            this.foldCtx = foldCtx;
            this.layout = layout;
            this.shardContexts = shardContexts;
            this.analysisRegistry = analysisRegistry;
        }

        /**
         * Builds the factory for {@code exp}, fusing the maximal fusable subtree rooted here when possible. The unfused
         * factory is built first (once, memoized) so the {@link FusionPlanner}'s descriptor probes and fusion fallback
         * are all served from the memo — never rebuilt — keeping fold-budget consumption additive. A {@code filter}
         * condition fuses through {@link FusionPlanner#maybeFuseFilter} so its fused evaluators select positions.
         */
        ExpressionEvaluator.Factory toEvaluator(Expression exp, boolean filter) {
            boolean probe = FusionSettings.isCoverageReportEnabled();
            ExpressionEvaluator.Factory unfused = unfused(exp);
            ExpressionEvaluator.Factory fused = filter
                ? FusionPlanner.maybeFuseFilter(exp, layout, this::unfused)
                : FusionPlanner.maybeFuse(exp, layout, this::unfused);
            if (probe) {
                recordCoverage(exp, fused);
            }
            return fused != null ? fused : unfused;
        }

        /** Returns the memoized unfused factory for {@code exp}, building it (and its subtree) once on first request. */
        private ExpressionEvaluator.Factory unfused(Expression exp) {
            ExpressionEvaluator.Factory cached = unfusedMemo.get(exp);
            if (cached != null) {
                return cached;
            }
            ExpressionEvaluator.Factory built = buildUnfused(exp);
            // Children are populated by the recursive unfused() calls above before we reach here, so a simple
            // put (rather than computeIfAbsent, which forbids re-entrant modification) is correct and single-threaded.
            unfusedMemo.put(exp, built);
            return built;
        }

        private ExpressionEvaluator.Factory buildUnfused(Expression exp) {
            if (exp instanceof EvaluatorMapper m) {
                // Same ToEvaluator context as the public toEvaluatorContext, differing only in how a child is turned
                // into a factory: here through this builder's memoized unfused walk, not the fusing toEvaluator.
                return m.toEvaluator(toEvaluatorContext(foldCtx, shardContexts, analysisRegistry, this::unfused));
            }
            if (exp instanceof Attribute attr) {
                return new LoadFromPageEvaluator.Factory(layout.get(attr.id()).channel());
            }
            throw new QlIllegalArgumentException("Unsupported expression [{}]", exp);
        }

        /** Coverage is diagnostic-only: never fail a query because the classifier hit an unexpected node. */
        private void recordCoverage(Expression exp, ExpressionEvaluator.Factory fused) {
            try {
                FusionCoverage.Result classified = FusionCoverage.classify(exp, layout, this::unfused, fused);
                FusionTelemetry.recordTopLevel(classified.category(), classified.refusalReason());
            } catch (Exception ignored) {
                // probe must stay additive
            }
        }

    }

    /**
     * The {@link EvaluatorMapper.ToEvaluator} context that {@link #toEvaluator} hands to expressions, exposed
     * for callers that need the context itself rather than an evaluator for a specific expression (e.g. building
     * scorers through {@code ScoreMapper}).
     */
    public static EvaluatorMapper.ToEvaluator toEvaluatorContext(
        FoldContext foldCtx,
        Layout layout,
        IndexedByShardId<? extends ShardContext> shardContexts,
        @Nullable AnalysisRegistry analysisRegistry
    ) {
        return toEvaluatorContext(
            foldCtx,
            shardContexts,
            analysisRegistry,
            expression -> toEvaluator(foldCtx, expression, layout, shardContexts, analysisRegistry)
        );
    }

    /**
     * The single {@link EvaluatorMapper.ToEvaluator} implementation shared by the fusing public entry point and the
     * {@link Builder}'s unfused subtree walk. The two paths differ only in how a child expression becomes a factory —
     * the fusing path recurses through {@link #toEvaluator}, the unfused path through the Builder's memoized
     * {@code unfused} — so that sole difference is {@code childFactory}; {@code foldCtx}, {@code shardContexts} and
     * {@code getAnalyzer} are identical either way.
     */
    static EvaluatorMapper.ToEvaluator toEvaluatorContext(
        FoldContext foldCtx,
        IndexedByShardId<? extends ShardContext> shardContexts,
        @Nullable AnalysisRegistry analysisRegistry,
        Function<Expression, ExpressionEvaluator.Factory> childFactory
    ) {
        return new EvaluatorMapper.ToEvaluator() {
            @Override
            public ExpressionEvaluator.Factory apply(Expression expression) {
                return childFactory.apply(expression);
            }

            @Override
            public FoldContext foldCtx() {
                return foldCtx;
            }

            @Override
            public IndexedByShardId<? extends ShardContext> shardContexts() {
                return shardContexts;
            }

            @Override
            public Analyzer getAnalyzer(String name) {
                return PlannerUtils.resolveAnalyzer(name, analysisRegistry);
            }
        };
    }
}
