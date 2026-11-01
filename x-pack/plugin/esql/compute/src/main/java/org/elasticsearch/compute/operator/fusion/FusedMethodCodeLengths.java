/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal class-file walk over freshly written fused-class bytes: reports each method's {@code Code} attribute
 * {@code code_length}. No class loading, no ASM. {@link Stitcher} keeps the public names tests already call.
 */
final class FusedMethodCodeLengths {

    private FusedMethodCodeLengths() {}

    /**
     * {@code Code} length of the {@link Stitcher#FUSED_METHOD_NAME} method, or 0 if that method (or its {@code Code}
     * attribute) is absent.
     */
    static int fusedMethodCodeLength(byte[] classBytes) {
        return methodCodeLength(classBytes, Stitcher.FUSED_METHOD_NAME);
    }

    /**
     * Largest {@code Code} {@code code_length} over every method. The huge-method guard refuses a class when this
     * exceeds {@link Stitcher#maxFusedMethodBytecodes}: a method above HotSpot's {@code -XX:HugeMethodLimit} (8000)
     * is never JIT-compiled. LOGICAL_VECTOR's {@code fusedInterface}, CHECKED's {@code fusedRaw}/{@code
     * fusedRawInterface}, filter-eval {@code pred}/{@code projAppend}, and logical-block {@code cmpN} helpers are
     * all measured.
     */
    static int maxMethodCodeLength(byte[] classBytes) {
        int[] max = { 0 };
        visitMethodCodeLengths(classBytes, (name, length) -> {
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
        visitMethodCodeLengths(classBytes, (name, length) -> {
            if (methodName.equals(name)) {
                found[0] = length;
            }
        });
        return found[0];
    }

    @FunctionalInterface
    private interface Visitor {
        void accept(String methodName, int codeLength);
    }

    /**
     * Walks every method of {@code classBytes} and reports each {@code Code} attribute's {@code code_length}.
     * Methods without a {@code Code} attribute are skipped.
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
            buf.getShort(); // access_flags
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
                    visitor.accept(methodName, buf.getInt()); // code_length
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
