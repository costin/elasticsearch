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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E1 safety-net dump harness: emits fused class bytes for a fixed corpus covering every surviving
 * {@link Stitcher} compile entry point. Writes {@code <shape>.class} into {@code esql.fusion.dump.dir}
 * (or {@code FUSION_DUMP_DIR}), defaulting to {@code build/fusion-dump}.
 *
 * <p>Not part of the committed test suite's intent — a Phase-E evidence tool. The corpus is deterministic
 * (no {@code random*}); dump twice and {@code diff -r} the dirs to prove byte-identity.
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
    private static final FusionDescriptor LONG_TO_DOUBLE = new FusionDescriptor(CastKernels.class, "longToDouble", "(J)D", false, true);
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
    private static final FusionDescriptor APPEND_SUFFIX = new FusionDescriptor(
        KernelFixtures.class,
        "processAppendSuffix",
        "(Lorg/apache/lucene/util/BytesRef;Ljava/lang/String;)Lorg/apache/lucene/util/BytesRef;",
        false,
        true,
        "",
        false,
        true
    );

    public void testDumpFusionClassCorpus() throws Exception {
        Path dir = dumpDir();
        Files.createDirectories(dir);
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        dump(dir, "plain_long_add_mul", stitcher.vectorLoopBytecode(lookup, addMulLong()));
        dump(dir, "plain_int_mul", stitcher.vectorLoopBytecode(lookup, mulInt()));
        dump(dir, "plain_double_add", stitcher.vectorLoopBytecode(lookup, addDouble()));
        dump(dir, "plain_long_gt", stitcher.vectorLoopBytecode(lookup, gtLong()));
        dump(dir, "plain_int_gt", stitcher.vectorLoopBytecode(lookup, gtInt()));
        dump(dir, "plain_cast_int_to_long_add", stitcher.vectorLoopBytecode(lookup, castIntToLongAdd()));
        dump(dir, "plain_runtime_constant", stitcher.vectorLoopBytecode(lookup, addRuntimeConstant()));
        dump(dir, "plain_baked_div", stitcher.vectorLoopBytecode(lookup, bakedDiv()));

        dump(dir, "checked_long_add_mul", stitcher.vectorLoopCheckedBytecode(lookup, addMulLong()));
        dump(dir, "checked_double_add", stitcher.vectorLoopCheckedBytecode(lookup, addDouble()));
        dump(dir, "checked_cast_int_to_long_add", stitcher.vectorLoopCheckedBytecode(lookup, castIntToLongAdd()));
        dump(dir, "checked_runtime_constant", stitcher.vectorLoopCheckedBytecode(lookup, addRuntimeConstant()));
        dump(dir, "checked_baked_div", stitcher.vectorLoopCheckedBytecode(lookup, bakedDiv()));

        dump(dir, "block_long_add_mul", stitcher.blockLoopBytecode(lookup, addMulLong()));
        dump(dir, "block_int_mul", stitcher.blockLoopBytecode(lookup, mulInt()));
        dump(dir, "block_double_add", stitcher.blockLoopBytecode(lookup, addDouble()));
        dump(dir, "block_long_gt", stitcher.blockLoopBytecode(lookup, gtLong()));
        dump(dir, "block_bytesref_identity", stitcher.blockLoopBytecode(lookup, bytesRefIdentity()));
        dump(dir, "block_bytesref_length_gt", stitcher.blockLoopBytecode(lookup, lengthGtConst()));
        dump(dir, "block_runtime_constant", stitcher.blockLoopBytecode(lookup, addRuntimeConstant()));
        dump(dir, "block_cast_int_to_long_add", stitcher.blockLoopBytecode(lookup, castIntToLongAdd()));

        dump(dir, "logical_block_and", stitcher.logicalBlockLoopBytecode(lookup, andTree()));
        dump(dir, "logical_block_or", stitcher.logicalBlockLoopBytecode(lookup, orTree()));
        dump(dir, "logical_block_not", stitcher.logicalBlockLoopBytecode(lookup, notTree()));
        dump(dir, "logical_block_nested", stitcher.logicalBlockLoopBytecode(lookup, nestedLogical()));

        dump(dir, "logical_vector_and", stitcher.logicalVectorLoopBytecode(lookup, andTree()));
        dump(dir, "logical_vector_or", stitcher.logicalVectorLoopBytecode(lookup, orTree()));
        dump(dir, "logical_vector_not", stitcher.logicalVectorLoopBytecode(lookup, notTree()));

        dump(dir, "mapping_append_suffix", stitcher.mappingBlockLoopBytecode(lookup, mappingAppend()));

        dump(dir, "simd_long_gt", stitcher.simdVectorLoopBytecode(lookup, gtLongFix(), "GT"));
        dump(dir, "simd_double_gt", stitcher.simdVectorLoopBytecode(lookup, gtDouble(), "GT"));
        dump(dir, "simd_int_gt", stitcher.simdVectorLoopBytecode(lookup, gtInt(), "GT"));

        dump(dir, "filter_eval_cmp", stitcher.filterEvalBlockLoopBytecode(lookup, gtOverAdd(), mulOverAdd()));
        dump(dir, "filter_eval_logical", stitcher.filterEvalBlockLoopBytecode(lookup, andTree(), addLong()));

        logger.info("dumped fusion corpus to {}", dir.toAbsolutePath());
    }

    private static Path dumpDir() {
        String prop = System.getProperty("esql.fusion.dump.dir");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("FUSION_DUMP_DIR");
        }
        return Path.of(prop == null || prop.isBlank() ? "build/fusion-dump" : prop);
    }

    private static void dump(Path dir, String shape, byte[] bytecode) throws Exception {
        Files.write(dir.resolve(shape + ".class"), bytecode);
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

    private static FusionNode mappingAppend() {
        return new FusionNode.Kernel(APPEND_SUFFIX, List.of(new FusionNode.Input(0), FusionNode.Constant.reference("_x", String.class)));
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
