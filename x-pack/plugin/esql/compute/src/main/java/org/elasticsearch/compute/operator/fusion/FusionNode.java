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
 * <p>A tree is built from two node kinds:
 * <ul>
 *   <li>{@link Input} — a leaf that reads position {@code p} of one of the fused method's input
 *       vectors. {@link Input#index()} selects which input (0-based); the fused method takes one
 *       vector parameter per distinct index in {@code [0, maxIndex]}.</li>
 *   <li>{@link Kernel} — an inner node that applies a fusable arithmetic kernel (identified by a
 *       {@link FusionDescriptor}) to its children. The child count and each child's category must
 *       match the kernel's JVM argument descriptor (e.g. a {@code (JJ)J} kernel has exactly two
 *       {@code long}-producing children).</li>
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
}
