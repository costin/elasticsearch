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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Structural proof for the dense logical vector path ({@link Stitcher#compileLogicalVectorLoop}): the emitted
 * class must evaluate an {@code AND}/{@code OR} tree over dense vectors without touching {@code Block}, any typed
 * {@code *Block}, or any compute-data {@code *Builder} (those are the 3VL block path's shared call sites). The
 * all-interface variant is allowed to {@code INVOKEINTERFACE} the concrete {@code *Vector} types, including the
 * allowed output wrapping {@code BooleanVector.asBlock()}. Owners are matched with {@link ForbiddenHotPathOwners}
 * so a typed {@code BooleanBlock.getBoolean} or {@code BooleanVectorBuilder.appendBoolean} cannot slip past an
 * exact-name filter.
 */
public class StitcherLogicalVectorFusionTests extends ESTestCase {

    public static final class ComparisonKernels {
        public static boolean gt(long a, long b) {
            return a > b;
        }

        public static boolean lt(long a, long b) {
            return a < b;
        }
    }

    private static final FusionDescriptor GT = new FusionDescriptor(ComparisonKernels.class, "gt", "(JJ)Z", false, true);
    private static final FusionDescriptor LT = new FusionDescriptor(ComparisonKernels.class, "lt", "(JJ)Z", false, true);

    private Stitcher stitcher;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        stitcher = new Stitcher(new TemplateRegistry());
    }

    /**
     * The emitted logical VECTOR method contains no {@code INVOKEINTERFACE}/{@code INVOKEVIRTUAL} on {@code Block}
     * or any typed {@code *Block}, and no reference to any compute-data {@code *Builder}. B2: the logical vector
     * class is INTERFACE-only ({@code fusedInterface} with typed {@code *Vector.getX}); the RAW {@code fused}
     * sibling is not emitted (it was the slow path). {@code BooleanVector.asBlock()} and
     * {@code BlockFactory.newBooleanArrayVector} stay allowed.
     */
    public void testLogicalVectorLoopHasNoBlockInterfaceOrBuilder() throws Exception {
        FusionNode tree = andGtLt();
        byte[] bytecode = stitcher.logicalVectorLoopBytecode(MethodHandles.lookup(), tree);
        assertStructurallyValid(bytecode);

        List<String> methods = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        // Count typed *Vector getters (getLong/getInt/getDouble) — NOT asBlock(), which the method also
        // calls on the BooleanVector result and which would make a class-wide >0 check vacuous.
        int[] interfaceGetters = { 0 };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name);
                boolean isInterfaceMethod = Stitcher.FUSED_INTERFACE_METHOD_NAME.equals(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                            && ForbiddenHotPathOwners.isBlockOwner(owner)) {
                            violations.add(name + ": " + invokeOpcodeName(opcode) + " " + owner + "." + mName + mDesc);
                        }
                        if (ForbiddenHotPathOwners.isBuilderOwner(owner)) {
                            violations.add(name + ": Builder call " + owner + "." + mName);
                        }
                        boolean typedGetter = opcode == Opcodes.INVOKEINTERFACE
                            && owner.contains("compute/data")
                            && owner.endsWith("Vector")
                            && (mName.equals("getLong") || mName.equals("getInt") || mName.equals("getDouble"));
                        if (isInterfaceMethod && typedGetter) {
                            interfaceGetters[0]++;
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (ForbiddenHotPathOwners.isBuilderOwner(type)) {
                            violations.add(name + ": Builder type " + type);
                        }
                    }
                };
            }
        }, 0);

        assertThat("logical vector is INTERFACE-only (B2): fusedInterface present", methods, hasItem(Stitcher.FUSED_INTERFACE_METHOD_NAME));
        assertThat("logical vector is INTERFACE-only (B2): no RAW fused method", methods, not(hasItem(Stitcher.FUSED_METHOD_NAME)));
        assertThat("logical VECTOR methods must not invoke compute/data *Block or *Builder: " + violations, violations, is(empty()));
        assertThat(
            "fusedInterface must INVOKEINTERFACE getLong/getInt/getDouble on a *Vector (asBlock does not count)",
            interfaceGetters[0],
            greaterThan(0)
        );
    }

    /**
     * Defining the hidden class succeeds (JVM verifier) for a representative {@code (a > b) AND (c < d)} tree.
     */
    public void testCompileLogicalVectorLoopDefines() throws Exception {
        Class<?> fused = stitcher.compileLogicalVectorLoop(MethodHandles.lookup(), andGtLt());
        assertThat(fused.getPackageName(), is(getClass().getPackageName()));
    }

    /** Only called after the visitor has already gated on {@code INVOKEINTERFACE} or {@code INVOKEVIRTUAL}. */
    private static String invokeOpcodeName(int opcode) {
        return opcode == Opcodes.INVOKEINTERFACE ? "INVOKEINTERFACE" : "INVOKEVIRTUAL";
    }

    private static FusionNode andGtLt() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
    }

    private static void assertStructurallyValid(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }
}
