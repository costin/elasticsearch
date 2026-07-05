/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single, self-verifying source of truth for the <b>ordered, structural facts</b> a fused expression tree's three
 * parties — the {@link Stitcher} (which emits parameter loads + warning registrations), {@code FusionPlanner} (which
 * collects the matching constant values and per-node warning {@code Source}s), and {@code FusedExpressionEvaluatorFactory}
 * (which appends the constants' types to the resolved {@code MethodType} and marshals the values into the invoke args) —
 * must agree on byte-for-byte. Those facts are the "positional contract": get any of these orderings out of sync between
 * the parties and you get a silent miscompute or an opaque {@code VerifyError}.
 *
 * <p><b>One walk, not many (efficiency).</b> Every fact here is derived in a <em>single</em> pre-order DFS of the tree
 * (a {@link FusionNode.Kernel}'s children left-to-right; a {@link FusionNode.Logical}'s left subtree before its right).
 * The {@link Stitcher} previously ran up to five separate walks per emit ({@code arity}, {@code consumedInputs},
 * {@code constantsInEmitOrder}, {@code warningsSourceIndices}, {@code overflowExceptionInternalNames}); an emit path now
 * derives one {@code FusionSignature} and reads them all from it. The walk is the same pre-order all emitters already
 * traverse, so the i-th constant reached is the i-th trailing parameter and the k-th kernel reached is warning-source
 * slot {@code k} — identical values to the old per-fact walks, computed once.
 *
 * <p><b>Derive + verify (correctness).</b> {@link #of(FusionNode)} runs {@link #assertConsistent()} under {@code -ea}
 * (tests), catching a structural break — a warning-source slot that is not densely numbered, an input index at or above
 * the arity, a constant with an unexpected element — at derivation time rather than as a downstream {@code VerifyError}.
 * The checks are assertions (elided in production), so the runtime generate path pays nothing.
 *
 * <p><b>Not module-coupled.</b> Everything here is expressed in terms of {@link FusionNode}/{@link FusionDescriptor}
 * (both in this package), so the same object is consulted from the {@code Stitcher} (compute) and, via the public
 * accessors {@link Stitcher#constantsInEmitOrder}/{@link Stitcher#warningsSourceIndices} it now backs, from the planner
 * (esql). The per-input {@code Element} typing (which needs a compute-only opcode table) is deliberately left to the
 * {@code Stitcher}; folding it in belongs with the {@code ElementKind}/{@code Element} unification.
 */
public final class FusionSignature {

    private final int arity;
    private final int[] consumedInputs;
    private final List<FusionNode.Constant> constantsInEmitOrder;
    private final Map<FusionNode, Integer> warningSourceIndices;
    private final Set<String> overflowExceptionInternalNames;
    private final Type[] inputTypes;

    private FusionSignature(
        int arity,
        int[] consumedInputs,
        List<FusionNode.Constant> constantsInEmitOrder,
        Map<FusionNode, Integer> warningSourceIndices,
        Set<String> overflowExceptionInternalNames,
        Type[] inputTypes
    ) {
        this.arity = arity;
        this.consumedInputs = consumedInputs;
        this.constantsInEmitOrder = constantsInEmitOrder;
        this.warningSourceIndices = warningSourceIndices;
        this.overflowExceptionInternalNames = overflowExceptionInternalNames;
        this.inputTypes = inputTypes;
    }

    /** Derives the signature of {@code tree} in a single pre-order walk and verifies its internal consistency. */
    public static FusionSignature of(FusionNode tree) {
        List<FusionNode.Constant> constants = new ArrayList<>();
        Map<FusionNode, Integer> warningSources = new IdentityHashMap<>();
        Set<String> overflow = new LinkedHashSet<>();
        TreeSet<Integer> consumed = new TreeSet<>();
        Map<Integer, Type> inputTypeByIndex = new HashMap<>();
        int[] maxInputIndex = { -1 };
        walk(tree, null, constants, warningSources, overflow, consumed, inputTypeByIndex, maxInputIndex);

        int[] consumedInputs = new int[consumed.size()];
        int i = 0;
        for (int index : consumed) {
            consumedInputs[i++] = index;
        }
        int arity = maxInputIndex[0] + 1;
        // Per-input JVM type, indexed by Input#index(): the element the consuming kernel expects at that operand
        // position (the planner guarantees it equals the column's own element, bridging any mismatch with a cast).
        // A declared-but-unconsumed index (the over-nullify guard) has no natural type, so it inherits the first
        // populated one — arbitrary since the fused body never reads it, but it keeps the parameter list homogeneous.
        Type[] inputTypes = new Type[arity];
        Type fallback = null;
        for (int index = 0; index < arity; index++) {
            Type type = inputTypeByIndex.get(index);
            if (type != null && fallback == null) {
                fallback = type;
            }
            inputTypes[index] = type;
        }
        for (int index = 0; index < arity; index++) {
            if (inputTypes[index] == null) {
                inputTypes[index] = fallback;
            }
        }
        FusionSignature signature = new FusionSignature(arity, consumedInputs, constants, warningSources, overflow, inputTypes);
        assert signature.assertConsistent();
        return signature;
    }

    /**
     * One pre-order DFS computing every fact at once. The visit order is exactly the one both the old
     * {@code constantsInEmitOrder} and {@code assignWarningsSourceIndices} walks used (a kernel is assigned its
     * warning-source slot before its children are visited; a constant is appended when reached; a logical node visits
     * left then right), so the derived orderings are byte-for-byte identical to the previous separate walks.
     */
    private static void walk(
        FusionNode node,
        Type expected,
        List<FusionNode.Constant> constants,
        Map<FusionNode, Integer> warningSources,
        Set<String> overflow,
        TreeSet<Integer> consumed,
        Map<Integer, Type> inputTypeByIndex,
        int[] maxInputIndex
    ) {
        if (node instanceof FusionNode.Input input) {
            consumed.add(input.index());
            if (input.index() > maxInputIndex[0]) {
                maxInputIndex[0] = input.index();
            }
            // The leaf's element is the type its consuming kernel expects at this operand position ({@code expected}).
            inputTypeByIndex.put(input.index(), expected);
        } else if (node instanceof FusionNode.Constant constant) {
            // Embedded literal: contributes a trailing parameter, no input vector, no warning source.
            constants.add(constant);
        } else if (node instanceof FusionNode.Kernel kernel) {
            // Pre-order: this kernel claims the next warning-source slot before its operands are visited.
            warningSources.put(kernel, warningSources.size());
            if (kernel.descriptor().hasOverflowException()) {
                overflow.add(kernel.descriptor().overflowExceptionType().replace('.', '/'));
            }
            Type[] argTypes = Type.getMethodType(kernel.descriptor().kernelType()).getArgumentTypes();
            List<FusionNode> children = kernel.children();
            // A variadic kernel's LAST parameter is an array; the fixed leading children map to their own argTypes, and
            // every trailing child (a varargs element) maps to that array's ELEMENT type. A non-variadic kernel has one
            // child per argType.
            boolean variadic = kernel.descriptor().variadic();
            Type varargsElementType = variadic ? argTypes[argTypes.length - 1].getElementType() : null;
            for (int i = 0; i < children.size(); i++) {
                Type childType = (variadic && i >= argTypes.length - 1) ? varargsElementType : argTypes[i];
                walk(children.get(i), childType, constants, warningSources, overflow, consumed, inputTypeByIndex, maxInputIndex);
            }
        } else if (node instanceof FusionNode.Logical logical) {
            // A logical node is not a warning source; recurse left then right. Its operands are comparison/logical
            // kernels (never bare Inputs), so no per-input type flows through here.
            walk(logical.left(), null, constants, warningSources, overflow, consumed, inputTypeByIndex, maxInputIndex);
            walk(logical.right(), null, constants, warningSources, overflow, consumed, inputTypeByIndex, maxInputIndex);
        }
    }

    /** Number of distinct input vectors the tree references, i.e. one past the maximum {@link FusionNode.Input} index. */
    public int arity() {
        return arity;
    }

    /** The distinct input indices the tree actually references, ascending (guard/read only what is consumed). */
    public int[] consumedInputs() {
        return consumedInputs;
    }

    /** The {@link FusionNode.Constant} leaves in canonical emit order (their trailing-parameter order). */
    public List<FusionNode.Constant> constantsInEmitOrder() {
        return constantsInEmitOrder;
    }

    /** Each kernel's index into the runtime {@code Warnings[]} (its own warning source), by node identity. */
    public Map<FusionNode, Integer> warningSourceIndices() {
        return warningSourceIndices;
    }

    /** The size of the runtime {@code Warnings[]} — one slot per kernel. */
    public int warningSourceCount() {
        return warningSourceIndices.size();
    }

    /** Distinct JVM internal names of the overflow exceptions the tree's kernels can throw (may be empty). */
    public Set<String> overflowExceptionInternalNames() {
        return overflowExceptionInternalNames;
    }

    /**
     * The per-input JVM {@link Type}, indexed by {@link FusionNode.Input#index()} and densely populated over
     * {@code [0, arity)} — the same result the {@code Stitcher} used to re-derive in a separate {@code inputElements}
     * tree walk, now produced by this one walk. Package-private: only the {@code Stitcher} (same package) maps these to
     * its bytecode-emission {@code Element}s; it deliberately does not leak through the public planner-facing API.
     */
    Type[] inputTypes() {
        return inputTypes;
    }

    /**
     * Structural invariants the three parties rely on; an {@code assert} (elided in production) so a planner/tree bug
     * fails loud at derivation time instead of as a downstream {@code VerifyError} or a silently misaligned argument.
     */
    private boolean assertConsistent() {
        // Warning-source slots must be a dense [0, count) numbering (the runtime Warnings[] is indexed by them).
        boolean[] seen = new boolean[warningSourceIndices.size()];
        for (int slot : warningSourceIndices.values()) {
            assert slot >= 0 && slot < seen.length && seen[slot] == false : "warning-source slots must be dense [0, count)";
            seen[slot] = true;
        }
        // Every consumed input index must fit under the arity (the fused method takes one parameter per index).
        for (int index : consumedInputs) {
            assert index >= 0 && index < arity : "consumed input index [" + index + "] is out of range for arity [" + arity + "]";
        }
        // Every input slot in [0, arity) has a resolved type (a consumed leaf's own, or the homogeneous fallback).
        assert inputTypes.length == arity : "inputTypes length [" + inputTypes.length + "] must equal arity [" + arity + "]";
        for (int index = 0; index < arity; index++) {
            assert inputTypes[index] != null : "input type at [" + index + "] must be resolved (arity " + arity + ")";
        }
        // Every constant is either a primitive the emit paths load with a typed xLOAD (J/I/D) or a reference (object)
        // @Fixed constant loaded with ALOAD (element 'L', carrying its declared refType) — anything else would surface
        // as an opaque VerifyError downstream.
        for (FusionNode.Constant constant : constantsInEmitOrder) {
            char element = constant.element();
            assert element == 'J' || element == 'I' || element == 'D' || (element == 'L' && constant.refType() != null)
                : "constant has unexpected element [" + element + "]";
        }
        return true;
    }
}
