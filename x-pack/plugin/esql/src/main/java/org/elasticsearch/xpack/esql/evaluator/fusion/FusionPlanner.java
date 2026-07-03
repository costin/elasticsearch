/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Cast;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.BinaryLogic;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
 *       {@link Literal} embedded as a bytecode constant at the consuming kernel's element (it reads no channel and does
 *       not widen the tree's arity);</li>
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

    /** Shared stitcher; the {@link TemplateRegistry} cache and the stitcher itself are thread-safe. */
    private static final Stitcher DEFAULT_STITCHER = new Stitcher(new TemplateRegistry());

    /**
     * Process-wide cache of stitched hidden classes keyed by expression {@linkplain FusedClassCache.Shape shape}. The
     * {@link #DEFAULT_COMPILER} consults it before invoking the {@link Stitcher}, so repeated identical query shapes
     * reuse one hidden class. Test-injected compilers ({@link #compilerForTests}) bypass it, so the raw stitch/fallback
     * seams stay uncached and order-independent across tests.
     */
    private static final FusedClassCache CLASS_CACHE = new FusedClassCache();

    /** Package-private accessor so tests in this package can assert cache reuse and reset counters. */
    static FusedClassCache classCache() {
        return CLASS_CACHE;
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
    }

    /**
     * Production compiler: looks the shape up in {@link #CLASS_CACHE} first and only invokes the shared {@link Stitcher}
     * on a miss (or after a cleared reference). The caller {@link MethodHandles.Lookup} is excluded from the cache key
     * because production always uses the single {@link FusedExpressionEvaluatorFactory} module lookup; caching by shape
     * alone is therefore safe here.
     */
    private static final FusedClassCompiler DEFAULT_COMPILER = new FusedClassCompiler() {
        @Override
        public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return CLASS_CACHE.get(shapeKey(tree, FusedClassCache.Path.BLOCK), () -> DEFAULT_STITCHER.compileBlockLoop(caller, tree));
        }

        @Override
        public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return CLASS_CACHE.get(
                shapeKey(tree, FusedClassCache.Path.PLAIN_VECTOR),
                () -> DEFAULT_STITCHER.compileVectorLoop(caller, tree)
            );
        }

        @Override
        public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return CLASS_CACHE.get(
                shapeKey(tree, FusedClassCache.Path.CHECKED_VECTOR),
                () -> DEFAULT_STITCHER.compileVectorLoopChecked(caller, tree)
            );
        }

        @Override
        public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return CLASS_CACHE.get(
                shapeKey(tree, FusedClassCache.Path.LOGICAL_BLOCK),
                () -> DEFAULT_STITCHER.compileLogicalBlockLoop(caller, tree)
            );
        }
    };

    /** Builds the binding-independent cache key for {@code tree} on the given emit {@code path}. */
    private static FusedClassCache.Shape shapeKey(FusionNode tree, FusedClassCache.Path path) {
        return new FusedClassCache.Shape(shapeOf(tree), rootElement(tree), path);
    }

    /**
     * The OUTPUT element kind of a (depth &ge; 2) fused tree, read from its root kernel's return type. For an
     * arithmetic root this is the homogeneous numeric element; for a comparison root it is {@link ElementKind#BOOLEAN}.
     * Part of the {@link FusedClassCache} key (the numeric input element is already implied by the kernel descriptors
     * encoded in {@link #shapeOf}).
     */
    private static ElementKind rootElement(FusionNode tree) {
        if (tree instanceof FusionNode.Logical) {
            // A 3VL AND/OR tree always produces a nullable boolean.
            return ElementKind.BOOLEAN;
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        String descriptor = root.descriptor().kernelType();
        return outputElementOf(descriptor.charAt(descriptor.indexOf(')') + 1));
    }

    /**
     * Test-only override for {@link #DEFAULT_COMPILER}. When non-null, {@link #maybeFuse} stitches through it, letting
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
        if (depth(tree) < 2) {
            // A lone kernel is not worth fusing — leave it to the generated evaluator.
            return null;
        }
        int[] channels = new int[ctx.channels.size()];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = ctx.channels.get(i);
        }
        ElementKind[] inputElements = ctx.inputElements.toArray(new ElementKind[0]);
        // The build walk appends one element per leaf input as it assigns channels, so every index in [0, arity) is
        // populated densely and exactly once. (The Stitcher's inputElements() fills any gap defensively, but a
        // planner-produced tree never has one — that fallback is dead code for this path, asserted here.)
        assert noNullElements(inputElements)
            : "planner must populate every input element densely; got " + java.util.Arrays.toString(inputElements);
        ExpressionEvaluator.Factory unfused = rawFactory.apply(exp);
        FusedClassCompiler compiler = compilerForTests != null ? compilerForTests : DEFAULT_COMPILER;
        Source[] warningSources = warningSources(tree, ctx);
        ElementKind outputElement = ctx.outputElement;
        boolean overflowChecked = ctx.overflowChecked;
        String shape = shapeOf(tree);

        long minRows = FusionSettings.adaptiveMinRows();
        if (minRows > 0) {
            // Adaptive path (Stage 6): defer the stitch. Serve the unfused chain until an evaluator has seen minRows
            // rows, then stitch once (memoised) and switch. The supplier returns the fused factory or null on failure.
            return new AdaptiveFusionEvaluatorFactory(
                unfused,
                () -> FusedExpressionEvaluatorFactory.tryCreateFusedOrNull(
                    warningSources,
                    tree,
                    channels,
                    inputElements,
                    outputElement,
                    overflowChecked,
                    unfused,
                    compiler,
                    shape
                ),
                minRows,
                shape
            );
        }
        // Eager path (default, minRows == 0): stitch now at plan time, falling back to the unfused factory on failure.
        return FusedExpressionEvaluatorFactory.tryCreate(
            warningSources,
            tree,
            channels,
            inputElements,
            outputElement,
            overflowChecked,
            unfused,
            compiler,
            shape
        );
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
    private static Source[] warningSources(FusionNode tree, PlanContext ctx) {
        Map<FusionNode, Integer> slots = Stitcher.warningsSourceIndices(tree);
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
            // A literal operand embeds as a bytecode constant (no page channel, no input vector), directly at the
            // element the consuming kernel expects (matching Cast.cast of the literal to the operator's commonType).
            return buildConstant(literal, expected, ctx);
        }

        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        if ((factory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) factory).fusionDescriptor();
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
            case INT, BOOLEAN -> false;
        };
    }

    /** The {@link DataType} for a numeric element kind (BOOLEAN has no cast source/target in scope). */
    private static DataType dataTypeOf(ElementKind element) {
        return switch (element) {
            case LONG -> DataType.LONG;
            case INT -> DataType.INTEGER;
            case DOUBLE -> DataType.DOUBLE;
            case BOOLEAN -> DataType.BOOLEAN;
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
        String kernelType = descriptor.kernelType();
        ElementKind argElement = homogeneousArgElement(kernelType);
        if (argElement == null) {
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
            // constant is embedded in the comparison helper; column children still carry the null/multi-value guard).
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
     * Builds a {@link FusionNode.Constant} embedded at the {@code expected} element the consuming kernel requires, from
     * a numeric {@link Literal}, or {@code null} if it cannot be fused: the consuming element must be numeric, the
     * literal's declared type must be that element or a supported widening into it (so converting the value here is
     * exact-for-the-cast the unfused chain would apply), and the value must be non-{@code null}. A {@code null} literal
     * is <b>refused</b>, so a subtree containing one falls back to the unfused chain — the correct + simplest handling
     * (ESQL normally folds null literals away, and a per-position "constant is null" would just null every row, which
     * the unfused evaluator already does).
     */
    private static FusionNode buildConstant(Literal literal, ElementKind expected, PlanContext ctx) {
        if (expected == null || expected == ElementKind.BOOLEAN) {
            // The consuming kernel's argument is always numeric; a boolean-typed constant leaf never arises here.
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
            case BOOLEAN -> null;
        };
    }

    /**
     * The uniform primitive element kind of a kernel descriptor's <b>arguments</b> {@code (TT..)?}, or {@code null} if
     * there are no arguments, they are not all the same {@code long}/{@code int}/{@code double}, or any is an
     * object/array type. The return kind is validated separately by {@link #build}.
     */
    private static ElementKind homogeneousArgElement(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 2) {
            // "()..." — a no-argument kernel is not fusable here.
            return null;
        }
        char first = descriptor.charAt(1);
        ElementKind element = elementOf(first);
        if (element == null) {
            // Not a supported primitive (also rejects an 'L'/'[' object/array first argument).
            return null;
        }
        for (int i = 2; i < close; i++) {
            if (descriptor.charAt(i) != first) {
                return null;
            }
        }
        return element;
    }

    /** Number of arguments in a homogeneous single-char-primitive descriptor (each arg is one char). */
    private static int primitiveArgCount(String descriptor) {
        int close = descriptor.indexOf(')');
        return close - 1;
    }

    private static ElementKind elementOf(char primitive) {
        return switch (primitive) {
            case 'J' -> ElementKind.LONG;
            case 'I' -> ElementKind.INT;
            case 'D' -> ElementKind.DOUBLE;
            default -> null;
        };
    }

    /** The supported numeric element kind for a column/literal {@link DataType}, or {@code null} if not fusable. */
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
        return null;
    }

    /** The output element for a return descriptor char: BOOLEAN for {@code 'Z'}, else the numeric {@link #elementOf}. */
    private static ElementKind outputElementOf(char returnChar) {
        return returnChar == 'Z' ? ElementKind.BOOLEAN : elementOf(returnChar);
    }

    private static boolean matches(DataType type, ElementKind element) {
        return switch (element) {
            case LONG -> type == DataType.LONG;
            case INT -> type == DataType.INTEGER;
            case DOUBLE -> type == DataType.DOUBLE;
            case BOOLEAN -> type == DataType.BOOLEAN;
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
            // The constant's VALUE is baked into the emitted bytecode, so it MUST be part of the cache key: `a > 5`
            // and `a > 6` stitch to distinct classes. (An Input leaf, by contrast, stays `#` — its column binding is
            // NOT in the bytecode.) Cardinality implication: each distinct literal value in a fusable shape produces
            // its own hidden class, so a query family that varies a threshold over many values will stitch (and cache)
            // one class per value; this is bounded by the FusedClassCache's soft-reference eviction like any other shape.
            sb.append("#C:").append(constant.element()).append(':').append(constant.value());
        } else {
            // FusionNode.Input: rendered as a bare '#' so column identities are excluded from the shape.
            sb.append('#');
        }
    }
}
