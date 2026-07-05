/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.operator.DriverContext;

import java.util.List;
import java.util.function.Function;

/**
 * An expression tree the {@link Stitcher} fuses into a single per-position body.
 *
 * <p>A tree is built from these node kinds:
 * <ul>
 *   <li>{@link Input} — a leaf that reads position {@code p} of one of the fused method's input
 *       vectors. {@link Input#index()} selects which input (0-based); the fused method takes one
 *       vector parameter per distinct index in {@code [0, maxIndex]}.</li>
 *   <li>{@link Constant} — a leaf whose value is a compile-time literal passed to the fused method as a
 *       trailing primitive <em>method argument</em> (loaded from its parameter slot at each use site), so it
 *       costs no input vector, no page channel and no per-position array read — it never widens the fused
 *       method's <em>input-vector</em> arity. Because the value is a runtime argument rather than baked into the
 *       bytecode, two trees that differ only in a constant's value stitch to the <em>same</em> class (the value
 *       is <b>not</b> part of the cache key — only the constant's element-typed slot marker is).</li>
 *   <li>{@link Kernel} — an inner node that applies a fusable arithmetic kernel (identified by a
 *       {@link FusionDescriptor}) to its children. The child count and each child's category must
 *       match the kernel's JVM argument descriptor (e.g. a {@code (JJ)J} kernel has exactly two
 *       {@code long}-producing children).</li>
 *   <li>{@link Logical} — a boolean {@code AND}/{@code OR} node with three-valued-logic (3VL)
 *       semantics (iter 20, S3.1). Unlike {@link Kernel} it carries <b>no</b> {@link FusionDescriptor}
 *       or template: the {@link Stitcher} <em>emits</em> the short-circuit 3VL truth tables directly
 *       (matching {@code BinaryLogicOperation}). Its operands are boolean-producing comparison
 *       {@link Kernel}s (from S3.0) or nested {@link Logical}s; the output is a nullable boolean, so a
 *       {@code Logical}-rooted tree is stitched only through the block path.</li>
 * </ul>
 *
 * <p>The stitcher walks this tree in post-order: it evaluates each child before the kernel that
 * consumes it, keeping intermediate results on the operand stack while each kernel body reads its
 * inputs from a disjoint local-slot range. A tree of {@link Kernel} depth &ge; 2 (a kernel whose
 * child is itself a kernel) is the case iter 10 targets — e.g. {@code Mul(Add(in0, in1), in2)} for
 * {@code (a + b) * c}.
 */
public sealed interface FusionNode {

    /**
     * A leaf reading position {@code p} of input vector {@code index}.
     *
     * @param index 0-based selector of the fused method's input vector
     */
    record Input(int index) implements FusionNode {
        public Input {
            if (index < 0) {
                throw new IllegalArgumentException("input index must be non-negative but was [" + index + "]");
            }
        }
    }

    /**
     * A leaf whose value is a compile-time literal passed to the fused method as a trailing primitive
     * <em>method argument</em>, rather than read from an input vector. It consumes no {@link Input} slot and does not
     * widen the fused method's <em>input-vector</em> arity, so a {@code kernel(column, constant)} tree still reads
     * exactly one input vector.
     *
     * <p>The stitcher appends one primitive parameter per constant (in the canonical
     * {@link Stitcher#constantsInEmitOrder emit order}) after the fused method's existing parameters and, at each use
     * site, loads the constant from its parameter slot with the type-correct {@code xLOAD} (typed to match the consuming
     * kernel's argument). Because the value is now a runtime argument — the planner reads it from {@link #value()} and
     * the factory marshals it into the invoke args — it is <b>not</b> baked into the bytecode and so is <b>not</b> part
     * of the fused class's cache key: {@code a > 5} and {@code a > 6} stitch to the <em>same</em> class and differ only
     * in the argument supplied at evaluation time. Only the constant's element-typed slot marker participates in the
     * shape, so a {@code long} and a {@code double} constant still stitch to distinct classes.
     *
     * @param value   the literal's value, boxed as {@link Long}/{@link Integer}/{@link Double} to match {@code element};
     *                must not be {@code null} (a null literal is not fusable — the planner refuses that subtree). The
     *                planner reads it to marshal the fused method's trailing argument at evaluation time.
     * @param element the primitive JVM type descriptor char of the value: {@code 'J'} (long), {@code 'I'} (int) or
     *                {@code 'D'} (double). It must equal the homogeneous element of the tree the constant sits in, and
     *                it types the constant's trailing parameter slot on the fused method.
     */
    record Constant(Object value, char element, Class<?> refType, Function<DriverContext, Object> scratchSupplier) implements FusionNode {
        public Constant {
            if (scratchSupplier != null) {
                // A per-driver SCRATCH reference @Fixed operand (#8b): a mutable buffer (e.g. a reusable BytesRef, a
                // UTF8CodePoint) that a THREAD_LOCAL @Fixed kernel parameter needs, created fresh per driver by the
                // supplier (not captured at plan time like a Locale). It rides the same reference parameter slot as a
                // captured reference constant (ALOAD) — only WHERE the value comes from differs — so it must be a
                // reference ('L') with a declared refType and carries no plan-time value.
                if (element != 'L') {
                    throw new IllegalArgumentException(
                        "a scratch constant must be reference-typed (element 'L') but was [" + element + "]"
                    );
                }
                if (refType == null) {
                    throw new IllegalArgumentException("a scratch constant must carry its declared refType");
                }
            } else if (value == null) {
                throw new IllegalArgumentException("constant value must not be null");
            } else if (element == 'L') {
                // A reference-typed (object) @Fixed constant (B3b): its value is captured off the expression at plan
                // time (e.g. a Locale from the query configuration, or an enum) and passed to the fused method as a
                // reference parameter. refType is the DECLARED parameter type the kernel expects (e.g. java.util.Locale),
                // which types the fused method's trailing L…; parameter and the factory's MethodType.
                if (refType == null) {
                    throw new IllegalArgumentException("a reference constant (element 'L') must carry its declared refType");
                }
                if (refType.isPrimitive()) {
                    throw new IllegalArgumentException("a reference constant's refType must not be primitive but was [" + refType + "]");
                }
            } else if (element != 'J' && element != 'I' && element != 'D') {
                throw new IllegalArgumentException("constant element must be one of J/I/D/L but was [" + element + "]");
            } else if (refType != null) {
                throw new IllegalArgumentException("a primitive constant (element [" + element + "]) must not carry a refType");
            }
        }

        /** A primitive ({@code J}/{@code I}/{@code D}) constant leaf; the common case, carries no reference type. */
        public Constant(Object value, char element) {
            this(value, element, null, null);
        }

        /**
         * A reference-typed (object) {@code @Fixed} constant leaf (B3b): {@code value} is the captured object and
         * {@code declaredType} is the parameter type the consuming kernel declares (e.g. {@code Locale.class}). It is
         * passed to the fused method as a reference argument and read with {@code ALOAD} — it is block-path only, since
         * a reference operand never rides the primitive vector fast paths.
         */
        public static Constant reference(Object value, Class<?> declaredType) {
            return new Constant(value, 'L', declaredType, null);
        }

        /**
         * A per-driver SCRATCH reference {@code @Fixed} operand (#8b): a {@code THREAD_LOCAL} mutable buffer the kernel
         * needs (e.g. a reusable {@code BytesRef} / {@code UTF8CodePoint} for {@code LEFT}/{@code RIGHT}). {@code supplier}
         * builds a fresh instance per driver ({@code context -> new BytesRef()}); {@code declaredType} is the parameter
         * type the kernel declares. Unlike {@link #reference} it is not captured once at plan time — the factory calls
         * the supplier in each {@code get(DriverContext)} so concurrent drivers never share one mutable buffer.
         */
        public static Constant scratch(Function<DriverContext, Object> supplier, Class<?> declaredType) {
            return new Constant(null, 'L', declaredType, supplier);
        }

        /** Whether this is a reference-typed (object) constant rather than a primitive one. */
        public boolean isReference() {
            return element == 'L';
        }

        /** Whether this is a per-driver scratch operand (its value is built per {@code get(DriverContext)}, not captured). */
        public boolean isScratch() {
            return scratchSupplier != null;
        }
    }

    /**
     * An inner node applying {@code descriptor}'s kernel to {@code children}.
     *
     * @param descriptor the fusable kernel to apply at this node
     * @param children   the kernel's operands, one per kernel argument, in argument order
     */
    record Kernel(FusionDescriptor descriptor, List<FusionNode> children) implements FusionNode {
        public Kernel {
            if (descriptor == null) {
                throw new IllegalArgumentException("kernel descriptor must not be null");
            }
            children = List.copyOf(children);
        }
    }

    /** The boolean connective a {@link Logical} node applies with three-valued-logic semantics. */
    enum BoolOp {
        AND,
        OR
    }

    /**
     * A three-valued-logic (3VL) boolean {@code AND}/{@code OR} over two boolean-producing operands (comparison
     * {@link Kernel}s or nested {@link Logical}s). It has no {@link FusionDescriptor}: the {@link Stitcher} emits the
     * short-circuit truth tables of {@code BinaryLogicOperation} directly ({@code false AND * = false},
     * {@code true OR * = true}, {@code null} otherwise). The output is a nullable boolean.
     *
     * @param op    the connective ({@code AND} or {@code OR})
     * @param left  the left operand (evaluated first; may short-circuit the right)
     * @param right the right operand
     */
    record Logical(BoolOp op, FusionNode left, FusionNode right) implements FusionNode {
        public Logical {
            if (op == null) {
                throw new IllegalArgumentException("logical op must not be null");
            }
            if (left == null || right == null) {
                throw new IllegalArgumentException("logical operands must not be null");
            }
        }
    }
}
