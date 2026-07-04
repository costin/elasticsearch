/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit proof for {@link FusionSignature}: the single-walk derivation of the cross-module positional contract (input
 * arity/consumed set, constants in emit order, per-kernel warning-source slots, overflow types) produces the byte-order
 * facts the {@link Stitcher} bakes into bytecode and the planner/factory must marshal against. The derivation is pure
 * (it reads only {@link FusionNode} structure + {@link FusionDescriptor} metadata, never a real kernel body), so these
 * trees need no live kernels.
 */
public class FusionSignatureTests extends ESTestCase {

    private static FusionDescriptor overflowKernel(String type) {
        // overflowChecked == true -> the convenience ctor maps to java.lang.ArithmeticException.
        return new FusionDescriptor(FusionSignatureTests.class, "k", type, true, true);
    }

    private static FusionDescriptor plainKernel(String type) {
        return new FusionDescriptor(FusionSignatureTests.class, "k", type, false, true);
    }

    public void testArithmeticTreeFacts() {
        // (a + b) * c == mul(add(in0, in1), in2)
        FusionNode.Kernel add = new FusionNode.Kernel(overflowKernel("(JJ)J"), List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode.Kernel root = new FusionNode.Kernel(overflowKernel("(JJ)J"), List.of(add, new FusionNode.Input(2)));

        FusionSignature sig = FusionSignature.of(root);
        assertThat(sig.arity(), equalTo(3));
        assertArrayEquals(new int[] { 0, 1, 2 }, sig.consumedInputs());
        assertThat(sig.constantsInEmitOrder(), empty());
        // Pre-order warning-source slots: the root (mul) is 0, then its first child (add) is 1.
        assertThat(sig.warningSourceCount(), equalTo(2));
        assertThat(sig.warningSourceIndices().get(root), equalTo(0));
        assertThat(sig.warningSourceIndices().get(add), equalTo(1));
        assertThat(sig.overflowExceptionInternalNames(), contains("java/lang/ArithmeticException"));
    }

    public void testConstantsInEmitOrderIsPreOrder() {
        // add(mul(in0, c5), c7): constants encountered in pre-order DFS are [c5, c7].
        FusionNode.Constant c5 = new FusionNode.Constant(5L, 'J');
        FusionNode.Constant c7 = new FusionNode.Constant(7L, 'J');
        FusionNode.Kernel mul = new FusionNode.Kernel(plainKernel("(JJ)J"), List.of(new FusionNode.Input(0), c5));
        FusionNode.Kernel add = new FusionNode.Kernel(plainKernel("(JJ)J"), List.of(mul, c7));

        FusionSignature sig = FusionSignature.of(add);
        assertThat(sig.constantsInEmitOrder(), contains(c5, c7));
        assertThat(sig.arity(), equalTo(1));
        assertArrayEquals(new int[] { 0 }, sig.consumedInputs());
        assertThat(sig.overflowExceptionInternalNames(), empty());
    }

    public void testLogicalTreeWarningSlotsAndArity() {
        // (in0 > in1) AND (in2 > in3): a logical node is not a warning source; left comparison is slot 0, right is 1.
        FusionNode.Kernel left = new FusionNode.Kernel(plainKernel("(JJ)Z"), List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode.Kernel right = new FusionNode.Kernel(plainKernel("(JJ)Z"), List.of(new FusionNode.Input(2), new FusionNode.Input(3)));
        FusionNode.Logical root = new FusionNode.Logical(FusionNode.BoolOp.AND, left, right);

        FusionSignature sig = FusionSignature.of(root);
        assertThat(sig.arity(), equalTo(4));
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, sig.consumedInputs());
        assertThat(sig.warningSourceCount(), equalTo(2));
        assertThat(sig.warningSourceIndices().get(left), equalTo(0));
        assertThat(sig.warningSourceIndices().get(right), equalTo(1));
        assertThat(sig.warningSourceIndices().containsKey(root), is(false));
    }

    public void testPublicStaticsDelegateToSignature() {
        // The Stitcher public statics now delegate to FusionSignature; they must agree with the object, byte-for-byte.
        FusionNode.Constant c = new FusionNode.Constant(9L, 'J');
        FusionNode.Kernel root = new FusionNode.Kernel(overflowKernel("(JJ)J"), List.of(new FusionNode.Input(0), c));

        FusionSignature sig = FusionSignature.of(root);
        assertThat(Stitcher.constantsInEmitOrder(root), equalTo(sig.constantsInEmitOrder()));
        assertThat(Stitcher.warningsSourceIndices(root), equalTo(sig.warningSourceIndices()));
        assertThat(Stitcher.warningsSourceCount(root), equalTo(sig.warningSourceCount()));
    }
}
