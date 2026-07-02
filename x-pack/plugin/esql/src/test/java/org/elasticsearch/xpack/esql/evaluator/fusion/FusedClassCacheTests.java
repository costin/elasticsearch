/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.RefType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Shape;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.planner.Layout;
import org.junit.After;
import org.junit.Before;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit coverage for iter 15's {@link FusedClassCache}: the shape-keyed, single-flight, {@link java.lang.ref.Reference}
 * -held cache of stitched hidden classes. Every test uses <b>real</b> {@link Class} objects (a counting stitcher hands
 * back real JDK classes as stand-in stitched classes) and is deterministic — the cleared-reference path is exercised by
 * clearing the reference programmatically ({@link FusedClassCache#clearReferentForTests}), never by relying on GC.
 *
 * <p>Scenarios (per the iter brief):
 * <ol>
 *   <li><b>Concurrent stampede</b>: {@code N} threads racing on one shape spin the stitcher exactly once
 *       ({@code SPINS == 1}, {@code HITS == N-1}).</li>
 *   <li><b>Different shapes → different classes</b>; <b>same shape, different column bindings → one class</b> (the key
 *       excludes bindings — proved via two {@link FusionNode} trees whose {@link FusionPlanner#shapeOf} signatures are
 *       equal despite different input indices).</li>
 *   <li><b>Cleared reference re-stitches</b> on the next access ({@code SOFT_REF_CLEARED} bumps, a new class is
 *       returned), for both the {@link RefType#SOFT} default and the {@link RefType#WEAK} toggle.</li>
 *   <li><b>Admission</b>: no admission filter was adopted (D3), so a first-sight shape stitches immediately.</li>
 * </ol>
 * A final smoke test proves the cache is wired into the plan-time path: two identical {@link EvalMapper} builds of the
 * same fusable shape stitch once and then hit the cache.
 */
public class FusedClassCacheTests extends ESTestCase {

    // Real, distinct classes used as stand-in "stitched" classes. Any real Class object works — the cache never
    // introspects them, it only stores and hands them back.
    private static final Class<?> CLASS_1 = java.util.ArrayList.class;
    private static final Class<?> CLASS_2 = java.util.HashMap.class;
    private static final Class<?> CLASS_3 = java.util.TreeMap.class;

    private static final Shape SHAPE_A = new Shape("Mul(Add(#,#),#)", ElementKind.LONG, Path.BLOCK);
    private static final Shape SHAPE_B = new Shape("Add(#,#)", ElementKind.LONG, Path.BLOCK);

    @Before
    public void resetSingletonCache() {
        // The wiring smoke test uses the process-wide singleton; start it clean so its counters are attributable.
        FusionPlanner.classCache().resetForTests();
        FusionPlanner.compilerForTests = null;
    }

    @After
    public void clearInjection() {
        FusionPlanner.compilerForTests = null;
    }

    public void testConcurrentStampedeStitchesExactlyOnce() throws Exception {
        FusedClassCache cache = new FusedClassCache();
        int threads = 16;
        AtomicInteger invocations = new AtomicInteger();
        CyclicBarrier startLine = new CyclicBarrier(threads);
        ThreadFactory daemons = runnable -> {
            Thread t = new Thread(runnable, "fused-class-cache-stampede");
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemons);
        List<Future<Class<?>>> futures = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(); // release all threads together so they genuinely contend on the same shape
                    return cache.get(SHAPE_A, () -> {
                        invocations.incrementAndGet();
                        // Widen the critical section so latecomers must observe the single-flight result, not race past it.
                        safeSleep(25);
                        return CLASS_1;
                    });
                }));
            }
            for (Future<Class<?>> future : futures) {
                assertThat("every caller must observe the one stitched class", future.get(30, TimeUnit.SECONDS), sameInstance(CLASS_1));
            }
        } finally {
            pool.shutdownNow();
            assertTrue("stampede pool must terminate", pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertThat("the stitcher must be invoked exactly once", invocations.get(), is(1));
        assertThat("SPINS", cache.spins(), is(1L));
        assertThat("MISSES", cache.misses(), is(1L));
        assertThat("HITS", cache.hits(), is((long) (threads - 1)));
        assertThat("SOFT_REF_CLEARED", cache.softRefClearedCount(), is(0L));
        assertThat(cache.size(), is(1));
    }

    public void testDifferentShapesGetDifferentClasses() {
        FusedClassCache cache = new FusedClassCache();
        Class<?> a = getUnchecked(cache, SHAPE_A, CLASS_1);
        Class<?> b = getUnchecked(cache, SHAPE_B, CLASS_2);

        assertThat(a, sameInstance(CLASS_1));
        assertThat(b, sameInstance(CLASS_2));
        assertThat(a, not(sameInstance(b)));
        assertThat("two shapes both missed", cache.misses(), is(2L));
        assertThat("two shapes both spun", cache.spins(), is(2L));
        assertThat(cache.hits(), is(0L));
        assertThat(cache.size(), is(2));

        // The same emit path but a different element type is a distinct key too.
        Class<?> aDouble = getUnchecked(cache, new Shape(SHAPE_A.signature(), ElementKind.DOUBLE, Path.BLOCK), CLASS_3);
        assertThat(aDouble, sameInstance(CLASS_3));
        assertThat(cache.size(), is(3));
    }

    public void testSameShapeDifferentBindingsReusesOneClass() {
        // Two trees with the SAME operator structure but DIFFERENT input bindings (column indices). The cache key is
        // built from FusionPlanner.shapeOf, which renders every leaf as '#', so the two signatures must be equal —
        // this is precisely how the key "excludes value bindings / column identities".
        FusionNode treeAbC = mul(add(input(0), input(1)), input(2)); // (col0 + col1) * col2
        FusionNode treeCaB = mul(add(input(2), input(0)), input(1)); // (col2 + col0) * col1

        String signatureAbC = FusionPlanner.shapeOf(treeAbC);
        String signatureCaB = FusionPlanner.shapeOf(treeCaB);
        assertThat("shape signature must be binding-independent", signatureAbC, is(signatureCaB));

        Shape first = new Shape(signatureAbC, ElementKind.LONG, Path.BLOCK);
        Shape second = new Shape(signatureCaB, ElementKind.LONG, Path.BLOCK);
        assertThat("the two Shapes must be equal keys", first, is(second));

        FusedClassCache cache = new FusedClassCache();
        Class<?> stitched = getUnchecked(cache, first, CLASS_1);
        Class<?> reused = getUnchecked(cache, second, null); // stitcher would fail if invoked

        assertThat(reused, sameInstance(stitched));
        assertThat("only the first binding stitched", cache.spins(), is(1L));
        assertThat("the second binding hit the cache", cache.hits(), is(1L));
        assertThat(cache.size(), is(1));
    }

    public void testDifferentKernelSameStructureGetDifferentClasses() {
        // Two STRUCTURALLY IDENTICAL trees (same shape, same element, same arity) differing ONLY in the child kernel:
        // (a + b) * c using an "Add"-named kernel vs (a - b) * c using a "Sub"-named kernel. Different kernels stitch to
        // different bytecode, so their signatures — and their cache entries — must differ.
        FusionNode addTree = mul(kernelNode(AddKernel.class, "processLongs", input(0), input(1)), input(2));
        FusionNode subTree = mul(kernelNode(SubKernel.class, "processLongs", input(0), input(1)), input(2));

        String addSignature = FusionPlanner.shapeOf(addTree);
        String subSignature = FusionPlanner.shapeOf(subTree);
        assertThat("different kernels must produce different signatures", addSignature, not(is(subSignature)));

        FusedClassCache cache = new FusedClassCache();
        Class<?> addClass = getUnchecked(cache, new Shape(addSignature, ElementKind.LONG, Path.BLOCK), CLASS_1);
        Class<?> subClass = getUnchecked(cache, new Shape(subSignature, ElementKind.LONG, Path.BLOCK), CLASS_2);

        assertThat("distinct kernels must not share a cached class", addClass, not(sameInstance(subClass)));
        assertThat(cache.spins(), is(2L));
        assertThat(cache.size(), is(2));
    }

    public void testSimpleNameCollisionDoesNotShareCachedClass() {
        // The landmine the iter-15 review flagged: two DISTINCT kernel classes that share a simple name (but not a
        // fully-qualified name) with the same method/arity/element. Structurally identical trees differ only in which
        // of the two same-simple-name kernels the child uses.
        FusionNode treeOne = mul(kernelNode(HolderOne.Kernel.class, "process", input(0), input(1)), input(2));
        FusionNode treeTwo = mul(kernelNode(HolderTwo.Kernel.class, "process", input(0), input(1)), input(2));

        assertThat(
            "the two kernels share a simple name",
            HolderOne.Kernel.class.getSimpleName(),
            is(HolderTwo.Kernel.class.getSimpleName())
        );
        assertThat("but not a fully-qualified name", HolderOne.Kernel.class.getName(), not(is(HolderTwo.Kernel.class.getName())));

        // Under the OLD simple-name-only key the two shapes were INDISTINGUISHABLE — this is exactly the silent
        // collision. This assertion is what would FAIL (flip to "not equal") had shapeOf not been hardened.
        assertThat(
            "the pre-fix simple-name key collided these distinct kernels",
            oldStyleSimpleNameSignature(treeOne),
            is(oldStyleSimpleNameSignature(treeTwo))
        );

        // The hardened shapeOf (FQN + method + descriptor + overflow-exception) separates them.
        String signatureOne = FusionPlanner.shapeOf(treeOne);
        String signatureTwo = FusionPlanner.shapeOf(treeTwo);
        assertThat("the hardened FQN-based signatures must differ", signatureOne, not(is(signatureTwo)));

        // And so the cache hands back the correct, DIFFERENT class for each — no wrong-class reuse.
        FusedClassCache cache = new FusedClassCache();
        Class<?> classOne = getUnchecked(cache, new Shape(signatureOne, ElementKind.LONG, Path.BLOCK), CLASS_1);
        Class<?> classTwo = getUnchecked(cache, new Shape(signatureTwo, ElementKind.LONG, Path.BLOCK), CLASS_2);
        assertThat(classOne, sameInstance(CLASS_1));
        assertThat(classTwo, sameInstance(CLASS_2));
        assertThat("no silent collision: distinct kernels → distinct cached classes", classOne, not(sameInstance(classTwo)));
        assertThat(cache.spins(), is(2L));
        assertThat(cache.size(), is(2));
    }

    public void testClearedSoftReferenceReStitches() {
        FusedClassCache cache = new FusedClassCache();
        assertThat(cache.referenceType(), is(RefType.SOFT));

        Class<?> first = getUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat(first, sameInstance(CLASS_1));
        assertThat(cache.spins(), is(1L));
        assertThat(cache.misses(), is(1L));

        // Clear the referent deterministically (no GC dependency), simulating a soft-reclaim under heap pressure.
        cache.clearReferentForTests(SHAPE_A);

        Class<?> reStitched = getUnchecked(cache, SHAPE_A, CLASS_2);
        assertThat("a cleared reference must re-stitch to the fresh class", reStitched, sameInstance(CLASS_2));
        assertThat("SOFT_REF_CLEARED bumps once", cache.softRefClearedCount(), is(1L));
        assertThat("the re-stitch is a spin", cache.spins(), is(2L));
        assertThat("but not a new first-sight miss", cache.misses(), is(1L));
        assertThat("SPINS == MISSES + SOFT_REF_CLEARED", cache.spins(), is(cache.misses() + cache.softRefClearedCount()));
        assertThat(cache.hits(), is(0L));
    }

    public void testWeakReferenceToggleReStitchesOnClear() {
        FusedClassCache cache = new FusedClassCache(RefType.WEAK);
        assertThat(cache.referenceType(), is(RefType.WEAK));

        Class<?> first = getUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat(first, sameInstance(CLASS_1));

        cache.clearReferentForTests(SHAPE_A);
        Class<?> reStitched = getUnchecked(cache, SHAPE_A, CLASS_2);
        assertThat(reStitched, sameInstance(CLASS_2));
        assertThat(cache.softRefClearedCount(), is(1L));
        assertThat(cache.spins(), is(2L));

        // The setter switches strategy for classes wrapped afterwards.
        cache.setReferenceType(RefType.SOFT);
        assertThat(cache.referenceType(), is(RefType.SOFT));
    }

    public void testNoAdmissionFilterFirstSightStitches() {
        // D3 decision: no admission filter. A first-sight shape must stitch immediately (no "wait for the 2nd sighting"
        // threshold), so a single get() spins once and there is no ADMISSION_REJECTED counter to consult.
        FusedClassCache cache = new FusedClassCache();
        Class<?> stitched = getUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat("first sight stitches, not admitted-and-skipped", stitched, sameInstance(CLASS_1));
        assertThat(cache.spins(), is(1L));
        assertThat(cache.misses(), is(1L));
        assertThat(cache.size(), is(1));
    }

    public void testFailedStitchIsNotCachedAndPropagates() {
        FusedClassCache cache = new FusedClassCache();
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, () -> {
            throw new Stitcher.StitchingException("boom", new VerifyError("injected"));
        }));
        assertThat(thrown.getMessage(), is("boom"));
        assertThat("a failed stitch leaves no poisoned entry behind", cache.size(), is(0));

        // A later attempt for the same shape starts clean and can succeed.
        Class<?> stitched = getUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat(stitched, sameInstance(CLASS_1));
        assertThat(cache.size(), is(1));
    }

    public void testCacheWiredIntoPlanTimePath() {
        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();

        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        // (a + b) * c over longs fuses; long arithmetic is overflow-checked, so the block path AND the checked-vector
        // path are both compiled — two stitches on the first build.
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        long spinsAfterFirstBuild = cache.spins();
        long sizeAfterFirstBuild = cache.size();
        assertThat("the first build must stitch (block + checked-vector paths)", spinsAfterFirstBuild, greaterThanOrEqualTo(2L));
        assertThat(sizeAfterFirstBuild, greaterThanOrEqualTo(2L));

        long hitsBeforeSecondBuild = cache.hits();
        // A second identical build (fresh attributes → different column identities, same shape) must reuse the classes.
        FieldAttribute a2 = field("a2", DataType.LONG);
        FieldAttribute b2 = field("b2", DataType.LONG);
        FieldAttribute c2 = field("c2", DataType.LONG);
        Layout layout2 = layout(a2, b2, c2);
        Expression expr2 = new Mul(Source.EMPTY, new Add(Source.EMPTY, a2, b2, EsqlTestUtils.TEST_CFG), c2);
        EvalMapper.toEvaluator(FoldContext.small(), expr2, layout2);

        assertThat("the second identical shape must not re-stitch", cache.spins(), is(spinsAfterFirstBuild));
        assertThat("the second build must hit the cache for every path", cache.hits(), greaterThanOrEqualTo(hitsBeforeSecondBuild + 2));
    }

    private static Class<?> getUnchecked(FusedClassCache cache, Shape shape, Class<?> result) {
        try {
            return cache.get(shape, () -> {
                if (result == null) {
                    throw new AssertionError("stitcher must not be invoked for shape [" + shape + "]");
                }
                return result;
            });
        } catch (Stitcher.StitchingException e) {
            throw new AssertionError("unexpected stitch failure", e);
        }
    }

    private static FusionNode input(int index) {
        return new FusionNode.Input(index);
    }

    private static FusionNode add(FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(descriptor("addExact"), List.of(left, right));
    }

    private static FusionNode mul(FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(descriptor("mulExact"), List.of(left, right));
    }

    /** A binary kernel node over {@code kernelClass}/{@code method} with a {@code (JJ)J} descriptor. */
    private static FusionNode kernelNode(Class<?> kernelClass, String method, FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(new FusionDescriptor(kernelClass, method, "(JJ)J", true, false), List.of(left, right));
    }

    /**
     * A descriptor whose {@link FusionDescriptor#kernelClass()} + {@link FusionDescriptor#kernelMethod()} +
     * {@link FusionDescriptor#kernelType()} feed {@link FusionPlanner#shapeOf}. The concrete class/method are arbitrary
     * (real {@code (JJ)J} static methods on {@link Math}); only their rendered signature matters here.
     */
    private static FusionDescriptor descriptor(String method) {
        return new FusionDescriptor(Math.class, method, "(JJ)J", true, false);
    }

    /**
     * Reproduces the PRE-FIX shapeOf format (kernel simple name + method only) so a test can prove the old key would
     * have silently collided two distinct kernels that share a simple name. Not used by production code.
     */
    private static String oldStyleSimpleNameSignature(FusionNode node) {
        StringBuilder sb = new StringBuilder();
        appendOldStyle(node, sb);
        return sb.toString();
    }

    private static void appendOldStyle(FusionNode node, StringBuilder sb) {
        if (node instanceof FusionNode.Kernel kernel) {
            sb.append(kernel.descriptor().kernelClass().getSimpleName()).append('.').append(kernel.descriptor().kernelMethod()).append('(');
            List<FusionNode> children = kernel.children();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendOldStyle(children.get(i), sb);
            }
            sb.append(')');
        } else {
            sb.append('#');
        }
    }

    // Distinct-simple-name kernels for the "different kernel, same structure" test (mirrors Add vs Sub).
    private static final class AddKernel {}

    private static final class SubKernel {}

    // Two kernels that SHARE a simple name ("Kernel") but differ in fully-qualified name — the silent-collision landmine.
    private static final class HolderOne {
        private static final class Kernel {}
    }

    private static final class HolderTwo {
        private static final class Kernel {}
    }

    private static FieldAttribute field(String name, DataType type) {
        return new FieldAttribute(
            Source.EMPTY,
            name,
            new EsField(name, type, Collections.emptyMap(), false, EsField.TimeSeriesFieldType.NONE)
        );
    }

    private static Layout layout(FieldAttribute... fields) {
        Layout.Builder builder = new Layout.Builder();
        for (FieldAttribute field : fields) {
            builder.append(field);
        }
        return builder.build();
    }
}
