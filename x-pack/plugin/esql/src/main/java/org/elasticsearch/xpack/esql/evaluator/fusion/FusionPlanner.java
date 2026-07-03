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
 * <h2>Eligibility</h2>
 * A subtree rooted at {@code exp} is fusable iff:
 * <ul>
 *   <li>every internal node maps to a {@link FusionAware} generated factory (i.e. its kernel {@code process} method is
 *       {@code @Fusable}) whose kernel signature is a <b>homogeneous primitive</b> {@code (TT..)T} over one of
 *       {@code long}/{@code int}/{@code double} — the element type is uniform across the whole tree, matching the
 *       {@link Stitcher}'s single-element-type assumption. This naturally excludes comparisons (boolean result) and
 *       any type-mixing cast boundary;</li>
 *   <li>every leaf is either an {@link Attribute} whose declared type equals that element type (so the unfused chain
 *       would apply no cast — the fused body reads the column directly, exactly as an identity cast would), resolvable
 *       to a page channel via the {@link Layout}, or a {@link Literal} of that element type (embedded as a bytecode
 *       constant — it reads no channel and does not count towards the depth/arity of the tree);</li>
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
        PlanContext ctx = new PlanContext(layout, rawFactory);
        FusionNode tree = build(exp, ctx, true);
        if (tree == null || ctx.inputElement == null || ctx.outputElement == null) {
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
            warningSources(tree, ctx),
            tree,
            channels,
            ctx.inputElement,
            ctx.outputElement,
            ctx.overflowChecked,
            unfused,
            compiler,
            shapeOf(tree)
        );
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
        /** Homogeneous numeric element of every leaf + arithmetic intermediate (kernel argument kind). */
        private ElementKind inputElement;
        /** The root kernel's return kind: the numeric input element for an arithmetic root, or BOOLEAN for a comparison root. */
        private ElementKind outputElement;
        private boolean overflowChecked;

        PlanContext(Layout layout, Function<Expression, ExpressionEvaluator.Factory> rawFactory) {
            this.layout = layout;
            this.rawFactory = rawFactory;
        }
    }

    /**
     * Recursively builds a {@link FusionNode} for {@code exp}, or returns {@code null} the moment any eligibility rule
     * is violated (which aborts the whole attempt — a partially fusable tree is not fused here).
     *
     * <p><b>Typed-tree gate.</b> The INPUT element is the homogeneous numeric kind of every kernel's arguments (and
     * therefore of every leaf); it must be uniform across the whole tree. The OUTPUT element is the root kernel's
     * return kind: the same numeric element for an arithmetic root, or {@link ElementKind#BOOLEAN} for a comparison
     * root (e.g. {@code a > b}). A comparison can only sit at the root, since no in-scope kernel takes a boolean
     * argument — a non-root kernel must therefore return the numeric input element it feeds into.
     *
     * @param root whether {@code exp} is the tree root (only the root may be a boolean-returning comparison)
     */
    private static FusionNode build(Expression exp, PlanContext ctx, boolean root) {
        if (exp instanceof And || exp instanceof Or) {
            // A boolean AND/OR node: build a 3VL Logical whose operands are fused comparison/logical subtrees over
            // homogeneous-primitive column inputs. This is emitted (not templated) by the Stitcher, so no descriptor is
            // probed here. Its output is always a nullable boolean.
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
            // The numeric input element must already be known from an enclosing kernel; a bare attribute cannot be a
            // fused root (depth-2 requirement) and homogeneity means its declared type must match the input element so
            // no cast is needed (the fused body reads the column directly). Leaves are Attribute-only numeric columns.
            if (ctx.inputElement == null || matches(attr.dataType(), ctx.inputElement) == false) {
                return null;
            }
            int index = ctx.channels.size();
            ctx.channels.add(channelAndType.channel());
            return new FusionNode.Input(index);
        }
        if (exp instanceof Literal literal) {
            // A literal operand embeds as a bytecode constant (no page channel, no input vector). It cannot establish
            // the tree's element (an enclosing kernel always sets it first), and its type must match that element.
            return buildConstant(literal, ctx);
        }

        ExpressionEvaluator.Factory factory = ctx.rawFactory.apply(exp);
        if ((factory instanceof FusionAware) == false) {
            return null;
        }
        FusionDescriptor descriptor = ((FusionAware) factory).fusionDescriptor();
        String kernelType = descriptor.kernelType();
        // INPUT-element gate: every argument must be the same primitive long/int/double.
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

        // Set / verify the homogeneous numeric input element across the whole tree.
        if (ctx.inputElement == null) {
            ctx.inputElement = argElement;
        } else if (ctx.inputElement != argElement) {
            return null;
        }

        if (root) {
            if (comparison) {
                ctx.outputElement = ElementKind.BOOLEAN;
            } else if (returnNumeric == argElement) {
                ctx.outputElement = returnNumeric;
            } else {
                // A root whose return differs from its (numeric) inputs (e.g. a cast-like kernel) is out of scope.
                return null;
            }
        } else {
            // A non-root kernel feeds a numeric argument, so it must return the numeric input element. This naturally
            // excludes a boolean-returning comparison anywhere but the root.
            if (returnNumeric != argElement) {
                return null;
            }
        }

        int argCount = primitiveArgCount(kernelType);
        List<Expression> children = exp.children();
        if (children.size() != argCount) {
            return null;
        }
        ctx.overflowChecked |= descriptor.overflowChecked();

        List<FusionNode> childNodes = new ArrayList<>(children.size());
        for (Expression child : children) {
            FusionNode childNode = build(child, ctx, false);
            if (childNode == null) {
                return null;
            }
            childNodes.add(childNode);
        }
        FusionNode.Kernel kernel = new FusionNode.Kernel(descriptor, childNodes);
        // Capture this kernel's own source so the fused path attributes its overflow / leaf multi-value warnings to the
        // same node source the unfused generated evaluator would.
        ctx.kernelSources.put(kernel, exp.source());
        return kernel;
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
        // Set / verify the homogeneous numeric input element across the whole tree.
        if (ctx.inputElement == null) {
            ctx.inputElement = argElement;
        } else if (ctx.inputElement != argElement) {
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
                if (channelAndType == null || matches(attr.dataType(), ctx.inputElement) == false) {
                    return null;
                }
                int index = ctx.channels.size();
                ctx.channels.add(channelAndType.channel());
                childNodes.add(new FusionNode.Input(index));
            } else if (child instanceof Literal literal) {
                FusionNode constant = buildConstant(literal, ctx);
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
     * Builds a {@link FusionNode.Constant} from a numeric {@link Literal}, or {@code null} if it cannot be fused into
     * the current tree: the tree's homogeneous element must already be known (a constant never establishes it), the
     * literal's declared type must equal that element (so the unfused chain would apply no cast), and its value must be
     * non-{@code null}. A {@code null} literal is <b>refused</b> (returns {@code null}), so a subtree containing one
     * falls back to the unfused chain — the correct + simplest handling (ESQL normally folds null literals away, and a
     * per-position "constant is null" would just null every row, which the unfused evaluator already does).
     */
    private static FusionNode buildConstant(Literal literal, PlanContext ctx) {
        if (ctx.inputElement == null) {
            return null;
        }
        Object value = literal.value();
        if (value == null || value instanceof Number == false) {
            return null;
        }
        if (matches(literal.dataType(), ctx.inputElement) == false) {
            return null;
        }
        Number number = (Number) value;
        return switch (ctx.inputElement) {
            case LONG -> new FusionNode.Constant(number.longValue(), 'J');
            case INT -> new FusionNode.Constant(number.intValue(), 'I');
            case DOUBLE -> new FusionNode.Constant(number.doubleValue(), 'D');
            // The element of a fusable tree is always numeric (kernel argument kind); BOOLEAN is output-only, so a
            // boolean-typed constant leaf never arises. Refuse defensively rather than emit an ill-typed constant.
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
