/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Benchmark/test-only {@code esql.fusion.max_method_bytecodes} hook: the same overflow-checked arithmetic shape
 * splits into {@code compute()} under a tiny budget and stays inline under a large one.
 */
public class StitcherMaxMethodBytecodesTests extends ESTestCase {

    /** {@code long} add shaped like the production overflow-checked {@code Add} kernel. */
    public static final class LongKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }
    }

    public void testMaxMethodBytecodesHookFlipsSplitDecision() throws Exception {
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        FusionNode tree = addChain(8);
        int previous = Stitcher.maxFusedMethodBytecodes;
        try {
            Stitcher.maxFusedMethodBytecodesForTests(8000);
            byte[] large = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), tree);
            assertThat("large budget (8000) must keep fusedRaw inline (no compute split)", methodNames(large), not(hasItem("compute")));
            int inlineSize = Stitcher.methodCodeLength(large, Stitcher.FUSED_RAW_METHOD_NAME);
            assertThat("emitted fusedRaw", inlineSize, greaterThan(0));

            Stitcher.maxFusedMethodBytecodesForTests(50);
            byte[] split = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), tree);
            assertThat("tiny budget (50) must lift fusedRaw into compute()", methodNames(split), hasItem("compute"));
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
        }
    }

    public void testMaxFusedMethodBytecodesRejectsNonPositive() {
        int previous = Stitcher.maxFusedMethodBytecodes;
        try {
            IllegalArgumentException zero = expectThrows(IllegalArgumentException.class, () -> Stitcher.maxFusedMethodBytecodesForTests(0));
            assertThat(zero.getMessage(), containsString("0"));
            IllegalArgumentException neg = expectThrows(IllegalArgumentException.class, () -> Stitcher.maxFusedMethodBytecodesForTests(-1));
            assertThat(neg.getMessage(), containsString("-1"));
            assertThat("a rejected set must leave the budget unchanged", Stitcher.maxFusedMethodBytecodes, is(previous));
        } finally {
            Stitcher.maxFusedMethodBytecodesForTests(previous);
        }
    }

    /**
     * Left-leaning chain of {@code depth} {@code addExact} kernels reusing two inputs:
     * {@code (((in0 + in1) + in1) + in1)...}.
     */
    private static FusionNode addChain(int depth) {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionNode node = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        for (int i = 1; i < depth; i++) {
            node = new FusionNode.Kernel(add, List.of(node, new FusionNode.Input(1)));
        }
        return node;
    }

    private static List<String> methodNames(byte[] bytecode) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }
}
