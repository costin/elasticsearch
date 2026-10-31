/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.ConstantLongVector;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.test.TestWarningsSource;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Overflow proof for iter 12 (binding rule #3). The block path ({@link Stitcher#compileBlockLoop}) and the
 * overflow-aware checked vector path ({@link Stitcher#compileVectorLoopChecked}) must, for an overflowing
 * position, produce a {@code null} AND register a warning on the supplied {@link Warnings} — byte-for-byte the
 * behaviour the generated unfused evaluator emits ({@code try { result.appendLong(process(...)); } catch
 * (ArithmeticException e) { warnings().registerException(e); result.appendNull(); }}, see
 * {@code AddLongsEvaluator.eval}).
 *
 * <p>The fixture kernels below are byte-for-byte shaped like the production long {@code Add}/{@code Mul}/{@code Sub}
 * kernels — {@code Math.*Exact} delegates that throw {@link ArithmeticException} with message {@code "long overflow"}
 * on overflow — so the stitched body splices the same instruction sequence the real overflow-checked kernels do. The
 * descriptors are built {@code overflowChecked = true}, so the convenience constructor derives
 * {@code overflowExceptionType = "java.lang.ArithmeticException"}, exactly what the annotation processor emits onto
 * the real evaluators' {@code FUSION_DESCRIPTOR}, and what the stitcher catches around each spliced kernel body.
 *
 * <p>Everything runs on a real breaker-backed {@link BlockFactory}; the {@code @After} asserts the breaker is
 * balanced (every produced/consumed block released). Warnings are collected through a real
 * {@link Warnings#createWarnings COLLECT} {@code Warnings} and asserted via {@link #assertWarnings}.
 */
public class StitcherOverflowTests extends ESTestCase {

    /**
     * {@code long} kernels shaped exactly like the production {@code Add.processLongs} etc.: {@code Math.*Exact}
     * delegates (a {@code java.base} {@code INVOKESTATIC} in the spliced body) that throw
     * {@code ArithmeticException("long overflow")} on overflow.
     */
    public static final class LongKernels {
        public static long add(long a, long b) {
            return Math.addExact(a, b);
        }

        public static long mul(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        public static long sub(long a, long b) {
            return Math.subtractExact(a, b);
        }
    }

    /** The exact overflow exception type/message {@code Math.*Exact} raises, mirrored into the warning assertions. */
    private static final String OVERFLOW_WARNING = "Line 1:1: java.lang.ArithmeticException: long overflow";
    private static final String FIRST_EXCEPTION_HEADER =
        "Line 1:1: evaluation of [overflow] failed, treating result as null. Only first 20 failures recorded.";
    private static final String MULTI_VALUE_WARNING =
        "Line 1:1: java.lang.IllegalArgumentException: single-value function encountered multi-value";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;
    private Stitcher stitcher;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
        stitcher = new Stitcher(new TemplateRegistry());
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    private Warnings collectingWarnings() {
        return Warnings.createWarnings(DriverContext.WarningsMode.COLLECT, new TestWarningsSource("overflow"));
    }

    /**
     * The per-source {@code Warnings[]} the fused methods now take (one slot per warning-source kernel, indexed by
     * {@link Stitcher#warningsSourceIndices}). Every slot points at the same collecting instance so these fixtures —
     * which build all kernels from a single {@code TestWarningsSource("overflow")} — accumulate into one collector and
     * keep asserting via {@link #assertWarnings}, exactly as before the per-source threading landed.
     */
    private static Warnings[] warningsArray(FusionNode tree, Warnings w) {
        Warnings[] arr = new Warnings[Stitcher.warningsSourceCount(tree)];
        Arrays.fill(arr, w);
        return arr;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: deep expression where an intermediate (and separately the root) kernel overflows.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code (a + b) * c} over long blocks. Positions are hand-picked so that: an INTERMEDIATE add overflows,
     * the ROOT mul overflows, a clean position computes, and a clean position mixes in. Each overflowing position
     * must be null with a registered warning; the fused output must equal the unfused reference position-by-position.
     */
    public void testDeepExpressionIntermediateOverflowBlockPath() throws Throwable {
        FusionNode tree = addThenMulTree();
        MethodHandle handle = blockHandle(tree, 3);
        Warnings warnings = collectingWarnings();

        // a, b, c per position. p0: intermediate add overflows. p1: clean. p2: root mul overflows. p3: clean.
        long[] a = { Long.MAX_VALUE, 3, 2, 10 };
        long[] b = { 1, 4, 3, 20 };
        long[] c = { 7, 5, Long.MAX_VALUE, 2 };
        int positionCount = a.length;

        LongBlock ba = null, bb = null, bc = null, result = null;
        try {
            ba = singleValued(a);
            bb = singleValued(b);
            bc = singleValued(c);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, ba, bb, bc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            // p0 and p2 overflow; both raise ArithmeticException("long overflow"), deduplicated to a single header line.
            assertThat("p0 must be null", result.isNull(0), is(true));
            assertThat("p2 must be null", result.isNull(2), is(true));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(35L));
            assertThat("p3 value", result.getLong(result.getFirstValueIndex(3)), equalTo(60L));
        } finally {
            Releasables.closeExpectNoException(ba, bb, bc, result);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: mixed overflow / non-overflow, randomized, differential vs the unfused reference.
    // ---------------------------------------------------------------------------------------------------------------

    public void testMixedOverflowBlockPathMatchesUnfused() throws Throwable {
        FusionNode tree = addThenMulTree();
        MethodHandle handle = blockHandle(tree, 3);
        Warnings warnings = collectingWarnings();

        int positionCount = 512;
        long[] a = new long[positionCount];
        long[] b = new long[positionCount];
        long[] c = new long[positionCount];
        boolean anyOverflow = false;
        for (int p = 0; p < positionCount; p++) {
            // ~1/4 of positions seed an operand at an extreme so (a+b)*c can overflow; the rest stay bounded/clean.
            a[p] = randomBoolean() ? randomLongBetween(-1_000, 1_000) : Long.MAX_VALUE - randomLongBetween(0, 5);
            b[p] = randomLongBetween(-1_000, 1_000);
            c[p] = randomBoolean() ? randomLongBetween(-1_000, 1_000) : Long.MAX_VALUE - randomLongBetween(0, 5);
            if (refAddThenMul(a[p], b[p], c[p]) == null) {
                anyOverflow = true;
            }
        }

        LongBlock ba = null, bb = null, bc = null, result = null;
        try {
            ba = singleValued(a);
            bb = singleValued(b);
            bc = singleValued(c);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, ba, bb, bc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
        } finally {
            Releasables.closeExpectNoException(ba, bb, bc, result);
        }
        // Every overflow raises ArithmeticException("long overflow"), so identical warnings deduplicate to one line.
        if (anyOverflow) {
            assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block path: multi-value position -> null + carried-forward single-value warning.
    // ---------------------------------------------------------------------------------------------------------------

    public void testMultiValueNullPlusWarningBlockPath() throws Throwable {
        // add(in0, in1): p0 multi-value lhs, p1 clean, p2 null lhs (no warning), p3 multi-value rhs.
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionNode tree = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        MethodHandle handle = blockHandle(tree, 2);
        Warnings warnings = collectingWarnings();

        int positionCount = 4;
        long[][] lhs = { { 1L, 2L }, { 3L }, {}, { 9L } };
        long[][] rhs = { { 5L }, { 4L }, { 8L }, { 1L, 2L, 3L } };

        LongBlock lb = null, rb = null, result = null;
        try {
            lb = multiValued(lhs);
            rb = multiValued(rhs);
            result = (LongBlock) handle.invokeWithArguments(blockFactory, warningsArray(tree, warnings), positionCount, lb, rb);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            assertThat("p0 multi-value lhs -> null", result.isNull(0), is(true));
            assertThat("p1 clean", result.isNull(1), is(false));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(7L));
            assertThat("p2 null lhs -> null", result.isNull(2), is(true));
            assertThat("p3 multi-value rhs -> null", result.isNull(3), is(true));
        } finally {
            Releasables.closeExpectNoException(lb, rb, result);
        }
        // p0 and p3 register the multi-value warning (deduplicated); p2's plain null registers NO warning.
        assertWarnings(FIRST_EXCEPTION_HEADER, MULTI_VALUE_WARNING);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Checked vector path: overflow -> nulls-capable *Block output + warning (ArrayVector cannot hold nulls).
    // ---------------------------------------------------------------------------------------------------------------

    public void testCheckedVectorPathOverflowProducesNullsAndWarnings() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        // The checked vector path reads backing arrays via the public VectorUnsafe forwarder (no teleport), so the
        // hidden class is defined into the caller's own package.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            Vector.class,
            Vector.class,
            Vector.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        // Dense single-valued vectors (no nulls/multi-values by construction): overflow is the only null source.
        long[] a = { Long.MAX_VALUE, 3, 2, 10 };
        long[] b = { 1, 4, 3, 20 };
        long[] c = { 7, 5, Long.MAX_VALUE, 2 };
        int positionCount = a.length;

        LongVector va = null, vb = null, vc = null;
        LongBlock result = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);
            result = (LongBlock) handle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddThenMul(a[p], b[p], c[p]);
                if (expected == null) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            assertThat("p0 overflow -> null", result.isNull(0), is(true));
            assertThat("p2 overflow -> null", result.isNull(2), is(true));
            assertThat("p1 value", result.getLong(result.getFirstValueIndex(1)), equalTo(35L));
            assertThat("p3 value", result.getLong(result.getFirstValueIndex(3)), equalTo(60L));
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, result);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    /** No-overflow sanity for the checked vector path: every position is a value, no warning is registered. */
    public void testCheckedVectorPathNoOverflowProducesAllValues() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        MethodType type = MethodType.methodType(
            LongBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            Vector.class,
            Vector.class,
            Vector.class
        );
        MethodHandle handle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        int positionCount = 256;
        long[] a = new long[positionCount];
        long[] b = new long[positionCount];
        long[] c = new long[positionCount];
        for (int p = 0; p < positionCount; p++) {
            a[p] = randomLongBetween(-1_000, 1_000);
            b[p] = randomLongBetween(-1_000, 1_000);
            c[p] = randomLongBetween(-1_000, 1_000);
        }

        LongVector va = null, vb = null, vc = null;
        LongBlock result = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);
            result = (LongBlock) handle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            for (int p = 0; p < positionCount; p++) {
                // Bounded operands cannot overflow, so the reference is always present.
                long expected = Math.multiplyExact(Math.addExact(a[p], b[p]), c[p]);
                assertThat("p=" + p, result.isNull(p), is(false));
                assertThat("p=" + p, result.getLong(result.getFirstValueIndex(p)), equalTo(expected));
            }
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, result);
        }
        // No overflow occurred, so nothing was registered.
        assertWarnings();
    }

    /**
     * Task 3 (a): a CHECKED_VECTOR page with no overflow must return a {@link LongArrayVector} from {@code fusedRaw}.
     * The existing builder-based {@code fused} method is left unchanged: a single-row dense page still collapses to
     * {@link ConstantLongVector} there. That TODAY class is pinned first so the new {@code fusedRaw} expectation is
     * the assertion that fails before the emit lands.
     */
    public void testCheckedVectorRawPathNoOverflowReturnsLongArrayVector() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        MethodType type = checkedVectorType(3);
        MethodHandle builderHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        // Single-row dense page: the builder path's 1-value collapse is ConstantLongVector TODAY.
        long[] a = { 3 };
        long[] b = { 4 };
        long[] c = { 5 };
        int positionCount = 1;

        LongVector va = null, vb = null, vc = null;
        Block builderResult = null;
        Block rawResult = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);
            builderResult = (Block) builderHandle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            assertThat(
                "TODAY the builder-based fused method collapses a 1-row dense page to ConstantLongVector",
                builderResult.asVector().getClass(),
                equalTo(ConstantLongVector.class)
            );

            MethodHandle rawHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_RAW_METHOD_NAME, type);
            rawResult = (Block) rawHandle.invoke(blockFactory, warningsArray(tree, warnings), positionCount, va, vb, vc);
            assertThat(
                "fusedRaw no-overflow page must wrap a LongArrayVector (not the builder's ConstantLongVector)",
                rawResult.asVector().getClass(),
                equalTo(LongArrayVector.class)
            );
            assertThat(rawResult.isNull(0), is(false));
            assertThat(((LongBlock) rawResult).getLong(0), equalTo(35L));
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, builderResult, rawResult);
        }
        assertWarnings();
    }

    /**
     * Task 3 (b) at the stitcher: {@code fusedRaw} must abandon (return {@code null}, register no warnings) on the
     * first overflow so the caller can re-run the builder-based {@code fused} method, which then produces the unfused
     * nulls + warnings.
     */
    public void testCheckedVectorRawPathOverflowReturnsNullThenBuilderMatchesUnfused() throws Throwable {
        FusionNode tree = addThenMulTree();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), tree);
        MethodType type = checkedVectorType(3);
        MethodHandle builderHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);
        MethodHandle rawHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_RAW_METHOD_NAME, type);

        // p0: intermediate add overflows. p1: clean. p2: root mul overflows. p3: clean.
        long[] a = { Long.MAX_VALUE, 3, 2, 10 };
        long[] b = { 1, 4, 3, 20 };
        long[] c = { 7, 5, Long.MAX_VALUE, 2 };
        int positionCount = a.length;

        LongVector va = null, vb = null, vc = null;
        Block rawResult = null;
        LongBlock builderResult = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            vc = blockFactory.newLongArrayVector(c, positionCount);

            Warnings rawWarnings = collectingWarnings();
            rawResult = (Block) rawHandle.invoke(blockFactory, warningsArray(tree, rawWarnings), positionCount, va, vb, vc);
            assertThat("fusedRaw must abandon the page on the first overflow", rawResult, nullValue());
            // fusedRaw must not register warnings — those come only from the builder re-run.
            assertWarnings();

            Warnings builderWarnings = collectingWarnings();
            builderResult = (LongBlock) builderHandle.invoke(blockFactory, warningsArray(tree, builderWarnings), positionCount, va, vb, vc);
            assertThat("p0 overflow -> null", builderResult.isNull(0), is(true));
            assertThat("p2 overflow -> null", builderResult.isNull(2), is(true));
            assertThat("p1 value", builderResult.getLong(builderResult.getFirstValueIndex(1)), equalTo(35L));
            assertThat("p3 value", builderResult.getLong(builderResult.getFirstValueIndex(3)), equalTo(60L));
        } finally {
            Releasables.closeExpectNoException(va, vb, vc, rawResult, builderResult);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    /**
     * Task 3 (c): {@code fusedRaw} must not touch any {@code *Block$Builder}. {@code INVOKEVIRTUAL BlockFactory.
     * new*ArrayVector} is the allowed write; the builder-based {@code fused} sibling keeps its builder invokes.
     */
    public void testCheckedVectorRawMethodHasNoBlockBuilderInvokes() throws Exception {
        FusionNode tree = addThenMulTree();
        byte[] bytecode = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), tree);

        List<String> methods = new ArrayList<>();
        List<String> rawBuilderInvokes = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name);
                boolean isRaw = Stitcher.FUSED_RAW_METHOD_NAME.equals(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (isRaw == false) {
                            return;
                        }
                        boolean builderOwner = owner.contains("Block$Builder");
                        if (builderOwner && (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)) {
                            rawBuilderInvokes.add(name + ": " + opcodeName(opcode) + " " + owner + "." + mName + mDesc);
                        }
                    }
                };
            }
        }, 0);

        assertThat("checked-vector class must emit fusedRaw", methods, hasItem(Stitcher.FUSED_RAW_METHOD_NAME));
        assertThat("fusedRaw must not invoke *Block$Builder: " + rawBuilderInvokes, rawBuilderInvokes, is(empty()));
    }

    /**
     * Over-budget {@code fusedRaw} is emitted via {@code emitCheckedVectorRawSplit}: the expression is lifted into a
     * private {@code compute()} helper and the loop calls {@code compute()} inside the overflow try. Forced with a
     * left-leaning {@code addExact} chain that exceeds {@link Stitcher#MAX_FUSED_METHOD_BYTECODES} — production emit is
     * not altered. The representative depth-2 tree is asserted to stay inline so a passing {@code compute()} check is
     * evidence the split was actually taken.
     */
    public void testCheckedVectorRawSplitHasNoBlockBuilderInvokes() throws Exception {
        byte[] inline = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), addThenMulTree());
        assertThat(
            "depth-2 fusedRaw must stay inline (no compute helper) so the split assertion below is not a tautology",
            methodNames(inline),
            not(hasItem("compute"))
        );

        SplitTree split = addChainThatSplitsRaw();
        List<String> methods = methodNames(split.bytecode);
        assertThat("over-budget fusedRaw must lift into compute()", methods, hasItem("compute"));
        assertThat(methods, hasItem(Stitcher.FUSED_RAW_METHOD_NAME));

        List<String> rawBuilderInvokes = new ArrayList<>();
        boolean[] fusedRawCallsCompute = { false };
        new ClassReader(split.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                boolean isRaw = Stitcher.FUSED_RAW_METHOD_NAME.equals(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (isRaw == false) {
                            return;
                        }
                        if ("compute".equals(mName) && opcode == Opcodes.INVOKESTATIC) {
                            fusedRawCallsCompute[0] = true;
                        }
                        boolean builderOwner = owner.contains("Block$Builder");
                        if (builderOwner && (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)) {
                            rawBuilderInvokes.add(name + ": " + opcodeName(opcode) + " " + owner + "." + mName + mDesc);
                        }
                    }
                };
            }
        }, 0);

        assertThat("split fusedRaw loop must INVOKESTATIC compute()", fusedRawCallsCompute[0], is(true));
        assertThat("split fusedRaw must not invoke *Block$Builder: " + rawBuilderInvokes, rawBuilderInvokes, is(empty()));

        int rawSize = Stitcher.methodCodeLength(split.bytecode, Stitcher.FUSED_RAW_METHOD_NAME);
        assertThat("split fusedRaw emitted a Code attribute", rawSize, greaterThan(0));
        assertThat(
            "split fusedRaw shell itself must stay within the inlining budget (does not refuse the unbudgeted builder)",
            rawSize,
            lessThanOrEqualTo(Stitcher.MAX_FUSED_METHOD_BYTECODES)
        );
        assertThat(
            "split fusedRaw must be the tiny call-loop, not the inlined expression",
            rawSize,
            lessThan(Stitcher.MAX_FUSED_METHOD_BYTECODES / 2)
        );
    }

    /**
     * Split {@code fusedRaw} no-overflow dense page returns a {@link LongArrayVector}. Same deep addExact chain that
     * tripped the split in {@link #testCheckedVectorRawSplitHasNoBlockBuilderInvokes}.
     */
    public void testCheckedVectorRawSplitNoOverflowReturnsLongArrayVector() throws Throwable {
        SplitTree split = addChainThatSplitsRaw();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), split.tree);
        MethodType type = checkedVectorType(2);
        MethodHandle rawHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_RAW_METHOD_NAME, type);
        Warnings warnings = collectingWarnings();

        long[] a = { 2, 4, 6, 8 };
        long[] b = { 1, 1, 1, 1 };
        int positionCount = a.length;

        LongVector va = null, vb = null;
        Block rawResult = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);
            rawResult = (Block) rawHandle.invoke(blockFactory, warningsArray(split.tree, warnings), positionCount, va, vb);
            assertThat(
                "split fusedRaw no-overflow page must wrap a LongArrayVector",
                rawResult.asVector().getClass(),
                equalTo(LongArrayVector.class)
            );
            assertThat(rawResult.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddChain(split.depth, a[p], b[p]);
                assertThat("p=" + p + " must not overflow at depth=" + split.depth, expected, not(nullValue()));
                assertThat("p=" + p, rawResult.isNull(p), is(false));
                assertThat("p=" + p, ((LongBlock) rawResult).getLong(p), equalTo(expected.longValue()));
            }
        } finally {
            Releasables.closeExpectNoException(va, vb, rawResult);
        }
        assertWarnings();
    }

    /**
     * Split {@code fusedRaw} abandons (returns {@code null}, no warnings) on the first overflow; the builder {@code fused}
     * re-run then matches the unfused nulls + warnings.
     */
    public void testCheckedVectorRawSplitOverflowReturnsNullThenBuilderMatchesUnfused() throws Throwable {
        SplitTree split = addChainThatSplitsRaw();
        Class<?> fused = stitcher.compileVectorLoopChecked(MethodHandles.lookup(), split.tree);
        MethodType type = checkedVectorType(2);
        MethodHandle rawHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_RAW_METHOD_NAME, type);
        MethodHandle builderHandle = MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        long[] a = { Long.MAX_VALUE, 2, 4, 6 };
        long[] b = { 1, 1, 1, 1 };
        int positionCount = a.length;

        LongVector va = null, vb = null;
        Block rawResult = null;
        LongBlock builderResult = null;
        try {
            va = blockFactory.newLongArrayVector(a, positionCount);
            vb = blockFactory.newLongArrayVector(b, positionCount);

            Warnings rawWarnings = collectingWarnings();
            rawResult = (Block) rawHandle.invoke(blockFactory, warningsArray(split.tree, rawWarnings), positionCount, va, vb);
            assertThat("split fusedRaw must abandon the page on the first overflow", rawResult, nullValue());
            assertWarnings();

            Warnings builderWarnings = collectingWarnings();
            builderResult = (LongBlock) builderHandle.invoke(
                blockFactory,
                warningsArray(split.tree, builderWarnings),
                positionCount,
                va,
                vb
            );
            for (int p = 0; p < positionCount; p++) {
                Long expected = refAddChain(split.depth, a[p], b[p]);
                if (expected == null) {
                    assertThat("p=" + p, builderResult.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, builderResult.isNull(p), is(false));
                    assertThat("p=" + p, builderResult.getLong(builderResult.getFirstValueIndex(p)), equalTo(expected.longValue()));
                }
            }
            assertThat("p0 overflow -> null", builderResult.isNull(0), is(true));
        } finally {
            Releasables.closeExpectNoException(va, vb, rawResult, builderResult);
        }
        assertWarnings(FIRST_EXCEPTION_HEADER, OVERFLOW_WARNING);
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            default -> Integer.toString(opcode);
        };
    }

    private static MethodType checkedVectorType(int arity) {
        Class<?>[] params = new Class<?>[3 + arity];
        params[0] = BlockFactory.class;
        params[1] = Warnings[].class;
        params[2] = int.class;
        for (int i = 0; i < arity; i++) {
            params[3 + i] = Vector.class;
        }
        return MethodType.methodType(LongBlock.class, params);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code (a + b) * c} : Mul(Add(in0, in1), in2). Add overflow is intermediate; Mul overflow is at the root. */
    private static FusionNode addThenMulTree() {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionDescriptor mul = new FusionDescriptor(LongKernels.class, "mul", "(JJ)J", true, true);
        return new FusionNode.Kernel(
            mul,
            List.of(new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1))), new FusionNode.Input(2))
        );
    }

    /**
     * Left-leaning chain of {@code depth} {@code addExact} kernels reusing two inputs:
     * {@code (((in0 + in1) + in1) + in1)...}. Each additional kernel is one {@code Math.addExact} in the spliced body,
     * so walking depth is a reliable way to push {@code fusedRaw} over {@link Stitcher#MAX_FUSED_METHOD_BYTECODES}.
     */
    private static FusionNode addChain(int depth) {
        FusionDescriptor add = new FusionDescriptor(LongKernels.class, "add", "(JJ)J", true, true);
        FusionNode node = new FusionNode.Kernel(add, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
        for (int i = 1; i < depth; i++) {
            node = new FusionNode.Kernel(add, List.of(node, new FusionNode.Input(1)));
        }
        return node;
    }

    /**
     * Smallest left-leaning addExact chain whose checked-vector class emits a {@code compute()} helper — i.e. the
     * {@code fusedRaw} split, not the inline body. Production emit is not intercepted.
     */
    private SplitTree addChainThatSplitsRaw() throws Exception {
        for (int depth = 4; depth <= 128; depth++) {
            FusionNode tree = addChain(depth);
            byte[] bytecode = stitcher.vectorLoopCheckedBytecode(MethodHandles.lookup(), tree);
            if (methodNames(bytecode).contains("compute")) {
                return new SplitTree(depth, tree, bytecode);
            }
        }
        throw new AssertionError("fusedRaw never split through depth 128; emitCheckedVectorRawSplit was not reached");
    }

    private static List<String> methodNames(byte[] bytecode) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }

    private static final class SplitTree {
        final int depth;
        final FusionNode tree;
        final byte[] bytecode;

        SplitTree(int depth, FusionNode tree, byte[] bytecode) {
            this.depth = depth;
            this.tree = tree;
            this.bytecode = bytecode;
        }
    }

    /** Unfused reference for a depth-{@code depth} left-leaning {@code addExact} chain: {@code in0 + depth * in1}. */
    private static Long refAddChain(int depth, long a, long b) {
        try {
            long acc = a;
            for (int i = 0; i < depth; i++) {
                acc = Math.addExact(acc, b);
            }
            return acc;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** Unfused reference for {@code (a + b) * c}: null iff any {@code Math.*Exact} in the post-order chain overflows. */
    private static Long refAddThenMul(long a, long b, long c) {
        long sum;
        try {
            sum = Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
        try {
            return Math.multiplyExact(sum, c);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private MethodHandle blockHandle(FusionNode tree, int arity) throws Throwable {
        Class<?> fused = stitcher.compileBlockLoop(MethodHandles.lookup(), tree);
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));
        Class<?>[] params = new Class<?>[3 + arity];
        params[0] = BlockFactory.class;
        params[1] = Warnings[].class;
        params[2] = int.class;
        for (int i = 0; i < arity; i++) {
            params[3 + i] = LongBlock.class;
        }
        return MethodHandles.lookup().findStatic(fused, Stitcher.FUSED_METHOD_NAME, MethodType.methodType(LongBlock.class, params));
    }

    private LongBlock singleValued(long[] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(values.length)) {
            for (long v : values) {
                builder.appendLong(v);
            }
            return builder.build();
        }
    }

    private LongBlock multiValued(long[][] values) {
        try (LongBlock.Builder builder = blockFactory.newLongBlockBuilder(values.length)) {
            for (long[] vv : values) {
                if (vv.length == 0) {
                    builder.appendNull();
                } else if (vv.length == 1) {
                    builder.appendLong(vv[0]);
                } else {
                    builder.beginPositionEntry();
                    for (long x : vv) {
                        builder.appendLong(x);
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }
}
