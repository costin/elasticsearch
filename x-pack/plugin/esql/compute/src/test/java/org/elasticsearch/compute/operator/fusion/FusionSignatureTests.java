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
import static org.hamcrest.Matchers.containsString;
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
        // overflowChecked == true -> ArithmeticException, warns=true, dictionarySafe=true (old 5-arg derivation).
        return new FusionDescriptor(
            FusionSignatureTests.class,
            "k",
            type,
            true,
            true,
            "java.lang.ArithmeticException",
            false,
            false,
            true,
            true
        );
    }

    private static FusionDescriptor plainKernel(String type) {
        return new FusionDescriptor(FusionSignatureTests.class, "k", type, false, true, "", false, false, false, true);
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

    /**
     * F2b: a {@code Not} is not a warning source; the walk recurses into its child so comparison slots stay dense
     * and constants under {@code NOT(a > 5)} still appear in emit order.
     */
    public void testNotRecursesIntoChildForWarningSlotsAndConstants() {
        FusionNode.Constant five = new FusionNode.Constant(5L, 'J');
        FusionNode.Kernel cmp = new FusionNode.Kernel(plainKernel("(JJ)Z"), List.of(new FusionNode.Input(0), five));
        FusionNode.Not root = new FusionNode.Not(cmp);

        FusionSignature sig = FusionSignature.of(root);
        assertThat(sig.arity(), equalTo(1));
        assertArrayEquals(new int[] { 0 }, sig.consumedInputs());
        assertThat(sig.constantsInEmitOrder(), contains(five));
        assertThat(sig.warningSourceCount(), equalTo(1));
        assertThat(sig.warningSourceIndices().get(cmp), equalTo(0));
        assertThat(sig.warningSourceIndices().containsKey(root), is(false));
    }

    /**
     * F2b: {@link FusionNode.Not} accepts a boolean-producing Kernel ({@code )Z}), a Logical, or a nested Not,
     * and rejects an arithmetic Kernel (which would mis-emit through {@code emitComparisonCall}).
     */
    public void testNotCtorRejectsNonBooleanKernel() {
        FusionNode.Kernel cmp = new FusionNode.Kernel(plainKernel("(JJ)Z"), List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode.Kernel cmp2 = new FusionNode.Kernel(plainKernel("(JJ)Z"), List.of(new FusionNode.Input(2), new FusionNode.Input(3)));
        FusionNode.Not ofCmp = new FusionNode.Not(cmp);
        assertThat(ofCmp.child(), is(cmp));
        FusionNode.Not nested = new FusionNode.Not(ofCmp);
        assertThat(nested.child(), is(ofCmp));
        FusionNode.Logical and = new FusionNode.Logical(FusionNode.BoolOp.AND, cmp, cmp2);
        assertThat(new FusionNode.Not(and).child(), is(and));

        FusionNode.Kernel arith = new FusionNode.Kernel(plainKernel("(JJ)J"), List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> new FusionNode.Not(arith));
        assertThat(e.getMessage(), containsString("kernelType ending in )Z"));
        assertThat(e.getMessage(), containsString("(JJ)J"));

        IllegalArgumentException input = expectThrows(IllegalArgumentException.class, () -> new FusionNode.Not(new FusionNode.Input(0)));
        assertThat(input.getMessage(), containsString("Input"));
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

    /**
     * F1: a baked constant is an immediate, not a trailing method parameter, so {@link FusionSignature#constantsInEmitOrder}
     * must exclude it. A following non-baked constant still occupies a dense slot 0 (the baked node must not consume a
     * slot or shift later non-baked constants).
     */
    public void testBakedConstantsAreExcludedFromEmitOrderAndDoNotShiftFollowingSlots() {
        FusionNode.Constant baked = FusionNode.Constant.baked(1000L, 'J');
        FusionNode.Constant runtime = new FusionNode.Constant(7L, 'J');
        // Pre-order: baked first (Div's rhs), then the Add's runtime addend — skipping baked must leave [runtime] only.
        FusionNode.Kernel div = new FusionNode.Kernel(overflowKernel("(JJ)J"), List.of(new FusionNode.Input(0), baked));
        FusionNode.Kernel add = new FusionNode.Kernel(plainKernel("(JJ)J"), List.of(div, runtime));

        FusionSignature sig = FusionSignature.of(add);
        assertThat(sig.constantsInEmitOrder(), contains(runtime));
        assertThat(baked.isBaked(), is(true));
        assertThat(runtime.isBaked(), is(false));

        // The same baked node in a tree with no other constants yields an empty trailing-param list.
        FusionSignature bakedOnly = FusionSignature.of(div);
        assertThat(bakedOnly.constantsInEmitOrder(), empty());
    }
}
