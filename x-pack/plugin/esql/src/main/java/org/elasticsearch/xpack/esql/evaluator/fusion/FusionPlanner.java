/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.UnicodeUtil;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.BreakingBytesRefBuilder;
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.FusionSignature;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cast;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Concat;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.LTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.RTrim;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Right;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Space;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Trim;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.BinaryLogic;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mod;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Plan-time entry point that decides whether an expression subtree can be evaluated by a single stitched per-position
 * body (iter 13's "wire fusion into evaluator construction"). It walks the <b>expression</b> tree — not the produced
 * factory tree, whose child factories are private — and, when the whole subtree is fusable, builds the
 * {@link FusionNode} the {@link Stitcher} consumes and returns a {@link FusedExpressionEvaluatorFactory}. Otherwise it
 * returns {@code null} and {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the usual unfused
 * factory unchanged (fusion is strictly additive).
 *
 * <h2>Eligibility (per-node typing, cast-bridged)</h2>
 * A subtree rooted at {@code exp} is fusable iff:
 * <ul>
 *   <li>every internal node maps to a {@link FusionAware} generated factory (i.e. its kernel {@code process} method is
 *       {@code @Fusable}) whose kernel signature is {@code (TT..)R} with <b>homogeneous primitive arguments</b> {@code T}
 *       (one of {@code int}/{@code long}/{@code double}) and a single-descriptor-char return {@code R} — a numeric
 *       {@code int}/{@code long}/{@code double} <em>or</em> a {@code boolean} (a comparison, only at the root). Nodes of
 *       <b>different</b> elements are allowed and bridged: this is <b>per-node</b> typed, not a single tree-wide element.
 *       When a node's output element differs from the element its parent kernel requires, the planner inserts a synthetic
 *       {@code @Fusable} widening-cast kernel ({@code int→long}, {@code int→double}, {@code long→double}) — the same
 *       explicit {@link Cast#cast} the unfused chain inserts for {@code commonType} — so e.g. {@code doubleCol + intCol}
 *       fuses as {@code Add(doubleCol, castIntToDouble(intCol))};</li>
 *   <li>every leaf is either an {@link Attribute} of a supported numeric element, resolvable to a page channel via the
 *       {@link Layout} (read at its OWN column element; a mismatch with the consuming kernel is cast-bridged), or a
 *       {@link Literal} passed to the fused method as a trailing primitive argument at the consuming kernel's element
 *       (it reads no channel and does not widen the tree's input-vector arity);</li>
 *   <li>optionally a boolean {@code AND}/{@code OR} 3VL {@link FusionNode.Logical} root or a Kleene 3VL
 *       {@link FusionNode.Not} root whose operands/child are column/constant comparisons over one homogeneous numeric
 *       element (or nested {@code AND}/{@code OR}/{@code NOT}); mixed-type comparisons under a logical root fall back
 *       to the unfused chain. A bare boolean column is not a fusable {@code NOT} child, so {@code NOT(bool_col)}
 *       refuses;</li>
 *   <li>the tree depth is &ge; 2 (a kernel, logical, or {@code Not} whose child is itself a kernel/logical/Not — a
 *       cast counts). A lone kernel gains nothing from fusion, so we leave it to the generated evaluator.
 *       {@code NOT(a>5)} is depth 2 and fuses; a bare comparison is depth 1 and does not.</li>
 * </ul>
 * At runtime the {@link Stitcher} emits a dense vector fast path (over each input's backing {@code *ArrayVector}) when
 * every input is a no-null single-valued vector, else a null/multi-value-aware block loop; both are per-node typed
 * (each leaf reads its own element; each kernel's arguments and result are its descriptor's types).
 *
 * <p>Descriptors are read by building each node's <b>unfused</b> factory through {@code rawFactory} (supplied by
 * {@code EvalMapper}) and inspecting {@link FusionAware#fusionDescriptor()} — including the synthetic cast kernels,
 * whose descriptor is read from the very {@link Cast#cast} evaluator the unfused chain would splice. This avoids
 * re-implementing per-operator type dispatch here and guarantees the descriptor matches what the generated evaluator
 * would use.
 */
public final class FusionPlanner {

    private FusionPlanner() {}

    /**
     * Owns the shared {@link Stitcher}, the process-wide {@link FusedClassCache}, and the caching production compiler —
     * the "how a shape is materialised" concern, kept out of the planner so this class is pure plan-building.
     */
    private static final FusionCompilationService COMPILATION = new FusionCompilationService();

    /** Package-private accessor so tests in this package can assert cache reuse and reset counters. */
    static FusedClassCache classCache() {
        return COMPILATION.cache();
    }

    /** Package-private accessor so tests can inject a {@link Stitcher} into the production compilation service. */
    static FusionCompilationService compilation() {
        return COMPILATION;
    }

    /**
     * Maximum distinct baked Div/Mod divisor values admitted per un-baked shape. Further values of the same shape
     * share one runtime-parameter class, bounding classes-per-shape at {@code BAKED_DIVISOR_CAP + 1}.
     */
    static final int BAKED_DIVISOR_CAP = 8;

    /**
     * Distinct baked Div/Mod divisor values admitted per un-baked shape (the {@link #shapeOf} string with baked
     * values stripped). The inner set is capped at {@link #BAKED_DIVISOR_CAP}; the outer map is capped at
     * {@link FusedClassCache#MAX_ENTRIES} so a long-lived node cannot accumulate unbounded shape keys after cache
     * eviction. Plan-time can be multi-threaded, so both tiers are concurrent: the map is a {@link ConcurrentHashMap}
     * and each inner set is {@link ConcurrentHashMap#newKeySet()}, with admission serialized per shape.
     *
     * <p>This lives on the planner rather than {@link FusedClassCache} because the bake decision happens at plan
     * time, before cache lookup, and the cache key <em>includes</em> the baked value — the cache cannot admit
     * before bake without a parallel map anyway.
     */
    private static final ConcurrentHashMap<String, Set<Object>> BAKED_DIVISORS_BY_UNBAKED_SHAPE = new ConcurrentHashMap<>();

    /**
     * The stitch entry points the planner uses. Extracted behind an interface so tests (in this package) can inject a
     * failing implementation to exercise the criterion-#5 fallback + telemetry without needing to craft bytecode that
     * fails JVM verification.
     */
    interface FusedClassCompiler {
        Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        /**
         * Like {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode)} but the production compiler resolves
         * {@code methods} against {@code type} <em>inside</em> the cached unit so a {@code findStatic} miss is
         * negatively cached (WARN once). Test doubles default to the 2-arg form.
         */
        default FusedClassCache.CachedFused compileBlockLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileBlockLoop(caller, tree), null, Map.of());
        }

        Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        /** See {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode, MethodType, String...)}. */
        default FusedClassCache.CachedFused compileVectorLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileVectorLoop(caller, tree), null, Map.of());
        }

        Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        /** See {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode, MethodType, String...)}. */
        default FusedClassCache.CachedFused compileVectorLoopChecked(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileVectorLoopChecked(caller, tree), null, Map.of());
        }

        Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        /** See {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode, MethodType, String...)}. */
        default FusedClassCache.CachedFused compileLogicalBlockLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileLogicalBlockLoop(caller, tree), null, Map.of());
        }

        /**
         * Compiles the dense 2VL logical vector loop (INTERFACE-only {@code fusedInterface} after B2; no RAW sibling).
         * Defaulted so existing {@link FusedClassCompiler} test doubles need not override it; the production
         * {@link FusionCompilationService} compiler provides the real, cache-backed implementation.
         */
        default Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            throw new UnsupportedOperationException("compileLogicalVectorLoop not implemented by this compiler");
        }

        /** See {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode, MethodType, String...)}. */
        default FusedClassCache.CachedFused compileLogicalVectorLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileLogicalVectorLoop(caller, tree), null, Map.of());
        }

        /**
         * Compiles the fused WHERE-predicate + EVAL-projection block loop (S3.2a). Defaulted so existing
         * {@link FusedClassCompiler} test doubles (which only exercise the single-expression paths) need not override it;
         * the production {@link FusionCompilationService} compiler and any filter-eval-specific test double provide a real implementation.
         */
        default Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
            throws Stitcher.StitchingException {
            throw new UnsupportedOperationException("compileFilterEvalBlockLoop not implemented by this compiler");
        }

        /** See {@link #compileBlockLoop(MethodHandles.Lookup, FusionNode, MethodType, String...)}. */
        default FusedClassCache.CachedFused compileFilterEvalBlockLoop(
            MethodHandles.Lookup caller,
            FusionNode predicateTree,
            FusionNode projectionTree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return new FusedClassCache.CachedFused(compileFilterEvalBlockLoop(caller, predicateTree, projectionTree), null, Map.of());
        }
    }

    /**
     * Test-only override for the production compiler. When non-null, {@link #maybeFuse} stitches through it, letting
     * a test force a {@link Stitcher.StitchingException} and assert the query still completes via the unfused chain
     * with the failure counter incremented. Never set in production.
     */
    static volatile FusedClassCompiler compilerForTests = null;

    /**
     * Attempts to fuse the subtree rooted at {@code exp}. Returns the fused factory on success, or {@code null} if the
     * subtree is not fusable (the caller then builds the unfused factory as usual). Any stitch failure is handled
     * inside {@link FusedExpressionEvaluatorFactory#tryCreate}, which falls back to {@code rawFactory.apply(exp)}.
     *
     * @param exp        the candidate expression subtree
     * @param layout     attribute-id → channel mapping, for resolving leaf inputs
     * @param rawFactory builds the <b>unfused</b> generated factory for any expression (used to read descriptors and
     *                   as the fallback chain); supplied by {@code EvalMapper} bound to the current planning context
     */
    public static ExpressionEvaluator.Factory maybeFuse(
        Expression exp,
        Layout layout,
        Function<Expression, ExpressionEvaluator.Factory> rawFactory
    ) {
        if (FusionSettings.isEnabled() == false) {
            // Runtime kill switch (criterion #3): the node never fuses; the caller uses the unfused tree unchanged and
            // the Stitcher is never touched. Not an "attempt" — the probe does not count kill-switch / sample skips.
            return null;
        }
        if (FusionSettings.shouldAttemptFusion() == false) {
            // A/B sampling (Stage 6): this subtree was not drawn into the fusion sample, so run it unfused. Returning
            // null keeps the fraction genuinely additive — the caller uses the ordinary unfused factory tree.
            return null;
        }
        FusionPlan plan = planOrNull(exp, layout, rawFactory);
        if (plan == null) {
            return null;
        }
        ExpressionEvaluator.Factory unfused = rawFactory.apply(exp);
        FusedClassCompiler compiler = compilerForTests != null ? compilerForTests : COMPILATION.compiler();
        long minRows = FusionSettings.adaptiveMinRows();
        if (minRows > 0) {
            // Adaptive path (Stage 6): defer the stitch. Serve the unfused chain until an evaluator has seen minRows
            // rows, then stitch once (memoised) and switch. The supplier returns the fused factory or null on failure.
            return new AdaptiveFusionEvaluatorFactory(
                unfused,
                () -> FusedExpressionEvaluatorFactory.tryCreateFusedOrNull(plan, unfused, compiler),
                minRows,
                plan.shape()
            );
        }
        // Eager path (default, minRows == 0): stitch now at plan time, falling back to the unfused factory on failure.
        return FusedExpressionEvaluatorFactory.tryCreate(plan, unfused, compiler);
    }

    /**
     * True when {@code exp} would pass the plan-time fusion gates (build + depth / stringDepth1 /
     * encoding-aware exemptions) and would proceed to a stitch. Does not stitch. Used by the H9 coverage classifier
     * to detect fusable islands without changing production factories.
     */
    static boolean isFusableAtPlan(Expression exp, Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        return planOrNull(exp, layout, rawFactory) != null;
    }

    /**
     * Builds a {@link FusionPlan} when {@code exp} is fusable at plan time, or {@code null} when the tree is
     * ineligible (build failed, empty inputs, or a lone kernel with no depth-1 exemption). Does not stitch and does
     * not consult the kill switch / sample — those stay in {@link #maybeFuse}.
     */
    private static FusionPlan planOrNull(Expression exp, Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
        PlanContext ctx = new PlanContext(layout, rawFactory);
        FusionNode tree = build(exp, ctx, null, null, true);
        if (tree == null || ctx.outputElement == null || ctx.inputElements.isEmpty()) {
            return null;
        }
        // H3: after the tree exists, un-bake over-cap Div/Mod divisors so they share one runtime-param class.
        // Must run before FusionSignature.of: un-baking a constant adds a trailing parameter slot.
        tree = applyBakedDivisorCap(tree, ctx);
        int[] channels = toIntArray(ctx.channels);
        ElementKind[] inputElements = ctx.inputElements.toArray(new ElementKind[0]);
        // The build walk appends one element per leaf input as it assigns channels, so every index in [0, arity) is
        // populated densely and exactly once. (The Stitcher's inputElements() fills any gap defensively, but a
        // planner-produced tree never has one — that fallback is dead code for this path, asserted here.)
        assert noNullElements(inputElements)
            : "planner must populate every input element densely; got " + java.util.Arrays.toString(inputElements);
        // One walk derives the whole positional contract (warning-source slots + constants-in-emit-order); the Stitcher
        // re-derives the same facts when it emits, and both agree because FusionSignature is the single source of truth.
        FusionSignature signature = FusionSignature.of(tree);
        // A lone single-value BytesRef string function (LEFT/SPACE/CONCAT) fuses at depth 1 only under the opt-in
        // esql.fusion.string_depth1 switch (its win is marginal — string work dominates — so it is off by default).
        // ctx.mapping is true for a lone converter root (TO_LOWER/TO_UPPER); excluding it here keeps that kernel
        // from wrongly fusing via the string path. A converter fuses only as a nested mapping in a depth>=2 chain (D4).
        boolean stringDepth1 = ctx.outputElement == ElementKind.BYTES_REF && ctx.mapping == false && FusionSettings.isStringDepth1Enabled();
        boolean encodingAware = encodingAware(tree, inputElements);
        // A lone BytesRef comparison (column == literal, column != literal, or literal == column) is depth-1, but
        // the encoding-aware dictionary gather evaluates once per distinct value — the same depth-1 exemption as
        // stringDepth1.
        boolean bytesRefComparisonDepth1 = encodingAware && ctx.outputElement == ElementKind.BOOLEAN;
        if (depth(tree) < 2 && stringDepth1 == false && bytesRefComparisonDepth1 == false) {
            // A lone kernel is not worth fusing — leave it to the generated evaluator.
            // A lone mapping convert (ctx.mapping) is always depth-1, so this refuses it (D4).
            return null;
        }
        return new FusionPlan(
            signature,
            tree,
            channels,
            inputElements,
            ctx.outputElement,
            ctx.overflowChecked,
            warningSources(signature.warningSourceIndices(), ctx),
            shapeOf(tree),
            encodingAware
        );
    }

    /**
     * Attempts to fuse a WHERE {@code predicate} and a single EVAL {@code projection} into ONE
     * {@link FusedFilterEvalEvaluatorFactory} (the plan-time product consumed by
     * {@link org.elasticsearch.compute.operator.FilterEvalOperator}). Returns the fused factory on success, or
     * {@code null} if either side is not fusable (the caller then builds the ordinary two-operator
     * {@code Filter → Eval} chain — fusion stays strictly additive). This is S3.2a: it produces the factory but does
     * <b>not</b> wire it into {@code LocalExecutionPlanner} (that is S3.2b).
     *
     * <p>Both sides must be fusable via the same {@link #build} eligibility used by {@link #maybeFuse} (including the
     * delegating-function guard {@code descriptor.kernelClass() == exp.getClass()}), and additionally: the predicate
     * must produce a boolean (a comparison {@link FusionNode.Kernel}, a 3VL {@link FusionNode.Logical}, or a
     * {@link FusionNode.Not} — FilterEval fuses a Not predicate because it reuses the logical 3VL emitter), and the
     * projection must be an arithmetic kernel root (a numeric output). A stitch failure inside
     * {@link FusedFilterEvalEvaluatorFactory#create} is handled there (it returns a fallback factory that reproduces
     * {@code Filter → Eval} from the two unfused evaluators), so this method still returns a usable factory in that case.
     *
     * @param predicate  the WHERE predicate expression
     * @param projection the EVAL projection expression
     * @param layout     attribute-id → channel mapping, for resolving leaf inputs (shared by both sides)
     * @param rawFactory builds the unfused generated factory for any expression (descriptor probe + fallback chain)
     */
    public static FilterEvalEvaluator.Factory maybeFuseFilterEval(
        Expression predicate,
        Expression projection,
        Layout layout,
        Function<Expression, ExpressionEvaluator.Factory> rawFactory
    ) {
        if (FusionSettings.isEnabled() == false) {
            return null;
        }
        // Build the predicate tree: must be a boolean-producing comparison/logical/Not subtree.
        PlanContext predCtx = new PlanContext(layout, rawFactory);
        FusionNode predicateTree = build(predicate, predCtx, null, null, true);
        if (predicateTree == null || predCtx.outputElement != ElementKind.BOOLEAN || predCtx.inputElements.isEmpty()) {
            return null;
        }
        // Build the projection tree: must be an arithmetic kernel root (numeric output); a bare column/constant EVAL or
        // a boolean projection is not fused here.
        PlanContext projCtx = new PlanContext(layout, rawFactory);
        FusionNode projectionTree = build(projection, projCtx, null, null, true);
        if (projectionTree == null
            || projCtx.outputElement == null
            || projCtx.outputElement == ElementKind.BOOLEAN
            || projCtx.inputElements.isEmpty()
            || (projectionTree instanceof FusionNode.Kernel) == false) {
            return null;
        }
        // Nested mapping (D3) needs a per-page MV fallback that FilterEval does not have; a ROOT mapping projection
        // (EVAL TO_LOWER(k)) would be stitched single-value and warn+null on MV instead of mapping. Refuse both so
        // those shapes stay on the unfused Filter→Eval chain. The single-expression path handles them.
        if (containsNestedMapping(predicateTree) || containsNestedMapping(projectionTree) || predCtx.mapping || projCtx.mapping) {
            return null;
        }
        predicateTree = applyBakedDivisorCap(predicateTree, predCtx);
        projectionTree = applyBakedDivisorCap(projectionTree, projCtx);

        // FilterEvalEmitter cannot forward an 'L' constant and constantParamClass rejects it, so these shapes stay
        // on the unfused chain; the single-expression path handles L constants.
        if (containsReferenceConstant(predicateTree) || containsReferenceConstant(projectionTree)) {
            return null;
        }

        int[] predicateChannels = toIntArray(predCtx.channels);
        int[] projectionChannels = toIntArray(projCtx.channels);
        ElementKind[] predicateInputElements = predCtx.inputElements.toArray(new ElementKind[0]);
        ElementKind[] projectionInputElements = projCtx.inputElements.toArray(new ElementKind[0]);
        assert noNullElements(predicateInputElements) && noNullElements(projectionInputElements)
            : "planner must populate every input element densely";

        ExpressionEvaluator.Factory unfusedPredicate = rawFactory.apply(predicate);
        ExpressionEvaluator.Factory unfusedProjection = rawFactory.apply(projection);
        FusedClassCompiler compiler = compilerForTests != null ? compilerForTests : COMPILATION.compiler();

        // One walk per sub-tree derives its warning-source slots + constants-in-emit-order (single source of truth).
        FusionSignature predicateSignature = FusionSignature.of(predicateTree);
        FusionSignature projectionSignature = FusionSignature.of(projectionTree);

        // Combined per-source warnings: predicate sources first, then projection sources (matching the Stitcher offset).
        Source[] predicateSources = warningSources(predicateSignature.warningSourceIndices(), predCtx);
        Source[] projectionSources = warningSources(projectionSignature.warningSourceIndices(), projCtx);
        Source[] warningSources = new Source[predicateSources.length + projectionSources.length];
        System.arraycopy(predicateSources, 0, warningSources, 0, predicateSources.length);
        System.arraycopy(projectionSources, 0, warningSources, predicateSources.length, projectionSources.length);

        FilterEvalPlan plan = new FilterEvalPlan(
            predicateSignature,
            projectionSignature,
            predicateTree,
            projectionTree,
            predicateChannels,
            projectionChannels,
            predicateInputElements,
            projectionInputElements,
            projCtx.outputElement,
            warningSources,
            filterEvalShapeOf(predicateTree, projectionTree)
        );
        return FusedFilterEvalEvaluatorFactory.create(plan, unfusedPredicate, unfusedProjection, compiler);
    }

    /**
     * A stable, column-/constant-value-independent signature of a fused filter+eval shape: the predicate sub-shape and
     * the projection sub-shape (each via {@link #shapeOf}), so two {@code WHERE p EVAL e} pairs with the same operator
     * arrangements share one stitched class regardless of columns or literal values.
     */
    static String filterEvalShapeOf(FusionNode predicateTree, FusionNode projectionTree) {
        return "FILTER_EVAL(" + shapeOf(predicateTree) + "|" + shapeOf(projectionTree) + ")";
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** True iff every input element was populated by the build walk (i.e. no gap the Stitcher would have to fill). */
    private static boolean noNullElements(ElementKind[] inputElements) {
        for (ElementKind element : inputElements) {
            if (element == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the per-warning-source {@link Source} array indexed by the {@link Stitcher}'s structural warning-source
     * slot: {@code out[slot]} is the source of the kernel assigned that slot. The {@link Stitcher} bakes those same
     * structural slots into the emitted bytecode, so the fused method's {@code warnings[slot].registerException(...)}
     * lands on a {@code Warnings} built from the kernel's real source — matching the unfused chain's per-node
     * attribution (criterion #1). The assignment is purely structural, so it does not affect the shape-keyed cache.
     */
    private static Source[] warningSources(Map<FusionNode, Integer> slots, PlanContext ctx) {
        Source[] out = new Source[slots.size()];
        for (Map.Entry<FusionNode, Integer> e : slots.entrySet()) {
            Source source = ctx.kernelSources.get(e.getKey());
            // Every Kernel node is recorded during the build walk, so this is always present; default to EMPTY as a
            // defensive net rather than risk an NPE that would break the query (fusion must never do that).
            out[e.getValue()] = source != null ? source : Source.EMPTY;
        }
        return out;
    }

    /** Mutable accumulators threaded through the recursive walk. */
    private static final class PlanContext {
        private final Layout layout;
        private final Function<Expression, ExpressionEvaluator.Factory> rawFactory;
        private final List<Integer> channels = new ArrayList<>();
        /**
         * The {@link Source} of every warning-emitting {@link FusionNode.Kernel} we build (overflow-checked kernels for
         * overflow warnings; comparison/arithmetic kernels for their direct leaf operands' multi-value warnings). Used
         * after the walk to build a per-warning-source {@code Source[]} keyed by the {@link Stitcher}'s structural
         * warning-source slot, so the fused path attributes each warning to the same source the unfused chain would.
         */
        private final Map<FusionNode, Source> kernelSources = new IdentityHashMap<>();
        /**
         * The primitive element of each input column, indexed by {@link FusionNode.Input#index()} (parallel to
         * {@link #channels}). With per-node typing a tree may mix elements — e.g. {@code doubleCol + intCol} reads
         * channel 0 as a {@code double} column and channel 1 as an {@code int} column (the latter bridged to
         * {@code double} by a synthetic cast kernel) — so this is a per-input list, not a single tree-wide element.
         */
        private final List<ElementKind> inputElements = new ArrayList<>();
        /**
         * Homogeneous element of a <b>logical</b> ({@code AND}/{@code OR}/{@code NOT}) tree's comparison operands. The
         * logical emit path keys its comparison helpers off one element, so within a fused logical tree every comparison
         * must be over the same primitive (mixed-type comparisons under a logical root fall back to the unfused chain).
         * Unused (stays {@code null}) for the arithmetic/comparison {@link #build} path, which is per-node typed.
         */
        private ElementKind logicalElement;
        /** The root node's output kind: numeric for an arithmetic root, BOOLEAN for a comparison/logical/Not root. */
        private ElementKind outputElement;
        private boolean overflowChecked;
        /**
         * Set when the root is a mapping convert kernel (TO_LOWER/TO_UPPER). Excludes that root from
         * {@code stringDepth1} so a lone converter stays unfused (D4); nested mapping leaves this false.
         */
        private boolean mapping;
        /**
         * True once this walk emitted a baked Div/Mod divisor. {@link #applyBakedDivisorCap} uses this as a
         * hot-path early-out so trees with no baked constant skip the un-baked-shape string and rewrite.
         */
        private boolean hasBakedDivisor;

        PlanContext(Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
            this.layout = layout;
            this.rawFactory = rawFactory;
        }
    }

    /**
     * Recursively builds a {@link FusionNode} for {@code exp} whose output element is {@code expected}, or returns
     * {@code null} the moment any eligibility rule is violated (which aborts the whole attempt — a partially fusable
     * tree is not fused here).
     *
     * <p><b>Per-node typing (with casts).</b> Each node carries its own element(s): a leaf reads its column/constant at
     * its OWN element; a kernel's arguments are its descriptor's argument element and its output is its descriptor's
     * return element. A tree may therefore mix {@code int}/{@code long}/{@code double} — e.g. {@code double + int} is
     * {@code Add(doubleCol, castIntToDouble(intCol))}, the same explicit cast the unfused chain inserts via
     * {@link Cast#cast}. When a node's natural output element differs from the {@code expected} element its parent
     * kernel requires, this method bridges the gap with a synthetic <b>cast kernel</b> (a {@code @Fusable} numeric
     * conversion, e.g. {@code (I)D}) whose warning source is the consuming kernel's source — matching the unfused chain,
     * where {@code Cast.cast(source(), ...)} carries the arithmetic operator's own source. Only the widening numeric
     * casts ESQL's {@code commonType} can produce ({@code int→long}, {@code int→double}, {@code long→double}) are
     * bridged; any other coercion (or a non-{@code @Fusable} cast) is out of scope and refuses to fuse.
     *
     * <p>The OUTPUT element of the whole tree is the root node's return kind: the numeric element for an arithmetic
     * root, or {@link ElementKind#BOOLEAN} for a comparison root (e.g. {@code a > b}). At the root {@code expected} is
     * {@code null} (the root may output any supported element); a boolean-returning comparison is therefore only
     * fusable at the root, since no in-scope kernel takes a boolean argument.
     *
     * @param expected        the element this node must output, or {@code null} at the tree root (any element)
     * @param consumingSource the source of the parent kernel that consumes this node (for cast warning attribution);
     *                        {@code null} at the root, where no cast is ever inserted
     * @param root            whether {@code exp} is the tree root (only the root may be a boolean-returning comparison)
     */
    private static FusionNode build(Expression exp, PlanContext ctx, ElementKind expected, Source consumingSource, boolean root) {
        if (exp instanceof And || exp instanceof Or) {
            // A boolean AND/OR node: build a 3VL Logical whose operands are fused comparison/logical subtrees over
            // homogeneous-primitive column inputs. This is emitted (not templated) by the Stitcher, so no descriptor is
            // probed here. Its output is always a nullable boolean, so it only sits at the root (expected == null).
            if (expected != null && expected != ElementKind.BOOLEAN) {
                return null;
            }
            FusionNode logical = buildLogical(exp, ctx);
            if (logical != null) {
                ctx.outputElement = ElementKind.BOOLEAN;
            }
            return logical;
        }
        if (exp instanceof Not) {
            // Kleene 3VL NOT of a fusable boolean subtree (comparison / AND / OR / nested Not). Emitted by the
            // Stitcher (no descriptor). A bare boolean column is not a fusable child, so NOT(bool_col) returns null.
            if (expected != null && expected != ElementKind.BOOLEAN) {
                return null;
            }
            FusionNode not = buildNot(exp, ctx);
            if (not != null) {
                ctx.outputElement = ElementKind.BOOLEAN;
            }
            return not;
        }
        if (exp instanceof Attribute attr) {
            Layout.ChannelAndType channelAndType = ctx.layout.get(attr.id());
            if (channelAndType == null) {
                return null;
            }
            // A leaf reads its column at the column's OWN numeric element; a bare attribute cannot be a fused root
            // (depth-2 requirement), so `expected` is always non-null here. If the column element differs from what the
            // parent kernel expects, maybeCast bridges it with a synthetic widening cast — mirroring the unfused chain.
            ElementKind columnElement = elementOf(attr.dataType());
            if (columnElement == null) {
                return null;
            }
            int index = ctx.channels.size();
            ctx.channels.add(channelAndType.channel());
            ctx.inputElements.add(columnElement);
            return maybeCast(new FusionNode.Input(index), columnElement, expected, consumingSource, ctx, exp);
        }
        if (exp instanceof Literal literal) {
            // A literal operand is passed as a trailing primitive method argument (no page channel, no input vector),
            // at the element the consuming kernel expects (matching Cast.cast of the literal to the operator's
            // commonType); its value rides in as an argument at eval time rather than being baked into the bytecode.
            return buildConstant(literal, expected, ctx);
        }
        if (exp instanceof Left) {
            // LEFT(str, length) (#8b): a BytesRef -> BytesRef kernel whose first two @Fixed params are per-driver SCRATCH
            // buffers (a reusable BytesRef + a UTF8CodePoint), created fresh per driver rather than captured. Its body
            // loops over code points and calls only public methods, so it links from the fused hidden class's package.
            return buildLeftRight(exp, ctx, expected, root, LEFT_DESCRIPTOR);
        }
        if (exp instanceof Right) {
            // RIGHT(str, length): identical shape to LEFT (same two scratch operands + str + length, public-only body).
            return buildLeftRight(exp, ctx, expected, root, RIGHT_DESCRIPTOR);
        }
        if (exp instanceof Trim) {
            // TRIM(str): a unary BytesRef -> BytesRef kernel, no scratch/@Fixed; its loop body calls only public methods.
            return buildUnaryStringFn(exp, ctx, expected, root, TRIM_DESCRIPTOR);
        }
        if (exp instanceof LTrim) {
            return buildUnaryStringFn(exp, ctx, expected, root, LTRIM_DESCRIPTOR);
        }
        if (exp instanceof RTrim) {
            return buildUnaryStringFn(exp, ctx, expected, root, RTRIM_DESCRIPTOR);
        }
        if (exp instanceof Space space) {
            // SPACE(number) (Releasable/breaker scratch): a BytesRef -> BytesRef kernel whose @Fixed param is a
            // per-driver BreakingBytesRefBuilder — a Releasable, circuit-breaker-accounted scratch built from the
            // DriverContext's breaker and released on close. Single-value @Evaluator (MV-safe); warns on a bad number.
            return buildSpace(space, ctx, expected, root);
        }
        if (exp instanceof Concat concat) {
            // CONCAT(a, b, ...) (variadic): a BytesRef -> BytesRef kernel whose process takes (@Fixed
            // BreakingBytesRefBuilder scratch, BytesRef[] values). The N string operands become the array's elements;
            // the stitcher builds the array per row. Single-value @Evaluator (MV-safe).
            return buildConcat(concat, ctx, expected, root);
        }

        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        FusionDescriptor descriptor;
        if (factory instanceof FusionAware fusionAware) {
            descriptor = fusionAware.fusionDescriptor();
        } else if ((exp instanceof Div || exp instanceof Mod) && exp.children().size() == 2 && exp.children().get(1) instanceof Literal) {
            // The unfused constant-RHS fast path returns *ByConstant, which is NOT @Fusable (it omits the zero
            // check, relying on excludeZeroRhs). Fusion must still splice process* with a baked divisor so
            // divide-by-zero / MIN/-1 semantics stay byte-identical to the unfused binary path. Recover the
            // process* descriptor from the expression's numeric element; unsigned_long / dense_vector yield null.
            // The *ByConstant factory built here is memoised by EvalMapper's unfusedMemo and reused as the
            // fusion fallback (maybeFuse's rawFactory.apply), so the constant-RHS fast path is not rebuilt.
            descriptor = bakedDivModDescriptor(exp.getClass(), elementOf(exp.dataType()));
            if (descriptor == null) {
                return null;
            }
        } else {
            return null;
        }
        if (descriptor.mapping()) {
            return buildMapping(exp, (FusionAware) factory, descriptor, ctx, expected, root);
        }
        // The @Fusable kernel must be THIS expression's own kernel — i.e. declared on this expression's class (Add
        // declares Add#processLongs, Floor declares Floor#..., etc.), so exp.children() ARE the kernel's operands.
        // A DELEGATING function breaks that: e.g. BUCKET(field, span).toEvaluator builds Mul(Floor(Div(field, span)),
        // span) and returns that Mul's (FusionAware) evaluator, so the factory reports the Mul kernel while exp is a
        // Bucket whose children are [field, span] — NOT Mul's operands. Fusing here would mis-map them, silently
        // dropping the delegate's real sub-tree (the Floor/Div) and computing field*span instead of the bucket. Refuse
        // to fuse whenever the kernel is not declared on exp's own class, so such functions fall back to the (correct)
        // unfused chain. (This is why a Bucket, Round, etc. never fuse today; a future stage could fuse their LOWERED
        // expression tree directly instead.)
        if (descriptor.kernelClass() != exp.getClass()) {
            return null;
        }
        String kernelType = descriptor.kernelType();
        // Every argument of THIS kernel must be the same primitive long/int/double (binary ops are homogeneous; a
        // unary cast kernel is trivially homogeneous). Children of different elements are bridged by casts, not here.
        ElementKind argElement = homogeneousArgElement(kernelType);
        if (argElement == null) {
            return null;
        }
        int close = kernelType.indexOf(')');
        if (close < 0 || close + 2 != kernelType.length()) {
            // The return must be exactly one descriptor char (a primitive or boolean); anything else is out of scope.
            return null;
        }
        char returnChar = kernelType.charAt(close + 1);
        boolean comparison = returnChar == 'Z';
        ElementKind returnNumeric = elementOf(returnChar); // null for 'Z' or an unsupported return kind
        // The node's natural output element: BOOLEAN for a comparison, else its numeric return kind.
        ElementKind naturalOut = comparison ? ElementKind.BOOLEAN : returnNumeric;
        if (naturalOut == null) {
            return null;
        }

        int argCount = primitiveArgCount(kernelType);
        List<Expression> children = exp.children();
        if (children.size() != argCount) {
            return null;
        }
        ctx.overflowChecked |= descriptor.overflowChecked();

        List<FusionNode> childNodes = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            Expression child = children.get(i);
            // F1/H3: bake ONLY an INT/LONG Literal in the divisor (rhs) of a Div or Mod kernel, so C2 can
            // strength-reduce the division. DOUBLE divisors stay runtime params (C2 does not strength-reduce
            // x/c for doubles). The lhs, and constants of every other kernel, stay runtime params so `a>5` and
            // `a>6` still share a class. Keep the kernel = process* (not *ByConstant). Over-cap distinct INT/LONG
            // values of one un-baked shape are un-baked after the tree is built (see applyBakedDivisorCap).
            FusionNode childNode;
            if (i == 1
                && child instanceof Literal literal
                && (exp instanceof Div || exp instanceof Mod)
                && (argElement == ElementKind.INT || argElement == ElementKind.LONG)) {
                childNode = buildConstant(literal, argElement, ctx, true);
            } else {
                // Each child must output this kernel's argument element; a mismatch is bridged by a widening cast inside
                // the child build, attributed to THIS kernel's source (as Cast.cast(source(), ...) does when unfused).
                childNode = build(child, ctx, argElement, exp.source(), false);
            }
            if (childNode == null) {
                return null;
            }
            childNodes.add(childNode);
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(descriptor, childNodes);
        // Capture this kernel's own source so the fused path attributes its overflow / leaf multi-value warnings to the
        // same node source the unfused generated evaluator would.
        ctx.kernelSources.put(kernel, exp.source());
        if (root) {
            ctx.outputElement = naturalOut;
            return kernel;
        }
        // A non-root node must output the element its parent expects; bridge a numeric mismatch with a cast kernel.
        return maybeCast(kernel, naturalOut, expected, consumingSource, ctx, exp);
    }

    /**
     * The manually-built kernel descriptor for {@code Left#process(@Fixed BytesRef out, @Fixed UTF8CodePoint cp,
     * BytesRef str, int length)}. The two leading {@code @Fixed} params are per-driver scratch buffers; the {@code $}
     * inner-class descriptor for {@code UnicodeUtil.UTF8CodePoint} is written explicitly. Not overflow-checked; a null
     * input yields null (the block emitter's present flag propagates it via the str/length operands).
     */
    static final FusionDescriptor LEFT_DESCRIPTOR = new FusionDescriptor(
        Left.class,
        "process",
        "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/UnicodeUtil$UTF8CodePoint;"
            + "Lorg/apache/lucene/util/BytesRef;I)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );

    /**
     * Builds the fused node for a mapping {@code @ConvertEvaluator} (e.g. {@code TO_LOWER}/{@code TO_UPPER}): a
     * single kernel over its BytesRef field child plus the Factory's {@code @Fixed} SINGLETON constants.
     *
     * <p>At the root {@link PlanContext#mapping} is set so a lone converter is excluded from the string-depth-1
     * opt-in and refused by the depth guard (D4: a converter fuses only as a nested mapping kernel). In a
     * <b>non-root</b> position the kernel is admitted only as a child of a single-value parent that consumes
     * {@code BytesRef} (e.g. {@code LENGTH}, {@code Equals}) — the parent compiles via the ordinary block path
     * ({@code ctx.mapping} stays false) and the nested mapping body is spliced as a unary kernel. Multi-value pages
     * of such a chain fall back to unfused at eval time (D3). A mapping under a LOGICAL root never reaches here
     * ({@link #buildComparisonOperand} refuses non-Attribute/Literal children). A mapping under another mapping is
     * refused by the direct-{@link FusionNode.Input} field restriction (one mapping level only).
     *
     * <p>The anti-delegation guard is {@code kernelClass.isAssignableFrom(exp.getClass())} rather than {@code ==}:
     * {@code @ConvertEvaluator} lives on the declaring class (e.g. {@code ChangeCase.process}) while the expression
     * is a concrete subclass ({@code ToLower}/{@code ToUpper}). A converter's single non-fixed child is
     * {@code exp.children().get(0)}, lining up with the kernel's one BytesRef input.
     */
    private static FusionNode buildMapping(
        Expression exp,
        FusionAware factory,
        FusionDescriptor descriptor,
        PlanContext ctx,
        ElementKind expected,
        boolean root
    ) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            // Nested mapping is only a child of a single-value parent that consumes BytesRef (LENGTH, Equals).
            return null;
        }
        if (descriptor.kernelClass().isAssignableFrom(exp.getClass()) == false) {
            return null;
        }
        List<Expression> children = exp.children();
        if (children.size() != 1) {
            return null;
        }
        FusionNode field = build(children.get(0), ctx, ElementKind.BYTES_REF, exp.source(), false);
        if (field == null || field instanceof FusionNode.Input == false) {
            // Direct BytesRef column input only — also refuses a mapping under another mapping (the inner
            // mapping would be a Kernel, not an Input).
            return null;
        }
        List<Object> fixedArgs = factory.fusionFixedArgs();
        List<Class<?>> fixedTypes = mappingFixedArgTypes(descriptor.kernelClass(), descriptor.kernelType());
        if (fixedTypes == null || fixedTypes.size() != fixedArgs.size()) {
            return null;
        }
        List<FusionNode> operands = new ArrayList<>(1 + fixedArgs.size());
        operands.add(field);
        for (int i = 0; i < fixedArgs.size(); i++) {
            // Use the DECLARED kernel parameter type, not value.getClass() — enum constants like ChangeCase.Case.LOWER
            // are anonymous subclasses (ChangeCase$Case$1) that would miss the kernel's ChangeCase$Case parameter.
            operands.add(FusionNode.Constant.reference(fixedArgs.get(i), fixedTypes.get(i)));
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(descriptor, operands);
        ctx.kernelSources.put(kernel, exp.source());
        if (root) {
            // Root mapping sets ctx.mapping so stringDepth1 excludes a lone converter; maybeFuse then refuses
            // depth-1 mapping so a lone TO_LOWER stays unfused (D4). Nested mapping does not flip this — the
            // parent single-value kernel (LENGTH→int, Equals→boolean) compiles via the ordinary block path.
            ctx.outputElement = ElementKind.BYTES_REF;
            ctx.mapping = true;
        }
        return kernel;
    }

    /**
     * Declared reference types of the {@code @Fixed} parameters in {@code kernelType}, i.e. the JVM argument list
     * after the leading {@code Lorg/apache/lucene/util/BytesRef;} input. {@code /} is converted to {@code .};
     * {@code $} is kept so inner classes (e.g. {@code ChangeCase$Case}) load as the declared type. Resolution uses
     * the <em>kernel class's</em> loader, not the planner's, so a kernel defined in another module still resolves
     * its own inner types.
     */
    private static List<Class<?>> mappingFixedArgTypes(Class<?> kernelClass, String kernelType) {
        if (kernelType == null || kernelType.startsWith("(") == false) {
            return null;
        }
        int close = kernelType.indexOf(')');
        if (close < 0) {
            return null;
        }
        String args = kernelType.substring(1, close);
        String bytesRef = "Lorg/apache/lucene/util/BytesRef;";
        if (args.startsWith(bytesRef) == false) {
            return null;
        }
        args = args.substring(bytesRef.length());
        List<Class<?>> types = new ArrayList<>();
        ClassLoader loader = kernelClass.getClassLoader();
        int i = 0;
        while (i < args.length()) {
            if (args.charAt(i) != 'L') {
                return null;
            }
            int semi = args.indexOf(';', i);
            if (semi < 0) {
                return null;
            }
            String binaryName = args.substring(i + 1, semi).replace('/', '.');
            try {
                types.add(Class.forName(binaryName, true, loader));
            } catch (ClassNotFoundException e) {
                return null;
            }
            i = semi + 1;
        }
        return types;
    }

    /**
     * The manually-built kernel descriptor for {@code Concat#process(@Fixed BreakingBytesRefBuilder scratch,
     * BytesRef[] values)} — variadic: the trailing {@code values} array gathers the N string operands. Not overflow-
     * checked: {@code Concat} declares no {@code @Evaluator.warnExceptions}, so an over-length concatenation throws
     * {@code EsqlClientException} which propagates (a query failure), exactly as the unfused evaluator does.
     * {@code dictionarySafe=false}: evaluating an unreferenced dictionary entry past 1MB would throw even though
     * no row hit it, so the encoding-aware ordinal path must refuse CONCAT.
     */
    static final FusionDescriptor CONCAT_DESCRIPTOR = new FusionDescriptor(
        Concat.class,
        "process",
        "(Lorg/elasticsearch/compute/operator/BreakingBytesRefBuilder;[Lorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        true,
        false,
        false,
        false
    );

    /**
     * Builds the fused node for a {@code CONCAT(a, b, ...)} ({@link Concat}) expression (variadic): a kernel whose first
     * operand is a per-driver breaker-accounted {@link BreakingBytesRefBuilder} scratch and whose remaining operands are
     * the N string arguments — each of which must fuse to a {@code BytesRef} column input or a keyword/text literal
     * (a nested function argument is not supported here, so the whole CONCAT then refuses). Produces a {@code BytesRef}
     * (block-path only).
     */
    private static FusionNode buildConcat(Concat concat, PlanContext ctx, ElementKind expected, boolean root) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        List<Expression> args = concat.children();
        if (args.size() < 2) {
            return null;
        }
        List<FusionNode> kernelChildren = new java.util.ArrayList<>(args.size() + 1);
        kernelChildren.add(
            FusionNode.Constant.scratch(context -> new BreakingBytesRefBuilder(context.breaker(), "concat"), BreakingBytesRefBuilder.class)
        );
        for (Expression arg : args) {
            FusionNode node = build(arg, ctx, ElementKind.BYTES_REF, concat.source(), false);
            // The array-gather emit reads each element as a fresh BytesRef from a column, or loads a captured
            // keyword/text literal; refuse the whole CONCAT otherwise (a nested function arg falls back to unfused).
            if (node instanceof FusionNode.Input == false && node instanceof FusionNode.Constant == false) {
                return null;
            }
            kernelChildren.add(node);
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(CONCAT_DESCRIPTOR, kernelChildren);
        ctx.kernelSources.put(kernel, concat.source());
        if (root) {
            ctx.outputElement = ElementKind.BYTES_REF;
        }
        return kernel;
    }

    /**
     * The manually-built kernel descriptor for {@code Space#process(@Fixed BreakingBytesRefBuilder scratch, int number)}.
     * The @Fixed param is a per-driver Releasable breaker-accounted scratch (first, before the int input). Overflow-
     * checked: {@code Space} declares {@code @Evaluator(warnExceptions = IllegalArgumentException.class)} (a negative or
     * too-long number throws), so the block emitter wraps the spliced body in a catch that warns + nulls, matching the
     * unfused {@code SpaceEvaluator}. An all-null input yields null. {@code dictionarySafe=false}: SPACE can throw
     * (caught as a warning) on a negative/too-long count, so the encoding-aware path must refuse it even though
     * {@code warns=true} already excludes it.
     */
    static final FusionDescriptor SPACE_DESCRIPTOR = new FusionDescriptor(
        Space.class,
        "process",
        "(Lorg/elasticsearch/compute/operator/BreakingBytesRefBuilder;I)Lorg/apache/lucene/util/BytesRef;",
        true,
        true,
        "java.lang.IllegalArgumentException",
        false,
        false,
        true,
        false
    );

    /**
     * Builds the fused node for a {@code SPACE(number)} ({@link Space}) expression: a kernel over its int {@code number}
     * child, preceded by a per-driver Releasable SCRATCH operand — a {@link BreakingBytesRefBuilder} built from the
     * {@link org.elasticsearch.compute.operator.DriverContext}'s circuit breaker (the same supplier {@code
     * Space#toEvaluator} uses) and released when the fused evaluator closes. Produces a {@code BytesRef} (block-path
     * only). Refuses unless it is the root or its parent expects a {@code BytesRef}, or if the child does not fuse.
     */
    private static FusionNode buildSpace(Space space, PlanContext ctx, ElementKind expected, boolean root) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        FusionNode number = build(space.children().get(0), ctx, ElementKind.INT, space.source(), false);
        if (number == null) {
            return null;
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(
            SPACE_DESCRIPTOR,
            List.of(
                FusionNode.Constant.scratch(
                    context -> new BreakingBytesRefBuilder(context.breaker(), "space"),
                    BreakingBytesRefBuilder.class
                ),
                number
            )
        );
        ctx.kernelSources.put(kernel, space.source());
        if (root) {
            ctx.outputElement = ElementKind.BYTES_REF;
        }
        return kernel;
    }

    /** The manually-built descriptor for {@code Right#process(@Fixed BytesRef out, @Fixed UTF8CodePoint cp, BytesRef str, int length)} — LEFT's shape. */
    static final FusionDescriptor RIGHT_DESCRIPTOR = new FusionDescriptor(
        Right.class,
        "process",
        "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/UnicodeUtil$UTF8CodePoint;"
            + "Lorg/apache/lucene/util/BytesRef;I)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );

    static final FusionDescriptor TRIM_DESCRIPTOR = unaryStringDescriptor(Trim.class);
    static final FusionDescriptor LTRIM_DESCRIPTOR = unaryStringDescriptor(LTrim.class);
    static final FusionDescriptor RTRIM_DESCRIPTOR = unaryStringDescriptor(RTrim.class);

    /** Descriptor for a unary {@code process(BytesRef) -> BytesRef} string kernel (TRIM/LTRIM/RTRIM). Not overflow-checked. */
    private static FusionDescriptor unaryStringDescriptor(Class<?> kernelClass) {
        return new FusionDescriptor(
            kernelClass,
            "process",
            "(Lorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
            false,
            true,
            "",
            false,
            false,
            false,
            true
        );
    }

    /**
     * Builds the fused node for a {@code LEFT}/{@code RIGHT}{@code (str, length)} expression: a kernel over its BytesRef
     * {@code str} + int {@code length} children, preceded by two per-driver SCRATCH reference operands — a reusable
     * {@link BytesRef} and a {@link UnicodeUtil.UTF8CodePoint}, built fresh per driver by the same suppliers
     * {@code Left/Right#toEvaluator} use. Produces a {@code BytesRef} (block-path only). Refuses unless it is the root
     * or its parent expects a {@code BytesRef}, or if either child does not fuse. {@code descriptor} selects LEFT vs RIGHT.
     */
    private static FusionNode buildLeftRight(
        Expression exp,
        PlanContext ctx,
        ElementKind expected,
        boolean root,
        FusionDescriptor descriptor
    ) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        FusionNode str = build(exp.children().get(0), ctx, ElementKind.BYTES_REF, exp.source(), false);
        if (str == null) {
            return null;
        }
        FusionNode length = build(exp.children().get(1), ctx, ElementKind.INT, exp.source(), false);
        if (length == null) {
            return null;
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(
            descriptor,
            List.of(
                FusionNode.Constant.scratch(context -> new BytesRef(), BytesRef.class),
                FusionNode.Constant.scratch(context -> new UnicodeUtil.UTF8CodePoint(), UnicodeUtil.UTF8CodePoint.class),
                str,
                length
            )
        );
        ctx.kernelSources.put(kernel, exp.source());
        if (root) {
            ctx.outputElement = ElementKind.BYTES_REF;
        }
        return kernel;
    }

    /**
     * Builds the fused node for a unary {@code BytesRef -> BytesRef} string function (TRIM/LTRIM/RTRIM): a single kernel
     * over its BytesRef field child, no scratch or {@code @Fixed} operand. Produces a {@code BytesRef} (block-path only).
     * Refuses unless it is the root or its parent expects a {@code BytesRef}, or if the field child does not fuse.
     */
    private static FusionNode buildUnaryStringFn(
        Expression exp,
        PlanContext ctx,
        ElementKind expected,
        boolean root,
        FusionDescriptor descriptor
    ) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        FusionNode field = build(exp.children().get(0), ctx, ElementKind.BYTES_REF, exp.source(), false);
        if (field == null) {
            return null;
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(descriptor, List.of(field));
        ctx.kernelSources.put(kernel, exp.source());
        if (root) {
            ctx.outputElement = ElementKind.BYTES_REF;
        }
        return kernel;
    }

    /**
     * Returns {@code node} unchanged when its {@code natural} output element already equals the {@code expected}
     * element (or {@code expected} is {@code null}, i.e. the root), otherwise wraps it in a synthetic {@code @Fusable}
     * widening-cast {@link FusionNode.Kernel} bridging {@code natural → expected}. Returns {@code null} when the cast
     * is not a supported widening or its evaluator is not {@code @Fusable} (the subtree then refuses to fuse). The cast
     * kernel's warning source is {@code consumingSource} — the source of the kernel that consumes it — matching the
     * unfused chain, where the arithmetic operator builds the cast with its own {@code source()}.
     */
    private static FusionNode maybeCast(
        FusionNode node,
        ElementKind natural,
        ElementKind expected,
        Source consumingSource,
        PlanContext ctx,
        Expression childExp
    ) {
        if (expected == null || natural == expected) {
            return node;
        }
        if (supportedWidening(natural, expected) == false) {
            return null;
        }
        FusionDescriptor castDescriptor = castDescriptor(natural, expected, consumingSource, childExp, ctx);
        if (castDescriptor == null) {
            return null;
        }
        FusionNode.Kernel cast = new FusionNode.Kernel(castDescriptor, List.of(node));
        // The cast reads its (numeric) child and never overflows, but a multi-valued child column still warns; attribute
        // that warning to the consuming kernel's source, exactly as the unfused CastXToYEvaluator (built with the
        // arithmetic operator's source()) would.
        ctx.kernelSources.put(cast, consumingSource != null ? consumingSource : Source.EMPTY);
        return cast;
    }

    /**
     * The {@link FusionDescriptor} of the {@code @Fusable} widening cast {@code from → to}, read from the same
     * {@link Cast#cast} evaluator the unfused chain uses (so the fused kernel is byte-for-byte the one the generated
     * evaluator would splice), or {@code null} if that cast evaluator is not {@link FusionAware} (not {@code @Fusable})
     * or the descriptor is not the expected {@code (from)to} numeric shape.
     */
    private static FusionDescriptor castDescriptor(ElementKind from, ElementKind to, Source source, Expression childExp, PlanContext ctx) {
        ExpressionEvaluator.Factory in = ctx.rawFactory.apply(childExp);
        ExpressionEvaluator.Factory castFactory = Cast.cast(source != null ? source : Source.EMPTY, dataTypeOf(from), dataTypeOf(to), in);
        if ((castFactory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) castFactory).fusionDescriptor();
        String kernelType = descriptor.kernelType();
        // Expect a single-argument numeric conversion (from)to, e.g. (I)D. A cast never overflows in this scope.
        if (homogeneousArgElement(kernelType) != from) {
            return null;
        }
        int close = kernelType.indexOf(')');
        if (close < 0 || close + 2 != kernelType.length() || elementOf(kernelType.charAt(close + 1)) != to) {
            return null;
        }
        return descriptor;
    }

    /** Whether {@code from → to} is one of the widening numeric casts ESQL's {@code commonType} can produce. */
    private static boolean supportedWidening(ElementKind from, ElementKind to) {
        return switch (to) {
            case LONG -> from == ElementKind.INT;
            case DOUBLE -> from == ElementKind.INT || from == ElementKind.LONG;
            // No widening targets INT/BOOLEAN, and BYTES_REF is never a cast target in this slice (a keyword/text leaf
            // always matches its consuming string kernel's BytesRef argument exactly, so no bridge is ever inserted).
            case INT, BOOLEAN, BYTES_REF -> false;
        };
    }

    /** The {@link DataType} for a numeric element kind (BOOLEAN/BYTES_REF have no cast source/target in scope). */
    private static DataType dataTypeOf(ElementKind element) {
        return switch (element) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> DataType.BOOLEAN;
            // Unreachable: BYTES_REF is never the source/target of a widening cast (guarded by supportedWidening),
            // so castDescriptor never asks for its DataType. Mapped to KEYWORD for switch exhaustiveness only.
            case BYTES_REF -> DataType.KEYWORD;
        };
    }

    /**
     * Builds a {@link FusionNode.Logical} for an {@code AND}/{@code OR} expression, or {@code null} if either operand is
     * not a fusable comparison/logical/{@code NOT} subtree. Both operands must be either a nested {@code AND}/{@code OR},
     * a {@code NOT}, or a boolean-producing comparison over homogeneous-primitive <b>column</b> and/or
     * <b>literal-constant</b> inputs (S3.1 scope: no bare boolean-column operands, no arithmetic-under-comparison;
     * literal constants ARE allowed as comparison children — e.g. {@code a > 5 AND b < 10}).
     */
    private static FusionNode buildLogical(Expression exp, PlanContext ctx) {
        BinaryLogic bl = (BinaryLogic) exp;
        FusionNode.BoolOp op = exp instanceof And ? FusionNode.BoolOp.AND : FusionNode.BoolOp.OR;
        FusionNode left = buildLogicalOperand(bl.left(), ctx);
        if (left == null) {
            return null;
        }
        FusionNode right = buildLogicalOperand(bl.right(), ctx);
        if (right == null) {
            return null;
        }
        return new FusionNode.Logical(op, left, right);
    }

    /** A logical operand is either a nested {@code AND}/{@code OR}, a {@code NOT}, or a column-column comparison. */
    private static FusionNode buildLogicalOperand(Expression exp, PlanContext ctx) {
        if (exp instanceof And || exp instanceof Or) {
            return buildLogical(exp, ctx);
        }
        if (exp instanceof Not) {
            return buildNot(exp, ctx);
        }
        return buildComparisonOperand(exp, ctx);
    }

    /**
     * Builds a {@link FusionNode.Not} whose child is a fusable boolean subtree (comparison, AND/OR, or nested Not),
     * or {@code null} if the child is not fusable (in particular a bare boolean column).
     */
    private static FusionNode buildNot(Expression exp, PlanContext ctx) {
        FusionNode child = buildLogicalOperand(((Not) exp).field(), ctx);
        if (child == null) {
            return null;
        }
        return new FusionNode.Not(child);
    }

    /**
     * Builds a boolean-producing comparison {@link FusionNode.Kernel} whose children are column {@link Attribute}s of
     * the homogeneous input element and/or literal constants of that element (e.g. {@code a > b}, {@code a > 5}).
     * Returns {@code null} if {@code exp} is not such a comparison — in particular if it is overflow-checked
     * (comparisons never are, but the guard keeps the logical body free of any overflow try/catch), has a non-boolean
     * return, mixes element types, or has a child that is neither a matching column nor a matching-typed literal
     * (arithmetic children are still out of the S3.1 logical scope).
     */
    private static FusionNode buildComparisonOperand(Expression exp, PlanContext ctx) {
        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        if ((factory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) factory).fusionDescriptor();
        // As in build(): only fuse when the kernel is declared on exp's own class, so a DELEGATING function (whose
        // FusionAware evaluator is really some other expression's kernel) cannot mis-map its children here.
        if (descriptor.kernelClass() != exp.getClass()) {
            return null;
        }
        String kernelType = descriptor.kernelType();
        ElementKind argElement = homogeneousArgElement(kernelType);
        if (argElement == null || argElement == ElementKind.BYTES_REF) {
            // The 3VL logical emit path keys its comparison helpers off a primitive element and reads column operands
            // as primitives; a BytesRef-argument comparison (e.g. keyword Equals) is out of the logical scope this
            // slice (BytesRef fuses only through the single-expression block path), so refuse it here.
            return null;
        }
        int close = kernelType.indexOf(')');
        if (close < 0 || close + 2 != kernelType.length() || kernelType.charAt(close + 1) != 'Z') {
            // Must be a comparison: exactly one boolean ('Z') return char.
            return null;
        }
        if (descriptor.overflowChecked()) {
            return null;
        }
        // The logical emit path keys its comparison helpers off ONE element, so every comparison in a fused logical
        // tree must be over the same primitive (mixed-type comparisons under a logical root fall back to unfused).
        if (ctx.logicalElement == null) {
            ctx.logicalElement = argElement;
        } else if (ctx.logicalElement != argElement) {
            return null;
        }
        int argCount = primitiveArgCount(kernelType);
        List<Expression> children = exp.children();
        if (children.size() != argCount) {
            return null;
        }
        List<FusionNode> childNodes = new ArrayList<>(children.size());
        for (Expression child : children) {
            // A logical comparison operand's children are columns of the input element type OR literal constants (the
            // constant is threaded as a trailing argument into the comparison helper; column children still carry the
            // null/multi-value guard).
            if (child instanceof Attribute attr) {
                Layout.ChannelAndType channelAndType = ctx.layout.get(attr.id());
                if (channelAndType == null || matches(attr.dataType(), ctx.logicalElement) == false) {
                    return null;
                }
                int index = ctx.channels.size();
                ctx.channels.add(channelAndType.channel());
                ctx.inputElements.add(argElement);
                childNodes.add(new FusionNode.Input(index));
            } else if (child instanceof Literal literal) {
                FusionNode constant = buildConstant(literal, argElement, ctx);
                if (constant == null) {
                    return null;
                }
                childNodes.add(constant);
            } else {
                return null;
            }
        }
        FusionNode.Kernel comparison = new FusionNode.Kernel(descriptor, childNodes);
        // Capture the comparison's own source so its inputs' multi-value warnings are attributed to it (not the root).
        ctx.kernelSources.put(comparison, exp.source());
        return comparison;
    }

    /**
     * Builds a {@link FusionNode.Constant} at the {@code expected} element the consuming kernel requires, from a
     * numeric or keyword {@link Literal}, or {@code null} if it cannot be fused. Numeric literals are trailing
     * primitive arguments (with a supported widening into {@code expected}), unless {@code baked} is true — then the
     * primitive is a bytecode immediate (F1/H3: an INT/LONG Div/Mod divisor, never DOUBLE). A keyword/text literal
     * is a captured {@link FusionNode.Constant#reference reference} {@code BytesRef} (F3), loaded with {@code ALOAD}
     * through the D2 reference-constant machinery. A {@code null} literal is <b>refused</b>, so a subtree containing
     * one falls back to the unfused chain — the correct + simplest handling (ESQL normally folds null literals away,
     * and a per-position "constant is null" would just null every row, which the unfused evaluator already does).
     */
    private static FusionNode buildConstant(Literal literal, ElementKind expected, PlanContext ctx) {
        return buildConstant(literal, expected, ctx, false);
    }

    private static FusionNode buildConstant(Literal literal, ElementKind expected, PlanContext ctx, boolean baked) {
        if (expected == null || expected == ElementKind.BOOLEAN) {
            // A boolean-typed constant leaf is out of scope, so refuse to fuse it.
            return null;
        }
        Object value = literal.value();
        if (value == null) {
            return null;
        }
        if (expected == ElementKind.BYTES_REF) {
            if (baked) {
                // Baking is primitive-only (J/I/D); a keyword literal keeps the captured-reference path.
                return null;
            }
            if (value instanceof BytesRef bytesRef && DataType.isString(literal.dataType())) {
                // Keyword/text literal only: captured reference constant, same trailing-arg path as a converter
                // @Fixed. IP/VERSION/GEO BytesRef values must not hitch a ride on this path.
                return FusionNode.Constant.reference(bytesRef, BytesRef.class);
            }
            return null;
        }
        if (value instanceof Number == false) {
            return null;
        }
        ElementKind literalElement = elementOf(literal.dataType());
        if (literalElement == null && expected == ElementKind.LONG && isLongKernelDate(literal.dataType())) {
            // Comparison-only: the only caller that can reach here is buildComparisonOperand, which already required
            // a same-type Longs FusionAware descriptor (mixed DATETIME/DATE_NANOS is not FusionAware). Date column
            // leaves refuse via elementOf, so no arithmetic LONG arg ever receives a DATETIME/DATE_NANOS literal.
            // The literal value is epoch millis / nanos as Long — the same processLongs kernel the unfused path uses.
            literalElement = ElementKind.LONG;
        }
        if (literalElement == null || (literalElement != expected && supportedWidening(literalElement, expected) == false)) {
            return null;
        }
        // Convert to the expected element here — for a widening cast (int→long, int→double, long→double) this is exactly
        // the value the unfused Cast.cast(literal) would produce at runtime (e.g. (double) longValue, same precision).
        Number number = (Number) value;
        FusionNode constant = switch (expected) {
            case LONG -> baked ? FusionNode.Constant.baked(number.longValue(), 'J') : new FusionNode.Constant(number.longValue(), 'J');
            case INT -> baked ? FusionNode.Constant.baked(number.intValue(), 'I') : new FusionNode.Constant(number.intValue(), 'I');
            case DOUBLE -> baked
                ? FusionNode.Constant.baked(number.doubleValue(), 'D')
                : new FusionNode.Constant(number.doubleValue(), 'D');
            // Unreachable: BOOLEAN is rejected by the guard above; BYTES_REF is handled before this switch.
            case BOOLEAN, BYTES_REF -> null;
        };
        if (constant != null && baked) {
            ctx.hasBakedDivisor = true;
        }
        return constant;
    }

    /**
     * The {@code process*} (not {@code *ByConstant}) {@link FusionDescriptor} for a Div/Mod kernel at {@code element},
     * or {@code null} if the element is not a fusable primitive. Used when the unfused factory is the constant-RHS
     * {@code *ByConstant} evaluator, which is not {@link FusionAware}.
     */
    static FusionDescriptor bakedDivModDescriptor(Class<?> kernelClass, ElementKind element) {
        if (element == null) {
            return null;
        }
        // Canonical 10-arg matches the generated DivLongsEvaluator / ModLongsEvaluator FUSION_DESCRIPTOR
        // field-for-field: explicit ArithmeticException, not variadic, not mapping, warns=true (non-empty
        // exception type), dictionarySafe=true (the throw is caught as a warning).
        return switch (element) {
            case LONG -> new FusionDescriptor(
                kernelClass,
                "processLongs",
                "(JJ)J",
                true,
                true,
                "java.lang.ArithmeticException",
                false,
                false,
                true,
                true
            );
            case INT -> new FusionDescriptor(
                kernelClass,
                "processInts",
                "(II)I",
                true,
                true,
                "java.lang.ArithmeticException",
                false,
                false,
                true,
                true
            );
            case DOUBLE -> new FusionDescriptor(
                kernelClass,
                "processDoubles",
                "(DD)D",
                true,
                true,
                "java.lang.ArithmeticException",
                false,
                false,
                true,
                true
            );
            case BOOLEAN, BYTES_REF -> null;
        };
    }

    /**
     * Un-bakes Div/Mod divisors of {@code tree} whose values were not admitted under {@link #BAKED_DIVISOR_CAP} for
     * this tree's un-baked shape. Already-admitted values stay baked (reuse the existing class). Kernel identity in
     * {@code ctx.kernelSources} is remapped when a {@link FusionNode.Kernel} is rewritten. Trees with no baked
     * constant return unchanged without allocating a shape string.
     */
    private static FusionNode applyBakedDivisorCap(FusionNode tree, PlanContext ctx) {
        if (ctx.hasBakedDivisor == false) {
            return tree;
        }
        return rewriteOverCap(tree, unbakedShapeOf(tree), ctx);
    }

    private static FusionNode rewriteOverCap(FusionNode node, String unbakedShape, PlanContext ctx) {
        return switch (node) {
            case FusionNode.Constant constant -> {
                if (constant.isBaked() && tryAdmitBakedDivisor(unbakedShape, constant.value()) == false) {
                    yield new FusionNode.Constant(constant.value(), constant.element());
                }
                yield node;
            }
            case FusionNode.Input unused -> node;
            case FusionNode.Kernel kernel -> {
                List<FusionNode> children = kernel.children();
                List<FusionNode> rewritten = null;
                for (int i = 0; i < children.size(); i++) {
                    FusionNode child = rewriteOverCap(children.get(i), unbakedShape, ctx);
                    if (child != children.get(i)) {
                        if (rewritten == null) {
                            rewritten = new ArrayList<>(children);
                        }
                        rewritten.set(i, child);
                    }
                }
                if (rewritten == null) {
                    yield node;
                }
                FusionNode.Kernel replacement = new FusionNode.Kernel(kernel.descriptor(), rewritten);
                Source source = ctx.kernelSources.remove(kernel);
                if (source != null) {
                    ctx.kernelSources.put(replacement, source);
                }
                yield replacement;
            }
            case FusionNode.Logical logical -> {
                FusionNode left = rewriteOverCap(logical.left(), unbakedShape, ctx);
                FusionNode right = rewriteOverCap(logical.right(), unbakedShape, ctx);
                if (left == logical.left() && right == logical.right()) {
                    yield node;
                }
                yield new FusionNode.Logical(logical.op(), left, right);
            }
            case FusionNode.Not not -> {
                FusionNode child = rewriteOverCap(not.child(), unbakedShape, ctx);
                yield child == not.child() ? node : new FusionNode.Not(child);
            }
        };
    }

    /**
     * Admits {@code value} as a baked divisor of {@code unbakedShape}, or returns {@code false} so the planner emits
     * the runtime-parameter form instead. Already-admitted values return {@code true} (reuse the baked class). The
     * inner set is capped at {@link #BAKED_DIVISOR_CAP}; a brand-new un-baked shape is refused without insertion when
     * the outer map is already at {@link FusedClassCache#MAX_ENTRIES}.
     */
    static boolean tryAdmitBakedDivisor(String unbakedShape, Object value) {
        Set<Object> admitted = BAKED_DIVISORS_BY_UNBAKED_SHAPE.get(unbakedShape);
        if (admitted == null) {
            // Fail-safe bound on un-baked *shapes* (not cached classes); mixed-grain with MAX_ENTRIES, harmless.
            if (BAKED_DIVISORS_BY_UNBAKED_SHAPE.size() >= FusedClassCache.MAX_ENTRIES) {
                return false;
            }
            admitted = BAKED_DIVISORS_BY_UNBAKED_SHAPE.computeIfAbsent(unbakedShape, unused -> ConcurrentHashMap.newKeySet());
        }
        if (admitted.contains(value)) {
            return true;
        }
        synchronized (admitted) {
            if (admitted.contains(value)) {
                return true;
            }
            if (admitted.size() >= BAKED_DIVISOR_CAP) {
                return false;
            }
            admitted.add(value);
            return true;
        }
    }

    /** Test-only: drops every per-shape baked-divisor admission so a later test starts from a clean cap. */
    static void resetBakedDivisorsForTests() {
        BAKED_DIVISORS_BY_UNBAKED_SHAPE.clear();
    }

    /** JVM descriptor of the one supported reference argument/leaf element, {@code org.apache.lucene.util.BytesRef}. */
    private static final String BYTES_REF_DESC = "Lorg/apache/lucene/util/BytesRef;";

    /**
     * Parses a kernel descriptor's <b>argument</b> list {@code (..)?} into one {@link ElementKind} per argument, or
     * {@code null} if any argument is an unsupported type. Each argument is either a supported primitive
     * ({@code long}/{@code int}/{@code double}) or the reference {@code BytesRef} ({@link ElementKind#BYTES_REF}); any
     * other object/array type aborts the parse. The return kind is validated separately by {@link #build}.
     */
    private static List<ElementKind> argElements(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return null;
        }
        List<ElementKind> elements = new ArrayList<>();
        int i = 1;
        while (i < close) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0 || end >= close || descriptor.substring(i, end + 1).equals(BYTES_REF_DESC) == false) {
                    return null;
                }
                elements.add(ElementKind.BYTES_REF);
                i = end + 1;
            } else {
                ElementKind element = elementOf(c);
                if (element == null) {
                    // Not a supported primitive (also rejects an array '[' argument).
                    return null;
                }
                elements.add(element);
                i++;
            }
        }
        return elements;
    }

    /**
     * The uniform element kind of a kernel descriptor's <b>arguments</b> {@code (TT..)?}, or {@code null} if there are
     * no arguments or they are not all the same supported element (numeric {@code long}/{@code int}/{@code double}, or
     * the reference {@code BytesRef}). The return kind is validated separately by {@link #build}.
     */
    private static ElementKind homogeneousArgElement(String descriptor) {
        List<ElementKind> elements = argElements(descriptor);
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        ElementKind first = elements.get(0);
        for (ElementKind element : elements) {
            if (element != first) {
                return null;
            }
        }
        return first;
    }

    /** Number of arguments in a kernel descriptor (a primitive is one char; a {@code BytesRef} is an {@code L..;} run). */
    private static int primitiveArgCount(String descriptor) {
        List<ElementKind> elements = argElements(descriptor);
        return elements == null ? 0 : elements.size();
    }

    private static ElementKind elementOf(char primitive) {
        return switch (primitive) {
            case 'J' -> ElementKind.LONG;
            case 'I' -> ElementKind.INT;
            case 'D' -> ElementKind.DOUBLE;
            default -> null;
        };
    }

    /**
     * The supported element kind for a column/literal {@link DataType}, or {@code null} if not fusable. Numeric types
     * map to their primitive element; {@code KEYWORD}/{@code TEXT} map to {@link ElementKind#BYTES_REF} so a string
     * column can feed a {@code BytesRef}-consuming kernel (e.g. {@code LENGTH}) and so a root mapping convert
     * ({@code TO_LOWER}/{@code TO_UPPER}) can type its output {@code BYTES_REF}. The {@code Stitcher} block path
     * produces that BytesRef output (B3b / D2); nested mapping under a single-value parent (D3) keeps the root's
     * own output element (int / boolean).
     */
    private static ElementKind elementOf(DataType type) {
        if (type == DataType.LONG) {
            return ElementKind.LONG;
        }
        if (type == DataType.INTEGER) {
            return ElementKind.INT;
        }
        if (type == DataType.DOUBLE) {
            return ElementKind.DOUBLE;
        }
        if (type == DataType.KEYWORD || type == DataType.TEXT) {
            return ElementKind.BYTES_REF;
        }
        return null;
    }

    /** The output element for a return descriptor char: BOOLEAN for {@code 'Z'}, else the numeric {@link #elementOf}. */
    static ElementKind outputElementOf(char returnChar) {
        return returnChar == 'Z' ? ElementKind.BOOLEAN : elementOf(returnChar);
    }

    /**
     * True when a comparison operand's {@link DataType} can feed the fused kernel's {@code element}. {@code LONG}
     * also matches {@link DataType#DATETIME} and {@link DataType#DATE_NANOS}: same-type unfused comparisons of those
     * types bind {@code *LongsEvaluator} / {@code processLongs} (see {@code GreaterThanOrEqual} and siblings). Mixed
     * DATETIME vs DATE_NANOS uses non-{@link FusionAware} conversion evaluators, so {@link #buildComparisonOperand}
     * still refuses them. Arithmetic does not use {@code matches} — this mapping is comparison-only.
     */
    private static boolean matches(DataType type, ElementKind element) {
        return switch (element) {
            case LONG -> type == DataType.LONG || isLongKernelDate(type);
            case INT -> type == DataType.INTEGER;
            case DOUBLE -> type == DataType.DOUBLE;
            case BOOLEAN -> type == DataType.BOOLEAN;
            case BYTES_REF -> type == DataType.KEYWORD || type == DataType.TEXT;
        };
    }

    /**
     * Date types whose same-type unfused comparison kernels are the long kernels ({@code processLongs}). Stored as
     * epoch millis (DATETIME) or nanos (DATE_NANOS) in a {@code LongBlock}.
     */
    private static boolean isLongKernelDate(DataType type) {
        return type == DataType.DATETIME || type == DataType.DATE_NANOS;
    }

    /**
     * True when a mapping convert kernel sits in a non-root position (D3: {@code LENGTH(TO_LOWER(k))},
     * {@code TO_LOWER(k)=="x"}). The root itself being a mapping kernel (lone {@code TO_LOWER}) is not nested.
     */
    static boolean containsNestedMapping(FusionNode node) {
        if (node instanceof FusionNode.Kernel kernel) {
            for (FusionNode child : kernel.children()) {
                if (child instanceof FusionNode.Kernel childKernel && childKernel.descriptor().mapping()) {
                    return true;
                }
                if (containsNestedMapping(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof FusionNode.Logical logical) {
            return containsNestedMapping(logical.left()) || containsNestedMapping(logical.right());
        }
        if (node instanceof FusionNode.Not not) {
            return containsNestedMapping(not.child());
        }
        return false;
    }

    /**
     * True when the tree carries a BytesRef/{@code 'L'} {@link FusionNode.Constant}. FilterEvalEmitter cannot forward
     * an {@code 'L'} constant and {@code constantParamClass} rejects it, so these shapes stay on the unfused chain;
     * the single-expression path handles L constants.
     */
    private static boolean containsReferenceConstant(FusionNode node) {
        if (node instanceof FusionNode.Constant c && c.isReference()) {
            return true;
        }
        if (node instanceof FusionNode.Kernel kernel) {
            for (FusionNode child : kernel.children()) {
                if (containsReferenceConstant(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof FusionNode.Logical logical) {
            return containsReferenceConstant(logical.left()) || containsReferenceConstant(logical.right());
        }
        if (node instanceof FusionNode.Not not) {
            return containsReferenceConstant(not.child());
        }
        return false;
    }

    /**
     * Test hook for the plan-time encoding-aware flag. Reads only descriptor flags ({@code warns},
     * {@code dictionarySafe}) plus the single-{@code BYTES_REF}-column / single-leaf conditions.
     */
    static boolean encodingAwareForTests(FusionNode tree, ElementKind[] inputElements) {
        return encodingAware(tree, inputElements);
    }

    /**
     * Plan-time encoding-aware flag (D1): true when the tree has exactly one column input, that input is
     * {@code BYTES_REF}, every kernel is {@code !warns && dictionarySafe}, and there is a single input leaf.
     * Constants are allowed. Warning-capable or throwing kernels are excluded because evaluating a dictionary
     * can warn or throw for entries no row references.
     */
    private static boolean encodingAware(FusionNode tree, ElementKind[] inputElements) {
        if (inputElements.length != 1 || inputElements[0] != ElementKind.BYTES_REF) {
            return false;
        }
        return countInputLeaves(tree) == 1 && hasWarningKernel(tree) == false && dictionarySafeTree(tree);
    }

    private static int countInputLeaves(FusionNode node) {
        if (node instanceof FusionNode.Input) {
            return 1;
        }
        if (node instanceof FusionNode.Constant) {
            return 0;
        }
        if (node instanceof FusionNode.Kernel kernel) {
            int n = 0;
            for (FusionNode child : kernel.children()) {
                n += countInputLeaves(child);
            }
            return n;
        }
        if (node instanceof FusionNode.Logical logical) {
            return countInputLeaves(logical.left()) + countInputLeaves(logical.right());
        }
        if (node instanceof FusionNode.Not not) {
            return countInputLeaves(not.child());
        }
        throw new AssertionError("unexpected fusion node " + node);
    }

    /** True if any kernel declares {@code warnExceptions} ({@link FusionDescriptor#warns()}). */
    private static boolean hasWarningKernel(FusionNode node) {
        if (node instanceof FusionNode.Kernel kernel) {
            if (kernel.descriptor().warns()) {
                return true;
            }
            for (FusionNode child : kernel.children()) {
                if (hasWarningKernel(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof FusionNode.Logical logical) {
            return hasWarningKernel(logical.left()) || hasWarningKernel(logical.right());
        }
        if (node instanceof FusionNode.Not not) {
            return hasWarningKernel(not.child());
        }
        return false;
    }

    /**
     * True when every kernel in {@code node} is {@link FusionDescriptor#dictionarySafe()}. Hand-built CONCAT and
     * SPACE are not; generated kernels are (throws are declared warnExceptions, encoded in {@code warns}).
     */
    private static boolean dictionarySafeTree(FusionNode node) {
        if (node instanceof FusionNode.Kernel kernel) {
            if (kernel.descriptor().dictionarySafe() == false) {
                return false;
            }
            for (FusionNode child : kernel.children()) {
                if (dictionarySafeTree(child) == false) {
                    return false;
                }
            }
            return true;
        }
        if (node instanceof FusionNode.Logical logical) {
            return dictionarySafeTree(logical.left()) && dictionarySafeTree(logical.right());
        }
        if (node instanceof FusionNode.Not not) {
            return dictionarySafeTree(not.child());
        }
        return true;
    }

    /** Kernel depth: 0 for a bare input, 1 for a kernel/logical/Not of inputs, ≥2 once a node has a kernel/logical/Not child. */
    private static int depth(FusionNode node) {
        if (node instanceof FusionNode.Logical logical) {
            return 1 + Math.max(depth(logical.left()), depth(logical.right()));
        }
        if (node instanceof FusionNode.Not not) {
            return 1 + depth(not.child());
        }
        if (node instanceof FusionNode.Kernel kernel) {
            int max = 0;
            for (FusionNode child : kernel.children()) {
                max = Math.max(max, depth(child));
            }
            return 1 + max;
        }
        return 0;
    }

    /**
     * A stable, column-independent signature of the fused shape (operator tree + kernel set). It is used both as the
     * {@link FusedClassCache} key and the {@link FusionTelemetry} failure key. Two expressions with the same operators
     * in the same arrangement share a shape regardless of which columns they read.
     *
     * <p><b>Correctness contract (iter-15 review).</b> Because this signature keys the {@link FusedClassCache} — i.e. it
     * decides which generated bytecode class is reused — each kernel node is encoded by the <b>full identity that
     * determines its stitched bytecode</b>, not a friendly short name: the kernel class {@linkplain Class#getName()
     * fully-qualified name} (not the simple name — two distinct classes can share a simple name and would otherwise
     * collide onto one cached class), the kernel method name, the kernel's JVM type descriptor
     * ({@link FusionDescriptor#kernelType()}), and the {@link FusionDescriptor#overflowExceptionType() overflow
     * exception type} (which changes the emitted try/catch). Any two nodes that would stitch to different bytecode are
     * therefore guaranteed different signatures. Leaves stay {@code #} so value bindings / column identities are
     * excluded; {@code element} and {@code Path} live in the {@link FusedClassCache.Shape}, not here.
     */
    static String shapeOf(FusionNode node) {
        StringBuilder sb = new StringBuilder();
        appendShape(node, sb, false);
        return sb.toString();
    }

    /**
     * {@link #shapeOf} with baked constant values stripped ({@code #C:J:1000} → {@code #C:J}). This is the unit the
     * H3 cap keys on: all baked values of one operator tree share this string, so the cap bounds classes per
     * un-baked shape rather than globally.
     */
    static String unbakedShapeOf(FusionNode node) {
        StringBuilder sb = new StringBuilder();
        appendShape(node, sb, true);
        return sb.toString();
    }

    private static void appendShape(FusionNode node, StringBuilder sb, boolean stripBakedValue) {
        if (node instanceof FusionNode.Logical logical) {
            // A Logical carries no descriptor; its bytecode is fully determined by the op + operand shapes.
            sb.append("LOGICAL:").append(logical.op()).append('(');
            appendShape(logical.left(), sb, stripBakedValue);
            sb.append(',');
            appendShape(logical.right(), sb, stripBakedValue);
            sb.append(')');
            return;
        }
        if (node instanceof FusionNode.Not not) {
            sb.append("NOT(");
            appendShape(not.child(), sb, stripBakedValue);
            sb.append(')');
            return;
        }
        if (node instanceof FusionNode.Kernel kernel) {
            FusionDescriptor descriptor = kernel.descriptor();
            sb.append(descriptor.kernelClass().getName())
                .append('#')
                .append(descriptor.kernelMethod())
                .append(descriptor.kernelType())
                .append('^')
                .append(descriptor.overflowExceptionType())
                .append('(');
            List<FusionNode> children = kernel.children();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendShape(children.get(i), sb, stripBakedValue);
            }
            sb.append(')');
        } else if (node instanceof FusionNode.Constant constant) {
            // A non-baked constant's VALUE is a trailing method argument, NOT baked into the bytecode, so it is
            // deliberately EXCLUDED from the cache key: `a > 5` and `a > 6` stitch to the SAME class and differ only in
            // the argument supplied at eval time. Only the element-typed slot marker (`#C:<element>`) participates, so
            // a long constant and a double constant still key distinct classes (their emit paths differ), while all
            // values of one element share one hidden class — collapsing the former one-class-per-value cardinality.
            // A baked constant (F1/H3: an INT/LONG Div/Mod divisor, capped per un-baked shape) IS an immediate, so its
            // value participates (`#C:J:1000`) unless {@code stripBakedValue} (the H3 cap key). (An Input leaf stays
            // `#` — its column binding is likewise not in the bytecode.)
            sb.append("#C:").append(constant.element());
            if (constant.isBaked() && stripBakedValue == false) {
                sb.append(':').append(constant.value());
            }
        } else {
            // FusionNode.Input: rendered as a bare '#' so column identities are excluded from the shape.
            sb.append('#');
        }
    }
}
