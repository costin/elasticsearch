/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide cache of stitched hidden {@link Class}es keyed by expression <b>shape</b>, so that repeated identical
 * query shapes reuse a single stitched class instead of paying the {@link Stitcher} cost — and, more importantly, the
 * per-class JVM cost of {@code defineHiddenClass} + verification + a fresh JIT profile — on every plan.
 *
 * <h2>Why esql-proper, not {@code :compute}</h2>
 * The three things a cache entry must compose live here: the shape signature ({@link FusionPlanner#shapeOf}), the
 * caller {@link java.lang.invoke.MethodHandles.Lookup} the {@link Stitcher} defines into
 * ({@link FusedExpressionEvaluatorFactory}'s module lookup), and the {@link FusionPlanner} plan-time walk that decides
 * to stitch at all. The {@code :compute} {@link Stitcher} is a stateless bytecode utility with no notion of a query
 * shape or of the esql-proper module; keeping the cache next to its only caller avoids leaking either concept across
 * the module boundary.
 *
 * <h2>Cache key — {@link Shape}</h2>
 * The key is the <b>structure</b> of the fused subtree. It deliberately <b>excludes column bindings</b> (which columns a
 * leaf reads) and, since iter 30, also <b>excludes literal constant values</b> (which arrive as trailing method
 * arguments rather than baked-in bytecode), keeping only the constant's element-typed slot marker:
 * <ul>
 *   <li><b>op-tree structure + kernel set</b> — {@link FusionPlanner#shapeOf} renders every <em>column</em> leaf as
 *       {@code #} and every kernel as {@code KernelClass.method(...)}, so {@code (a+b)*c} and {@code (x+y)*z} collapse to
 *       one signature;</li>
 *   <li><b>literal constants (value-independent)</b> — a {@link org.elasticsearch.compute.operator.fusion.FusionNode.Constant}
 *       leaf renders as {@code #C:<element>} (its element only, <em>no</em> value). Because the constant is now passed as
 *       a trailing method argument (not pushed as a bytecode constant), {@code (a+b)>5} and {@code (a+b)>6} stitch to the
 *       <em>same</em> class and differ only in the argument supplied at eval time — so they <em>share</em> one shape and
 *       one cached class. Retaining the element marker keeps a {@code long} tree distinct from a {@code double} tree
 *       (their emit paths and parameter types differ);</li>
 *   <li><b>element type</b> — the uniform primitive of the homogeneous tree (already implied by the kernel method names
 *       such as {@code processLongs} vs {@code processDoubles}, but pinned explicitly for clarity). It also keeps a long
 *       tree with constant {@code 5} distinct from a double tree with constant {@code 5.0};</li>
 *   <li><b>{@link Path}</b> — which of the {@link Stitcher}'s three emit shapes this class is (block / plain-vector /
 *       checked-vector). One shape maps to up to three distinct classes, so the path is part of the key.</li>
 * </ul>
 * Excluding <em>column</em> bindings and constant <em>values</em> is what makes the cache useful: a dashboard that runs
 * {@code metric_a + metric_b > threshold} against a thousand different column pairs and threshold values stitches once,
 * not a thousand times — the threshold rides in as a method argument, so all values of one element share a hidden class.
 *
 * <h2>Reference type — {@link SoftReference} default, {@link WeakReference} toggle</h2>
 * Entries hold the {@link Class} through a {@link Reference}. By default a {@link SoftReference} keeps the class alive
 * across queries (the whole point — cross-query reuse) while still letting the GC reclaim it under heap pressure. A
 * {@link WeakReference} toggle ({@link #setReferenceType}) is provided for memory-stressed deployments that would
 * rather drop-and-restitch aggressively than risk holding class metadata. A reclaimed (cleared) reference is detected
 * on the next access and transparently re-stitched.
 *
 * <h2>Single-flight</h2>
 * Concurrent misses for the same shape must spin the {@link Stitcher} exactly once. Identity is provided by
 * {@link ConcurrentHashMap#computeIfAbsent} (one {@link Entry} per live shape); the actual stitch is serialised on that
 * entry's monitor, so a stampede of {@code N} threads produces one {@code SPIN} and {@code N-1} {@code HIT}s, and a
 * cleared reference re-stitches once on the next access.
 *
 * <h2>Negative cache</h2>
 * The wrap covers {@link RuntimeException}, {@link ReflectiveOperationException}, and unwrapped {@link LinkageError}
 * (turned into a {@link Stitcher.StitchingException} by {@link #wrapStitchFailures}) so a failing shape is stored as a
 * lightweight {@link Failure} marker and attempted exactly once per lifetime — bounded by {@link #MAX_ENTRIES} plus
 * eviction, after which it re-attempts once. The thrown {@link Stitcher.StitchingException} carries
 * {@link Stitcher.StitchingException#isFirstFailure()} so the factory — which alone knows whether fusion was lost
 * entirely or only a vector path degraded — can WARN once per shape on full unfused fallback and stay at DEBUG for
 * a degrade-to-block. This cache does not log. Other {@link Error}s, including {@link VirtualMachineError}
 * (e.g. {@link OutOfMemoryError}) and siblings such as {@link AssertionError}, are treated as transient: they
 * propagate unwrapped and {@link #get} drops the empty slot so a later attempt can succeed. {@link OutOfMemoryError}
 * is a {@link VirtualMachineError}, which is an {@link Error} but <em>not</em> a {@link LinkageError} — wrapping
 * {@code LinkageError} specifically (not {@code Error}) is what keeps those transient failures retryable.
 *
 * <h2>No admission filter (D3)</h2>
 * Every miss stitches immediately; there is no threshold-2 "stitch only on the second sighting" admission gate. See the
 * {@code ## Iter 15 Adjustment} note in the implementation plan and {@code iters/15/implementer-summary.md} for the
 * cost/cardinality reasoning behind dropping the {@code ConstantMethodResultSpecializer} precedent here.
 */
final class FusedClassCache {

    /**
     * Lightweight negative-cache payload for a shape whose stitch failed with a {@link Stitcher.StitchingException}.
     * Stored instead of a class so a failing shape is not re-attempted until the entry is evicted.
     */
    private record Failure(String causeClassName, String message) {}

    /**
     * Hard upper bound on distinct shapes retained. The cache is purely <b>opportunistic</b> — it only pays off when a
     * query shape recurs often enough to reuse its stitched class — so it does not need to grow without bound to be
     * useful. When a miss would exceed this cap the cache first drops any entries whose class the GC already reclaimed
     * (free wins) and then, if still over, evicts arbitrary victims (a wrongly-evicted shape simply re-stitches on its
     * next sighting). Deliberately simple: no LRU bookkeeping, no admission filter — just a size ceiling so a
     * pathological (e.g. machine-generated) workload cannot accumulate unbounded hidden-class metadata.
     */
    static final int MAX_ENTRIES = 512;

    /** Whether cached classes are held {@link RefType#SOFT softly} (default) or {@link RefType#WEAK weakly}. */
    enum RefType {
        SOFT,
        WEAK
    }

    /** Which {@link Stitcher} emit shape a cached class is; part of the cache key since one shape yields up to three. */
    enum Path {
        BLOCK,
        /**
         * {@link Stitcher#compileVectorLoop}: one hidden class carries both the ALL-RAW and ALL-INTERFACE methods,
         * so a single cache entry covers both per-page read variants.
         */
        PLAIN_VECTOR,
        /**
         * {@link Stitcher#compileVectorLoopChecked}: one hidden class carries ALL-RAW and ALL-INTERFACE variants of
         * both the builder {@code fused} method and the overflow-abandoning {@code fusedRaw} method.
         */
        CHECKED_VECTOR,
        /** {@link Stitcher#compileLogicalBlockLoop}: a 3VL {@code AND}/{@code OR} tree; nullable boolean, block-only. */
        LOGICAL_BLOCK,
        /**
         * {@link Stitcher#compileLogicalVectorLoop}: the dense 2VL vector sibling of {@link #LOGICAL_BLOCK}. One
         * hidden class carries both the ALL-RAW and ALL-INTERFACE methods, so a single cache entry covers both
         * per-page read variants.
         */
        LOGICAL_VECTOR,
        /**
         * {@link Stitcher#compileFilterEvalBlockLoop}: a fused WHERE predicate + single EVAL projection; nullable,
         * block-only. Its signature encodes BOTH the predicate and projection sub-shapes (see
         * {@link FusionPlanner#filterEvalShapeOf}).
         */
        FILTER_EVAL_BLOCK,
        /** {@link Stitcher#compileVectorLoopSimd}: the opt-in Panama Vector-API variant of the plain-vector path (B2). */
        SIMD_VECTOR,
        /** {@link Stitcher#compileMappingBlockLoop}: a unary convert kernel that maps over multi-values (TO_LOWER/TO_UPPER). */
        MAPPING_BLOCK
    }

    /**
     * The column-binding- and constant-value-independent identity of a stitched class. Two expressions with the same
     * operator tree, kernel set, constant element markers, element type, and emit {@link Path} share a {@link Shape}
     * regardless of which columns they read <em>or</em> which constant values they carry — the values arrive as trailing
     * method arguments (iter 30), so they no longer change the generated class.
     *
     * @param signature the {@link FusionPlanner#shapeOf} op-tree + kernel-set signature: column leaves rendered as
     *                  {@code #}, and literal constants rendered as {@code #C:<element>} (element only, no value) so all
     *                  values of one element share a class
     * @param element   the uniform primitive element kind of the homogeneous tree
     * @param path      which emit shape this class is
     */
    record Shape(String signature, ElementKind element, Path path) {}

    /**
     * The stitch operation deferred behind the cache; invoked at most once per live shape (single-flight).
     * {@link ReflectiveOperationException} is declared so a {@code findStatic} failure inside a stitch lambda can
     * take the same wrap-and-negative-cache path as emit/{@code LinkageError} failures.
     */
    @FunctionalInterface
    interface ClassStitcher {
        Class<?> stitch() throws Stitcher.StitchingException, ReflectiveOperationException;
    }

    /**
     * Converts a stitch-time {@link RuntimeException}, {@link ReflectiveOperationException}, or unwrapped
     * {@link LinkageError} into a {@link Stitcher.StitchingException} (cause preserved) so the failure takes the
     * negative-cache path and the factory's first-failure signal. {@link VirtualMachineError} is rethrown unwrapped
     * (explicit catch, documentation that OOM is not a {@link LinkageError}): a transient OOM must be retried, not
     * remembered as a permanent shape failure. Other {@link Error}s are not caught here and fall through to
     * {@link #get}'s empty-slot drop. Invoked only from {@link Entry#resolve} so every cache-backed stitch (plan-time
     * and adaptive switch) is covered once.
     */
    private static Class<?> wrapStitchFailures(ClassStitcher stitcher) throws Stitcher.StitchingException {
        try {
            return stitcher.stitch();
        } catch (Stitcher.StitchingException e) {
            throw e;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
            throw new Stitcher.StitchingException(e.getMessage(), e);
        }
    }

    /**
     * One cache slot. Holds either the stitched class through a {@link Reference}, or a {@link Failure} negative-cache
     * marker when the stitch failed, both guarded by the entry monitor; the monitor is what serialises concurrent
     * stitches of the same shape into a single spin (and a single {@code firstFailure=true} exception).
     */
    private final class Entry {
        // Volatile because the lock-free fast path in resolve() (and isEmpty()) reads it outside the monitor; all
        // writes happen under 'this'. Null until the first successful stitch; a non-null reference whose referent has
        // been cleared (GC reclaim or an explicit clear) triggers a re-stitch on the next access.
        private volatile Reference<Class<?>> ref;
        // Null until a stitch fails with StitchingException; written under the monitor. A non-null marker is a
        // negative-cache hit: later lookups throw without invoking the stitcher.
        private volatile Failure failure;

        Class<?> resolve(ClassStitcher stitcher) throws Stitcher.StitchingException {
            // Fast path: a live class is returned without taking the monitor, so steady-state hits never contend.
            Reference<Class<?>> current = ref;
            if (current != null) {
                Class<?> cached = current.get();
                if (cached != null) {
                    hits.increment();
                    return cached;
                }
            }
            synchronized (this) {
                current = ref;
                if (current != null) {
                    Class<?> cached = current.get();
                    if (cached != null) {
                        // A concurrent stitcher populated the entry while we waited for the monitor.
                        hits.increment();
                        return cached;
                    }
                }
                if (failure != null) {
                    negativeHits.increment();
                    // Reconstruct with firstFailure=false so the factory DEBUGs a repeat instead of WARNing again.
                    throw new Stitcher.StitchingException(failure.message(), null, false);
                }
                if (current != null) {
                    // The reference existed but its referent was reclaimed/cleared: re-stitch this generation.
                    softRefCleared.increment();
                } else {
                    misses.increment();
                }
                spins.increment();
                try {
                    Class<?> stitched = wrapStitchFailures(stitcher);
                    ref = wrap(stitched);
                    return stitched;
                } catch (Stitcher.StitchingException e) {
                    this.failure = new Failure(e.getCause() == null ? null : e.getCause().getClass().getName(), e.getMessage());
                    failures.increment();
                    // Do not log here: the factory decides WARN vs DEBUG from e.isFirstFailure() and the consequence
                    // (full unfused fallback vs degrade-to-block). wrapStitchFailures already produced a
                    // firstFailure=true StitchingException.
                    throw e;
                }
            }
        }

        boolean isEmpty() {
            // Lock-free: reads the volatile failure marker and ref (and the Reference's own referent), all safe
            // without the monitor. A Failure marker is a retained negative-cache entry, so it is non-empty: the
            // failure-cleanup guard and the "free wins" eviction pass keep it, while the arbitrary-victim pass can
            // still drop it when over capacity.
            return failure == null && (ref == null || ref.get() == null);
        }

        synchronized void clearReferent() {
            if (ref != null) {
                ref.clear();
            }
        }
    }

    private final ConcurrentHashMap<Shape, Entry> classes = new ConcurrentHashMap<>();

    // Reference strategy for newly wrapped classes; reads/writes are rare (plan-time only) so volatile is enough.
    private volatile RefType refType;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder spins = new LongAdder();
    private final LongAdder softRefCleared = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder negativeHits = new LongAdder();

    /**
     * Test-only: when non-null, {@link #get} substitutes a failing stitch lambda for this {@link Path} so the real
     * cache still single-flights and negatively caches the failure. WHY this lives on the cache rather than
     * {@code FusionPlanner.compilerForTests}: that seam bypasses this cache entirely, so a vector-path-only degrade
     * would not move {@link #failures()} / {@link #negativeHits()}. A natural production over-budget shape no longer
     * fails only the vector path (plain/checked trees split instead of refusing; a deep logical tree refuses the
     * <em>block</em> path and loses fusion). Never set in production; {@link #resetForTests} clears it.
     */
    volatile Path injectFailureForPathForTests;

    FusedClassCache() {
        this(RefType.SOFT);
    }

    FusedClassCache(RefType refType) {
        this.refType = refType;
    }

    /**
     * Returns the cached class for {@code shape}, stitching it (exactly once across concurrent callers) via
     * {@code stitcher} on a miss or after a cleared reference. A {@link Stitcher.StitchingException} (including a
     * {@link RuntimeException} / {@link ReflectiveOperationException} / {@link LinkageError} wrapped by
     * {@link #wrapStitchFailures}) is negatively cached as a lightweight {@link Failure} marker so a failing shape is
     * attempted exactly once per lifetime (bounded by {@link #MAX_ENTRIES} + eviction, after which it re-attempts
     * once). The thrown exception's {@link Stitcher.StitchingException#isFirstFailure()} is {@code true} on that first
     * attempt and {@code false} on later negative-cache hits, so the factory can WARN once per shape when fusion is
     * lost. Other {@link Error}s (including {@link VirtualMachineError}) are treated as transient: they propagate and
     * the empty slot is dropped so a later attempt can succeed.
     */
    Class<?> get(Shape shape, ClassStitcher stitcher) throws Stitcher.StitchingException {
        // Test hook: replace the caller-supplied stitch lambda for one Path so a vector-path-only failure still
        // goes through this cache (single-flight + negative marker + failures()/negativeHits()). Production never
        // sets injectFailureForPathForTests; the extra volatile read is plan-time only.
        Path injected = injectFailureForPathForTests;
        ClassStitcher toRun = stitcher;
        if (injected != null && injected == shape.path()) {
            toRun = () -> { throw new Stitcher.StitchingException("injected stitch failure for path [" + injected + "]", null); };
        }
        Entry entry = classes.computeIfAbsent(shape, unused -> new Entry());
        try {
            Class<?> stitched = entry.resolve(toRun);
            evictIfOverCapacity(shape);
            return stitched;
        } catch (Stitcher.StitchingException e) {
            // The entry now holds the Failure marker, so keep it. Still trim so a flood of failing shapes cannot grow
            // the cache without bound (an evicted failing shape re-attempts once later).
            evictIfOverCapacity(shape);
            throw e;
        } catch (RuntimeException | Error e) {
            // Don't leave a poisoned empty slot behind: if the stitch failed with a transient unchecked throwable and
            // nothing else populated the entry, drop it so the next attempt starts clean (counted as a fresh miss, not
            // a cleared ref). The check-and-remove is done atomically inside computeIfPresent so a concurrent
            // single-flight success — which populates this same entry object — is never evicted: we remove only if the
            // mapping is still this exact, still-empty entry.
            classes.computeIfPresent(shape, (unused, current) -> (current == entry && current.isEmpty()) ? null : current);
            throw e;
        }
    }

    /**
     * Keeps the shape count at or below {@link #MAX_ENTRIES} after a miss. First removes entries whose class the GC
     * already reclaimed (cleared reference — free to drop); if still over, evicts arbitrary victims (this is an
     * opportunistic cache, so a dropped shape merely re-stitches on its next sighting). Never evicts {@code keep} (the
     * shape just resolved). {@link ConcurrentHashMap}'s iterator is weakly consistent, so concurrent removal is safe;
     * a benign race may leave the map transiently a few entries over the cap, which the next miss trims.
     */
    private void evictIfOverCapacity(Shape keep) {
        if (classes.size() <= MAX_ENTRIES) {
            return;
        }
        for (var it = classes.entrySet().iterator(); it.hasNext() && classes.size() > MAX_ENTRIES;) {
            var mapping = it.next();
            if (mapping.getKey().equals(keep) == false && mapping.getValue().isEmpty()) {
                it.remove();
            }
        }
        for (var it = classes.entrySet().iterator(); it.hasNext() && classes.size() > MAX_ENTRIES;) {
            var mapping = it.next();
            if (mapping.getKey().equals(keep) == false) {
                it.remove();
            }
        }
    }

    private Reference<Class<?>> wrap(Class<?> stitched) {
        return refType == RefType.WEAK ? new WeakReference<>(stitched) : new SoftReference<>(stitched);
    }

    /** Switches the reference strategy for classes wrapped after this call; existing entries keep their current type. */
    void setReferenceType(RefType type) {
        this.refType = type;
    }

    RefType referenceType() {
        return refType;
    }

    long hits() {
        return hits.sum();
    }

    long misses() {
        return misses.sum();
    }

    long spins() {
        return spins.sum();
    }

    long softRefClearedCount() {
        return softRefCleared.sum();
    }

    long failures() {
        return failures.sum();
    }

    long negativeHits() {
        return negativeHits.sum();
    }

    /** Number of shapes currently tracked (live or awaiting re-stitch). */
    int size() {
        return classes.size();
    }

    /**
     * Test-only: clears the {@link Reference} referent for {@code shape} without relying on GC timing, so a test can
     * deterministically prove the cleared-reference re-stitch path. No-op if the shape is not present.
     */
    void clearReferentForTests(Shape shape) {
        Entry entry = classes.get(shape);
        if (entry != null) {
            entry.clearReferent();
        }
    }

    /** Test-only: drops all entries, zeroes the counters, and clears {@link #injectFailureForPathForTests}. */
    void resetForTests() {
        classes.clear();
        hits.reset();
        misses.reset();
        spins.reset();
        softRefCleared.reset();
        failures.reset();
        negativeHits.reset();
        injectFailureForPathForTests = null;
    }
}
