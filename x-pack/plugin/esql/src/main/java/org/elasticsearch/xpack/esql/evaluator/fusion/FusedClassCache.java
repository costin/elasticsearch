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
 * The key is the <b>structure</b> of the fused subtree, deliberately <b>excluding value bindings and column
 * identities</b>:
 * <ul>
 *   <li><b>op-tree structure + kernel set</b> — {@link FusionPlanner#shapeOf} renders every leaf as {@code #} and every
 *       kernel as {@code KernelClass.method(...)}, so {@code (a+b)*c} and {@code (x+y)*z} collapse to one signature;</li>
 *   <li><b>element type</b> — the uniform primitive of the homogeneous tree (already implied by the kernel method names
 *       such as {@code processLongs} vs {@code processDoubles}, but pinned explicitly for clarity);</li>
 *   <li><b>{@link Path}</b> — which of the {@link Stitcher}'s three emit shapes this class is (block / plain-vector /
 *       checked-vector). One shape maps to up to three distinct classes, so the path is part of the key.</li>
 * </ul>
 * Excluding bindings is what makes the cache useful: a dashboard that runs {@code metric_a + metric_b} against a
 * thousand different column pairs stitches once, not a thousand times.
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
 * <h2>No admission filter (D3)</h2>
 * Every miss stitches immediately; there is no threshold-2 "stitch only on the second sighting" admission gate. See the
 * {@code ## Iter 15 Adjustment} note in the implementation plan and {@code iters/15/implementer-summary.md} for the
 * cost/cardinality reasoning behind dropping the {@code ConstantMethodResultSpecializer} precedent here.
 */
final class FusedClassCache {

    /** Whether cached classes are held {@link RefType#SOFT softly} (default) or {@link RefType#WEAK weakly}. */
    enum RefType {
        SOFT,
        WEAK
    }

    /** Which {@link Stitcher} emit shape a cached class is; part of the cache key since one shape yields up to three. */
    enum Path {
        BLOCK,
        PLAIN_VECTOR,
        CHECKED_VECTOR
    }

    /**
     * The binding-independent identity of a stitched class. Two expressions with the same operator tree, kernel set,
     * element type, and emit {@link Path} share a {@link Shape} regardless of which columns they read.
     *
     * @param signature the {@link FusionPlanner#shapeOf} op-tree + kernel-set signature (leaves rendered as {@code #})
     * @param element   the uniform primitive element kind of the homogeneous tree
     * @param path      which emit shape this class is
     */
    record Shape(String signature, ElementKind element, Path path) {}

    /** The stitch operation deferred behind the cache; invoked at most once per live shape (single-flight). */
    @FunctionalInterface
    interface ClassStitcher {
        Class<?> stitch() throws Stitcher.StitchingException;
    }

    /**
     * One cache slot. Holds the stitched class through a {@link Reference} guarded by the entry monitor; the monitor is
     * what serialises concurrent stitches of the same shape into a single spin.
     */
    private final class Entry {
        // Volatile because the lock-free fast path in resolve() (and isEmpty()) reads it outside the monitor; all
        // writes happen under 'this'. Null until the first successful stitch; a non-null reference whose referent has
        // been cleared (GC reclaim or an explicit clear) triggers a re-stitch on the next access.
        private volatile Reference<Class<?>> ref;

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
                    // The reference existed but its referent was reclaimed/cleared: re-stitch this generation.
                    softRefCleared.increment();
                } else {
                    misses.increment();
                }
                spins.increment();
                Class<?> stitched = stitcher.stitch();
                ref = wrap(stitched);
                return stitched;
            }
        }

        boolean isEmpty() {
            // Lock-free: reads the volatile ref and the Reference's own referent, both safe without the monitor. Used
            // by the atomic failure-cleanup guard in get(), which must not take the entry monitor while holding a map
            // bin lock.
            Reference<Class<?>> current = ref;
            return current == null || current.get() == null;
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

    FusedClassCache() {
        this(RefType.SOFT);
    }

    FusedClassCache(RefType refType) {
        this.refType = refType;
    }

    /**
     * Returns the cached class for {@code shape}, stitching it (exactly once across concurrent callers) via
     * {@code stitcher} on a miss or after a cleared reference. A stitch failure is propagated to the caller (which
     * falls back to the unfused chain) and never cached, so a transient failure does not poison the shape.
     */
    Class<?> get(Shape shape, ClassStitcher stitcher) throws Stitcher.StitchingException {
        Entry entry = classes.computeIfAbsent(shape, unused -> new Entry());
        try {
            return entry.resolve(stitcher);
        } catch (Stitcher.StitchingException | RuntimeException | Error e) {
            // Don't leave a poisoned empty slot behind: if the stitch failed and nothing else populated the entry, drop
            // it so the next attempt for this shape starts clean (counted as a fresh miss, not a cleared ref). The
            // check-and-remove is done atomically inside computeIfPresent so a concurrent single-flight success — which
            // populates this same entry object — is never evicted: we remove only if the mapping is still this exact,
            // still-empty entry.
            classes.computeIfPresent(shape, (unused, current) -> (current == entry && current.isEmpty()) ? null : current);
            throw e;
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

    /** Test-only: drops all entries and zeroes the counters so an assertion can observe behaviour in isolation. */
    void resetForTests() {
        classes.clear();
        hits.reset();
        misses.reset();
        spins.reset();
        softRefCleared.reset();
    }
}
