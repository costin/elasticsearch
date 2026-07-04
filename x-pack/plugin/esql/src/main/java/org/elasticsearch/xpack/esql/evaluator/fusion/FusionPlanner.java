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
import org.elasticsearch.compute.operator.FilterEvalEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.FusionSignature;
import org.elasticsearch.compute.operator.fusion.FusionSimd;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cast;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ChangeCase;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.BinaryLogic;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EsqlBinaryComparison;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>optionally a boolean {@code AND}/{@code OR} 3VL {@link FusionNode.Logical} root whose operands are
 *       column/constant comparisons over one homogeneous numeric element (mixed-type comparisons under a logical root
 *       fall back to the unfused chain);</li>
 *   <li>the kernel depth is &ge; 2 (a kernel whose child is itself a kernel — a cast counts). A lone kernel gains nothing
 *       from fusion, so we leave it to the generated evaluator.</li>
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

    /**
     * The stitch entry points the planner uses. Extracted behind an interface so tests (in this package) can inject a
     * failing implementation to exercise the criterion-#5 fallback + telemetry without needing to craft bytecode that
     * fails JVM verification.
     */
    interface FusedClassCompiler {
        Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;

        /**
         * Compiles the fused WHERE-predicate + EVAL-projection block loop (S3.2a). Defaulted so existing
         * {@link FusedClassCompiler} test doubles (which only exercise the single-expression paths) need not override it;
         * the production {@link FusionCompilationService} compiler and any filter-eval-specific test double provide a real implementation.
         */
        default Class<?> compileFilterEvalBlockLoop(MethodHandles.Lookup caller, FusionNode predicateTree, FusionNode projectionTree)
            throws Stitcher.StitchingException {
            throw new UnsupportedOperationException("compileFilterEvalBlockLoop not implemented by this compiler");
        }

        /**
         * Compiles the opt-in SIMD (Vector-API) variant of the plain-vector path for a boolean comparison (B2), where
         * {@code comparison} is the {@code VectorOperators.Comparison} constant name. Defaulted so existing test doubles
         * need not implement it; {@link FusionCompilationService} provides the real, cache-backed implementation.
         */
        default Class<?> compileVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison)
            throws Stitcher.StitchingException {
            throw new UnsupportedOperationException("compileVectorLoopSimd not implemented by this compiler");
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
            // the Stitcher is never touched.
            return null;
        }
        if (FusionSettings.shouldAttemptFusion() == false) {
            // A/B sampling (Stage 6): this subtree was not drawn into the fusion sample, so run it unfused. Returning
            // null keeps the fraction genuinely additive — the caller uses the ordinary unfused factory tree.
            return null;
        }
        PlanContext ctx = new PlanContext(layout, rawFactory);
        FusionNode tree = build(exp, ctx, null, null, true);
        if (tree == null || ctx.outputElement == null || ctx.inputElements.isEmpty()) {
            return null;
        }
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
        // A SIMD-eligible boolean comparison is a single (depth-1) comparison over two columns — normally "not worth
        // fusing" on the scalar paths, but the Vector-API lane compare DOES beat the generated evaluator, so it is
        // worth fusing when SIMD is opted in AND available in this runtime. Everything else still requires depth >= 2.
        String vectorComparison = simdComparison(exp, tree, inputElements, ctx.outputElement, signature.constantsInEmitOrder());
        boolean simdWorthwhile = vectorComparison.isEmpty() == false && FusionSettings.isSimdEnabled() && FusionSimd.available();
        if (depth(tree) < 2 && simdWorthwhile == false) {
            // A lone kernel is not worth fusing on the scalar paths — leave it to the generated evaluator.
            return null;
        }
        ExpressionEvaluator.Factory unfused = rawFactory.apply(exp);
        FusedClassCompiler compiler = compilerForTests != null ? compilerForTests : COMPILATION.compiler();
        String shape = shapeOf(tree);
        // One typed carrier bundles everything the factory needs (the signature is the single source for the constant
        // emit order + warning-source slots); the esql-side bindings the tree can't carry travel alongside it.
        FusionPlan plan = new FusionPlan(
            signature,
            tree,
            channels,
            inputElements,
            ctx.outputElement,
            ctx.overflowChecked,
            warningSources(signature.warningSourceIndices(), ctx),
            shape,
            vectorComparison
        );

        long minRows = FusionSettings.adaptiveMinRows();
        if (minRows > 0) {
            // Adaptive path (Stage 6): defer the stitch. Serve the unfused chain until an evaluator has seen minRows
            // rows, then stitch once (memoised) and switch. The supplier returns the fused factory or null on failure.
            return new AdaptiveFusionEvaluatorFactory(
                unfused,
                () -> FusedExpressionEvaluatorFactory.tryCreateFusedOrNull(plan, unfused, compiler),
                minRows,
                shape
            );
        }
        // Eager path (default, minRows == 0): stitch now at plan time, falling back to the unfused factory on failure.
        return FusedExpressionEvaluatorFactory.tryCreate(plan, unfused, compiler);
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
     * must produce a boolean (a comparison {@link FusionNode.Kernel} or a 3VL {@link FusionNode.Logical}), and the
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
        // Build the predicate tree: must be a boolean-producing comparison/logical subtree.
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

    /**
     * The {@code jdk.incubator.vector.VectorOperators.Comparison} constant name for a SIMD-eligible boolean comparison
     * (B2), or {@code ""} when the tree is not eligible. Eligible iff the root {@code Expression} is an
     * {@link EsqlBinaryComparison} and the built tree is a single comparison kernel over exactly two <b>column</b>
     * operands of the same {@code int}/{@code long} element (double is deferred pending NaN/-0.0 parity coverage), with
     * a boolean output and no embedded constants — the narrow shape the Vector-API lane compare actually accelerates.
     */
    private static String simdComparison(
        Expression exp,
        FusionNode tree,
        ElementKind[] inputElements,
        ElementKind outputElement,
        List<FusionNode.Constant> constants
    ) {
        if (outputElement != ElementKind.BOOLEAN
            || constants.isEmpty() == false
            || inputElements.length != 2
            || inputElements[0] != inputElements[1]
            || (inputElements[0] != ElementKind.INT && inputElements[0] != ElementKind.LONG)
            || exp instanceof EsqlBinaryComparison == false
            || tree instanceof FusionNode.Kernel == false) {
            return "";
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        if (root.children().size() != 2
            || root.children().get(0) instanceof FusionNode.Input == false
            || root.children().get(1) instanceof FusionNode.Input == false) {
            return "";
        }
        return switch (((EsqlBinaryComparison) exp).getFunctionType()) {
            case EQ -> "EQ";
            case NEQ -> "NE";
            case LT -> "LT";
            case LTE -> "LE";
            case GT -> "GT";
            case GTE -> "GE";
        };
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
         * Homogeneous element of a <b>logical</b> ({@code AND}/{@code OR}) tree's comparison operands. The logical
         * emit path keys its comparison helpers off one element, so within a fused logical tree every comparison must
         * be over the same primitive (mixed-type comparisons under a logical root fall back to the unfused chain).
         * Unused (stays {@code null}) for the arithmetic/comparison {@link #build} path, which is per-node typed.
         */
        private ElementKind logicalElement;
        /** The root node's output kind: the numeric element for an arithmetic root, or BOOLEAN for a comparison/logical root. */
        private ElementKind outputElement;
        private boolean overflowChecked;

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
        if (exp instanceof ChangeCase changeCase) {
            // TO_LOWER/TO_UPPER (B3b): a BytesRef -> BytesRef @ConvertEvaluator kernel with two object @Fixed operands
            // (the query Locale and the UPPER/LOWER Case). It is not FusionAware (convert-evaluators emit no descriptor),
            // so it is recognised explicitly here and its Locale/Case captured as reference constants.
            return buildChangeCase(changeCase, ctx, expected, root);
        }
        if (exp instanceof Left left) {
            // LEFT(str, length) (#8b): a BytesRef -> BytesRef kernel whose first two @Fixed params are per-driver SCRATCH
            // buffers (a reusable BytesRef + a UTF8CodePoint), created fresh per driver rather than captured. Its body
            // loops over code points and calls only public methods, so it links from the fused hidden class's package.
            return buildLeft(left, ctx, expected, root);
        }

        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        if ((factory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) factory).fusionDescriptor();
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
        for (Expression child : children) {
            // Each child must output this kernel's argument element; a mismatch is bridged by a widening cast inside
            // the child build, attributed to THIS kernel's source (as Cast.cast(source(), ...) does when unfused).
            FusionNode childNode = build(child, ctx, argElement, exp.source(), false);
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
     * The manually-built kernel descriptor for {@code ChangeCase#process(BytesRef, @Fixed Locale, @Fixed Case)}. Convert-
     * evaluators emit no {@code FUSION_DESCRIPTOR}, so this names the real static method (the stitcher reads its bytecode
     * off {@code ChangeCase.class} and splices the straight-line body). The {@code $Case} inner-class descriptor is
     * written explicitly (a naive {@code '.'->'/'} would wrongly produce {@code ChangeCase.Case}). Not overflow-checked;
     * an all-null input yields null (matching the unfused convert evaluator).
     */
    private static final FusionDescriptor CHANGE_CASE_DESCRIPTOR = new FusionDescriptor(
        ChangeCase.class,
        "process",
        "(Lorg/apache/lucene/util/BytesRef;Ljava/util/Locale;"
            + "Lorg/elasticsearch/xpack/esql/expression/function/scalar/string/ChangeCase$Case;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true
    );

    /**
     * Builds the fused node for a {@code TO_LOWER}/{@code TO_UPPER} ({@link ChangeCase}) expression: a single kernel over
     * its {@code BytesRef} field child plus two object {@code @Fixed} reference constants — the query {@link Locale} and
     * the {@code UPPER}/{@code LOWER} {@link ChangeCase.Case} — captured off the expression at plan time. It produces a
     * {@code BytesRef} (block-path only). Refuses (returns {@code null}) unless it is the root or its parent expects a
     * {@code BytesRef}, or if the field child does not itself fuse to a {@code BytesRef} producer.
     */
    private static FusionNode buildChangeCase(ChangeCase changeCase, PlanContext ctx, ElementKind expected, boolean root) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        FusionNode field = build(changeCase.field(), ctx, ElementKind.BYTES_REF, changeCase.source(), false);
        if (field == null) {
            return null;
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(
            CHANGE_CASE_DESCRIPTOR,
            List.of(
                field,
                FusionNode.Constant.reference(changeCase.configuration().locale(), Locale.class),
                FusionNode.Constant.reference(changeCase.caseType(), ChangeCase.Case.class)
            )
        );
        ctx.kernelSources.put(kernel, changeCase.source());
        if (root) {
            ctx.outputElement = ElementKind.BYTES_REF;
        }
        return kernel;
    }

    /**
     * The manually-built kernel descriptor for {@code Left#process(@Fixed BytesRef out, @Fixed UTF8CodePoint cp,
     * BytesRef str, int length)}. The two leading {@code @Fixed} params are per-driver scratch buffers; the {@code $}
     * inner-class descriptor for {@code UnicodeUtil.UTF8CodePoint} is written explicitly. Not overflow-checked; a null
     * input yields null (the block emitter's present flag propagates it via the str/length operands).
     */
    private static final FusionDescriptor LEFT_DESCRIPTOR = new FusionDescriptor(
        Left.class,
        "process",
        "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/UnicodeUtil$UTF8CodePoint;"
            + "Lorg/apache/lucene/util/BytesRef;I)Lorg/apache/lucene/util/BytesRef;",
        false,
        true
    );

    /**
     * Builds the fused node for a {@code LEFT(str, length)} ({@link Left}) expression (#8b): a kernel over its BytesRef
     * {@code str} + int {@code length} children, preceded by two per-driver SCRATCH reference operands — a reusable
     * {@link BytesRef} and a {@link UnicodeUtil.UTF8CodePoint}, built fresh per driver by the same suppliers
     * {@code Left#toEvaluator} uses. Produces a {@code BytesRef} (block-path only). Refuses unless it is the root or its
     * parent expects a {@code BytesRef}, or if either child does not fuse.
     */
    private static FusionNode buildLeft(Left left, PlanContext ctx, ElementKind expected, boolean root) {
        if (root == false && expected != ElementKind.BYTES_REF) {
            return null;
        }
        FusionNode str = build(left.children().get(0), ctx, ElementKind.BYTES_REF, left.source(), false);
        if (str == null) {
            return null;
        }
        FusionNode length = build(left.children().get(1), ctx, ElementKind.INT, left.source(), false);
        if (length == null) {
            return null;
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(
            LEFT_DESCRIPTOR,
            List.of(
                FusionNode.Constant.scratch(context -> new BytesRef(), BytesRef.class),
                FusionNode.Constant.scratch(context -> new UnicodeUtil.UTF8CodePoint(), UnicodeUtil.UTF8CodePoint.class),
                str,
                length
            )
        );
        ctx.kernelSources.put(kernel, left.source());
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
     * not a fusable comparison/logical subtree. Both operands must be either a nested {@code AND}/{@code OR} or a
     * boolean-producing comparison over homogeneous-primitive <b>column</b> and/or <b>literal-constant</b> inputs (S3.1
     * scope: no bare boolean-column operands, no arithmetic-under-comparison; literal constants ARE allowed as
     * comparison children — e.g. {@code a > 5 AND b < 10}).
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

    /** A logical operand is either a nested {@code AND}/{@code OR} or a column-column comparison. */
    private static FusionNode buildLogicalOperand(Expression exp, PlanContext ctx) {
        if (exp instanceof And || exp instanceof Or) {
            return buildLogical(exp, ctx);
        }
        return buildComparisonOperand(exp, ctx);
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
     * Builds a {@link FusionNode.Constant} at the {@code expected} element the consuming kernel requires (its value
     * threaded to the fused method as a trailing primitive argument), from a numeric {@link Literal}, or {@code null}
     * if it cannot be fused: the consuming element must be numeric, the
     * literal's declared type must be that element or a supported widening into it (so converting the value here is
     * exact-for-the-cast the unfused chain would apply), and the value must be non-{@code null}. A {@code null} literal
     * is <b>refused</b>, so a subtree containing one falls back to the unfused chain — the correct + simplest handling
     * (ESQL normally folds null literals away, and a per-position "constant is null" would just null every row, which
     * the unfused evaluator already does).
     */
    private static FusionNode buildConstant(Literal literal, ElementKind expected, PlanContext ctx) {
        if (expected == null || expected == ElementKind.BOOLEAN || expected == ElementKind.BYTES_REF) {
            // Only numeric constants are threaded as trailing primitive arguments. A boolean- or BytesRef-typed
            // constant leaf is out of scope (no BytesRef constant marshalling this slice), so refuse to fuse it.
            return null;
        }
        Object value = literal.value();
        if (value == null || value instanceof Number == false) {
            return null;
        }
        ElementKind literalElement = elementOf(literal.dataType());
        if (literalElement == null || (literalElement != expected && supportedWidening(literalElement, expected) == false)) {
            return null;
        }
        // Convert to the expected element here — for a widening cast (int→long, int→double, long→double) this is exactly
        // the value the unfused Cast.cast(literal) would produce at runtime (e.g. (double) longValue, same precision).
        Number number = (Number) value;
        return switch (expected) {
            case LONG -> new FusionNode.Constant(number.longValue(), 'J');
            case INT -> new FusionNode.Constant(number.intValue(), 'I');
            case DOUBLE -> new FusionNode.Constant(number.doubleValue(), 'D');
            // Unreachable: BOOLEAN/BYTES_REF constant leaves are rejected by the guard above; listed for exhaustiveness.
            case BOOLEAN, BYTES_REF -> null;
        };
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
     * column can feed a {@code BytesRef}-consuming kernel (e.g. {@code LENGTH}). {@code BYTES_REF} is INPUT-only <b>to the
     * planner</b>: the {@code Stitcher} block path can now also produce a {@code BytesRef} output (B3b), but this planner
     * does not yet type a node's output {@code BYTES_REF} (no {@code BytesRef}-returning kernel is {@code @Fusable} +
     * object-{@code @Fixed}-constant support is still needed), so it never emits a BytesRef-output tree today.
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

    private static boolean matches(DataType type, ElementKind element) {
        return switch (element) {
            case LONG -> type == DataType.LONG;
            case INT -> type == DataType.INTEGER;
            case DOUBLE -> type == DataType.DOUBLE;
            case BOOLEAN -> type == DataType.BOOLEAN;
            case BYTES_REF -> type == DataType.KEYWORD || type == DataType.TEXT;
        };
    }

    /** Kernel depth: 0 for a bare input, 1 for a kernel/logical of inputs, ≥2 once a node has a kernel/logical child. */
    private static int depth(FusionNode node) {
        if (node instanceof FusionNode.Logical logical) {
            return 1 + Math.max(depth(logical.left()), depth(logical.right()));
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
        appendShape(node, sb);
        return sb.toString();
    }

    private static void appendShape(FusionNode node, StringBuilder sb) {
        if (node instanceof FusionNode.Logical logical) {
            // A Logical carries no descriptor; its bytecode is fully determined by the op + operand shapes.
            sb.append("LOGICAL:").append(logical.op()).append('(');
            appendShape(logical.left(), sb);
            sb.append(',');
            appendShape(logical.right(), sb);
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
                appendShape(children.get(i), sb);
            }
            sb.append(')');
        } else if (node instanceof FusionNode.Constant constant) {
            // The constant's VALUE is now passed as a trailing method argument, NOT baked into the bytecode, so it is
            // deliberately EXCLUDED from the cache key: `a > 5` and `a > 6` stitch to the SAME class and differ only in
            // the argument supplied at eval time. Only the element-typed slot marker (`#C:<element>`) participates, so
            // a long constant and a double constant still key distinct classes (their emit paths differ), while all
            // values of one element share one hidden class — collapsing the former one-class-per-value cardinality.
            // (An Input leaf, by contrast, stays `#` — its column binding is likewise not in the bytecode.)
            sb.append("#C:").append(constant.element());
        } else {
            // FusionNode.Input: rendered as a bare '#' so column identities are excluded from the shape.
            sb.append('#');
        }
    }
}
