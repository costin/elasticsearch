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

/**
 * Structural proof for the arithmetic INTERFACE-VECTOR siblings of {@link Stitcher#compileVectorLoop} and
 * {@link Stitcher#compileVectorLoopChecked}: the all-interface methods must read via typed {@code *Vector.getX}
 * (not {@code VectorUnsafe}) and must not invoke {@code Block} or any typed {@code *Block}. Builder-forbidding is
 * scoped to the raw-output methods ({@code fused}/{@code fusedInterface} on PLAIN, {@code fusedRaw}/{@code
 * fusedRawInterface} on CHECKED); the checked builder {@code fusedInterface} legitimately uses {@code *Block$Builder}
 * for overflow output. Owners are matched with {@link ForbiddenHotPathOwners} so a typed {@code LongBlock.getLong}
 * or {@code BooleanVectorBuilder.appendBoolean} cannot slip past an exact-name filter.
 */
public class StitcherArithmeticVectorInterfaceTests extends ESTestCase {

    public static final class LongKernels {
        public static long add(long a, long b) {
            return a + b;
        }

        public static long mul(long a, long b) {
            return a * b;
        }

        public static long addExact(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mulExact(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    }

    private static final FusionDescriptor ADD = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", false, true);
    private static final FusionDescriptor MUL = new FusionDescriptor(LongKernels.class, "mul", "(JJ)J", false, true);
    private static final FusionDescriptor ADD_EXACT = new FusionDescriptor(LongKernels.class, "addExact", "(JJ)J", true, true);
    private static final FusionDescriptor MUL_EXACT = new FusionDescriptor(LongKernels.class, "mulExact", "(JJ)J", true, true);

    private static final String VECTOR_UNSAFE = "org/elasticsearch/compute/data/VectorUnsafe";

    private Stitcher stitcher;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        stitcher = new Stitcher(new TemplateRegistry());
    }

    /**
     * PLAIN_VECTOR emits both {@code fused} (ALL-RAW) and {@code fusedInterface}. The interface method has typed
     * {@code *Vector.getX} getters, no {@code INVOKEINTERFACE}/{@code INVOKEVIRTUAL} on {@code Block} or any typed
     * {@code *Block}, no compute-data {@code *Builder}, and no {@code VectorUnsafe} reads. The raw method uses
     * {@code VectorUnsafe} and must not call those getters. {@code *Vector.asBlock()} and
     * {@code BlockFactory.new*ArrayVector} stay allowed (owners are not {@code *Block}/{@code *Builder}).
     */
    public void testPlainVectorInterfaceHasTypedGettersAndNoBlockOrBuilder() throws Exception {
        byte[] bytecode = stitcher.vectorLoopBytecode(MethodHandles.lookup(), addThenMul(ADD, MUL));
        assertStructurallyValid(bytecode);

        List<String> methods = new ArrayList<>();
        List<String> interfaceViolations = new ArrayList<>();
        int[] interfaceGetters = { 0 };
        int[] interfaceUnsafe = { 0 };
        int[] rawGetters = { 0 };
        int[] rawUnsafe = { 0 };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name);
                boolean isInterfaceMethod = Stitcher.FUSED_INTERFACE_METHOD_NAME.equals(name);
                boolean isRawMethod = Stitcher.FUSED_METHOD_NAME.equals(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (isInterfaceMethod) {
                            if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && ForbiddenHotPathOwners.isBlockOwner(owner)) {
                                interfaceViolations.add(name + ": " + invokeOpcodeName(opcode) + " " + owner + "." + mName + mDesc);
                            }
                            if (ForbiddenHotPathOwners.isBuilderOwner(owner)) {
                                interfaceViolations.add(name + ": Builder call " + owner + "." + mName);
                            }
                            if (typedGetter(opcode, owner, mName)) {
                                interfaceGetters[0]++;
                            }
                            if (opcode == Opcodes.INVOKESTATIC && VECTOR_UNSAFE.equals(owner)) {
                                interfaceUnsafe[0]++;
                            }
                        }
                        if (isRawMethod && typedGetter(opcode, owner, mName)) {
                            rawGetters[0]++;
                        }
                        if (isRawMethod && opcode == Opcodes.INVOKESTATIC && VECTOR_UNSAFE.equals(owner)) {
                            rawUnsafe[0]++;
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (isInterfaceMethod && ForbiddenHotPathOwners.isBuilderOwner(type)) {
                            interfaceViolations.add(name + ": Builder type " + type);
                        }
                    }
                };
            }
        }, 0);

        assertThat("emitted both fused and fusedInterface", methods, hasItem(Stitcher.FUSED_METHOD_NAME));
        assertThat("emitted both fused and fusedInterface", methods, hasItem(Stitcher.FUSED_INTERFACE_METHOD_NAME));
        assertThat(
            "plain fusedInterface must not invoke compute/data *Block or *Builder: " + interfaceViolations,
            interfaceViolations,
            is(empty())
        );
        assertThat("fusedInterface must INVOKEINTERFACE getLong/getInt/getDouble on a *Vector", interfaceGetters[0], greaterThan(0));
        assertThat("fusedInterface must not INVOKESTATIC VectorUnsafe", interfaceUnsafe[0], is(0));
        assertThat("fused ALL-RAW must INVOKESTATIC VectorUnsafe", rawUnsafe[0], greaterThan(0));
        assertThat("fused ALL-RAW must not call typed *Vector getters", rawGetters[0], is(0));
    }

    /**
     * CHECKED_VECTOR emits {@code fused}/{@code fusedInterface} (builder) and {@code fusedRaw}/{@code fusedRawInterface}
     * (dense array output). Every interface method has typed {@code *Vector.getX} and no {@code VectorUnsafe} reads.
     * {@code fusedRawInterface} must not touch any compute-data {@code *Builder} or invoke {@code Block}/{@code *Block};
     * the builder {@code fusedInterface} may. {@code fusedRaw} uses {@code VectorUnsafe} and must not call typed getters.
     */
    public void testCheckedVectorInterfaceHasTypedGettersAndFusedRawInterfaceHasNoBuilder() throws Exception {
        byte[] bytecode = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), addThenMul(ADD_EXACT, MUL_EXACT));
        assertStructurallyValid(bytecode);

        List<String> methods = new ArrayList<>();
        List<String> rawInterfaceViolations = new ArrayList<>();
        int[] fusedInterfaceGetters = { 0 };
        int[] fusedRawInterfaceGetters = { 0 };
        int[] fusedRawInterfaceUnsafe = { 0 };
        int[] fusedRawGetters = { 0 };
        int[] fusedRawUnsafe = { 0 };
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name);
                boolean isFusedInterface = Stitcher.FUSED_INTERFACE_METHOD_NAME.equals(name);
                boolean isRawInterface = Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME.equals(name);
                boolean isFusedRaw = Stitcher.FUSED_RAW_METHOD_NAME.equals(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (isRawInterface) {
                            if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && ForbiddenHotPathOwners.isBlockOwner(owner)) {
                                rawInterfaceViolations.add(name + ": " + invokeOpcodeName(opcode) + " " + owner + "." + mName + mDesc);
                            }
                            if (ForbiddenHotPathOwners.isBuilderOwner(owner)) {
                                rawInterfaceViolations.add(name + ": Builder call " + owner + "." + mName);
                            }
                            if (opcode == Opcodes.INVOKESTATIC && VECTOR_UNSAFE.equals(owner)) {
                                fusedRawInterfaceUnsafe[0]++;
                            }
                        }
                        if (typedGetter(opcode, owner, mName)) {
                            if (isFusedInterface) {
                                fusedInterfaceGetters[0]++;
                            }
                            if (isRawInterface) {
                                fusedRawInterfaceGetters[0]++;
                            }
                            if (isFusedRaw) {
                                fusedRawGetters[0]++;
                            }
                        }
                        if (isFusedRaw && opcode == Opcodes.INVOKESTATIC && VECTOR_UNSAFE.equals(owner)) {
                            fusedRawUnsafe[0]++;
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (isRawInterface && ForbiddenHotPathOwners.isBuilderOwner(type)) {
                            rawInterfaceViolations.add(name + ": Builder type " + type);
                        }
                    }
                };
            }
        }, 0);

        assertThat("checked-vector class must emit fused", methods, hasItem(Stitcher.FUSED_METHOD_NAME));
        assertThat("checked-vector class must emit fusedInterface", methods, hasItem(Stitcher.FUSED_INTERFACE_METHOD_NAME));
        assertThat("checked-vector class must emit fusedRaw", methods, hasItem(Stitcher.FUSED_RAW_METHOD_NAME));
        assertThat("checked-vector class must emit fusedRawInterface", methods, hasItem(Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME));
        assertThat(
            "fusedRawInterface must not invoke compute/data *Block or *Builder: " + rawInterfaceViolations,
            rawInterfaceViolations,
            is(empty())
        );
        assertThat("fusedInterface must INVOKEINTERFACE getLong/getInt/getDouble on a *Vector", fusedInterfaceGetters[0], greaterThan(0));
        assertThat(
            "fusedRawInterface must INVOKEINTERFACE getLong/getInt/getDouble on a *Vector",
            fusedRawInterfaceGetters[0],
            greaterThan(0)
        );
        assertThat("fusedRawInterface must not INVOKESTATIC VectorUnsafe", fusedRawInterfaceUnsafe[0], is(0));
        assertThat("fusedRaw ALL-RAW must INVOKESTATIC VectorUnsafe", fusedRawUnsafe[0], greaterThan(0));
        assertThat("fusedRaw ALL-RAW must not call typed *Vector getters", fusedRawGetters[0], is(0));
    }

    /** Defining the hidden classes succeeds (JVM verifier) and exposes the interface method names. */
    public void testCompilePlainAndCheckedDefineInterfaceMethods() throws Exception {
        Class<?> plain = stitcher.compileVectorLoop(MethodHandles.lookup(), addThenMul(ADD, MUL));
        assertThat(plain.getPackageName(), is(getClass().getPackageName()));
        assertThat(methodNames(plain), hasItem(Stitcher.FUSED_INTERFACE_METHOD_NAME));
        Class<?> checked = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), addThenMul(ADD_EXACT, MUL_EXACT));
        assertThat(checked.getPackageName(), is(getClass().getPackageName()));
        assertThat(methodNames(checked), hasItem(Stitcher.FUSED_INTERFACE_METHOD_NAME));
        assertThat(methodNames(checked), hasItem(Stitcher.FUSED_RAW_INTERFACE_METHOD_NAME));
    }

    private static List<String> methodNames(Class<?> fused) {
        List<String> names = new ArrayList<>();
        for (var method : fused.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private static boolean typedGetter(int opcode, String owner, String mName) {
        return opcode == Opcodes.INVOKEINTERFACE
            && owner.contains("compute/data")
            && owner.endsWith("Vector")
            && (mName.equals("getLong") || mName.equals("getInt") || mName.equals("getDouble"));
    }

    /** Only called after the visitor has already gated on {@code INVOKEINTERFACE} or {@code INVOKEVIRTUAL}. */
    private static String invokeOpcodeName(int opcode) {
        return opcode == Opcodes.INVOKEINTERFACE ? "INVOKEINTERFACE" : "INVOKEVIRTUAL";
    }

    private static FusionNode addThenMul(FusionDescriptor add, FusionDescriptor mul) {
        return new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    private static void assertStructurallyValid(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }
}
