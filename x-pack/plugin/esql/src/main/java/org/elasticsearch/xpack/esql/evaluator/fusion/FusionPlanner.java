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
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Plan-time entry point that decides whether an expression subtree can be evaluated by a single stitched per-position
 * body (iter 13's "wire fusion into evaluator construction"). It walks the <b>expression</b> tree — not the produced
 * factory tree, whose child factories are private — and, when the whole subtree is fusable, builds the
 * {@link FusionNode} the {@link Stitcher} consumes and returns a {@link FusedExpressionEvaluatorFactory}. Otherwise it
 * returns {@code null} and {@link org.elasticsearch.xpack.esql.evaluator.EvalMapper} produces the usual unfused
 * factory unchanged (fusion is strictly additive).
 *
 * <h2>Eligibility</h2>
 * A subtree rooted at {@code exp} is fusable iff:
 * <ul>
 *   <li>every internal node maps to a {@link FusionAware} generated factory (i.e. its kernel {@code process} method is
 *       {@code @Fusable}) whose kernel signature is a <b>homogeneous primitive</b> {@code (TT..)T} over one of
 *       {@code long}/{@code int}/{@code double} — the element type is uniform across the whole tree, matching the
 *       {@link Stitcher}'s single-element-type assumption. This naturally excludes comparisons (boolean result) and
 *       any type-mixing cast boundary;</li>
 *   <li>every leaf is an {@link Attribute} whose declared type equals that element type (so the unfused chain would
 *       apply no cast — the fused body reads the column directly, exactly as an identity cast would), resolvable to a
 *       page channel via the {@link Layout};</li>
 *   <li>the kernel depth is &ge; 2 (a kernel whose child is itself a kernel). A lone kernel gains nothing from fusion,
 *       so we leave it to the generated evaluator.</li>
 * </ul>
 * Descriptors are read by building each node's <b>unfused</b> factory through {@code rawFactory} (supplied by
 * {@code EvalMapper}) and inspecting {@link FusionAware#fusionDescriptor()}; this avoids re-implementing per-operator
 * type dispatch here and guarantees the descriptor matches what the generated evaluator would use.
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
    };

    /** Builds the binding-independent cache key for {@code tree} on the given emit {@code path}. */
    private static FusedClassCache.Shape shapeKey(FusionNode tree, FusedClassCache.Path path) {
        return new FusedClassCache.Shape(shapeOf(tree), rootElement(tree), path);
    }

    /**
     * The element kind of a (depth &ge; 2) fused tree, read from its root kernel's return type. The tree is homogeneous
     * by construction (see {@link #build}), so the root's return primitive is the element kind of the whole subtree.
     */
    private static ElementKind rootElement(FusionNode tree) {
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        String descriptor = root.descriptor().kernelType();
        // build() only ever produces long/int/double homogeneous trees, so elementOf is never null here.
        return elementOf(descriptor.charAt(descriptor.indexOf(')') + 1));
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
        PlanContext ctx = new PlanContext(layout, rawFactory);
        FusionNode tree = build(exp, ctx);
        if (tree == null || ctx.element == null) {
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
        ExpressionEvaluator.Factory unfused = rawFactory.apply(exp);
        FusedClassCompiler compiler = compilerForTests != null ? compilerForTests : DEFAULT_COMPILER;
        return FusedExpressionEvaluatorFactory.tryCreate(
            exp.source(),
            tree,
            channels,
            ctx.element,
            ctx.overflowChecked,
            unfused,
            compiler,
            shapeOf(tree)
        );
    }

    /** Mutable accumulators threaded through the recursive walk. */
    private static final class PlanContext {
        private final Layout layout;
        private final Function<Expression, ExpressionEvaluator.Factory> rawFactory;
        private final List<Integer> channels = new ArrayList<>();
        private ElementKind element;
        private boolean overflowChecked;

        PlanContext(Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
            this.layout = layout;
            this.rawFactory = rawFactory;
        }
    }

    /**
     * Recursively builds a {@link FusionNode} for {@code exp}, or returns {@code null} the moment any eligibility rule
     * is violated (which aborts the whole attempt — a partially fusable tree is not fused here).
     */
    private static FusionNode build(Expression exp, PlanContext ctx) {
        if (exp instanceof Attribute attr) {
            Layout.ChannelAndType channelAndType = ctx.layout.get(attr.id());
            if (channelAndType == null) {
                return null;
            }
            // The element type must already be known from an enclosing kernel; a bare attribute cannot be a fused root
            // (depth-2 requirement) and homogeneity means its declared type must match the element type so no cast is
            // needed (the fused body reads the column directly).
            if (ctx.element == null || matches(attr.dataType(), ctx.element) == false) {
                return null;
            }
            int index = ctx.channels.size();
            ctx.channels.add(channelAndType.channel());
            return new FusionNode.Input(index);
        }

        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        if ((factory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) factory).fusionDescriptor();
        ElementKind element = homogeneousElement(descriptor.kernelType());
        if (element == null) {
            return null;
        }
        if (ctx.element == null) {
            ctx.element = element;
        } else if (ctx.element != element) {
            return null;
        }
        int argCount = primitiveArgCount(descriptor.kernelType());
        List<Expression> children = exp.children();
        if (children.size() != argCount) {
            return null;
        }
        ctx.overflowChecked |= descriptor.overflowChecked();

        List<FusionNode> childNodes = new ArrayList<>(children.size());
        for (Expression child : children) {
            FusionNode childNode = build(child, ctx);
            if (childNode == null) {
                return null;
            }
            childNodes.add(childNode);
        }
        return new FusionNode.Kernel(descriptor, childNodes);
    }

    /**
     * The uniform element kind of a homogeneous primitive kernel descriptor {@code (TT..)T}, or {@code null} if the
     * descriptor is not homogeneous, not a supported primitive, or references object/array types.
     */
    private static ElementKind homogeneousElement(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0 || close + 2 != descriptor.length()) {
            // The return must be exactly one primitive descriptor char.
            return null;
        }
        char ret = descriptor.charAt(close + 1);
        ElementKind element = elementOf(ret);
        if (element == null) {
            return null;
        }
        for (int i = 1; i < close; i++) {
            if (descriptor.charAt(i) != ret) {
                // Any differing char (a second primitive kind, or an 'L'/'[' object/array descriptor) breaks homogeneity.
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

    private static boolean matches(DataType type, ElementKind element) {
        return switch (element) {
            case LONG -> type == DataType.LONG;
            case INT -> type == DataType.INTEGER;
            case DOUBLE -> type == DataType.DOUBLE;
        };
    }

    /** Kernel depth: 0 for a bare input, 1 for a kernel of inputs, ≥2 once a kernel has a kernel child. */
    private static int depth(FusionNode node) {
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
        } else {
            sb.append('#');
        }
    }
}
