/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import java.util.List;

/**
 * An expression tree the {@link Stitcher} fuses into a single per-position body.
 *
 * <p>A tree is built from these node kinds:
 * <ul>
 *   <li>{@link Input} — a leaf that reads position {@code p} of one of the fused method's input
 *       vectors. {@link Input#index()} selects which input (0-based); the fused method takes one
 *       vector parameter per distinct index in {@code [0, maxIndex]}.</li>
 *   <li>{@link Constant} — a leaf whose value is a compile-time literal embedded directly into the
 *       emitted loop as a bytecode constant ({@code LDC}/{@code LDC2_W}/{@code ICONST}/…), so it costs
 *       no input vector, no page channel and no per-position array read — it never widens the fused
 *       method's arity. Because the value is baked into the bytecode, two trees that differ only in a
 *       constant's value stitch to <em>different</em> classes (the value is part of the cache key).</li>
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
     * A leaf whose value is a compile-time literal embedded into the emitted loop as a bytecode constant, rather
     * than read from an input vector. It consumes no {@link Input} slot and does not widen the fused method's arity,
     * so a {@code kernel(column, constant)} tree still reads exactly one input vector.
     *
     * <p>The stitcher pushes {@code value} with the tightest opcode for {@code element} ({@code ICONST}/{@code BIPUSH}/
     * {@code SIPUSH}/{@code LDC} for {@code int}, {@code LDC2_W} for {@code long}/{@code double}) on every emit path
     * (plain-vector, checked-vector, block, and the logical comparison helpers), typed to match the consuming kernel's
     * argument. Because the value is baked into the bytecode, the constant's value is part of the fused class's cache
     * key: {@code a > 5} and {@code a > 6} stitch to distinct classes (a cardinality the shape cache must tolerate).
     *
     * @param value   the literal's value, boxed as {@link Long}/{@link Integer}/{@link Double} to match {@code element};
     *                must not be {@code null} (a null literal is not fusable — the planner refuses that subtree)
     * @param element the primitive JVM type descriptor char of the value: {@code 'J'} (long), {@code 'I'} (int) or
     *                {@code 'D'} (double). It must equal the homogeneous element of the tree the constant sits in.
     */
    record Constant(Object value, char element) implements FusionNode {
        public Constant {
            if (value == null) {
                throw new IllegalArgumentException("constant value must not be null");
            }
            if (element != 'J' && element != 'I' && element != 'D') {
                throw new IllegalArgumentException("constant element must be one of J/I/D but was [" + element + "]");
            }
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
