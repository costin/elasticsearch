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
import org.elasticsearch.test.ESTestCase;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * E1 dump harness: emits fused class bytes for the TE structural corpus and writes a deterministic
 * {@code sha256  &lt;shape-label&gt;} list (sorted by label, no timestamps). Set
 * {@code -Desql.fusion.dump.list} (via {@code -Dtests.jvm.argline}) to the output path; skipped otherwise.
 *
 * <p>The original TE corpus is 34 shapes. The dump kept 33 after H11 deleted the unreachable mapping-block emit
 * path ({@code mapping_append_suffix} stays omitted). D.0a added 13 shapes that pin the paths later D refactors
 * touch (two-BytesRef-column, filter-eval BytesRef, double Div, baked divisor tuple, CONCAT varargs, nested
 * mapping, logical+Not, checked {@code a+b*c}, int array-vector boolean, mixed cast, warning-only). D.1 dropped
 * the three Vector-API comparison shapes. Total 43.
 */
public class FusionClassDumpTests extends ESTestCase {

    private static final FusionDescriptor ADD = new FusionDescriptor(ArithmeticKernels.class, "add", "(JJ)J", true, true);
    private static final FusionDescriptor MUL = new FusionDescriptor(ArithmeticKernels.class, "mul", "(JJ)J", true, true);
    private static final FusionDescriptor MUL_INT = new FusionDescriptor(ArithmeticKernels.class, "mulInt", "(II)I", true, true);
    private static final FusionDescriptor ADD_INT = new FusionDescriptor(ArithmeticKernels.class, "addInt", "(II)I", true, true);
    private static final FusionDescriptor ADD_DOUBLE = new FusionDescriptor(ArithmeticKernels.class, "addDouble", "(DD)D", true, true);
    private static final FusionDescriptor DIV = new FusionDescriptor(KernelFixtures.class, "processLongsDiv", "(JJ)J", true, true);
    private static final FusionDescriptor MOD = new FusionDescriptor(KernelFixtures.class, "processLongsMod", "(JJ)J", true, true);
    private static final FusionDescriptor DIV_DOUBLE = new FusionDescriptor(ArithmeticKernels.class, "divDouble", "(DD)D", true, true);
    private static final FusionDescriptor GT = new FusionDescriptor(ComparisonKernels.class, "gt", "(JJ)Z", false, true);
    private static final FusionDescriptor LT = new FusionDescriptor(ComparisonKernels.class, "lt", "(JJ)Z", false, true);
    private static final FusionDescriptor EQ = new FusionDescriptor(ComparisonKernels.class, "eq", "(JJ)Z", false, true);
    private static final FusionDescriptor GT_INT = new FusionDescriptor(ComparisonKernels.class, "gtInt", "(II)Z", false, true);
    private static final FusionDescriptor INT_TO_LONG = new FusionDescriptor(CastKernels.class, "intToLong", "(I)J", false, true);
    private static final FusionDescriptor INT_TO_DOUBLE = new FusionDescriptor(CastKernels.class, "intToDouble", "(I)D", false, true);
    private static final FusionDescriptor LENGTH = new FusionDescriptor(
        StringKernels.class,
        "length",
        "(Lorg/apache/lucene/util/BytesRef;)I",
        false,
        true
    );
    private static final FusionDescriptor IDENTITY_BYTES = new FusionDescriptor(
        KernelFixtures.class,
        "processBytesRefIdentity",
        "(Lorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true
    );
    private static final FusionDescriptor EQ_BYTES = new FusionDescriptor(
        StringKernels.class,
        "eq",
        "(Lorg/apache/lucene/util/BytesRef;Lorg/apache/lucene/util/BytesRef;)Z",
        false,
        true
    );
    private static final FusionDescriptor LEFT = new FusionDescriptor(
        StringKernels.class,
        "left",
        "(Lorg/apache/lucene/util/BytesRef;ILorg/apache/lucene/util/BytesRef;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true
    );
    /** Filter-eval LEFT: no scratch {@code 'L'} constant — {@code pushConstantArg} only loads J/I/D. */
    private static final FusionDescriptor LEFT_NO_SCRATCH = new FusionDescriptor(
        StringKernels.class,
        "left",
        "(Lorg/apache/lucene/util/BytesRef;I)Lorg/apache/lucene/util/BytesRef;",
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

    public void testDumpFusionClassCorpus() throws Exception {
        String listProp = System.getProperty("esql.fusion.dump.list");
        assumeFalse("set -Desql.fusion.dump.list to write the E1 sha256 list", listProp == null || listProp.isBlank());

        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        TreeMap<String, String> hashes = new TreeMap<>();

        put(hashes, "plain_long_add_mul", stitcher.vectorLoopBytecode(lookup, addMulLong()));
        put(hashes, "plain_int_mul", stitcher.vectorLoopBytecode(lookup, mulInt()));
        put(hashes, "plain_double_add", stitcher.vectorLoopBytecode(lookup, addDouble()));
        put(hashes, "plain_long_gt", stitcher.vectorLoopBytecode(lookup, gtLong()));
        put(hashes, "plain_int_gt", stitcher.vectorLoopBytecode(lookup, gtInt()));
        put(hashes, "plain_cast_int_to_long_add", stitcher.vectorLoopBytecode(lookup, castIntToLongAdd()));
        put(hashes, "plain_runtime_constant", stitcher.vectorLoopBytecode(lookup, addRuntimeConstant()));
        put(hashes, "plain_baked_div", stitcher.vectorLoopBytecode(lookup, bakedDiv()));

        put(hashes, "checked_long_add_mul", stitcher.vectorLoopCheckedBytecode(lookup, addMulLong()));
        put(hashes, "checked_double_add", stitcher.vectorLoopCheckedBytecode(lookup, addDouble()));
        put(hashes, "checked_cast_int_to_long_add", stitcher.vectorLoopCheckedBytecode(lookup, castIntToLongAdd()));
        put(hashes, "checked_runtime_constant", stitcher.vectorLoopCheckedBytecode(lookup, addRuntimeConstant()));
        put(hashes, "checked_baked_div", stitcher.vectorLoopCheckedBytecode(lookup, bakedDiv()));

        put(hashes, "block_long_add_mul", stitcher.blockLoopBytecode(lookup, addMulLong()));
        put(hashes, "block_int_mul", stitcher.blockLoopBytecode(lookup, mulInt()));
        put(hashes, "block_double_add", stitcher.blockLoopBytecode(lookup, addDouble()));
        put(hashes, "block_long_gt", stitcher.blockLoopBytecode(lookup, gtLong()));
        put(hashes, "block_bytesref_identity", stitcher.blockLoopBytecode(lookup, bytesRefIdentity()));
        put(hashes, "block_bytesref_length_gt", stitcher.blockLoopBytecode(lookup, lengthGtConst()));
        put(hashes, "block_runtime_constant", stitcher.blockLoopBytecode(lookup, addRuntimeConstant()));
        put(hashes, "block_cast_int_to_long_add", stitcher.blockLoopBytecode(lookup, castIntToLongAdd()));

        put(hashes, "logical_block_and", stitcher.logicalBlockLoopBytecode(lookup, andTree()));
        put(hashes, "logical_block_or", stitcher.logicalBlockLoopBytecode(lookup, orTree()));
        put(hashes, "logical_block_not", stitcher.logicalBlockLoopBytecode(lookup, notTree()));
        put(hashes, "logical_block_nested", stitcher.logicalBlockLoopBytecode(lookup, nestedLogical()));

        put(hashes, "logical_vector_and", stitcher.logicalVectorLoopBytecode(lookup, andTree()));
        put(hashes, "logical_vector_or", stitcher.logicalVectorLoopBytecode(lookup, orTree()));
        put(hashes, "logical_vector_not", stitcher.logicalVectorLoopBytecode(lookup, notTree()));

        put(hashes, "filter_eval_cmp", stitcher.filterEvalBlockLoopBytecode(lookup, gtOverAdd(), mulOverAdd()));
        put(hashes, "filter_eval_logical", stitcher.filterEvalBlockLoopBytecode(lookup, andTree(), addLong()));

        put(hashes, "block_k1_eq_left_k2", stitcher.blockLoopBytecode(lookup, eqLeft()));
        put(hashes, "filter_eval_k1_eq_k2", stitcher.filterEvalBlockLoopBytecode(lookup, eqBytes(), addLong()));
        put(hashes, "filter_eval_k1_eq_left_k2", stitcher.filterEvalBlockLoopBytecode(lookup, eqLeftNoScratch(), addLong()));
        put(hashes, "block_double_div_const", stitcher.blockLoopBytecode(lookup, doubleDivConst()));
        put(hashes, "checked_baked_div_mod", stitcher.vectorLoopCheckedBytecode(lookup, bakedDivMod()));
        put(hashes, "block_length_concat", stitcher.blockLoopBytecode(lookup, lengthConcat()));
        put(hashes, "block_length_to_lower", stitcher.blockLoopBytecode(lookup, lengthToLower()));
        put(hashes, "logical_block_not_and_or", stitcher.logicalBlockLoopBytecode(lookup, notAndOr()));
        put(hashes, "filter_eval_not_and_mul_add", stitcher.filterEvalBlockLoopBytecode(lookup, notAnd(), mulAddRight()));
        put(hashes, "checked_add_mul_right", stitcher.vectorLoopCheckedBytecode(lookup, addMulRight()));
        put(hashes, "plain_int_add_gt", stitcher.vectorLoopBytecode(lookup, intAddGt()));
        put(hashes, "plain_cast_int_to_double_add", stitcher.vectorLoopBytecode(lookup, castIntToDoubleAdd()));
        put(hashes, "block_warn_only", stitcher.blockLoopBytecode(lookup, warnOnly()));

        assertEquals(
            "TE corpus minus deleted mapping_append_suffix plus D.0a shapes minus D.1 Vector-API comparison shapes is 43",
            43,
            hashes.size()
        );

        StringBuilder out = new StringBuilder();
        for (var e : hashes.entrySet()) {
            out.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        Path list = Path.of(listProp);
        Files.createDirectories(list.getParent());
        Files.writeString(list, out.toString(), StandardCharsets.UTF_8);
        logger.info("wrote {} fusion sha256 lines to {}", hashes.size(), list.toAbsolutePath());
    }

    private static void put(TreeMap<String, String> hashes, String shape, byte[] bytecode) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytecode);
        hashes.put(shape, HexFormat.of().formatHex(digest));
    }

    private static FusionNode addMulLong() {
        return new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    private static FusionNode addLong() {
        return new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
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

    private static FusionNode gtOverAdd() {
        return new FusionNode.Kernel(
            GT,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    private static FusionNode mulOverAdd() {
        return new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
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

    /**
     * Filter-eval {@code k1 == LEFT(k2, 2)} without a scratch {@code 'L'} constant ({@code pushConstantArg} rejects
     * it). The pred helper still has two BytesRef columns, so H1 per-column spares land inside the helper.
     */
    private static FusionNode eqLeftNoScratch() {
        return new FusionNode.Kernel(
            EQ_BYTES,
            List.of(
                new FusionNode.Input(0),
                new FusionNode.Kernel(LEFT_NO_SCRATCH, List.of(new FusionNode.Input(1), new FusionNode.Constant(2, 'I')))
            )
        );
    }

    /** {@code k1 == k2} over two BytesRef columns. */
    private static FusionNode eqBytes() {
        return new FusionNode.Kernel(EQ_BYTES, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    /** {@code d / 3.0}: double constant is a trailing param, not a baked immediate. */
    private static FusionNode doubleDivConst() {
        return new FusionNode.Kernel(DIV_DOUBLE, List.of(new FusionNode.Input(0), new FusionNode.Constant(3.0, 'D')));
    }

    /** {@code (x / 7) % 3}: two baked long divisors (H3 tuple). */
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

    /** {@code NOT (a > 1) AND b < 2} — filter-eval logical predicate. */
    private static FusionNode notAnd() {
        return new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Not(new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Constant(1L, 'J')))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(1), new FusionNode.Constant(2L, 'J')))
        );
    }

    /** {@code a * 2 + b} — filter-eval projection for the logical-predicate shape. */
    private static FusionNode mulAddRight() {
        return new FusionNode.Kernel(
            ADD,
            List.of(new FusionNode.Kernel(MUL, List.of(new FusionNode.Input(0), new FusionNode.Constant(2L, 'J'))), new FusionNode.Input(1))
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
