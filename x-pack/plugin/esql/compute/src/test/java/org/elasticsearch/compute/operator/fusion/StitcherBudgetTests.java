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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Bytecode <b>budget</b> and <b>structural-validity</b> proofs for the {@link Stitcher} (iter-22b, folded into iter
 * 25). These are the deterministic guards behind criterion #6 ("large expressions still inline, or fall back"):
 *
 * <ul>
 *   <li><b>Budget.</b> On every emit path the largest method {@code Code} length ({@link FusedMethodCodeLengths#maxMethodCodeLength})
 *       must stay {@code <=} {@link Stitcher#maxFusedMethodBytecodes} (HotSpot's default {@code HugeMethodLimit}, 8000)
 *       for representative <em>and</em> deep trees. A method above that limit is never JIT-compiled at all, which is
 *       strictly worse than the unfused chain.</li>
 *   <li><b>Refuse-to-fuse.</b> A tree whose any-method body would exceed the huge-method guard must be
 *       <em>refused</em> with a {@link Stitcher.StitchingException} (→ the caller falls back to the unfused chain)
 *       rather than emitted as a method HotSpot will not compile. Walking increasing chain depth proves both
 *       directions at once: every tree that emits is within budget, and a deep enough tree eventually refuses.</li>
 *   <li><b>Structural validity.</b> Every emitted class — on all four paths (plain-vector, checked-vector, block,
 *       logical), including a <b>mixed-type per-node-typed</b> tree ({@code add(castIntToLong(in0), in1)}) — passes
 *       {@link CheckClassAdapter} structural verification, an ASM check layered on top of the JVM's
 *       {@code defineHiddenClass} verifier.</li>
 * </ul>
 *
 * <p>The kernels are local fixtures ({@link ArithmeticKernels}, {@link ComparisonKernels}, {@link CastKernels}) that
 * compile to the same instruction shapes the production {@code Add}/{@code Mul}/comparison/{@code Cast} kernels emit,
 * so the measured sizes and validity are representative of what the stitcher sees for real expressions. These tests
 * build class <em>bytes</em> only (via the package-private emitters) — they neither define nor invoke
 * the hidden classes, so no breaker-backed data is needed.
 */
public class StitcherBudgetTests extends ESTestCase {

    /** {@code long} arithmetic kernels shaped like the production {@code Add}/{@code Mul} (overflow-checked). */
    public static final class ArithmeticKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        /** {@code int} multiply shaped like the production {@code Mul} over ints (overflow-checked, {@code int} result). */
        public static int mulInt(int a, int b) {
            return Math.multiplyExact(a, b);
        }

        /** {@code int} add shaped like the production {@code Add} over ints (overflow-checked, {@code int} result). */
        public static int addInt(int a, int b) {
            return Math.addExact(a, b);
        }

        /** {@code double} add shaped like the production {@code Add} over doubles (overflow/finite-checked, {@code double} result). */
        public static double addDouble(double a, double b) {
            return a + b;
        }

        /**
         * {@code double} divide shaped like the production {@code Div} over doubles: zero-guard then {@code lhs/rhs}.
         * Overflow-checked (the fused path keeps the per-kernel try/catch).
         */
        public static double divDouble(double lhs, double rhs) {
            if (rhs == 0.0) {
                throw new ArithmeticException("/ by zero");
            }
            return lhs / rhs;
        }
    }

    /** {@code long} comparison kernels shaped like the production comparisons (boolean return, never overflow). */
    public static final class ComparisonKernels {
        public static boolean gt(long a, long b) {
            return a > b;
        }

        public static boolean lt(long a, long b) {
            return a < b;
        }

        /** {@code int} comparison shaped like the production comparison over ints (boolean return, never overflow). */
        public static boolean gtInt(int a, int b) {
            return a > b;
        }

        /** {@code long} equality shaped like the production {@code Equals} over longs (boolean return, never overflow). */
        public static boolean eq(long a, long b) {
            return a == b;
        }
    }

    /**
     * A {@code BytesRef -> int} string kernel shaped like the production {@code Length#process}: it reads a reference
     * ({@code BytesRef}) argument and returns a primitive, exercising the {@code BYTES_REF} INPUT seam (a leaf read into
     * a reusable spare via {@code getBytesRef}, ASTORE/ALOAD reference slots). {@code v.length} keeps the body a single
     * straight-line read so the test measures the seam's own emit cost, not an unrelated kernel body.
     */
    public static final class StringKernels {
        public static int length(org.apache.lucene.util.BytesRef v) {
            return v.length;
        }

        /**
         * {@code BytesRef} equality shaped like production {@code Equals#processBytesRef}: {@code lhs.equals(rhs)},
         * throw-free, boolean return.
         */
        public static boolean eq(org.apache.lucene.util.BytesRef lhs, org.apache.lucene.util.BytesRef rhs) {
            return lhs.equals(rhs);
        }

        /**
         * Simplified {@code LEFT}: {@code (BytesRef str, int length, BytesRef scratch) -> BytesRef}. Copies a
         * prefix of {@code str} into the per-driver scratch and returns it (the same THREAD_LOCAL {@code @Fixed
         * BytesRef out} contract as production {@code Left#process}, minus the UTF-8 code-point walker).
         */
        public static org.apache.lucene.util.BytesRef left(
            org.apache.lucene.util.BytesRef str,
            int length,
            org.apache.lucene.util.BytesRef scratch
        ) {
            scratch.bytes = str.bytes;
            scratch.offset = str.offset;
            int n = length;
            if (n < 0) {
                n = 0;
            }
            if (n > str.length) {
                n = str.length;
            }
            scratch.length = n;
            return scratch;
        }

        /**
         * Filter-eval overload of {@link #left}: the host {@code pushConstantArg} cannot forward element {@code 'L'}
         * (I4), so this path has no scratch param. Still {@code LEFT(k, n)} over two BytesRef columns so the pred
         * helper allocates per-column spares.
         */
        public static org.apache.lucene.util.BytesRef left(org.apache.lucene.util.BytesRef str, int length) {
            return left(str, length, new org.apache.lucene.util.BytesRef());
        }

        /**
         * Variadic {@code CONCAT}: last parameter is {@code BytesRef[]}, no breaker scratch. Copies every element
         * into one new {@code BytesRef}. One terminal return, no {@code ATHROW} (the production 1MB cap is omitted
         * so {@link BodyExtractor} can splice the loop body).
         */
        public static org.apache.lucene.util.BytesRef concat(org.apache.lucene.util.BytesRef[] values) {
            int n = 0;
            for (int i = 0; i < values.length; i++) {
                n += values[i].length;
            }
            byte[] bytes = new byte[n];
            int p = 0;
            for (int i = 0; i < values.length; i++) {
                org.apache.lucene.util.BytesRef v = values[i];
                System.arraycopy(v.bytes, v.offset, bytes, p, v.length);
                p += v.length;
            }
            return new org.apache.lucene.util.BytesRef(bytes);
        }

        /**
         * Nested mapping convert shaped like {@code ChangeCase#process}: {@code BytesRef -> BytesRef} with a
         * captured {@code @Fixed Locale}. {@code String.concat} is avoided so the body has no {@code invokedynamic}.
         */
        public static org.apache.lucene.util.BytesRef toLower(org.apache.lucene.util.BytesRef v, java.util.Locale locale) {
            return new org.apache.lucene.util.BytesRef(v.utf8ToString().toLowerCase(locale));
        }
    }

    /** Widening numeric cast kernels shaped like the implicit {@code Cast} evaluators (return element != arg element). */
    public static final class CastKernels {
        public static long intToLong(int v) {
            return v;
        }

        public static double longToDouble(long v) {
            return v;
        }

        /** Widening {@code int -> double} cast shaped like the implicit {@code Cast} evaluator. */
        public static double intToDouble(int v) {
            return v;
        }
    }

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
    private static final FusionDescriptor LONG_TO_DOUBLE = new FusionDescriptor(
        CastKernels.class,
        "longToDouble",
        "(J)D",
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

    private FusionTestEmitters emitters;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        emitters = new FusionTestEmitters();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Budget — plain-vector fast path (ENFORCED).
    // -----------------------------------------------------------------------------------------------------------------

    /** A representative depth-2 tree {@code (a + b) * c} fits the huge-method guard on the plain-vector fast path. */
    public void testPlainVectorRepresentativeWithinBudget() throws Exception {
        FusionNode tree = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        int size = FusedMethodCodeLengths.fusedMethodCodeLength(emitters.vectors.emitVectorLoop(MethodHandles.lookup(), tree));
        assertThat("emitted a fused method", size, greaterThan(0));
        assertThat(
            "representative plain-vector body must stay within the huge-method limit",
            size,
            lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
        );
    }

    /**
     * On the plain-vector path an over-limit expression is refused (→ unfused fallback) rather than split: walking
     * increasing left-{@code ADD} chain depth, every tree the stitcher <em>emits</em> stays within
     * {@link Stitcher#maxFusedMethodBytecodes} (HotSpot's {@code HugeMethodLimit} default), and a deep enough
     * chain is <em>refused</em> with a {@link Stitcher.StitchingException}. Cap 4000 is well above the empirical
     * crossing for a left ADD chain; the loop breaks on the first refusal.
     */
    public void testDeepPlainVectorStaysWithinBudgetElseRefuses() throws Exception {
        boolean sawRefusal = false;
        int maxFusedDepth = 0;
        for (int depth = 2; depth <= 4000; depth++) {
            FusionNode tree = leftChain(ADD, depth);
            try {
                int size = FusedMethodCodeLengths.fusedMethodCodeLength(emitters.vectors.emitVectorLoop(MethodHandles.lookup(), tree));
                assertThat(
                    "depth=" + depth + " plain-vector body must stay within the huge-method limit when it fuses",
                    size,
                    lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
                );
                maxFusedDepth = depth;
            } catch (Stitcher.StitchingException refused) {
                assertBudgetRefusal(refused, "plain-vector loop");
                logger.info(
                    "plain-vector left ADD chain fused through depth {} (limit {}), refused next: {}",
                    maxFusedDepth,
                    Stitcher.maxFusedMethodBytecodes,
                    refused.getMessage()
                );
                sawRefusal = true;
                break;
            }
        }
        assertThat("a representative-deep plain-vector chain must still fuse within budget", maxFusedDepth, greaterThan(2));
        assertTrue("a deep enough plain-vector chain must refuse to fuse (over-limit → unfused fallback)", sawRefusal);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Budget — logical block loop (ENFORCED).
    // -----------------------------------------------------------------------------------------------------------------

    /** A representative {@code (a > b) AND (c < d)} logical tree fits the budget: the per-comparison guards are helpers. */
    public void testLogicalRepresentativeWithinBudget() throws Exception {
        FusionNode tree = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        int size = FusedMethodCodeLengths.fusedMethodCodeLength(emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), tree));
        assertThat("emitted a fused logical method", size, greaterThan(0));
        assertThat(
            "representative logical body must stay within the huge-method limit",
            size,
            lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
        );
    }

    /**
     * A deep {@code AND}/{@code OR} nest keeps the top-level {@code fused} loop under the huge-method guard because
     * each comparison's null/multi-value guard is factored into a {@code private static} {@code cmpN} helper — the
     * top-level body only calls them and combines their tri-states with the 3VL truth tables. As with the
     * plain-vector path, walking increasing nesting depth proves both halves of the guard: every logical tree the
     * stitcher <em>emits</em> is within the huge-method limit, and a deep enough nest (whose top-level tri-state
     * combining alone exceeds the limit) is <em>refused</em> with a {@link Stitcher.StitchingException} rather than
     * emitted as a method HotSpot will not compile.
     */
    public void testDeepLogicalStaysWithinBudgetElseRefuses() throws Exception {
        boolean sawRefusal = false;
        int maxFusedDepth = 0;
        for (int depth = 1; depth <= 16; depth++) {
            FusionNode tree = balancedAnd(depth);
            try {
                int size = FusedMethodCodeLengths.fusedMethodCodeLength(emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), tree));
                assertThat(
                    "depth=" + depth + " logical top-level body must stay within the huge-method limit when it fuses",
                    size,
                    lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
                );
                maxFusedDepth = depth;
            } catch (Stitcher.StitchingException refused) {
                // As above: the refusal must be the huge-method guard specifically (message identifies it and the
                // measured size exceeds the limit), proving the top-level 3VL combining alone crossed HugeMethodLimit.
                assertBudgetRefusal(refused, "logical block loop");
                logger.info(
                    "balanced AND nest fused through depth {} (limit {}), refused next: {}",
                    maxFusedDepth,
                    Stitcher.maxFusedMethodBytecodes,
                    refused.getMessage()
                );
                sawRefusal = true;
                break;
            }
        }
        assertThat("a representative-deep logical tree must still fuse within budget", maxFusedDepth, greaterThan(1));
        assertTrue("a deep enough logical tree must refuse to fuse (over-limit → unfused fallback)", sawRefusal);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Structural validity — every emit path passes CheckClassAdapter (on top of the JVM verifier).
    // -----------------------------------------------------------------------------------------------------------------

    public void testPlainVectorStructurallyValid() throws Exception {
        assertStructurallyValid(emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree()));
    }

    public void testCheckedVectorStructurallyValid() throws Exception {
        assertStructurallyValid(emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree()));
    }

    public void testBlockLoopStructurallyValid() throws Exception {
        assertStructurallyValid(emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree()));
    }

    public void testLogicalBlockLoopStructurallyValid() throws Exception {
        FusionNode tree = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        assertStructurallyValid(emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), tree));
    }

    /**
     * A mixed-type, per-node-typed tree {@code add(castIntToLong(in0), in1)} — input 0 read as {@code int}, cast to
     * {@code long}, added to the {@code long} input 1 — emits a structurally valid class on every path. This is the
     * core iter-25 generalization (a cast kernel whose return element differs from its argument element) proven at the
     * Stitcher level, independent of the ESQL planner.
     */
    public void testMixedTypeCastTreeStructurallyValidOnAllPaths() throws Exception {
        FusionNode tree = new FusionNode.Kernel(
            ADD,
            List.of(new FusionNode.Kernel(INT_TO_LONG, List.of(new FusionNode.Input(0))), new FusionNode.Input(1))
        );
        assertStructurallyValid(emitters.vectors.emitVectorLoop(MethodHandles.lookup(), tree));
        assertStructurallyValid(emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), tree));
        assertStructurallyValid(emitters.blocks.emitBlockLoop(MethodHandles.lookup(), tree));
    }

    /**
     * A DEEPER, nested mixed-width tree {@code addDouble(longToDouble(add(intToLong(mulInt(in0, in1)), in2)), in3)} —
     * inputs {@code [int, int, long, double]} widened int→long (after the {@code int} {@code mulInt}) then long→double
     * (after the {@code long} {@code add}) by two cast kernels at different depths — emits a structurally valid class on
     * every non-logical path. This stresses per-node typing across stacked widths (two-slot {@code long}/{@code double}
     * locals interleaved with one-slot {@code int}s), the mixed-width analogue of the iter-25 double+int deterministic
     * differential {@code testMixedTypeNestedIntLongDoubleFusesDeterministic}.
     */
    public void testDeeperMixedWidthCastTreeStructurallyValidOnAllPaths() throws Exception {
        FusionNode intMul = new FusionNode.Kernel(MUL_INT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        FusionNode innerAddLong = new FusionNode.Kernel(
            ADD,
            List.of(new FusionNode.Kernel(INT_TO_LONG, List.of(intMul)), new FusionNode.Input(2))
        );
        FusionNode tree = new FusionNode.Kernel(
            ADD_DOUBLE,
            List.of(new FusionNode.Kernel(LONG_TO_DOUBLE, List.of(innerAddLong)), new FusionNode.Input(3))
        );
        assertStructurallyValid(emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), tree));
        assertStructurallyValid(emitters.blocks.emitBlockLoop(MethodHandles.lookup(), tree));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // BYTES_REF INPUT seam (block path only) — a string kernel that consumes a BytesRef leaf and returns a primitive.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Depth-2 trees mixing a {@code BytesRef}-input string kernel with a numeric/comparison kernel —
     * {@code gtInt(length(in0), length(in1))} (boolean root) and {@code mulInt(length(in0), length(in1))} (int root) —
     * emit a structurally valid, boxing-free BLOCK-path class (the ONLY path a {@code BytesRef} tree takes: it is
     * routed away from the vector fast paths because a keyword/text column may be ordinal-encoded). This covers the
     * {@code getBytesRef}-into-a-spare leaf read and the reference ASTORE/ALOAD slots the seam introduces.
     *
     * <p><b>Budget.</b> The block loop is enforced like every other path: an over-limit body is refused. These
     * two-leaf trees stay well under the limit; the size is asserted positive (a method was emitted) and logged so a
     * regression in the seam's per-leaf emit cost is visible; the single-leaf shape below additionally proves the
     * common {@code LENGTH(x) > k} tree stays inlinable.
     */
    public void testBytesRefInputBlockLoopStructurallyValidAndBoxingFree() throws Exception {
        FusionNode booleanRoot = new FusionNode.Kernel(
            GT_INT,
            List.of(
                new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(0))),
                new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(1)))
            )
        );
        FusionNode intRoot = new FusionNode.Kernel(
            MUL_INT,
            List.of(
                new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(0))),
                new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(1)))
            )
        );
        for (FusionNode tree : List.of(booleanRoot, intRoot)) {
            byte[] bytecode = emitters.blocks.emitBlockLoop(MethodHandles.lookup(), tree);
            assertStructurallyValid(bytecode);
            assertNoBoxingOrIndy(bytecode, "bytes-ref block");
            int size = FusedMethodCodeLengths.fusedMethodCodeLength(bytecode);
            assertThat("emitted a fused BytesRef block method", size, greaterThan(0));
            logger.info("BytesRef two-leaf block loop fused method size = {} bytecodes", size);
        }
    }

    /**
     * The common {@code LENGTH(x) > k}-shaped mixed tree over ONE {@code BytesRef} leaf (a {@code BytesRef}-consuming
     * string kernel feeding a comparison over an {@code int} operand) emits a structurally valid block class whose
     * fused method stays within the huge-method limit — so the acceptance-target trees remain JIT-compilable.
     */
    public void testBytesRefSingleLeafBlockLoopWithinBudget() throws Exception {
        FusionNode tree = new FusionNode.Kernel(
            GT_INT,
            List.of(new FusionNode.Kernel(LENGTH, List.of(new FusionNode.Input(0))), new FusionNode.Input(1))
        );
        byte[] bytecode = emitters.blocks.emitBlockLoop(MethodHandles.lookup(), tree);
        assertStructurallyValid(bytecode);
        int size = FusedMethodCodeLengths.fusedMethodCodeLength(bytecode);
        logger.info("BytesRef single-leaf block loop fused method size = {} bytecodes", size);
        assertThat(
            "single BytesRef-leaf block body must stay within the huge-method limit",
            size,
            lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
        );
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Filter + Eval mega-fusion (S3.2a) — the fused WHERE-predicate + EVAL-projection block loop.
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * A representative fused filter+eval — predicate {@code (in0 + in1) > in2}, projection {@code (in0 + in1) * in2} —
     * keeps the top-level {@code fused} loop within the huge-method limit: the predicate and projection per-position
     * bodies are factored into the {@code pred}/{@code projAppend} helpers, so the top-level loop only calls them and
     * records surviving positions.
     */
    public void testFilterEvalRepresentativeWithinBudget() throws Exception {
        FusionNode predicate = new FusionNode.Kernel(
            GT,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        FusionNode projection = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        int size = FusedMethodCodeLengths.maxMethodCodeLength(
            emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection)
        );
        assertThat("emitted a fused filter-eval method", size, greaterThan(0));
        assertThat(
            "filter-eval largest method must stay within the huge-method limit",
            size,
            lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
        );
    }

    /** A logical-predicate fused filter+eval — {@code (in0 > in1) AND (in2 < in3)} guarding projection {@code in0 + in1}. */
    public void testFilterEvalLogicalPredicateWithinBudget() throws Exception {
        FusionNode predicate = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        FusionNode projection = new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        int size = FusedMethodCodeLengths.maxMethodCodeLength(
            emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection)
        );
        assertThat("emitted a fused filter-eval (logical predicate) method", size, greaterThan(0));
        assertThat(
            "filter-eval largest method must stay within the huge-method limit",
            size,
            lessThanOrEqualTo(Stitcher.maxFusedMethodBytecodes)
        );
    }

    /** Both filter-eval flavours — comparison predicate and logical predicate — emit structurally valid classes. */
    public void testFilterEvalBlockLoopStructurallyValid() throws Exception {
        FusionNode comparisonPredicate = new FusionNode.Kernel(
            GT,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        FusionNode projection = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        assertStructurallyValid(emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), comparisonPredicate, projection));

        FusionNode logicalPredicate = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        FusionNode shallowProjection = new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        assertStructurallyValid(emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), logicalPredicate, shallowProjection));
    }

    /** The fused filter+eval body (and its pred/projAppend/cmpN helpers) must carry no boxing or invokedynamic. */
    public void testFilterEvalHasNoBoxingOrInvokedynamic() throws Exception {
        FusionNode predicate = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        FusionNode projection = new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
        assertNoBoxingOrIndy(emitters.filterEval.emitFilterEvalBlockLoop(MethodHandles.lookup(), predicate, projection), "filter-eval");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Emission quality (S5.4) — the emitted fused loop must "help the JIT": no boxing, no invokedynamic. Static-method
    // invocation of the kernel bodies and the huge-method guard are already covered above and by the differential
    // suite; this pins that the hot per-position body carries no autobox/unbox or indy call that would defeat inlining
    // or allocate per row. (Zero per-row heap allocation is the -prof gc concern of the JMH macro benchmark; here we
    // prove the bytecode has none of the boxing that would cause it.)
    // -----------------------------------------------------------------------------------------------------------------

    /** Boxing factory owners whose {@code valueOf} in the hot body would allocate/deopt the primitive fast path. */
    private static final Set<String> BOXING_OWNERS = Set.of(
        "java/lang/Long",
        "java/lang/Integer",
        "java/lang/Double",
        "java/lang/Boolean",
        "java/lang/Float",
        "java/lang/Short",
        "java/lang/Byte",
        "java/lang/Character"
    );

    /** Unboxing accessor names ({@code x.longValue()} etc.) — the other half of an autobox round-trip. */
    private static final Set<String> UNBOXING_METHODS = Set.of(
        "longValue",
        "intValue",
        "doubleValue",
        "booleanValue",
        "floatValue",
        "shortValue",
        "byteValue",
        "charValue"
    );

    public void testFusedMethodsHaveNoBoxingOrInvokedynamic() throws Exception {
        assertNoBoxingOrIndy(emitters.vectors.emitVectorLoop(MethodHandles.lookup(), arithmeticTree()), "plain-vector");
        assertNoBoxingOrIndy(emitters.vectors.emitVectorLoopChecked(MethodHandles.lookup(), arithmeticTree()), "checked-vector");
        assertNoBoxingOrIndy(emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree()), "block");
        FusionNode logical = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1))),
            new FusionNode.Kernel(LT, List.of(new FusionNode.Input(2), new FusionNode.Input(3)))
        );
        assertNoBoxingOrIndy(emitters.blocks.emitLogicalBlockLoop(MethodHandles.lookup(), logical), "logical-block");
    }

    /** Scans EVERY method of the emitted class (the fused loop and any {@code cmpN} helper) for boxing / invokedynamic. */
    private static void assertNoBoxingOrIndy(byte[] classBytes, String pathName) {
        List<String> violations = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && BOXING_OWNERS.contains(owner) && mName.equals("valueOf")) {
                            violations.add(name + ": boxing " + owner + ".valueOf" + mDesc);
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && UNBOXING_METHODS.contains(mName)) {
                            violations.add(name + ": unboxing " + owner + "." + mName);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... bsmArgs) {
                        violations.add(name + ": invokedynamic " + n + d);
                    }
                };
            }
        }, 0);
        assertThat("fused " + pathName + " method must carry no boxing/unboxing or invokedynamic: " + violations, violations, is(empty()));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Debugging aid (S5.3) — a test-only utility to dump a stitched class's bytes for javap/decompilation, and to
    // base64-encode them (the transport-safe form if they are ever surfaced through a diagnostic response/profile).
    // Kept test-only on purpose: a production dump-to-disk would need a speculative file-write entitlement, which
    // AGENTS.md forbids adding without a concrete need. The package-private emitters already expose
    // the raw bytes, so this is the intended way to inspect generated code: dump then `javap -c -p <file>`.
    // -----------------------------------------------------------------------------------------------------------------

    public void testDumpFusedClassForJavapAndBase64RoundTrips() throws Exception {
        byte[] bytecode = emitters.blocks.emitBlockLoop(MethodHandles.lookup(), arithmeticTree());
        assertThat("emitted non-empty class bytes", bytecode.length, greaterThan(0));

        // Dump to a file a developer can decompile with `javap -c -p <file>` (verified re-readable by ClassReader).
        Path dumped = dumpForJavap(bytecode, "FusedBlockLoop");
        byte[] readBack = Files.readAllBytes(dumped);
        new ClassReader(readBack); // throws if the dumped bytes are not a well-formed class file
        assertThat("dumped bytes round-trip", readBack.length, is(bytecode.length));

        // Base64 is the transport-safe encoding if the bytes are ever returned through a diagnostic response/profile.
        String base64 = Base64.getEncoder().encodeToString(bytecode);
        assertThat("base64 round-trips to the original bytes", Base64.getDecoder().decode(base64), is(bytecode));
    }

    /** Writes {@code bytecode} to a temp {@code .class} file so a developer can {@code javap -c -p} / decompile it. */
    private Path dumpForJavap(byte[] bytecode, String className) throws IOException {
        Path file = createTempDir().resolve(className + ".class");
        Files.write(file, bytecode);
        logger.info("dumped fused class [{}] to [{}] for javap/decompile", className, file);
        return file;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    private static FusionNode arithmeticTree() {
        // ((a + b) * c) — depth-2, reads three inputs.
        return new FusionNode.Kernel(
            MUL,
            List.of(new FusionNode.Kernel(ADD, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    /**
     * A balanced {@code AND} nest of depth {@code depth}: at depth 0 a single {@code gt(in0, in1)} comparison, else an
     * {@code AND} of two depth-{@code (depth-1)} subtrees. The nest has {@code 2^depth} comparison leaves, so the
     * top-level tri-state combining grows with depth until it crosses the huge-method limit.
     */
    private static FusionNode balancedAnd(int depth) {
        if (depth <= 0) {
            return new FusionNode.Kernel(GT, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        }
        return new FusionNode.Logical(FusionNode.BoolOp.AND, balancedAnd(depth - 1), balancedAnd(depth - 1));
    }

    /** A left-leaning chain of {@code depth} binary kernels reusing two inputs: {@code (((in0 op in1) op in1) op in1)...}. */
    private static FusionNode leftChain(FusionDescriptor descriptor, int depth) {
        FusionNode node = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        for (int i = 1; i < depth; i++) {
            node = new FusionNode.Kernel(descriptor, List.of(node, new FusionNode.Input(1)));
        }
        return node;
    }

    /**
     * Asserts that a refuse-to-fuse {@link Stitcher.StitchingException} is SPECIFICALLY the &gt;{@code
     * maxFusedMethodBytecodes} huge-method guard on {@code pathName}, not an unrelated stitch failure that would
     * spuriously satisfy a "did not emit a class" check. It verifies (a) the message identifies the huge-method limit
     * on the named enforced path, and (b) the size the guard measured — parsed from the message's {@code [<n>
     * bytecodes]} — actually exceeds the limit, i.e. the emitted method WOULD not be JIT-compiled.
     */
    private static void assertBudgetRefusal(Stitcher.StitchingException refused, String pathName) {
        String message = refused.getMessage();
        assertThat(
            "refusal must be the huge-method guard, not some other stitch failure: " + message,
            message,
            containsString("exceeds the huge-method limit")
        );
        assertThat("refusal must name the enforced path [" + pathName + "]: " + message, message, containsString(pathName));
        int reportedSize = parseReportedSize(message);
        assertThat(
            "the size the huge-method guard measured must actually exceed the limit (the method WOULD not be JIT-compiled)",
            reportedSize,
            greaterThan(Stitcher.maxFusedMethodBytecodes)
        );
    }

    /** Parses the {@code [<n> bytecodes]} size the budget guard baked into its {@link Stitcher.StitchingException} message. */
    private static int parseReportedSize(String message) {
        Matcher matcher = Pattern.compile("\\[(\\d+) bytecodes\\]").matcher(message);
        assertThat("budget message must report the measured method size: " + message, matcher.find(), is(true));
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Runs {@code classBytes} through {@link CheckClassAdapter} with data-flow checking disabled: this is a
     * <b>STRUCTURAL</b> check only — it validates every instruction's operand shapes, the frame/label consistency and
     * the visit order, without resolving (loading) the referenced {@code compute.data}/{@code VectorUnsafe} types that
     * a data-flow ({@code SimpleVerifier}) pass would try to link (and could not, here, for a not-yet-defined hidden
     * class). Data-flow correctness — operand-stack typing, local-variable width (the {@code long}/{@code double}
     * two-slot rule that per-node cast typing must get right), and frame maps — is instead verified by (1) the JVM's
     * own bytecode verifier when the production {@code MethodHandles.Lookup.defineHiddenClass} defines each class, and
     * (2) the esql-proper {@code FusionSpecDifferentialTests}, which really defines AND invokes the fused classes over
     * live pages (including mixed-width cast trees) and asserts value/null parity with the unfused chain. Any
     * structural defect here throws, failing the test.
     */
    private static void assertStructurallyValid(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }
}
