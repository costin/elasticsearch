/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The concrete {@code compute.data} types and opcodes for one supported primitive element kind. Resolving these
 * once keeps the emit code free of per-type branching and confines the {@code long}/{@code int}/{@code double}/
 * {@code boolean} differences to a single {@link #of(Type)} table — the emit paths never hardcode a specific
 * primitive, they read everything (array-vector class, raw-array descriptor, block/builder types, get/append
 * methods, the {@code Type} that derives every load/store/return opcode) from here, so a new primitive is a
 * pure data-add.
 *
 * <p><b>float:</b> deliberately absent. It would be one more {@code case Type.FLOAT} here (plus a
 * {@code FloatArrayVector.rawValues()} exposed through {@code VectorUnsafe.floats}), but no {@code float} arithmetic
 * kernel is {@code @Fusable} today, so the planner never types a node {@code float} and this table is never asked
 * for it — adding it now would be untested dead code. When a fusable {@code float} op lands, add those two data
 * entries and it flows through every path unchanged.
 *
 * <p>Extracted from {@link Stitcher} (it was a private nested record) so the emit-time type table lives on its own
 * and can be read/tested independently; behaviour is unchanged.
 */
record Element(
    String arrayVectorInternalName,
    String rawValuesDescriptor,
    String vectorDescriptor,
    String newVectorMethod,
    String newVectorDescriptor,
    int newArrayType,
    String blockInternalName,
    String builderInternalName,
    String newBlockBuilderMethod,
    String getValueMethod,
    String appendMethod,
    Type type
) {
    static Element of(Type t) {
        return switch (t.getSort()) {
            case Type.LONG -> new Element(
                "org/elasticsearch/compute/data/LongArrayVector",
                "()[J",
                "Lorg/elasticsearch/compute/data/LongVector;",
                "newLongArrayVector",
                "([JI)Lorg/elasticsearch/compute/data/LongVector;",
                Opcodes.T_LONG,
                "org/elasticsearch/compute/data/LongBlock",
                "org/elasticsearch/compute/data/LongBlock$Builder",
                "newLongBlockBuilder",
                "getLong",
                "appendLong",
                t
            );
            case Type.INT -> new Element(
                "org/elasticsearch/compute/data/IntArrayVector",
                "()[I",
                "Lorg/elasticsearch/compute/data/IntVector;",
                "newIntArrayVector",
                "([II)Lorg/elasticsearch/compute/data/IntVector;",
                Opcodes.T_INT,
                "org/elasticsearch/compute/data/IntBlock",
                "org/elasticsearch/compute/data/IntBlock$Builder",
                "newIntBlockBuilder",
                "getInt",
                "appendInt",
                t
            );
            case Type.DOUBLE -> new Element(
                "org/elasticsearch/compute/data/DoubleArrayVector",
                "()[D",
                "Lorg/elasticsearch/compute/data/DoubleVector;",
                "newDoubleArrayVector",
                "([DI)Lorg/elasticsearch/compute/data/DoubleVector;",
                Opcodes.T_DOUBLE,
                "org/elasticsearch/compute/data/DoubleBlock",
                "org/elasticsearch/compute/data/DoubleBlock$Builder",
                "newDoubleBlockBuilder",
                "getDouble",
                "appendDouble",
                t
            );
            // BOOLEAN is an OUTPUT-only element in this scope: it is the return kind of a comparison-rooted tree
            // (e.g. a > b). Its input reads (rawValues()[Z, getBoolean, BALOAD) are wired for completeness/symmetry,
            // but fusion leaves are Attribute-only numeric columns today, so a boolean element is never an INPUT.
            // A boolean occupies one slot and rides the operand stack as an int (0/1), so BALOAD/BASTORE and the
            // ILOAD/ISTORE the emitters derive via Type#getOpcode all resolve to the int-category boolean opcodes.
            case Type.BOOLEAN -> new Element(
                "org/elasticsearch/compute/data/BooleanArrayVector",
                "()[Z",
                "Lorg/elasticsearch/compute/data/BooleanVector;",
                "newBooleanArrayVector",
                "([ZI)Lorg/elasticsearch/compute/data/BooleanVector;",
                Opcodes.T_BOOLEAN,
                "org/elasticsearch/compute/data/BooleanBlock",
                "org/elasticsearch/compute/data/BooleanBlock$Builder",
                "newBooleanBlockBuilder",
                "getBoolean",
                "appendBoolean",
                t
            );
            // BYTES_REF is a reference element (keyword/text). The VECTOR-path fields (rawValues array + ArrayVector
            // forwarder) stay unset: a variable-width BytesRef has no dense primitive backing array, so a
            // BytesRef-in or BytesRef-OUT tree is always routed through the BLOCK path. As an INPUT it is read via
            // getBytesRef(int, spare); as an OUTPUT (B3b: BytesRef-returning kernels like TO_LOWER) it is appended
            // through the BytesRefBlock.Builder — appendBytesRef copies the (reused) spare's bytes into block
            // storage, so the block-builder fields below are now populated for the output path.
            case Type.OBJECT -> {
                if (Stitcher.BYTES_REF.equals(t.getInternalName()) == false) {
                    throw new IllegalArgumentException("unsupported fused reference element type: " + t.getClassName());
                }
                yield new Element(
                    null,
                    null,
                    null,
                    null,
                    null,
                    -1,
                    "org/elasticsearch/compute/data/BytesRefBlock",
                    "org/elasticsearch/compute/data/BytesRefBlock$Builder",
                    "newBytesRefBlockBuilder",
                    "getBytesRef",
                    "appendBytesRef",
                    t
                );
            }
            default -> throw new IllegalArgumentException("unsupported fused output element type: " + t.getClassName());
        };
    }

    /**
     * Whether this element is reference-typed (an object, i.e. {@code BYTES_REF}) rather than a primitive. A
     * reference leaf is read into a reusable spare via {@code getBytesRef(int, BytesRef)} and rides {@code ALOAD}/
     * {@code ASTORE} slots (both derived from {@link Type#getOpcode}); its argument slot is seeded with
     * {@code null}. Primitive elements have no such spare and read via the one-arg {@code get*(int)} accessor.
     */
    boolean reference() {
        return type.getSort() == Type.OBJECT;
    }

    /** JVM type descriptor of the concrete {@code *Block}, e.g. {@code Lorg/.../LongBlock;}. */
    String blockDescriptor() {
        return "L" + blockInternalName + ";";
    }

    /**
     * Name of the {@link org.elasticsearch.compute.data.VectorUnsafe} static forwarder that returns this element's
     * backing array, e.g. {@code longs} for {@code long[]}. The fused vector loop calls it via {@code INVOKESTATIC}
     * (instead of the package-private {@code INVOKEVIRTUAL rawValues()}), so the hidden class can be defined in the
     * caller's own module rather than teleported into {@code compute.data}.
     */
    String vectorUnsafeMethod() {
        return switch (type.getSort()) {
            case Type.LONG -> "longs";
            case Type.INT -> "ints";
            case Type.DOUBLE -> "doubles";
            case Type.BOOLEAN -> "booleans";
            default -> throw new IllegalArgumentException("no VectorUnsafe accessor for element type: " + type.getClassName());
        };
    }

    /**
     * Descriptor of the {@link org.elasticsearch.compute.data.VectorUnsafe} forwarder for this element, e.g.
     * {@code (Lorg/.../LongArrayVector;)[J}: it takes the concrete {@code *ArrayVector} (narrowed once by the
     * emitted {@code CHECKCAST}) and returns the backing primitive array. Derived from {@link #rawValuesDescriptor}
     * (a {@code ()[X}) by threading the {@code *ArrayVector} in as the sole argument.
     */
    String vectorUnsafeDescriptor() {
        return "(L" + arrayVectorInternalName + ";)" + rawValuesDescriptor.substring(2);
    }

    /** JVM type descriptor of the concrete {@code *Block.Builder}, e.g. {@code Lorg/.../LongBlock$Builder;}. */
    String builderDescriptor() {
        return "L" + builderInternalName + ";";
    }

    /** Descriptor of {@code BlockFactory.new*BlockBuilder(int)} -> {@code *Block.Builder}. */
    String newBlockBuilderDescriptor() {
        return "(I)" + builderDescriptor();
    }

    /**
     * Descriptor of the concrete {@code *Block} value accessor. For a primitive element this is
     * {@code *Block.get*(int)} -> primitive, e.g. {@code (I)J} for {@code getLong}. For a reference element it is
     * {@code BytesRefBlock.getBytesRef(int, BytesRef)} -> {@code BytesRef}: the value is read into a caller-supplied
     * reusable spare (pushed by the emitter before the call), e.g.
     * {@code (ILorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;}.
     */
    String getValueDescriptor() {
        if (reference()) {
            return "(I" + type.getDescriptor() + ")" + type.getDescriptor();
        }
        return "(I)" + type.getDescriptor();
    }

    /** Descriptor of the fluent {@code *Block.Builder.append*(primitive)} -> {@code *Block.Builder}. */
    String appendDescriptor() {
        return "(" + type.getDescriptor() + ")" + builderDescriptor();
    }

    /** Descriptor of the fluent {@code *Block.Builder.appendNull()} -> {@code *Block.Builder}. */
    String appendNullDescriptor() {
        return "()" + builderDescriptor();
    }

    /** Descriptor of {@code *Block.Builder.build()} -> {@code *Block}. */
    String buildDescriptor() {
        return "()" + blockDescriptor();
    }
}
