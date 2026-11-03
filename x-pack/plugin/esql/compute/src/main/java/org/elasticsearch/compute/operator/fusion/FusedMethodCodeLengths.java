/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal class-file walk over freshly written fused-class bytes: reports each method's {@code Code} attribute
 * {@code code_length} and role. No class loading, no ASM parsing. {@link Stitcher#serializeWithBudgetGuard} and the fusion
 * tests call these methods directly.
 *
 * <p><b>Roles.</b> A fused class has two kinds of method, told apart by their access flag rather than their name:
 * a {@code private} method is a per-row <em>helper</em> (only {@link Stitcher#newFusedHelper} makes one, and it is only
 * ever called from inside the class), any other method is a <em>root</em> the factory looks up and invokes
 * ({@link Stitcher#newFusedMethod}, always {@code public}).
 */
final class FusedMethodCodeLengths {

    private FusedMethodCodeLengths() {}

    /** One method of a fused class: its name, whether it is a per-row helper (see the class javadoc), its code length. */
    record MethodSize(String name, boolean helper, int codeLength) {}

    /** Every method that has a {@code Code} attribute, in class-file order. */
    static List<MethodSize> methodSizes(byte[] classBytes) {
        List<MethodSize> sizes = new ArrayList<>();
        visitMethodCodeLengths(classBytes, (name, access, length) -> sizes.add(new MethodSize(name, isHelper(access), length)));
        return sizes;
    }

    /**
     * The largest helper (when {@code helpers}) or root (otherwise) by {@code Code} length, or {@code null} when the
     * class has no method of that role. The budget guard measures roots and helpers against different limits.
     */
    static MethodSize largest(byte[] classBytes, boolean helpers) {
        MethodSize largest = null;
        for (MethodSize size : methodSizes(classBytes)) {
            if (size.helper() == helpers && (largest == null || size.codeLength() > largest.codeLength())) {
                largest = size;
            }
        }
        return largest;
    }

    private static boolean isHelper(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    /**
     * {@code Code} length of the {@link Stitcher#FUSED_METHOD_NAME} method, or 0 if that method (or its {@code Code}
     * attribute) is absent.
     */
    static int fusedMethodCodeLength(byte[] classBytes) {
        return methodCodeLength(classBytes, Stitcher.FUSED_METHOD_NAME);
    }

    /**
     * Largest {@code Code} {@code code_length} over every method, whatever its role. LOGICAL_VECTOR's
     * {@code fusedInterface}, CHECKED's {@code fusedRaw}/{@code fusedRawInterface}, and logical-block {@code cmpN}
     * helpers are all measured. The budget guard itself measures by role, via {@link #largest}.
     */
    static int maxMethodCodeLength(byte[] classBytes) {
        int[] max = { 0 };
        visitMethodCodeLengths(classBytes, (name, access, length) -> {
            if (length > max[0]) {
                max[0] = length;
            }
        });
        return max[0];
    }

    /**
     * Same walk for an arbitrary static method name — used to budget {@link Stitcher#FUSED_RAW_METHOD_NAME}
     * independently of the builder-based {@code fused} sibling on the same class.
     */
    static int methodCodeLength(byte[] classBytes, String methodName) {
        int[] found = { 0 };
        visitMethodCodeLengths(classBytes, (name, access, length) -> {
            if (methodName.equals(name)) {
                found[0] = length;
            }
        });
        return found[0];
    }

    @FunctionalInterface
    private interface Visitor {
        void accept(String methodName, int accessFlags, int codeLength);
    }

    /**
     * Walks every method of {@code classBytes} and reports each {@code Code} attribute's {@code code_length} with the
     * method's access flags. Methods without a {@code Code} attribute are skipped.
     */
    private static void visitMethodCodeLengths(byte[] classBytes, Visitor visitor) {
        ByteBuffer buf = ByteBuffer.wrap(classBytes); // class files are big-endian, ByteBuffer's default
        buf.getInt();   // magic
        buf.getShort(); // minor_version
        buf.getShort(); // major_version
        int cpCount = buf.getShort() & 0xFFFF;
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = buf.get() & 0xFF;
            switch (tag) {
                case 1 -> { // CONSTANT_Utf8
                    int len = buf.getShort() & 0xFFFF;
                    byte[] b = new byte[len];
                    buf.get(b);
                    utf8[i] = new String(b, StandardCharsets.UTF_8);
                }
                case 7, 8, 16, 19, 20 -> buf.getShort();          // Class, String, MethodType, Module, Package
                case 15 -> {
                    buf.get();       // MethodHandle: reference_kind
                    buf.getShort();  // reference_index
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> buf.getInt(); // Integer/Float/Fieldref/Methodref/IMethodref/NameAndType/(Invoke)Dynamic
                case 5, 6 -> {                                    // Long/Double occupy two constant-pool slots
                    buf.getLong();
                    i++;
                }
                default -> throw new IllegalStateException("unexpected constant-pool tag [" + tag + "] measuring fused method");
            }
        }
        buf.getShort(); // access_flags
        buf.getShort(); // this_class
        buf.getShort(); // super_class
        int interfaces = buf.getShort() & 0xFFFF;
        buf.position(buf.position() + interfaces * 2);
        int fields = buf.getShort() & 0xFFFF;
        for (int f = 0; f < fields; f++) {
            buf.getShort(); // access_flags
            buf.getShort(); // name_index
            buf.getShort(); // descriptor_index
            skipAttributes(buf);
        }
        int methods = buf.getShort() & 0xFFFF;
        for (int m = 0; m < methods; m++) {
            int accessFlags = buf.getShort() & 0xFFFF;
            int nameIndex = buf.getShort() & 0xFFFF;
            buf.getShort(); // descriptor_index
            int attributeCount = buf.getShort() & 0xFFFF;
            String methodName = utf8[nameIndex];
            for (int a = 0; a < attributeCount; a++) {
                int attrNameIndex = buf.getShort() & 0xFFFF;
                int attrLength = buf.getInt();
                int attrStart = buf.position();
                if ("Code".equals(utf8[attrNameIndex])) {
                    buf.getShort(); // max_stack
                    buf.getShort(); // max_locals
                    visitor.accept(methodName, accessFlags, buf.getInt()); // code_length
                }
                buf.position(attrStart + attrLength);
            }
        }
    }

    private static void skipAttributes(ByteBuffer buf) {
        int attributeCount = buf.getShort() & 0xFFFF;
        for (int a = 0; a < attributeCount; a++) {
            buf.getShort(); // attribute_name_index
            int attrLength = buf.getInt();
            buf.position(buf.position() + attrLength);
        }
    }
}
