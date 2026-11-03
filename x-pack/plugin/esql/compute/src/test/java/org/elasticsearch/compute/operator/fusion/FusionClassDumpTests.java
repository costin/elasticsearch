/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.operator.fusion.BodyExtractorTests.KernelFixtures;
import org.elasticsearch.compute.operator.fusion.StitcherBudgetTests.ArithmeticKernels;
import org.elasticsearch.compute.operator.fusion.StitcherBudgetTests.CastKernels;
import org.elasticsearch.compute.operator.fusion.StitcherBudgetTests.ComparisonKernels;
import org.elasticsearch.compute.operator.fusion.StitcherBudgetTests.StringKernels;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.test.ESTestCase;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

/** Fusion class dump corpus: emit fused class bytes and a sha256-per-shape list. */
public class FusionClassDumpTests extends ESTestCase {

    private static final FusionDescriptor ADD = new FusionDescriptor(
        ArithmeticKernels.class,
        "add",
        "(JJ)J",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor MUL = new FusionDescriptor(
        ArithmeticKernels.class,
        "mul",
        "(JJ)J",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor MUL_INT = new FusionDescriptor(
        ArithmeticKernels.class,
        "mulInt",
        "(II)I",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor ADD_INT = new FusionDescriptor(
        ArithmeticKernels.class,
        "addInt",
        "(II)I",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor ADD_DOUBLE = new FusionDescriptor(
        ArithmeticKernels.class,
        "addDouble",
        "(DD)D",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor DIV = new FusionDescriptor(
        KernelFixtures.class,
        "processLongsDiv",
        "(JJ)J",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor MOD = new FusionDescriptor(
        KernelFixtures.class,
        "processLongsMod",
        "(JJ)J",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor DIV_DOUBLE = new FusionDescriptor(
        ArithmeticKernels.class,
        "divDouble",
        "(DD)D",
        true,
        true,
        "java.lang.ArithmeticException",
        false,
        false,
        true,
        true
    );
    private static final FusionDescriptor GT = new FusionDescriptor(
        ComparisonKernels.class,
        "gt",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor LT = new FusionDescriptor(
        ComparisonKernels.class,
        "lt",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor EQ = new FusionDescriptor(
        ComparisonKernels.class,
        "eq",
        "(JJ)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor GT_INT = new FusionDescriptor(
        ComparisonKernels.class,
        "gtInt",
        "(II)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor INT_TO_LONG = new FusionDescriptor(
        CastKernels.class,
        "intToLong",
        "(I)J",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor INT_TO_DOUBLE = new FusionDescriptor(
        CastKernels.class,
        "intToDouble",
        "(I)D",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor LENGTH = new FusionDescriptor(
        StringKernels.class,
        "length",
        "(Lorg/apache/lucene/util/BytesRef;)I",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor IDENTITY_BYTES = new FusionDescriptor(
        KernelFixtures.class,
        "processBytesRefIdentity",
        "(Lorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor EQ_BYTES = new FusionDescriptor(
        StringKernels.class,
        "eq",
        "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/BytesRef;)Z",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor LEFT = new FusionDescriptor(
        StringKernels.class,
        "left",
        "(Lorg/apache/lucene/util/BytesRef;ILorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        false,
        false,
        true
    );
    private static final FusionDescriptor CONCAT = new FusionDescriptor(
        StringKernels.class,
        "concat",
        "([Lorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        true,
        false,
        false,
        true
    );
    private static final FusionDescriptor TO_LOWER = new FusionDescriptor(
        StringKernels.class,
        "toLower",
        "(Lorg/apache/lucene/util/BytesRef;Ljava/util/Locale;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        true,
        false,
        true
    );
    /** warns=true, overflowChecked=false: catch IllegalArgumentException and registerException on the block path. */
    private static final FusionDescriptor WARN_ONLY = new FusionDescriptor(
        KernelFixtures.class,
        "processWarnOnNegative",
        "(I)I",
        false,
        true,
        "java.lang.IllegalArgumentException",
        false,
        false,
        true,
        true
    );

    /** Evidence-only: when {@code esql.fusion.dump.dir} is set, {@link #put} also writes {@code <shape>.class}. */
    private static Path dumpDir;

    public void testDumpFusionClassCorpus() throws Exception {
        String listProp = System.getProperty("esql.fusion.dump.list");
        assumeFalse("set -Desql.fusion.dump.list to write the fusion class dump sha256 list", listProp == null || listProp.isBlank());

        String dirProp = System.getProperty("esql.fusion.dump.dir");
        dumpDir = (dirProp == null || dirProp.isBlank()) ? null : PathUtils.get(dirProp);
        if (dumpDir != null) {
            Files.createDirectories(dumpDir);
        }

        TreeMap<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, ShapeEmitter> shape : corpus().entrySet()) {
            put(hashes, shape.getKey(), shape.getValue().emit());
        }

        assertEquals(
            "TE corpus minus deleted mapping_append_suffix plus shapes minus Vector-API comparison shapes is 38",
            38,
            hashes.size()
        );

        StringBuilder out = new StringBuilder();
        for (var e : hashes.entrySet()) {
            out.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        Path list = PathUtils.get(listProp);
        Files.createDirectories(list.getParent());
        Files.writeString(list, out.toString(), StandardCharsets.UTF_8);
        logger.info("wrote {} fusion sha256 lines to {}", hashes.size(), list.toAbsolutePath());
    }

    /**
     * Every per-row helper ({@code cmpN}) in the corpus fits the C2 inlining limit ({@code -XX:FreqInlineSize}), or the
     * stitcher refuses its shape for that reason. Logs the census: which shapes have helpers and the largest one.
     */
    public void testCorpusHelpersFitInliningLimit() throws Exception {
        int limit = Stitcher.maxFusedHelperBytecodes();
        List<String> withHelpers = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        String largest = "none";
        int largestSize = 0;
        Map<String, ShapeEmitter> corpus = corpus();
        for (Map.Entry<String, ShapeEmitter> shape : corpus.entrySet()) {
            byte[] bytecode;
            try {
                bytecode = shape.getValue().emit();
            } catch (Stitcher.StitchingException e) {
                assertThat(shape.getKey() + " may only be refused by the inlining limit", e.getMessage(), containsString("inlining limit"));
                refused.add(shape.getKey());
                continue;
            }
            boolean hasHelper = false;
            for (FusedMethodCodeLengths.MethodSize size : FusedMethodCodeLengths.methodSizes(bytecode)) {
                if (size.helper()) {
                    hasHelper = true;
                    assertThat(shape.getKey() + " helper " + size.name(), size.codeLength(), lessThanOrEqualTo(limit));
                    if (size.codeLength() > largestSize) {
                        largestSize = size.codeLength();
                        largest = shape.getKey() + "#" + size.name();
                    }
                }
            }
            if (hasHelper) {
                withHelpers.add(shape.getKey());
            }
        }
        logger.info(
            "fusion corpus helper census: {} of {} shapes have helpers {}; largest helper {} = {} bytecodes (limit {}); refused {}",
            withHelpers.size(),
            corpus.size(),
            withHelpers,
            largest,
            largestSize,
            limit,
            refused
        );
        assertThat("the logical block shapes carry cmpN helpers", withHelpers, not(empty()));
    }

    /** Emits one corpus shape's class bytes; the stitcher may refuse it. */
    @FunctionalInterface
    private interface ShapeEmitter {
        byte[] emit() throws Stitcher.StitchingException;
    }

    /** The corpus: shape name to the emit call that builds it, in a stable order. */
    private static Map<String, ShapeEmitter> corpus() {
        FusionTestEmitters emitters = new FusionTestEmitters();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Map<String, ShapeEmitter> shapes = new LinkedHashMap<>();

        shapes.put("plain_long_add_mul", () -> emitters.vectors.emitVectorLoop(lookup, addMulLong()));
        shapes.put("plain_int_mul", () -> emitters.vectors.emitVectorLoop(lookup, mulInt()));
        shapes.put("plain_double_add", () -> emitters.vectors.emitVectorLoop(lookup, addDouble()));
        shapes.put("plain_long_gt", () -> emitters.vectors.emitVectorLoop(lookup, gtLong()));
        shapes.put("plain_int_gt", () -> emitters.vectors.emitVectorLoop(lookup, gtInt()));
        shapes.put("plain_cast_int_to_long_add", () -> emitters.vectors.emitVectorLoop(lookup, castIntToLongAdd()));
        shapes.put("plain_runtime_constant", () -> emitters.vectors.emitVectorLoop(lookup, addRuntimeConstant()));
        shapes.put("plain_baked_div", () -> emitters.vectors.emitVectorLoop(lookup, bakedDiv()));

        shapes.put("checked_long_add_mul", () -> emitters.vectors.emitVectorLoopChecked(lookup, addMulLong()));
        shapes.put("checked_double_add", () -> emitters.vectors.emitVectorLoopChecked(lookup, addDouble()));
        shapes.put("checked_cast_int_to_long_add", () -> emitters.vectors.emitVectorLoopChecked(lookup, castIntToLongAdd()));
        shapes.put("checked_runtime_constant", () -> emitters.vectors.emitVectorLoopChecked(lookup, addRuntimeConstant()));
        shapes.put("checked_baked_div", () -> emitters.vectors.emitVectorLoopChecked(lookup, bakedDiv()));

        shapes.put("block_long_add_mul", () -> emitters.blocks.emitBlockLoop(lookup, addMulLong()));
        shapes.put("block_int_mul", () -> emitters.blocks.emitBlockLoop(lookup, mulInt()));
        shapes.put("block_double_add", () -> emitters.blocks.emitBlockLoop(lookup, addDouble()));
        shapes.put("block_long_gt", () -> emitters.blocks.emitBlockLoop(lookup, gtLong()));
        shapes.put("block_bytesref_identity", () -> emitters.blocks.emitBlockLoop(lookup, bytesRefIdentity()));
        shapes.put("block_bytesref_length_gt", () -> emitters.blocks.emitBlockLoop(lookup, lengthGtConst()));
        shapes.put("block_runtime_constant", () -> emitters.blocks.emitBlockLoop(lookup, addRuntimeConstant()));
        shapes.put("block_cast_int_to_long_add", () -> emitters.blocks.emitBlockLoop(lookup, castIntToLongAdd()));

        shapes.put("logical_block_and", () -> emitters.blocks.emitLogicalBlockLoop(lookup, andTree()));
        shapes.put("logical_block_or", () -> emitters.blocks.emitLogicalBlockLoop(lookup, orTree()));
        shapes.put("logical_block_not", () -> emitters.blocks.emitLogicalBlockLoop(lookup, notTree()));
        shapes.put("logical_block_nested", () -> emitters.blocks.emitLogicalBlockLoop(lookup, nestedLogical()));

        shapes.put("logical_vector_and", () -> emitters.vectors.emitLogicalVectorLoop(lookup, andTree()));
        shapes.put("logical_vector_or", () -> emitters.vectors.emitLogicalVectorLoop(lookup, orTree()));
        shapes.put("logical_vector_not", () -> emitters.vectors.emitLogicalVectorLoop(lookup, notTree()));

        shapes.put("block_k1_eq_left_k2", () -> emitters.blocks.emitBlockLoop(lookup, eqLeft()));
        shapes.put("block_double_div_const", () -> emitters.blocks.emitBlockLoop(lookup, doubleDivConst()));
        shapes.put("checked_baked_div_mod", () -> emitters.vectors.emitVectorLoopChecked(lookup, bakedDivMod()));
        shapes.put("block_length_concat", () -> emitters.blocks.emitBlockLoop(lookup, lengthConcat()));
        shapes.put("block_length_to_lower", () -> emitters.blocks.emitBlockLoop(lookup, lengthToLower()));
        shapes.put("logical_block_not_and_or", () -> emitters.blocks.emitLogicalBlockLoop(lookup, notAndOr()));
        shapes.put("checked_add_mul_right", () -> emitters.vectors.emitVectorLoopChecked(lookup, addMulRight()));
        shapes.put("plain_int_add_gt", () -> emitters.vectors.emitVectorLoop(lookup, intAddGt()));
        shapes.put("plain_cast_int_to_double_add", () -> emitters.vectors.emitVectorLoop(lookup, castIntToDoubleAdd()));
        shapes.put("block_warn_only", () -> emitters.blocks.emitBlockLoop(lookup, warnOnly()));
        return shapes;
    }

    private static void put(TreeMap<String, String> hashes, String shape, byte[] bytecode) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytecode);
        hashes.put(shape, HexFormat.of().formatHex(digest));
        if (dumpDir != null) {
            Files.write(dumpDir.resolve(shape + ".class"), bytecode);
        }
    }

    private static FusionNode addMulLong() {
        return new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    private static FusionNode mulInt() {
        return new FusionNode.Kernel(MUL_INT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode addDouble() {
        return new FusionNode.Kernel(ADD_DOUBLE, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode gtLong() {
        return new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode gtInt() {
        return new FusionNode.Kernel(GT_INT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode castIntToLongAdd() {
        return new FusionNode.Kernel(
            ADD,
            List.of(new FusionNode.Kernel(INT_TO_LONG, List.of(new FusionNode.Input(0))), new FusionNode.Input(1))
        );
    }

    private static FusionNode addRuntimeConstant() {
        return new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Constant(5L, 'J')));
    }

    private static FusionNode bakedDiv() {
        return new FusionNode.Kernel(
            DIV,
            List.of(
                new FusionNode.Kernel(MUL, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
                FusionNode.Constant.baked(1000L, 'J')
            )
        );
    }

    private static FusionNode bytesRefIdentity() {
        return new FusionNode.Kernel(IDENTITY_BYTES, List.of(new FusionNode.Input(0)));
    }

    private static FusionNode lengthGtConst() {
        return new FusionNode.Kernel(
            GT_INT,
            List.of(new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(0))), new FusionNode.Constant(3, 'I'))
        );
    }

    private static FusionNode andTree() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
    }

    private static FusionNode orTree() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.OR,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
    }

    private static FusionNode notTree() {
        return new FusionNode.Not(new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))));
    }

    private static FusionNode nestedLogical() {
        return new FusionNode.Logical(FusionNode.BoolOp.AND, orTree(), notTree());
    }

    /** {@code k1 == LEFT(k2, 2)}: two BytesRef columns, LEFT scratch, block-loop spares. */
    private static FusionNode eqLeft() {
        return new FusionNode.Kernel(
            EQ_BYTES,
            List.of(
                new FusionNode.Input(0),
                new FusionNode.Kernel(
                    LEFT,
                    List.of(
                        new FusionNode.Input(1),
                        new FusionNode.Constant(2, 'I'),
                        FusionNode.Constant.scratch(ctx -> new BytesRef(), BytesRef.class)
                    )
                )
            )
        );
    }

    /** {@code d / 3.0}: double constant is a trailing param, not a baked immediate. */
    private static FusionNode doubleDivConst() {
        return new FusionNode.Kernel(DIV_DOUBLE, List.of(new FusionNode.Input(0), new FusionNode.Constant(3.0, 'D')));
    }

    /** {@code (x / 7) % 3}: two baked long divisors (tuple). */
    private static FusionNode bakedDivMod() {
        return new FusionNode.Kernel(
            MOD,
            List.of(
                new FusionNode.Kernel(DIV, List.of(new FusionNode.Input(0), FusionNode.Constant.baked(7L, 'J'))),
                FusionNode.Constant.baked(3L, 'J')
            )
        );
    }

    /** {@code LENGTH(CONCAT(k, "x"))}: variadic gather, one fresh {@code new BytesRef} per element. */
    private static FusionNode lengthConcat() {
        return new FusionNode.Kernel(
            LENGTH,
            List.of(
                new FusionNode.Kernel(
                    CONCAT,
                    List.of(new FusionNode.Input(0), FusionNode.Constant.reference(new BytesRef("x"), BytesRef.class))
                )
            )
        );
    }

    /** {@code LENGTH(TO_LOWER(k))}: nested mapping kernel with a captured {@code Locale}. */
    private static FusionNode lengthToLower() {
        return new FusionNode.Kernel(
            LENGTH,
            List.of(
                new FusionNode.Kernel(TO_LOWER, List.of(new FusionNode.Input(0), FusionNode.Constant.reference(Locale.ROOT, Locale.class)))
            )
        );
    }

    /** {@code NOT (a > 1 AND b < 2) OR c == 3} on nullable blocks. */
    private static FusionNode notAndOr() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.OR,
            new FusionNode.Not(
                new FusionNode.Logical(
                    FusionNode.BoolOp.AND,
                    new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Constant(1L, 'J'))),
                    new FusionNode.Kernel(LT, List.of(new FusionNode.Input(1), new FusionNode.Constant(2L, 'J')))
                )
            ),
            new FusionNode.Kernel(EQ, List.of(new FusionNode.Input(2), new FusionNode.Constant(3L, 'J')))
        );
    }

    /** {@code a + b * c} on long vectors (overflow-checked). Distinct from {@link #addMulLong()} which is {@code (a+b)*c}. */
    private static FusionNode addMulRight() {
        return new FusionNode.Kernel(
            ADD,
            List.of(new FusionNode.Input(0), new FusionNode.Kernel(MUL, List.of(new FusionNode.Input(1), new FusionNode.Input(2))))
        );
    }

    /** {@code (a + b) > c} on int vectors. */
    private static FusionNode intAddGt() {
        return new FusionNode.Kernel(
            GT_INT,
            List.of(new FusionNode.Kernel(ADD_INT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    /** {@code TO_DOUBLE(i) + d}: mixed int/double inputs via a cast kernel. */
    private static FusionNode castIntToDoubleAdd() {
        return new FusionNode.Kernel(
            ADD_DOUBLE,
            List.of(new FusionNode.Kernel(INT_TO_DOUBLE, List.of(new FusionNode.Input(0))), new FusionNode.Input(1))
        );
    }

    /** Warning-only kernel (warns=true, no overflowChecked) on the block path. */
    private static FusionNode warnOnly() {
        return new FusionNode.Kernel(WARN_ONLY, List.of(new FusionNode.Input(0)));
    }
}
