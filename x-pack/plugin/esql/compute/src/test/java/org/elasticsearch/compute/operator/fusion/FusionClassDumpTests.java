/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

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
import java.util.TreeMap;

/**
 * E1 dump harness: emits fused class bytes for the TE structural corpus and writes a deterministic
 * {@code sha256  &lt;shape-label&gt;} list (sorted by label, no timestamps). Set
 * {@code -Desql.fusion.dump.list} (via {@code -Dtests.jvm.argline}) to the output path; skipped otherwise.
 *
 * <p>The original TE corpus is 34 shapes. This dump uses 33: {@code mapping_append_suffix} is omitted because
 * H11 deletes the unreachable mapping-block emit path. Historical H1/H2/H3 dumps use the same 33-shape list.
 */
public class FusionClassDumpTests extends ESTestCase {

    private static final FusionDescriptor ADD = new FusionDescriptor(ArithmeticKernels.class, "add", "(JJ)J", true, true);
    private static final FusionDescriptor MUL = new FusionDescriptor(ArithmeticKernels.class, "mul", "(JJ)J", true, true);
    private static final FusionDescriptor MUL_INT = new FusionDescriptor(ArithmeticKernels.class, "mulInt", "(II)I", true, true);
    private static final FusionDescriptor ADD_DOUBLE = new FusionDescriptor(ArithmeticKernels.class, "addDouble", "(DD)D", true, true);
    private static final FusionDescriptor DIV = new FusionDescriptor(KernelFixtures.class, "processLongsDiv", "(JJ)J", true, true);
    private static final FusionDescriptor GT = new FusionDescriptor(ComparisonKernels.class, "gt", "(JJ)Z", false, true);
    private static final FusionDescriptor LT = new FusionDescriptor(ComparisonKernels.class, "lt", "(JJ)Z", false, true);
    private static final FusionDescriptor GT_INT = new FusionDescriptor(ComparisonKernels.class, "gtInt", "(II)Z", false, true);
    private static final FusionDescriptor GT_LONG_FIX = new FusionDescriptor(
        KernelFixtures.class,
        "processLongsGreaterThan",
        "(JJ)Z",
        false,
        true
    );
    private static final FusionDescriptor GT_DOUBLE = new FusionDescriptor(
        KernelFixtures.class,
        "processDoublesGreaterThan",
        "(DD)Z",
        false,
        true
    );
    private static final FusionDescriptor INT_TO_LONG = new FusionDescriptor(CastKernels.class, "intToLong", "(I)J", false, true);
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

        put(hashes, "simd_long_gt", stitcher.simdVectorLoopBytecode(lookup, gtLongFix(), "GT"));
        put(hashes, "simd_double_gt", stitcher.simdVectorLoopBytecode(lookup, gtDouble(), "GT"));
        put(hashes, "simd_int_gt", stitcher.simdVectorLoopBytecode(lookup, gtInt(), "GT"));

        put(hashes, "filter_eval_cmp", stitcher.filterEvalBlockLoopBytecode(lookup, gtOverAdd(), mulOverAdd()));
        put(hashes, "filter_eval_logical", stitcher.filterEvalBlockLoopBytecode(lookup, andTree(), addLong()));

        assertEquals("TE corpus minus deleted mapping_append_suffix is 33 shapes", 33, hashes.size());

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

    private static FusionNode gtLongFix() {
        return new FusionNode.Kernel(GT_LONG_FIX, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode gtInt() {
        return new FusionNode.Kernel(GT_INT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    private static FusionNode gtDouble() {
        return new FusionNode.Kernel(GT_DOUBLE, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
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
}
