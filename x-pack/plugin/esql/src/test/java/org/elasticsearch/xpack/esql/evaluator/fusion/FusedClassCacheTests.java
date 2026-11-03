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
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.evaluator.EvalMapper;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.CachedFused;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Path;
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
 * Unit coverage for the {@link FusedClassCache}: the shape-keyed, single-flight, weight-bounded
 * {@link org.elasticsearch.common.cache.Cache} of stitched hidden classes. Every test uses <b>real</b> {@link Class}
 * objects (a counting loader hands back real JDK classes as stand-in stitched classes) and is deterministic.
 *
 * <p>Scenarios:
 * <ol>
 * <li><b>Concurrent stampede</b>: {@code N} threads racing on one shape invoke the loader exactly once and all
 * observe the one stitched class.</li>
 * <li><b>Different shapes → different classes</b>; <b>same shape, different column bindings → one class</b> (the key
 * excludes bindings and non-baked constant values).</li>
 * <li><b>Weight-bounded eviction</b>: inserting far more class weight than the byte ceiling evicts LRU entries, the
 * retained weight stays under the ceiling, and a unit obtained before eviction is still usable.</li>
 * <li><b>Admission doorkeeper</b>: with {@code min_sightings=2} a shape is left unfused on its first sighting and
 * fused on its second; a later cache hit does not look like an admission skip.</li>
 * <li><b>Negative stitch cache</b>: a failing shape is attempted once and retained as a marker; the first thrown
 * exception has {@code isFirstFailure()==true}, repeats {@code false}. The cache does not log.</li>
 * <li><b>Transient errors</b> (OOM) are not cached and retry; <b>expiry</b> drops an idle shape so it re-stitches.</li>
 * </ol>
 * A smoke test proves the cache is wired into the plan-time path.
 */
public class FusedClassCacheTests extends ESTestCase {

    private static final Class<?> CLASS_1 = java.util.ArrayList.class;
    private static final Class<?> CLASS_2 = java.util.HashMap.class;
    private static final Class<?> CLASS_3 = java.util.TreeMap.class;

    private static final int CLASS_BYTES = 1000;

    private static final Shape SHAPE_A = new Shape("Mul(Add(#,#),#)", ElementKind.LONG, Path.BLOCK);
    private static final Shape SHAPE_B = new Shape("Add(#,#)", ElementKind.LONG, Path.BLOCK);

    @Before
    public void resetSingletonCache() {
        FusionPlanner.classCache().resetForTests();
        FusionPlanner.classCache().setMinSightings(1);
        FusionPlanner.compilerForTests = null;
    }

    @After
    public void clearInjection() {
        FusionPlanner.classCache().setMinSightings(1);
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
                        safeSleep(25);
                        return new CachedFused(CLASS_1, CLASS_BYTES, null, Map.of());
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

        assertThat("the loader must be invoked exactly once", invocations.get(), is(1));
        assertThat("exactly one class was defined", cache.classesDefined(), is(1L));
        assertThat(cache.size(), is(1));
    }

    public void testDifferentShapesGetDifferentClasses() {
        FusedClassCache cache = new FusedClassCache();
        Class<?> a = getUnchecked(cache, SHAPE_A, CLASS_1);
        Class<?> b = getUnchecked(cache, SHAPE_B, CLASS_2);

        assertThat(a, sameInstance(CLASS_1));
        assertThat(b, sameInstance(CLASS_2));
        assertThat(a, not(sameInstance(b)));
        assertThat("two distinct shapes defined two classes", cache.classesDefined(), is(2L));
        assertThat("two shapes both missed", cache.misses(), is(2L));
        assertThat(cache.hits(), is(0L));
        assertThat(cache.size(), is(2));

        Class<?> aDouble = getUnchecked(cache, new Shape(SHAPE_A.signature(), ElementKind.DOUBLE, Path.BLOCK), CLASS_3);
        assertThat(aDouble, sameInstance(CLASS_3));
        assertThat(cache.size(), is(3));
    }

    public void testSameShapeDifferentBindingsReusesOneClass() {
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
        Class<?> reused = getUnchecked(cache, second, (Class<?>) null); // loader would fail if invoked

        assertThat(reused, sameInstance(stitched));
        assertThat("only the first binding stitched", cache.classesDefined(), is(1L));
        assertThat("the second binding hit the cache", cache.hits(), is(1L));
        assertThat(cache.size(), is(1));
    }

    public void testDifferentConstantValuesShareOneShapeAndClass() {
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
        Class<?> reused = getUnchecked(cache, shapeSix, (Class<?>) null); // the loader must NOT be invoked for the second value
        assertThat("a differing constant value must reuse the first's stitched class", reused, sameInstance(stitched));
        assertThat("only the first value stitched", cache.classesDefined(), is(1L));
        assertThat("the differing-value shape hit the cache", cache.hits(), is(1L));
        assertThat(cache.size(), is(1));
    }

    /**
     *: {@code NOT(a>b)} is a distinct shape from {@code a>b}, {@code NOT(...)} appears in the signature, and
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

    /** a baked Div/Mod divisor IS part of the cache key (it is an immediate in the bytecode), so {@code x/1000} */
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
        Class<?> reused = getUnchecked(cache, shape1000Again, (Class<?>) null); // loader must NOT be invoked for the repeat
        assertThat(class1000, sameInstance(CLASS_1));
        assertThat(class500, sameInstance(CLASS_2));
        assertThat("distinct baked divisors must not share a cached class", class1000, not(sameInstance(class500)));
        assertThat("x/1000 twice must reuse the first stitched class", reused, sameInstance(class1000));
        assertThat(cache.classesDefined(), is(2L));
        assertThat(cache.hits(), is(1L));
        assertThat(cache.size(), is(2));
    }

    public void testDifferentKernelSameStructureGetDifferentClasses() {
        FusionNode addTree = mul(kernelNode(AddKernel.class, "processLongs", input(0), input(1)), input(2));
        FusionNode subTree = mul(kernelNode(SubKernel.class, "processLongs", input(0), input(1)), input(2));

        String addSignature = FusionPlanner.shapeOf(addTree);
        String subSignature = FusionPlanner.shapeOf(subTree);
        assertThat("different kernels must produce different signatures", addSignature, not(is(subSignature)));

        FusedClassCache cache = new FusedClassCache();
        Class<?> addClass = getUnchecked(cache, new Shape(addSignature, ElementKind.LONG, Path.BLOCK), CLASS_1);
        Class<?> subClass = getUnchecked(cache, new Shape(subSignature, ElementKind.LONG, Path.BLOCK), CLASS_2);

        assertThat("distinct kernels must not share a cached class", addClass, not(sameInstance(subClass)));
        assertThat(cache.classesDefined(), is(2L));
        assertThat(cache.size(), is(2));
    }

    public void testSimpleNameCollisionDoesNotShareCachedClass() {
        FusionNode treeOne = mul(kernelNode(HolderOne.Kernel.class, "process", input(0), input(1)), input(2));
        FusionNode treeTwo = mul(kernelNode(HolderTwo.Kernel.class, "process", input(0), input(1)), input(2));

        assertThat(
            "the two kernels share a simple name",
            HolderOne.Kernel.class.getSimpleName(),
            is(HolderTwo.Kernel.class.getSimpleName())
        );
        assertThat("but not a fully-qualified name", HolderOne.Kernel.class.getName(), not(is(HolderTwo.Kernel.class.getName())));

        assertThat(
            "the pre-fix simple-name key collided these distinct kernels",
            oldStyleSimpleNameSignature(treeOne),
            is(oldStyleSimpleNameSignature(treeTwo))
        );

        String signatureOne = FusionPlanner.shapeOf(treeOne);
        String signatureTwo = FusionPlanner.shapeOf(treeTwo);
        assertThat("the hardened FQN-based signatures must differ", signatureOne, not(is(signatureTwo)));

        FusedClassCache cache = new FusedClassCache();
        Class<?> classOne = getUnchecked(cache, new Shape(signatureOne, ElementKind.LONG, Path.BLOCK), CLASS_1);
        Class<?> classTwo = getUnchecked(cache, new Shape(signatureTwo, ElementKind.LONG, Path.BLOCK), CLASS_2);
        assertThat(classOne, sameInstance(CLASS_1));
        assertThat(classTwo, sameInstance(CLASS_2));
        assertThat("no silent collision: distinct kernels → distinct cached classes", classOne, not(sameInstance(classTwo)));
        assertThat(cache.classesDefined(), is(2L));
        assertThat(cache.size(), is(2));
    }

    public void testDefaultCacheAdmitsOnFirstSighting() {
        FusedClassCache cache = new FusedClassCache();
        assertThat("the static default admits on first sight", cache.minSightings(), is(1));
        Class<?> stitched = getUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat("first sight stitches, not admitted-and-skipped", stitched, sameInstance(CLASS_1));
        assertThat(cache.classesDefined(), is(1L));
        assertThat(cache.admissionSkips(), is(0L));
        assertThat(cache.size(), is(1));
    }

    /**
     * A direct-mapped doorkeeper slot collision must never refuse a shape whose class is already cached. With a
     * 1-slot doorkeeper every plan shape collides, so once A and B are both cached the pre-fix code — which wrote the
     * colliding slot on every miss and consulted it before the cache — refused each shape forever on alternating
     * sightings. The already-cached short-circuit in {@link FusedClassCache#admit} fixes this: the second half all
     * fuse and {@link FusedClassCache#admissionSkips()} stays at the two first-sighting skips.
     *
     * <p>Fails before fix: the first {@code A} of the second half (slot last written by {@code B}) is refused, so
     * {@code plan(...)} returns {@code false} and {@code admissionSkips} reaches 3 instead of 2.
     */
    public void testDoorkeeperSlotCollisionDoesNotRefuseCachedShape() {
        FusedClassCache cache = new FusedClassCache(
            FusedClassCache.DEFAULT_MAX_WEIGHT_BYTES,
            FusedClassCache.DEFAULT_EXPIRE_AFTER_ACCESS,
            1
        );
        cache.setMinSightings(2);

        String planA = SHAPE_A.signature();
        String planB = SHAPE_B.signature();

        assertThat("A first sighting refused", plan(cache, planA, SHAPE_A, CLASS_1), is(false));
        assertThat("A second sighting admitted (and cached)", plan(cache, planA, SHAPE_A, CLASS_1), is(true));
        assertThat("B first sighting refused", plan(cache, planB, SHAPE_B, CLASS_2), is(false));
        assertThat("B second sighting admitted (and cached)", plan(cache, planB, SHAPE_B, CLASS_2), is(true));
        assertThat("exactly two first-sighting skips so far", cache.admissionSkips(), is(2L));

        assertThat("cached A fuses despite the collision", plan(cache, planA, SHAPE_A, CLASS_1), is(true));
        assertThat("cached B fuses despite the collision", plan(cache, planB, SHAPE_B, CLASS_2), is(true));
        assertThat("cached A fuses again", plan(cache, planA, SHAPE_A, CLASS_1), is(true));
        assertThat("cached B fuses again", plan(cache, planB, SHAPE_B, CLASS_2), is(true));
        assertThat("no further admission skips once both shapes are cached", cache.admissionSkips(), is(2L));
        assertThat("only the two distinct shapes were ever stitched", cache.classesDefined(), is(2L));
    }

    /**
     * A plan whose primary class is already cached must add exactly ONE cache hit, not two. With
     * {@code min_sightings >= 2}, {@link FusedClassCache#admit} consults the already-cached short-circuit
     * ({@code containsShape}, a {@link org.elasticsearch.common.cache.Cache#get} that would count a hit) before the
     * plan's real lookup ({@code getResolved}). The probe's hit is subtracted from the public counters, so a reused
     * cached plan reports a single hit.
     *
     * <p>Fails before fix: the presence peek also counts, so the reused plan records 2 hits instead of 1.
     */
    public void testCachedPlanAddsExactlyOneHit() {
        FusedClassCache cache = new FusedClassCache(
            FusedClassCache.DEFAULT_MAX_WEIGHT_BYTES,
            FusedClassCache.DEFAULT_EXPIRE_AFTER_ACCESS,
            1
        );
        cache.setMinSightings(2); // forces admit() through the doorkeeper AND the already-cached containsShape probe
        String planA = SHAPE_A.signature();
        assertThat("first sighting refused by the doorkeeper", plan(cache, planA, SHAPE_A, CLASS_1), is(false));
        assertThat("second sighting admits and caches", plan(cache, planA, SHAPE_A, CLASS_1), is(true));

        long hitsBefore = cache.hits();
        assertThat("cached plan fuses via the already-cached short-circuit", plan(cache, planA, SHAPE_A, CLASS_1), is(true));
        assertThat("a cached plan adds exactly one hit (probe not counted)", cache.hits() - hitsBefore, is(1L));
    }

    public void testCacheEvictsByWeightCeiling() {
        long maxBytes = 4 * CLASS_BYTES;
        FusedClassCache cache = new FusedClassCache(maxBytes);
        int distinct = 40;
        CachedFused firstUnit = getResolvedUnchecked(cache, new Shape("Add(#,#)#0", ElementKind.LONG, Path.BLOCK), CLASS_1);
        for (int i = 1; i < distinct; i++) {
            getResolvedUnchecked(cache, new Shape("Add(#,#)#" + i, ElementKind.LONG, Path.BLOCK), CLASS_1);
            assertThat("weight must never exceed the ceiling", cache.weightBytes(), is(lessThanOrEqualTo(maxBytes)));
        }
        assertThat("every distinct shape stitched exactly once", cache.classesDefined(), is((long) distinct));
        assertThat("the cache evicted the overflow", cache.evictions(), is(greaterThan(0L)));
        assertThat("retained weight stays under the ceiling", cache.weightBytes(), is(lessThanOrEqualTo(maxBytes)));
        assertThat("far fewer entries than inserted are retained", cache.size(), is(lessThanOrEqualTo((int) (maxBytes / CLASS_BYTES) + 1)));
        assertThat("a pre-eviction unit still carries its class", firstUnit.fusedClass(), sameInstance(CLASS_1));
    }

    public void testExpiredEntryReStitches() throws Exception {
        FusedClassCache cache = new FusedClassCache(FusedClassCache.DEFAULT_MAX_WEIGHT_BYTES, TimeValue.timeValueMillis(20));
        getResolvedUnchecked(cache, SHAPE_A, CLASS_1);
        assertThat("first sight defines the class", cache.classesDefined(), is(1L));
        safeSleep(120);
        Class<?> reStitched = getUnchecked(cache, SHAPE_A, CLASS_2);
        assertThat("an expired entry must re-stitch to the fresh class", reStitched, sameInstance(CLASS_2));
        assertThat("the re-stitch defines a second class", cache.classesDefined(), is(2L));
    }

    public void testFailedStitchIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            invocations.incrementAndGet();
            throw new Stitcher.StitchingException("boom", new VerifyError("injected"));
        };
        Stitcher.StitchingException first = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
        assertThat(first.getMessage(), is("boom"));
        assertThat("the original StitchingException keeps its cause", first.getCause(), instanceOf(VerifyError.class));
        assertThat("the first failure is signalled to the factory", first.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
            assertThat(repeat.getMessage(), is("java.lang.VerifyError: boom"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a failing shape must be attempted exactly once", invocations.get(), is(1));
        assertThat("the negative-cache marker is retained", cache.size(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("two subsequent lookups are negative hits", cache.negativeHits(), is(2L));
        assertThat("a failing stitch defines no class", cache.classesDefined(), is(0L));
    }

    public void testRuntimeExceptionIsNegativeCachedFirstFailureThenRepeat() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            invocations.incrementAndGet();
            throw new IllegalArgumentException("injected emit failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(IllegalArgumentException.class));
        assertThat(thrown.getCause().getMessage(), is("injected emit failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
            assertThat(repeat.getMessage(), is("java.lang.IllegalArgumentException: injected emit failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a RuntimeException stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat("the negative-cache marker is retained", cache.size(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("two subsequent lookups are negative hits", cache.negativeHits(), is(2L));
    }

    public void testLinkageErrorIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            invocations.incrementAndGet();
            throw new LinkageError("injected linkage failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(LinkageError.class));
        assertThat(thrown.getCause().getMessage(), is("injected linkage failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
            assertThat(repeat.getMessage(), is("java.lang.LinkageError: injected linkage failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a LinkageError stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    public void testReflectiveOperationExceptionIsNegativeCachedAndAttemptedOnce() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            invocations.incrementAndGet();
            throw new NoSuchMethodException("injected findStatic failure");
        };
        Stitcher.StitchingException thrown = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
        assertThat("the original failure is preserved as the cause", thrown.getCause(), instanceOf(NoSuchMethodException.class));
        assertThat(thrown.getCause().getMessage(), is("injected findStatic failure"));
        assertThat("the first failure is signalled to the factory", thrown.isFirstFailure(), is(true));
        for (int i = 0; i < 2; i++) {
            Stitcher.StitchingException repeat = expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
            assertThat(repeat.getMessage(), is("java.lang.NoSuchMethodException: injected findStatic failure"));
            assertThat("reconstructed marker exceptions drop the cause", repeat.getCause(), nullValue());
            assertThat("repeats must not re-signal first failure", repeat.isFirstFailure(), is(false));
        }
        assertThat("a ReflectiveOperationException stitch failure must be attempted exactly once", invocations.get(), is(1));
        assertThat(cache.failures(), is(1L));
        assertThat(cache.negativeHits(), is(2L));
    }

    /**
     * {@link OutOfMemoryError} / {@link InternalError} are transient — they must not poison the shape. A second
     * attempt stitches again and can succeed. {@link StackOverflowError} is a {@link VirtualMachineError} too and is
     * handled the same way (see {@link #testStackOverflowErrorIsTransientAndRetries}).
     */
    public void testOutOfMemoryErrorIsNotCachedAndRetries() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                throw new OutOfMemoryError("injected");
            }
            return new CachedFused(CLASS_1, CLASS_BYTES, null, Map.of());
        };
        OutOfMemoryError oom = expectThrows(OutOfMemoryError.class, () -> cache.get(SHAPE_A, loader));
        assertThat("VirtualMachineError must propagate unwrapped, not as StitchingException", oom.getMessage(), is("injected"));
        assertThat("the first failure must not be retained as a negative-cache marker", cache.failures(), is(0L));
        assertThat("the empty slot must be dropped so the retry is a fresh miss", cache.size(), is(0));
        assertThat("the second attempt must stitch again and succeed", getUnchecked(cache, SHAPE_A, loader), sameInstance(CLASS_1));
        assertThat("OOM then success is two stitches", invocations.get(), is(2));
        assertThat(cache.failures(), is(0L));
        assertThat(cache.size(), is(1));
    }

    /**
     * A {@link StackOverflowError} is a {@link VirtualMachineError}, so it is treated as transient (like
     * {@link OutOfMemoryError}): it must not poison the shape, it propagates unwrapped, and a second attempt stitches
     * again and can succeed. Catching {@link StackOverflowError} is forbidden ("track recursion depth manually
     * instead"), so the planner refuses any tree past {@link FusionPlanner#MAX_FUSION_TREE_DEPTH} and a genuine
     * {@link StackOverflowError} never arises from emit recursion in practice.
     */
    public void testStackOverflowErrorIsTransientAndRetries() {
        FusedClassCache cache = new FusedClassCache();
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                throw new StackOverflowError("injected emit recursion");
            }
            return new CachedFused(CLASS_1, CLASS_BYTES, null, Map.of());
        };
        StackOverflowError soe = expectThrows(StackOverflowError.class, () -> cache.get(SHAPE_A, loader));
        assertThat(
            "VirtualMachineError must propagate unwrapped, not as StitchingException",
            soe.getMessage(),
            is("injected emit recursion")
        );
        assertThat("the first failure must not be retained as a negative-cache marker", cache.failures(), is(0L));
        assertThat("the empty slot must be dropped so the retry is a fresh miss", cache.size(), is(0));
        assertThat("the second attempt must stitch again and succeed", getUnchecked(cache, SHAPE_A, loader), sameInstance(CLASS_1));
        assertThat("SOE then success is two stitches", invocations.get(), is(2));
        assertThat(cache.failures(), is(0L));
        assertThat(cache.size(), is(1));
    }

    public void testConcurrentFailingStampedeAttemptsOnce() throws Exception {
        FusedClassCache cache = new FusedClassCache();
        int threads = 16;
        AtomicInteger invocations = new AtomicInteger();
        FusedClassCache.Loader loader = () -> {
            invocations.incrementAndGet();
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
                    return expectThrows(Stitcher.StitchingException.class, () -> cache.get(SHAPE_A, loader));
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

        assertThat("the loader must be invoked exactly once", invocations.get(), is(1));
        assertThat("one first-failure transition", cache.failures(), is(1L));
        assertThat("the rest are negative hits", cache.negativeHits(), is((long) (threads - 1)));
        assertThat(cache.size(), is(1));
    }

    public void testCacheWiredIntoPlanTimePath() {
        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();

        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Layout layout = layout(a, b, c);
        Expression expr = new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);

        EvalMapper.toEvaluator(FoldContext.small(), expr, layout);
        long classesAfterFirstBuild = cache.classesDefined();
        long sizeAfterFirstBuild = cache.size();
        assertThat("the first build must stitch (block + checked-vector paths)", classesAfterFirstBuild, greaterThanOrEqualTo(2L));
        assertThat(sizeAfterFirstBuild, greaterThanOrEqualTo(2L));

        long hitsBeforeSecondBuild = cache.hits();
        FieldAttribute a2 = field("a2", DataType.LONG);
        FieldAttribute b2 = field("b2", DataType.LONG);
        FieldAttribute c2 = field("c2", DataType.LONG);
        Layout layout2 = layout(a2, b2, c2);
        Expression expr2 = new Mul(Source.EMPTY, new Add(Source.EMPTY, a2, b2, EsqlTestUtils.TEST_CFG), c2);
        EvalMapper.toEvaluator(FoldContext.small(), expr2, layout2);

        assertThat("the second identical shape must not define new classes", cache.classesDefined(), is(classesAfterFirstBuild));
        assertThat("the second build must hit the cache for every path", cache.hits(), greaterThanOrEqualTo(hitsBeforeSecondBuild + 2));
    }

    /**
     * Admission doorkeeper end-to-end through the planner: with {@code min_sightings=2} the first sighting of a
     * fusable shape is left unfused (one admission skip, no class defined) and the second sighting fuses. A later
     * cache hit does not re-trip the doorkeeper as a skip.
     */
    public void testDoorkeeperAdmitsOnSecondSighting() {
        FusedClassCache cache = FusionPlanner.classCache();
        cache.resetForTests();
        cache.setMinSightings(2);
        try {
            Expression first = mulAddOverLongs("a", "b", "c");
            var firstFactory = EvalMapper.toEvaluator(FoldContext.small(), first, layout(fieldsOf(first)));
            assertThat(
                "the first sighting must be refused by the doorkeeper (unfused)",
                firstFactory,
                not(instanceOf(FusedExpressionEvaluatorFactory.class))
            );
            assertThat("one admission skip was recorded", cache.admissionSkips(), is(1L));
            assertThat("no class was defined for the refused first sighting", cache.classesDefined(), is(0L));

            Expression second = mulAddOverLongs("a2", "b2", "c2");
            var secondFactory = EvalMapper.toEvaluator(FoldContext.small(), second, layout(fieldsOf(second)));
            assertThat("the second sighting of the same shape must fuse", secondFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
            assertThat("the admitted sighting records no new skip", cache.admissionSkips(), is(1L));
            long classesAfterSecond = cache.classesDefined();
            assertThat("the second sighting defined at least one class", classesAfterSecond, greaterThanOrEqualTo(1L));

            Expression third = mulAddOverLongs("a3", "b3", "c3");
            var thirdFactory = EvalMapper.toEvaluator(FoldContext.small(), third, layout(fieldsOf(third)));
            assertThat("a later sighting (cache hit) still fuses", thirdFactory, instanceOf(FusedExpressionEvaluatorFactory.class));
            assertThat("a cache hit does not look like an admission skip", cache.admissionSkips(), is(1L));
            assertThat("a cache hit defines no new class", cache.classesDefined(), is(classesAfterSecond));
        } finally {
            cache.resetForTests();
            cache.setMinSightings(1);
        }
    }

    /**
     * /: a cache HIT must not call {@code findStatic}. The first plan resolves inside the cached unit; the
     * second plan reuses those handles.
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

    /** {@code bind} must not return a cached handle whose resolved {@link MethodType} differs from the requested one. */
    public void testBindFallsBackToFindStaticOnTypeMismatch() throws Exception {
        MethodType typeA = MethodType.methodType(int.class, int.class);
        MethodType typeB = MethodType.methodType(long.class, long.class);
        MethodHandle cached = FusedClassCache.findStaticCounted(MethodHandles.lookup(), Math.class, "abs", typeA);
        int afterSetup = FusedClassCache.findStaticCallsForTests.get();
        CachedFused unit = new CachedFused(
            Math.class,
            0,
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

    /**
     * One admission-then-stitch cycle, mirroring {@link FusionPlanner}: consult {@link FusedClassCache#admit} on the
     * plan shape and, if admitted, stitch (or hit) the primary {@code shape}'s class. Returns whether the plan was
     * admitted (fused).
     */
    private static boolean plan(FusedClassCache cache, String planShape, Shape shape, Class<?> result) {
        if (cache.admit(planShape, shape) == false) {
            return false;
        }
        getUnchecked(cache, shape, result);
        return true;
    }

    private static Class<?> getUnchecked(FusedClassCache cache, Shape shape, Class<?> result) {
        return getResolvedUnchecked(cache, shape, result).fusedClass();
    }

    private static Class<?> getUnchecked(FusedClassCache cache, Shape shape, FusedClassCache.Loader loader) {
        try {
            return cache.get(shape, loader);
        } catch (Stitcher.StitchingException e) {
            throw new AssertionError("unexpected stitch failure", e);
        }
    }

    private static CachedFused getResolvedUnchecked(FusedClassCache cache, Shape shape, Class<?> result) {
        try {
            return cache.getResolved(
                shape,
                result == null
                    ? () -> { throw new AssertionError("loader must not be invoked for shape [" + shape + "]"); }
                    : () -> new CachedFused(result, CLASS_BYTES, null, Map.of())
            );
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
     * {@link FusionDescriptor#kernelType()} feed {@link FusionPlanner#shapeOf}.
     */
    private static FusionDescriptor descriptor(String method) {
        return new FusionDescriptor(Math.class, method, "(JJ)J", true, false, "java.lang.ArithmeticException", false, false, true, true);
    }

    private Expression mulAddOverLongs(String x, String y, String z) {
        FieldAttribute a = field(x, DataType.LONG);
        FieldAttribute b = field(y, DataType.LONG);
        FieldAttribute c = field(z, DataType.LONG);
        return new Mul(Source.EMPTY, new Add(Source.EMPTY, a, b, EsqlTestUtils.TEST_CFG), c);
    }

    private static FieldAttribute[] fieldsOf(Expression expr) {
        return expr.references().stream().map(r -> (FieldAttribute) r).toArray(FieldAttribute[]::new);
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

    private static final class AddKernel {}

    private static final class SubKernel {}

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
