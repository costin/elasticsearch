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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit coverage for iter 15's {@link FusedClassCache}: the shape-keyed, single-flight, {@link java.lang.ref.Reference}
 * -held cache of stitched hidden classes. Every test uses <b>real</b> {@link Class} objects (a counting stitcher hands
 * back real JDK classes as stand-in stitched classes) and is deterministic — the cleared-reference path is exercised by
 * clearing the reference programmatically ({@link FusedClassCache#clearReferentForTests}), never by relying on GC,
 * except {@link #testWeakReferenceSurvivesGcWhileHeldByUnit} which proves a held unit pins a WEAK entry across a real
 * GC.
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
 *   <li><b>Negative stitch cache</b>: a failing shape is attempted once and retained as a marker (bounded by
 *       {@link FusedClassCache#MAX_ENTRIES} + eviction); the first thrown exception has {@code isFirstFailure()==true}, repeats have {@code false}. The cache does not log (the factory is the single WARN site).</li>
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

    public void testDifferentConstantValuesShareOneShapeAndClass() {
        // iter 30: a constant's VALUE is now passed as a trailing method argument, not baked into the bytecode, so two
        // trees that differ ONLY in a constant value render the SAME shape signature and reuse ONE cached stitched
        // class. (Before iter 30 the value was folded into the key — #C:J:5 vs #C:J:6 — so each value stitched its own
        // class.) The element-typed slot marker (#C:J) is retained, so a long and a double constant still differ.
        FusionNode addFive = add(input(0), constantLeaf(5L, 'J'));
        FusionNode addSix = add(input(0), constantLeaf(6L, 'J'));

        String signatureFive = FusionPlanner.shapeOf(addFive);
        String signatureSix = FusionPlanner.shapeOf(addSix);
        assertThat("the constant VALUE must be excluded from the shape signature", signatureFive, is(signatureSix));
        assertThat("the element-typed slot marker is retained", signatureFive, containsString("#C:J"));
        assertThat("the constant value must NOT appear in the signature", signatureFive, not(containsString("#C:J:5")));

        Shape shapeFive = new Shape(signatureFive, ElementKind.LONG, Path.CHECKED_VECTOR);
        Shape shapeSix = new Shape(signatureSix, ElementKind.LONG, Path.CHECKED_VECTOR);
        assertThat("two trees differing only in a constant value must be equal cache keys", shapeFive, is(shapeSix));

        FusedClassCache cache = new FusedClassCache();
        Class<?> stitched = getUnchecked(cache, shapeFive, CLASS_1);
        Class<?> reused = getUnchecked(cache, shapeSix, null); // the stitcher must NOT be invoked for the second value
        assertThat("a differing constant value must reuse the first's stitched class", reused, sameInstance(stitched));
        assertThat("only the first value stitched", cache.spins(), is(1L));
        assertThat("the differing-value shape hit the cache", cache.hits(), is(1L));
        assertThat(cache.size(), is(1));
    }

    /**
     * F2b: {@code NOT(a>b)} is a distinct shape from {@code a>b}, {@code NOT(...)} appears in the signature, and
     * {@code NOT(a>b AND c<d)} is stable across two equivalent trees.
     */
    public void testNotShapeDiffersFromBareComparisonAndIsStable() {
        FusionDescriptor gt = new FusionDescriptor(Math.class, "gt", "(JJ)Z", false, true, "", false, false, false, true);
        FusionDescriptor lt = new FusionDescriptor(Math.class, "lt", "(JJ)Z", false, true, "", false, false, false, true);
        FusionNode cmp = new FusionNode.Kernel(gt, List.of(input(0), input(1)));
        FusionNode notCmp = new FusionNode.Not(cmp);
        String cmpShape = FusionPlanner.shapeOf(cmp);
        String notShape = FusionPlanner.shapeOf(notCmp);
        assertThat("NOT(...) must appear in the shape", notShape, containsString("NOT("));
        assertThat("NOT(a>b) must not share a shape with a>b", notShape, not(is(cmpShape)));
        FusionNode doubleNot = new FusionNode.Not(new FusionNode.Not(cmp));
        assertThat("NOT(NOT(a>b)) must not share a shape with a>b", FusionPlanner.shapeOf(doubleNot), not(is(cmpShape)));
        assertThat(FusionPlanner.shapeOf(doubleNot), containsString("NOT(NOT("));

        FusionNode and = new FusionNode.Logical(
            FusionNode.BoolOp.AND,
            new FusionNode.Kernel(gt, List.of(input(0), input(1))),
            new FusionNode.Kernel(lt, List.of(input(2), input(3)))
        );
        FusionNode notAnd = new FusionNode.Not(and);
        FusionNode notAndAgain = new FusionNode.Not(
            new FusionNode.Logical(
                FusionNode.BoolOp.AND,
                new FusionNode.Kernel(gt, List.of(input(0), input(1))),
                new FusionNode.Kernel(lt, List.of(input(2), input(3)))
            )
        );
        assertThat("NOT(a>b AND c<d) must be a stable shape", FusionPlanner.shapeOf(notAnd), is(FusionPlanner.shapeOf(notAndAgain)));
        assertThat(FusionPlanner.shapeOf(notAnd), containsString("NOT("));
        assertThat(FusionPlanner.shapeOf(notAnd), containsString("LOGICAL:AND"));
    }

    /**
     * F1: a baked Div/Mod divisor IS part of the cache key (it is an immediate in the bytecode), so {@code x/1000}
     * and {@code x/500} stitch to distinct shapes/classes, while {@code x/1000} twice reuses one class. Comparison
     * constants stay value-independent — {@link #testDifferentConstantValuesShareOneShapeAndClass} stays green.
     */
    public void testBakedDivisorValuesGetDistinctClasses() {
        FusionNode div1000 = div(input(0), FusionNode.Constant.baked(1000L, 'J'));
        FusionNode div500 = div(input(0), FusionNode.Constant.baked(500L, 'J'));
        FusionNode div1000Again = div(input(0), FusionNode.Constant.baked(1000L, 'J'));

        String signature1000 = FusionPlanner.shapeOf(div1000);
        String signature500 = FusionPlanner.shapeOf(div500);
        String signature1000Again = FusionPlanner.shapeOf(div1000Again);
        assertThat("a baked divisor's VALUE must appear in the shape signature", signature1000, containsString("#C:J:1000"));
        assertThat(signature500, containsString("#C:J:500"));
        assertThat("distinct baked divisors must produce distinct signatures", signature1000, not(is(signature500)));
        assertThat("the same baked divisor must reuse one signature", signature1000, is(signature1000Again));

        Shape shape1000 = new Shape(signature1000, ElementKind.LONG, Path.CHECKED_VECTOR);
        Shape shape500 = new Shape(signature500, ElementKind.LONG, Path.CHECKED_VECTOR);
        Shape shape1000Again = new Shape(signature1000Again, ElementKind.LONG, Path.CHECKED_VECTOR);
        assertThat("distinct baked divisors must be distinct cache keys", shape1000, not(is(shape500)));
        assertThat("the same baked divisor must be an equal cache key", shape1000, is(shape1000Again));

        FusedClassCache cache = new FusedClassCache();
        Class<?> class1000 = getUnchecked(cache, shape1000, CLASS_1);
        Class<?> class500 = getUnchecked(cache, shape500, CLASS_2);
        Class<?> reused = getUnchecked(cache, shape1000Again, null); // stitcher must NOT be invoked for the repeat
        assertThat(class1000, sameInstance(CLASS_1));
        assertThat(class500, sameInstance(CLASS_2));
        assertThat("distinct baked divisors must not share a cached class", class1000, not(sameInstance(class500)));
        assertThat("x/1000 twice must reuse the first stitched class", reused, sameInstance(class1000));
        assertThat(cache.spins(), is(2L));
        assertThat(cache.hits(), is(1L));
        assertThat(cache.size(), is(2));
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

    public void testCacheIsBoundedInSize() {
        // The cache is opportunistic, so it is bounded: inserting many more distinct shapes than the cap must not grow
        // it without limit — size stays at or below MAX_ENTRIES, and the most-recently-resolved shape is retained.
        FusedClassCache cache = new FusedClassCache();
        int inserted = FusedClassCache.MAX_ENTRIES * 3;
        Shape last = null;
        for (int i = 0; i < inserted; i++) {
            last = new Shape("Add(#,#)#" + i, ElementKind.LONG, Path.BLOCK);
            getUnchecked(cache, last, CLASS_1);
            assertThat("cache size must never exceed the cap", cache.size(), is(lessThanOrEqualTo(FusedClassCache.MAX_ENTRIES)));
        }
        assertThat("far more shapes were inserted than the cap", inserted, is(greaterThan(FusedClassCache.MAX_ENTRIES)));
        assertThat("the cache settled at the cap", cache.size(), is(FusedClassCache.MAX_ENTRIES));
        // The shape resolved most recently is never the eviction victim of its own miss, so it is still cached (a hit).
        long hitsBefore = cache.hits();
        getUnchecked(cache, last, null); // stitcher would fail if invoked
        assertThat("the just-resolved shape must be retained (not evicted by its own insert)", cache.hits(), is(hitsBefore + 1));
    }

    public void testFailedStitchIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            throw new Stitcher.StitchingException("boom", new VerifyError("injected"));
        };
        Stitcher.StitchingException first = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat(first.getMessage(), is("boom"));
        assertThat("the original StitchingException keeps its cause", first.getCause(), instanceOf(VerifyError.class));
        assertThat("the first failure is signalled to the factory", first.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
            assertThat(repeat.getMessage(), is("java.lang.VerifyError: boom"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a failing shape must be attempted exactly once", invocations.get(), is(1));
        assertThat("the negative-cache marker is retained", cache.size(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("two subsequent lookups are negative hits", cache.negativeHits(), is(2L));
    }

    /**
     * A1(a): an {@link IllegalArgumentException} from emit (or ASM {@code MethodTooLargeException}) must take the
     * same negative-cache path as {@link Stitcher.StitchingException}, so resolving the shape three times stitches
     * once. The first thrown exception has {@code isFirstFailure()==true}; repeats have {@code false}. The cache
     * itself does not WARN — the factory is the single WARN site.
     */
    public void testRuntimeExceptionIsNegativeCachedFirstFailureThenRepeat() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            throw new IllegalArgumentException("injected emit failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(IllegalArgumentException.class));
        assertThat(thrown.getCause().getMessage(), is("injected emit failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
            assertThat(repeat.getMessage(), is("java.lang.IllegalArgumentException: injected emit failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a RuntimeException stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat("the negative-cache marker is retained", cache.size(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("two subsequent lookups are negative hits", cache.negativeHits(), is(2L));
    }

    /**
     * A1: an unwrapped {@link LinkageError} (the third kind that used to escape the negative cache) must also be
     * attempted once, with {@code isFirstFailure()==true} then {@code false}, and the original error preserved as
     * the cause. The cache itself does not WARN.
     */
    public void testLinkageErrorIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            throw new LinkageError("injected linkage failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(LinkageError.class));
        assertThat(thrown.getCause().getMessage(), is("injected linkage failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
            assertThat(repeat.getMessage(), is("java.lang.LinkageError: injected linkage failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a LinkageError stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /**
     * A1: a {@link ReflectiveOperationException} (the shape of a {@code findStatic} miss on the emitted class) must
     * take the same negative-cache path, with {@code isFirstFailure()==true} then {@code false}. The
     * {@link FusedClassCache.ClassStitcher} seam declares it so a test can inject {@link NoSuchMethodException}
     * without wrapping. The cache itself does not WARN.
     */
    public void testReflectiveOperationExceptionIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            throw new NoSuchMethodException("injected findStatic failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(NoSuchMethodException.class));
        assertThat(thrown.getCause().getMessage(), is("injected findStatic failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
            assertThat(repeat.getMessage(), is("java.lang.NoSuchMethodException: injected findStatic failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a ReflectiveOperationException stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /**
     * A1(b): {@link OutOfMemoryError} / {@link InternalError} are transient — they must not poison the shape. A second
     * attempt stitches again and can succeed. {@link StackOverflowError} is a {@link VirtualMachineError} too but is
     * wrapped (see {@link #testStackOverflowErrorIsNegativeCachedFirstFailureThenRepeat}).
     */
    public void testOutOfMemoryErrorIsNotCachedAndRetries() throws Exception {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                throw new OutOfMemoryError("injected");
            }
            return CLASS_1;
        };
        OutOfMemoryError oom = expectThrows(OutOfMemoryError.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat("VirtualMachineError must propagate unwrapped, not as StitchingException", oom.getMessage(), is("injected"));
        assertThat("the first failure must not be retained as a negative-cache marker", cache.failures(), is(0L));
        assertThat("the empty slot must be dropped so the retry is a fresh miss", cache.size(), is(0));
        assertThat("the second attempt must stitch again and succeed", cache.get(SHAPE_A, stitcher), sameInstance(CLASS_1));
        assertThat("OOM then success is two stitches", invocations.get(), is(2));
        assertThat(cache.failures(), is(0L));
        assertThat(cache.size(), is(1));
    }

    /**
     * A {@link StackOverflowError} from emit recursion is deterministic for that shape: wrap it, negatively cache it,
     * {@code isFirstFailure()==true} then {@code false}. Remaining {@link VirtualMachineError}s stay transient.
     */
    public void testStackOverflowErrorIsNegativeCachedFirstFailureThenRepeat() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            throw new StackOverflowError("injected emit recursion");
        };
        Stitcher.StitchingException first = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        assertThat("SOE is preserved as the cause", first.getCause(), instanceOf(StackOverflowError.class));
        assertThat("the first failure is signalled to the factory", first.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
            assertThat(repeat.getMessage(), is("java.lang.StackOverflowError: injected emit recursion"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a StackOverflowError stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /**
     * A failing stitch is a first-failure <em>signal</em> ({@code failures()}/{@code negativeHits()}), not a log
     * line. {@code FusedClassCache} has no logger; {@code isFirstFailure} for a {@link Stitcher.StitchingException}
     * is already asserted by {@link #testFailedStitchIsNegativeCachedAndAttemptedOnce}.
     */
    public void testFailedStitchCountsFirstFailureThenNegativeHitsOnRepeat() {
        FusedClassCache cache = new FusedClassCache();
        FusedClassCache.ClassStitcher stitcher = () -> { throw new Stitcher.StitchingException("boom", new VerifyError("injected")); };
        expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        for (int i = 0; i < 2; i++) {
            expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
        }
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    public void testNegativeCacheIsBoundedInSize() {
        // Negative markers count toward MAX_ENTRIES and survive the "free wins" pass, but the arbitrary-victim pass
        // still drops them when over capacity — a flood of failing shapes must not grow the cache without bound.
        FusedClassCache cache = new FusedClassCache();
        FusedClassCache.ClassStitcher stitcher = () -> { throw new Stitcher.StitchingException("boom", new VerifyError("injected")); };
        int inserted = FusedClassCache.MAX_ENTRIES * 3;
        Shape last = null;
        for (int i = 0; i < inserted; i++) {
            Shape shape = new Shape("Add(#,#)#" + i, ElementKind.LONG, Path.BLOCK);
            last = shape;
            expectThrows(Stitcher.StitchingException.class, () -> cache.get(shape, stitcher));
            assertThat("negative-cache size must never exceed the cap", cache.size(), is(lessThanOrEqualTo(FusedClassCache.MAX_ENTRIES)));
        }
        assertThat("far more failing shapes were inserted than the cap", inserted, is(greaterThan(FusedClassCache.MAX_ENTRIES)));
        assertThat("the cache settled at the cap", cache.size(), is(FusedClassCache.MAX_ENTRIES));
        // The shape resolved most recently is never the eviction victim of its own miss, so the marker is still there.
        long negativeHitsBefore = cache.negativeHits();
        Shape retained = last;
        expectThrows(Stitcher.StitchingException.class, () -> cache.get(retained, stitcher));
        assertThat("the just-failed shape must be retained as a negative hit", cache.negativeHits(), is(negativeHitsBefore + 1));
    }

    public void testConcurrentFailingStampedeAttemptsOnce() throws Exception {
        FusedClassCache cache = new FusedClassCache();
        int threads = 16;
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.ClassStitcher stitcher = () -> {
            invocations.incrementAndGet();
            // Widen the critical section so latecomers must observe the cached Failure, not race a second stitch.
            safeSleep(25);
            throw new Stitcher.StitchingException("boom", new VerifyError("injected"));
        };
        CyclicBarrier startLine = new CyclicBarrier(threads);
        ThreadFactory daemons = runnable -> {
            Thread t = new Thread(runnable, "fused-class-cache-fail-stampede");
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemons);
        List<Future<Stitcher.StitchingException>> futures = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, stitcher));
                }));
            }
            int firstFailures = 0;
            for (Future<Stitcher.StitchingException> future : futures) {
                Stitcher.StitchingException thrown = future.get(30, TimeUnit.SECONDS);
                if (thrown.isFirstFailure()) {
                    assertThat(thrown.getMessage(), is("boom"));
                    assertThat("the original StitchingException keeps its cause", thrown.getCause(), instanceOf(VerifyError.class));
                    firstFailures++;
                } else {
                    assertThat(thrown.getMessage(), is("java.lang.VerifyError: boom"));
                    assertThat("reconstructed marker exceptions drop the cause", thrown.getCause(), nullValue());
                }
            }
            assertThat("exactly one first-failure signal among the stampede", firstFailures, is(1));
        } finally {
            pool.shutdownNow();
            assertTrue("failing stampede pool must terminate", pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertThat("the stitcher must be invoked exactly once", invocations.get(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("the rest are negative hits", cache.negativeHits(), is((long) (threads - 1)));
        assertThat("SPINS", cache.spins(), is(1L));
        assertThat("MISSES", cache.misses(), is(1L));
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

    /**
     * G5/E7: a cache HIT must not call {@code findStatic}. The first plan resolves inside the cached unit; the
     * second plan reuses those handles. Fails before handles are stored on the cached unit because the factory
     * re-resolves on every plan.
     */
    public void testCacheHitDoesNotCallFindStatic() {
        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        assertThat(FusionPlanner.compilerForTests, nullValue());

        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        int afterFirst = FusedClassCache.findStaticCallsForTests.get();
        assertThat("first plan must resolve fused methods", afterFirst, greaterThan(0));

        EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        assertThat("cache hit must not call findStatic", FusedClassCache.findStaticCallsForTests.get(), is(afterFirst));
    }

    /**
     * iter5 I2: a {@link RefType#WEAK} cache entry must survive GC while the {@code CachedFused} unit is held
     * (this models the factory pinning the unit). Fails on the pre-fix code ({@code c1474931f0e6}) because
     * {@code get} returned a bare {@code Class<?>} — holding it did not pin the {@code CachedFused}, so GC
     * cleared the entry and the second lookup re-stitched a different class ({@code spins()==2}).
     *
     * <p>The {@code resolvedHandle}/{@code resolvedSpreader}/{@code resolvedMethod} scans are deleted by I2;
     * a cache HIT therefore cannot refresh the soft-ref LRU clock by walking the table. Zero-{@code findStatic}
     * on a HIT remains covered by {@link #testCacheHitDoesNotCallFindStatic}.
     */
    public void testWeakReferenceSurvivesGcWhileHeldByUnit() throws Exception {
        FusedClassCache cache = new FusedClassCache(RefType.WEAK);
        AtomicInteger n = new AtomicInteger();
        Class<?>[] fresh = { CLASS_1, CLASS_2, CLASS_3 };
        FusedClassCache.CachedFused held = cache.getResolved(SHAPE_A, () -> fresh[n.getAndIncrement() % fresh.length]);
        assertThat(cache.spins(), is(1L));
        Class<?> first = held.fusedClass();

        WeakReference<Object> canary = new WeakReference<>(new Object());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (canary.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(50);
        }
        assertThat("GC must have run (canary collected)", canary.get(), nullValue());

        FusedClassCache.CachedFused again = cache.getResolved(SHAPE_A, () -> { throw new AssertionError("stitcher must not be invoked"); });
        assertThat("holding the unit must pin the WEAK entry (no re-stitch)", cache.spins(), is(1L));
        assertThat(again.fusedClass(), sameInstance(first));
        assertThat(cache.softRefClearedCount(), is(0L));
        Reference.reachabilityFence(held);
    }

    /**
     * I2: {@code bindHandle} must not return a cached handle whose resolved {@link MethodType} differs from the
     * requested one. A mismatch falls back to {@code findStaticCounted} so shape/type drift fails at plan time,
     * not as {@code WrongMethodTypeException} at eval. The deleted {@code resolvedHandle} scan keyed only by
     * {@code Class}+name and would have returned the cached (wrong-typed) handle.
     */
    public void testBindFallsBackToFindStaticOnTypeMismatch() throws Exception {
        MethodType typeA = MethodType.methodType(int.class, int.class);
        MethodType typeB = MethodType.methodType(long.class, long.class);
        MethodHandle cached = FusedClassCache.findStaticCounted(MethodHandles.lookup(), Math.class, "abs", typeA);
        int afterSetup = FusedClassCache.findStaticCallsForTests.get();
        FusedClassCache.CachedFused unit = new FusedClassCache.CachedFused(
            Math.class,
            typeA,
            Map.of("abs", new FusedClassCache.ResolvedMethod(cached, cached.asSpreader(Object[].class, 1)))
        );

        FusedExpressionEvaluatorFactory.Bound bound = FusedExpressionEvaluatorFactory.bind(unit, "abs", typeB);
        assertThat(
            "type mismatch must fall back to findStatic rather than returning the cached handle",
            FusedClassCache.findStaticCallsForTests.get(),
            is(afterSetup + 1)
        );
        assertThat(bound.handle().type(), is(typeB));
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

    /** A constant leaf carrying {@code value} at the given JVM element char (J/I/D) — the value the planner would embed. */
    private static FusionNode constantLeaf(Object value, char element) {
        return new FusionNode.Constant(value, element);
    }

    private static FusionNode add(FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(descriptor("addExact"), List.of(left, right));
    }

    private static FusionNode mul(FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(descriptor("mulExact"), List.of(left, right));
    }

    private static FusionNode div(FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(descriptor("floorDiv"), List.of(left, right));
    }

    /** A binary kernel node over {@code kernelClass}/{@code method} with a {@code (JJ)J} descriptor. */
    private static FusionNode kernelNode(Class<?> kernelClass, String method, FusionNode left, FusionNode right) {
        return new FusionNode.Kernel(
            new FusionDescriptor(kernelClass, method, "(JJ)J", true, false, "java.lang.ArithmeticException", false, false, true, true),
            List.of(left, right)
        );
    }

    /**
     * A descriptor whose {@link FusionDescriptor#kernelClass()} + {@link FusionDescriptor#kernelMethod()} +
     * {@link FusionDescriptor#kernelType()} feed {@link FusionPlanner#shapeOf}. The concrete class/method are arbitrary
     * (real {@code (JJ)J} static methods on {@link Math}); only their rendered signature matters here.
     */
    private static FusionDescriptor descriptor(String method) {
        return new FusionDescriptor(Math.class, method, "(JJ)J", true, false, "java.lang.ArithmeticException", false, false, true, true);
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
