/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.cache.RemovalNotification;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * leaf reads) and also <b>excludes non-baked literal constant values</b> (which arrive as trailing method
 * arguments rather than baked-in bytecode), keeping only the constant's element-typed slot marker. A <b>baked</b>
 * constant (a Div/Mod divisor pushed as a bytecode immediate) <em>does</em> participate in the key. See
 * {@link Shape} for the full key composition.
 *
 * <h2>Storage — a weight-bounded {@link Cache}</h2>
 * Entries are held <b>strongly</b> in an {@link org.elasticsearch.common.cache.Cache}, not through a
 * {@link java.lang.ref.SoftReference}. A stitched hidden class is costly to materialise and its retained metaspace
 * footprint is proportional to its class-file byte length, so the cache is bounded by a <b>byte weight ceiling</b>
 * ({@code esql.fusion.cache.max_bytes}) rather than an entry count: the {@linkplain #buildCache weigher} charges each
 * {@link CachedFused} its {@link CachedFused#classBytes() class-file byte length} (carried from the {@link Stitcher})
 * and each negative-cache {@link Failure} a small fixed {@link #FAILURE_WEIGHT}. When the total weight exceeds the
 * ceiling the {@link Cache} evicts least-recently-used entries; a {@link #evictions eviction counter} is maintained by
 * a removal listener that fires only on {@link RemovalNotification.RemovalReason#EVICTED} (so an {@link #resetForTests}
 * {@code invalidateAll} does not inflate it). Entries also expire a fixed {@link #DEFAULT_EXPIRE_AFTER_ACCESS} after
 * their last access, so a shape that stops recurring is eventually dropped even below the weight ceiling. A factory
 * that already holds a {@link CachedFused} keeps it alive independently of the cache — eviction never pulls a class
 * out from under a live query; the query simply re-stitches on its next plan if the shape was evicted meanwhile.
 *
 * <h2>Single-flight</h2>
 * Concurrent misses for the same shape spin the {@link Stitcher} exactly once: {@link Cache#computeIfAbsent} runs the
 * {@link Loader} on the one thread that wins the put-if-absent race, and every other thread blocks on and observes
 * that single result. The {@link Cache} does <b>not</b> cache loader exceptions — a thrown exception removes the key
 * and is re-attempted — so a stitch <em>failure</em> must be turned into a cached {@link Failure} <em>value</em>
 * (see below) to stay single-flighted; only genuinely transient failures (see {@link #load}) are allowed to escape
 * uncached.
 *
 * <h2>Negative cache</h2>
 * {@link #load} catches {@link Stitcher.StitchingException} (and the {@link RuntimeException},
 * {@link ReflectiveOperationException}, and unwrapped {@link LinkageError} it maps to one)
 * <em>inside</em> the loader and returns a lightweight {@link Failure} marker as the cached value, so a failing shape
 * is attempted exactly once per lifetime (until evicted). The thrown {@link Stitcher.StitchingException} carries
 * {@link Stitcher.StitchingException#isFirstFailure()}{@code ==true} for the thread that produced the failure and
 * {@code false} for later negative-cache hits, so the factory — which alone knows whether fusion was lost entirely or
 * only a vector path degraded — can WARN once per shape and stay at DEBUG afterwards. This cache does not log.
 * Every {@link VirtualMachineError} — {@link OutOfMemoryError}, {@link InternalError} and {@link StackOverflowError} —
 * and other {@link Error}s such as {@link AssertionError} are treated as <b>transient</b>: {@link #load} rethrows them
 * wrapped in a {@link TransientStitchError} so the {@link Cache} drops the key (uncached) and a later attempt can
 * succeed. {@link StackOverflowError} is not special-cased: the planner refuses any tree past
 * {@link FusionPlanner#MAX_FUSION_TREE_DEPTH}, so the emit walk never recurses far enough to overflow the stack and a
 * genuine {@link StackOverflowError} reaching here is an unexpected, transient condition like any other
 * {@link VirtualMachineError}.
 *
 * <h2>Admission (doorkeeper)</h2>
 * {@link #admit} is a direct-mapped doorkeeper that lets {@link FusionPlanner} stitch a <b>new</b> shape only on its
 * <b>second</b> sighting, so a one-off (e.g. machine-generated) query shape never pays the stitch. A shape whose
 * primary class is <b>already cached</b> short-circuits the doorkeeper entirely (see {@link #admit}), so a direct-map
 * slot collision can never refuse a shape that has already been fused. The static initial {@link #minSightings} is
 * {@code 1} (admission off — fuse on first sight), so plugin-less unit tests and benchmarks fuse immediately;
 * {@link FusionSettings#initFromNodeSettings} raises it to the production default
 * ({@code esql.fusion.cache.min_sightings}, default {@link #DEFAULT_MIN_SIGHTINGS}). The table is a single
 * {@code long[}{@value #DEFAULT_DOORKEEPER_SLOTS}{@code ]} of 64-bit plan-shape hashes (the size is overridable for
 * tests); it tracks a single prior sighting, so any {@code min_sightings >= 2} admits on the second sighting (finer
 * thresholds are intentionally not represented).
 */
final class FusedClassCache {

    /** Default byte-weight ceiling: roughly 512 classes at 1.2–4.3 KB each. Overridden by {@code esql.fusion.cache.max_bytes}. */
    static final long DEFAULT_MAX_WEIGHT_BYTES = 2L * 1024 * 1024;

    /** How long an unused shape survives before it is dropped even below the weight ceiling. */
    static final TimeValue DEFAULT_EXPIRE_AFTER_ACCESS = TimeValue.timeValueHours(1);

    /** Nominal weight of a negative-cache {@link Failure} marker — tiny next to a stitched class, but non-zero so markers still age out. */
    private static final long FAILURE_WEIGHT = 64;

    /** Weight charged when a {@link CachedFused}'s class size is unknown (test-only stitch paths that do not carry the byte length). */
    private static final int UNKNOWN_CLASS_BYTES = 0;

    /** Production default for {@code esql.fusion.cache.min_sightings}; the static {@link #minSightings} starts at 1 (admission off). */
    static final int DEFAULT_MIN_SIGHTINGS = 2;

    /** Default doorkeeper table size; a power of two so the index is {@code hash & (slots - 1)}. */
    private static final int DEFAULT_DOORKEEPER_SLOTS = 4096;

    /**
     * Which {@link Stitcher} emit shape a cached class is; part of the cache key since one shape yields up to three.
     * A {@link Output#POSITIONS} class is keyed under the path of its value-output sibling: {@link #BLOCK} /
     * {@link #LOGICAL_BLOCK} for {@link Stitcher#compileBlockPositionsLoop}, and {@link #PLAIN_VECTOR} /
     * {@link #CHECKED_VECTOR} / {@link #LOGICAL_VECTOR} for {@link Stitcher#compileVectorPositionsLoop}.
     */
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
        /** {@link Stitcher#compileLogicalBlockLoop}: a 3VL {@code AND}/{@code OR}/{@code NOT} tree; nullable boolean, block-only. */
        LOGICAL_BLOCK,
        /**
         * {@link Stitcher#compileLogicalVectorLoop}: the dense 2VL vector sibling of {@link #LOGICAL_BLOCK}.
         * INTERFACE-only ({@code fusedInterface}; no RAW sibling).
         */
        LOGICAL_VECTOR
    }

    /**
     * What a cached class's fused methods produce. Part of the cache key because one boolean predicate compiles to a
     * different class for each: the same {@code a + b > c} in an EVAL and in a WHERE must not share a class.
     */
    enum Output {
        /** The expression's result {@code Block} (or {@code Vector}): every value-output emit path. */
        VALUES,
        /**
         * The positions where a WHERE predicate is {@code true}, written into an {@code int[]} with their count returned
         * ({@link Stitcher#compileBlockPositionsLoop} / {@link Stitcher#compileVectorPositionsLoop}).
         */
        POSITIONS
    }

    /**
     * The column-binding- and (non-baked) constant-value-independent identity of a stitched class. Two expressions
     * with the same operator tree, kernel set, constant element markers, element type, emit {@link Path} and
     * {@link Output} share a {@link Shape} regardless of which columns they read <em>or</em> which non-baked constant
     * values they carry — the values arrive as trailing method arguments. A baked Div/Mod divisor does participate in
     * the key.
     *
     * @param signature the {@link FusionPlanner#shapeOf} op-tree + kernel-set signature: column leaves rendered as
     *                  {@code #}, non-baked literal constants as {@code #C:<element>} (element only, no value), and a
     *                  baked divisor as {@code #C:<element>:<value>}
     * @param element   the uniform primitive element kind of the homogeneous tree
     * @param path      which emit shape this class is
     * @param output    what the class's fused methods produce
     */
    record Shape(String signature, ElementKind element, Path path, Output output) {
        /** A {@link Output#VALUES} key, the output of every path that is not a WHERE predicate's positions. */
        Shape(String signature, ElementKind element, Path path) {
            this(signature, element, path, Output.VALUES);
        }
    }

    /**
     * A value stored in the {@link Cache}: either a successfully stitched {@link CachedFused} unit or a negative-cache
     * {@link Failure} marker. Sealed so {@link #buildCache}'s weigher can pattern-match the two with no catch-all.
     */
    sealed interface CacheValue permits CachedFused, Failure {}

    /**
     * One resolved fused method: the {@code findStatic} handle and its {@code asSpreader(Object[].class, arity)}
     * form. Stored on the cached unit so a cache HIT pays neither call.
     */
    record ResolvedMethod(MethodHandle handle, MethodHandle spreader) {}

    /**
     * The cached unit for one shape: the stitched class, its class-file byte length (the cache weight), and every
     * method resolved inside {@link #stitchAndResolveSized}. {@code classBytes} is a cheap, representative proxy for
     * the class's retained metaspace footprint — the cache weighs entries by it so the byte ceiling bounds retained
     * hidden-class metadata rather than a bare entry count. {@code type} is the {@link MethodType} the methods were
     * resolved against (one per unit), or {@code null} when none were resolved (2-arg compile paths / test units). The
     * factory's {@code bind} type-check compares a requested signature against this field and falls back to
     * {@code findStatic} on mismatch, so a future shape/type drift fails at plan time rather than as
     * {@code WrongMethodTypeException} at eval.
     */
    record CachedFused(Class<?> fusedClass, int classBytes, MethodType type, Map<String, ResolvedMethod> resolved) implements CacheValue {}

    /**
     * Lightweight negative-cache payload for a shape whose stitch failed with a {@link Stitcher.StitchingException}.
     * Stored instead of a class so a failing shape is not re-attempted until the entry is evicted. Repeats reconstruct
     * the exception with a null cause, so {@link #reconstructedMessage()} bakes {@code causeClassName} into the
     * message (an NPE or {@link LinkageError} with a null message would otherwise leave nothing to diagnose).
     */
    record Failure(String causeClassName, String message) implements CacheValue {
        String reconstructedMessage() {
            return causeClassName == null ? message : causeClassName + ": " + message;
        }
    }

    /**
     * Statistics snapshot for diagnostics/telemetry. {@code hits}/{@code misses} come from {@link Cache#stats()};
     * {@code evictions} from the {@link RemovalNotification.RemovalReason#EVICTED}-only removal listener; the rest
     * from {@link LongAdder}s. Immutable; read at most once per stats request, never on the per-row path.
     */
    record FusionCacheStats(
        long hits,
        long misses,
        long evictions,
        long classesDefined,
        long admissionSkips,
        long stitchNanos,
        int count,
        long weightBytes
    ) {}

    /**
     * The stitch-and-resolve operation deferred behind the cache; invoked at most once per live shape (single-flight).
     * {@link ReflectiveOperationException} is declared so a {@code findStatic} failure inside a stitch lambda
     * (see {@link #stitchAndResolveSized}) takes the same negative-cache path as emit/{@code LinkageError} failures.
     */
    @FunctionalInterface
    interface Loader {
        CachedFused load() throws Stitcher.StitchingException, ReflectiveOperationException;
    }

    /**
     * A {@link Class}-returning stitch, for the test-only {@link #stitchAndResolve} convenience overload (production
     * carries class-file byte lengths via {@link SizedStitch}). The returned class is weighed as
     * {@link #UNKNOWN_CLASS_BYTES}.
     */
    @FunctionalInterface
    interface ClassStitcher {
        Class<?> stitch() throws Stitcher.StitchingException, ReflectiveOperationException;
    }

    /** A {@link Stitcher.Fused}-returning stitch — the production path, carrying the class-file byte length for the weigher. */
    @FunctionalInterface
    interface SizedStitch {
        Stitcher.Fused stitch() throws Stitcher.StitchingException;
    }

    /**
     * Unchecked wrapper for a <b>transient</b> stitch failure ({@link OutOfMemoryError}, {@link InternalError}, other
     * non-{@link StackOverflowError} {@link Error}s). It is a {@link RuntimeException} so {@link Cache#computeIfAbsent}
     * catches it, removes the key (uncached), and rethrows it as an {@link ExecutionException}; {@link #getResolved}
     * then unwraps and rethrows the original {@link Error}/{@link RuntimeException} so the shape is retried later
     * rather than poisoned. An {@link Error} thrown directly by the loader would instead escape {@code computeIfAbsent}
     * <em>without</em> cleaning up the in-flight future, stranding every later caller of that shape on it.
     */
    private static final class TransientStitchError extends RuntimeException {
        TransientStitchError(Throwable cause) {
            super(cause);
        }
    }

    private final long maxWeightBytes;
    private final TimeValue expireAfterAccess;

    // Rebuilt (never mutated in place) by resetForTests so test counter assertions start from zero; volatile because
    // resetForTests swaps the reference while plan-time threads may read it. Production never rebuilds after startup.
    private volatile Cache<Shape, CacheValue> cache;

    private final LongAdder classesDefined = new LongAdder();
    private final LongAdder stitchNanos = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder negativeHits = new LongAdder();
    private final LongAdder admissionSkips = new LongAdder();

    /**
     * The hit/miss effect of the {@link #admit} presence peek on {@link Cache#stats()}, tracked so the reported
     * {@link #hits()}/{@link #misses()}/{@link #stats()} can subtract it. {@link #containsShape} must probe through
     * {@link Cache#get} (so a shape about to be reused is promoted in LRU recency), but that probe is not a plan's
     * authentic lookup — the plan's {@link #getResolved} does the real, counted lookup. Without this adjustment a
     * single cached plan records two hits (the probe plus the real lookup); subtracting the probe makes the public
     * counters report exactly one lookup per plan.
     */
    private final LongAdder doorkeeperProbeHits = new LongAdder();
    private final LongAdder doorkeeperProbeMisses = new LongAdder();

    /**
     * Test-only: every fused {@code findStatic} (cache miss resolution and factory fallback) increments this.
     * A cache HIT must leave it unchanged — the resolved handles live on the cached unit.
     */
    static final AtomicInteger findStaticCallsForTests = new AtomicInteger();

    /**
     * Direct-mapped doorkeeper of 64-bit plan-shape hashes (see {@link #admit}). Benign data races on a slot are
     * tolerated: the worst case is one extra admission skip or one early admit, both harmless for an opportunistic
     * cache, so the plan-time path needs no lock. The size is {@link #DEFAULT_DOORKEEPER_SLOTS} in production and
     * overridable (a power of two) for tests; {@link #doorkeeperMask} is {@code size - 1}.
     */
    private final long[] doorkeeper;
    private final int doorkeeperMask;

    /**
     * Minimum sightings before a shape is admitted for stitching. Static initial value {@code 1} (admission off, fuse
     * on first sight) so plugin-less unit tests and benchmarks fuse immediately; {@link FusionSettings#initFromNodeSettings}
     * raises it to the node setting (default {@link #DEFAULT_MIN_SIGHTINGS}). Plan-time only, so a plain volatile is enough.
     */
    private volatile int minSightings = 1;

    /**
     * Test-only: when non-null, {@link #getResolved} substitutes a failing stitch lambda for this {@link Path} so the
     * real cache still single-flights and negatively caches the failure. Never set in production; {@link #resetForTests}
     * clears it.
     */
    volatile Path injectFailureForPathForTests;

    FusedClassCache() {
        this(DEFAULT_MAX_WEIGHT_BYTES, DEFAULT_EXPIRE_AFTER_ACCESS);
    }

    FusedClassCache(long maxWeightBytes) {
        this(maxWeightBytes, DEFAULT_EXPIRE_AFTER_ACCESS);
    }

    FusedClassCache(long maxWeightBytes, TimeValue expireAfterAccess) {
        this(maxWeightBytes, expireAfterAccess, DEFAULT_DOORKEEPER_SLOTS);
    }

    /**
     * Test-only full constructor: {@code doorkeeperSlots} must be a power of two. A 1-slot table forces every plan
     * shape to collide in the doorkeeper, so a test can prove the already-cached short-circuit admits a previously
     * fused shape despite the collision.
     */
    FusedClassCache(long maxWeightBytes, TimeValue expireAfterAccess, int doorkeeperSlots) {
        assert Integer.bitCount(doorkeeperSlots) == 1 : "doorkeeper slots must be a power of two, got " + doorkeeperSlots;
        this.maxWeightBytes = maxWeightBytes;
        this.expireAfterAccess = expireAfterAccess;
        this.doorkeeper = new long[doorkeeperSlots];
        this.doorkeeperMask = doorkeeperSlots - 1;
        this.cache = buildCache();
    }

    private Cache<Shape, CacheValue> buildCache() {
        return CacheBuilder.<Shape, CacheValue>builder()
            .setMaximumWeight(maxWeightBytes)
            .weigher((shape, value) -> value instanceof CachedFused cf ? Math.max(1, cf.classBytes()) : FAILURE_WEIGHT)
            .setExpireAfterAccess(expireAfterAccess)
            .removalListener(notification -> {
                if (notification.getRemovalReason() == RemovalNotification.RemovalReason.EVICTED) {
                    evictions.increment();
                }
            })
            .build();
    }

    /**
     * The admission decision for a plan keyed on its plan-level {@code planShape} string (not a per-{@link Path}
     * {@link Shape}) plus the {@code primaryShape} — the always-compiled block (or logical-block) class, in the plan's
     * {@link Output}, for that plan: returns {@code true} to stitch, {@code false} to leave this plan unfused.
     *
     * <p><b>Already cached short-circuits the doorkeeper.</b> When {@code primaryShape}'s class is already retained,
     * the plan has been fused before, so it always fuses again without touching the doorkeeper or
     * {@link #admissionSkips}. This is essential, not merely an optimisation: the doorkeeper is a direct-mapped table,
     * so two recurring shapes that hash to the same slot overwrite each other; without this check each would refuse
     * the other forever even though both classes are cached. Only a <b>cache miss</b> consults the doorkeeper.
     *
     * <p>On a miss, with {@link #minSightings} {@code <= 1} every sighting is admitted; otherwise a shape is admitted
     * only on its second (or later) sighting, and a refused first sighting increments {@link #admissionSkips}. Keyed
     * on the plan shape so one plan — which may stitch several per-{@link Path} classes — consumes a single doorkeeper
     * decision.
     */
    boolean admit(String planShape, Shape primaryShape) {
        if (minSightings <= 1 || containsShape(primaryShape)) {
            return true;
        }
        long hash = hash64(planShape);
        int idx = (int) (hash & doorkeeperMask);
        if (doorkeeper[idx] == hash) {
            return true;
        }
        doorkeeper[idx] = hash;
        admissionSkips.increment();
        return false;
    }

    /**
     * Presence peek for the {@link #admit} already-cached short-circuit: {@code true} when {@code shape}'s stitched
     * class (or negative-cache marker) is already retained. Uses {@link Cache#get} — which also promotes LRU recency
     * for a shape about to be reused — so the probe would otherwise count in {@link Cache#stats()}. The probe is not a
     * plan's authentic lookup, so its hit/miss is recorded in {@link #doorkeeperProbeHits}/{@link #doorkeeperProbeMisses}
     * and subtracted by {@link #hits()}/{@link #misses()}/{@link #stats()}; the plan's real lookup is the subsequent
     * {@link #getResolved}. Admission runs only when {@code min_sightings >= 2}, so plugin-less unit tests and
     * benchmarks never reach it.
     */
    private boolean containsShape(Shape shape) {
        boolean present = cache.get(shape) != null;
        (present ? doorkeeperProbeHits : doorkeeperProbeMisses).increment();
        return present;
    }

    /** 64-bit string hash for the doorkeeper; reserves {@code 0} for the empty-slot sentinel. */
    private static long hash64(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h == 0 ? 1 : h;
    }

    /**
     * Returns the cached class for {@code shape}. Thin wrapper over {@link #getResolved}; callers that only need the
     * {@link Class} keep compiling.
     */
    Class<?> get(Shape shape, Loader loader) throws Stitcher.StitchingException {
        return getResolved(shape, loader).fusedClass();
    }

    /**
     * Returns the cached unit for {@code shape}, stitching it (exactly once across concurrent callers) via
     * {@code loader} on a miss. A {@link Stitcher.StitchingException} (including a wrapped {@link RuntimeException} /
     * {@link ReflectiveOperationException} / {@link LinkageError} / {@link StackOverflowError}) is negatively cached as
     * a lightweight {@link Failure} value and re-thrown; its {@link Stitcher.StitchingException#isFirstFailure()} is
     * {@code true} for the thread that produced it and {@code false} for later negative-cache hits. Transient
     * {@link Error}s ({@link OutOfMemoryError}, {@link InternalError}, {@link AssertionError}, ...) propagate and leave
     * no entry, so a later attempt can succeed.
     */
    CachedFused getResolved(Shape shape, Loader loader) throws Stitcher.StitchingException {
        Path injected = injectFailureForPathForTests;
        Loader toRun = loader;
        if (injected != null && injected == shape.path()) {
            toRun = () -> { throw new Stitcher.StitchingException("injected stitch failure for path [" + injected + "]", null); };
        }
        // Single-element holders so the loader (which runs on the winning thread only) can report back to this thread
        // whether it ran and, if so, the first-failure exception it produced. Losing threads observe the cached value
        // with ranLoader[0]==false and take the negative-hit path.
        boolean[] ranLoader = { false };
        Stitcher.StitchingException[] firstEx = { null };
        Loader runnable = toRun;
        CacheValue value;
        try {
            value = cache.computeIfAbsent(shape, key -> load(runnable, ranLoader, firstEx));
        } catch (ExecutionException e) {
            // The loader completes exceptionally only for a transient (uncached) failure wrapped in TransientStitchError;
            // the Cache has already removed the key, so rethrow the original so the shape is retried, not poisoned.
            Throwable cause = e.getCause();
            if (cause instanceof TransientStitchError transient_) {
                Throwable original = transient_.getCause();
                if (original instanceof Error error) {
                    throw error;
                }
                if (original instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new AssertionError("unexpected transient stitch cause", original);
            }
            throw new AssertionError("unexpected fused-class cache load failure", e);
        }
        if (value instanceof Failure failure) {
            if (ranLoader[0] && firstEx[0] != null) {
                // This thread produced the first failure: rethrow the original (firstFailure=true, cause preserved).
                throw firstEx[0];
            }
            // A negative-cache hit (another thread stored the marker, or a repeat lookup): reconstruct with
            // firstFailure=false and a dropped cause so the factory DEBUGs a repeat instead of WARNing again.
            negativeHits.increment();
            throw new Stitcher.StitchingException(failure.reconstructedMessage(), null, false);
        }
        return (CachedFused) value;
    }

    /**
     * Runs {@code loader} once on the winning single-flight thread, timing the stitch and mapping its outcome to a
     * cached {@link CacheValue}. A success returns the {@link CachedFused} and bumps {@link #classesDefined}; a
     * deterministic stitch failure ({@link Stitcher.StitchingException} or the {@link RuntimeException} /
     * {@link ReflectiveOperationException} / unwrapped {@link LinkageError} it maps to)
     * returns a {@link Failure} value (so the {@link Cache} retains it rather than dropping the key); a transient
     * {@link Error} (including a {@link VirtualMachineError}) is rethrown wrapped in {@link TransientStitchError} so the
     * {@link Cache} drops the key uncached.
     */
    private CacheValue load(Loader loader, boolean[] ranLoader, Stitcher.StitchingException[] firstEx) {
        ranLoader[0] = true;
        long start = System.nanoTime();
        try {
            CachedFused cached = loader.load();
            classesDefined.increment();
            return cached;
        } catch (Stitcher.StitchingException e) {
            return recordFailure(e, firstEx);
        } catch (VirtualMachineError e) {
            // OutOfMemoryError / InternalError / StackOverflowError: transient, not a permanent shape failure — drop the
            // key and retry later. Deep trees cannot reach here: the planner refuses any tree past
            // FusionPlanner#MAX_FUSION_TREE_DEPTH, so the emit walk never recurses far enough to overflow the stack.
            throw new TransientStitchError(e);
        } catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
            return recordFailure(new Stitcher.StitchingException(e.getMessage(), e), firstEx);
        } catch (Error e) {
            // AssertionError and other non-VM, non-Linkage Errors: treat as transient.
            throw new TransientStitchError(e);
        } finally {
            stitchNanos.add(System.nanoTime() - start);
        }
    }

    private Failure recordFailure(Stitcher.StitchingException e, Stitcher.StitchingException[] firstEx) {
        failures.increment();
        firstEx[0] = e;
        String causeClassName = e.getCause() == null ? null : e.getCause().getClass().getName();
        return new Failure(causeClassName, e.getMessage());
    }

    /**
     * Stitches via {@code stitch} (carrying the class-file byte length for the weigher) then resolves {@code methods}
     * against {@code type} on the resulting class, returning the {@link CachedFused} unit. WHY resolution runs here,
     * inside the cache loader: a {@code findStatic} miss is a {@link ReflectiveOperationException} that {@link #load}
     * turns into a negatively-cached first-failure, so resolving <em>after</em> the cache returned would instead store
     * the class as a success and WARN on every plan. {@code type == null} (or no method names) skips resolution so the
     * 2-arg compile entry points stay stitch-only.
     */
    static CachedFused stitchAndResolveSized(SizedStitch stitch, MethodHandles.Lookup lookup, MethodType type, String... methods)
        throws Stitcher.StitchingException, ReflectiveOperationException {
        Stitcher.Fused fused = stitch.stitch();
        return new CachedFused(fused.clazz(), fused.classFileBytes(), type, resolveMethods(lookup, fused.clazz(), type, methods));
    }

    /**
     * Test-only convenience: like {@link #stitchAndResolveSized} but takes a {@link Class}-returning stitch whose
     * byte length is unknown (weighed as {@link #UNKNOWN_CLASS_BYTES}). Used by fallback-hardening tests that inject a
     * real/failing stitch without constructing a {@link Stitcher.Fused}.
     */
    static CachedFused stitchAndResolve(ClassStitcher stitcher, MethodHandles.Lookup lookup, MethodType type, String... methods)
        throws Stitcher.StitchingException, ReflectiveOperationException {
        Class<?> fused = stitcher.stitch();
        return new CachedFused(fused, UNKNOWN_CLASS_BYTES, type, resolveMethods(lookup, fused, type, methods));
    }

    private static Map<String, ResolvedMethod> resolveMethods(
        MethodHandles.Lookup lookup,
        Class<?> fused,
        MethodType type,
        String... methods
    ) throws NoSuchMethodException, IllegalAccessException {
        if (type == null || methods.length == 0) {
            return Map.of();
        }
        Map<String, ResolvedMethod> map = HashMap.newHashMap(methods.length);
        int arity = type.parameterCount();
        for (String method : methods) {
            MethodHandle handle = findStaticCounted(lookup, fused, method, type);
            map.put(method, new ResolvedMethod(handle, handle.asSpreader(Object[].class, arity)));
        }
        return Map.copyOf(map);
    }

    /**
     * The single fused {@code findStatic} seam. Production resolution and factory fallback both go through here so a
     * test can prove a cache hit pays neither {@code findStatic} nor a second resolution.
     */
    static MethodHandle findStaticCounted(MethodHandles.Lookup lookup, Class<?> cls, String name, MethodType type)
        throws NoSuchMethodException, IllegalAccessException {
        findStaticCallsForTests.incrementAndGet();
        return lookup.findStatic(cls, name, type);
    }

    /** Raises (or lowers) the admission threshold; seeded from the node setting by {@link FusionSettings#initFromNodeSettings}. */
    void setMinSightings(int minSightings) {
        this.minSightings = Math.max(1, minSightings);
    }

    int minSightings() {
        return minSightings;
    }

    long hits() {
        return cache.stats().getHits() - doorkeeperProbeHits.sum();
    }

    long misses() {
        return cache.stats().getMisses() - doorkeeperProbeMisses.sum();
    }

    /** Evictions by weight/expiry only (a removal listener filters {@link RemovalNotification.RemovalReason#EVICTED}). */
    long evictions() {
        return evictions.sum();
    }

    /** Hidden classes successfully defined (one per successful first-sight stitch). */
    long classesDefined() {
        return classesDefined.sum();
    }

    long failures() {
        return failures.sum();
    }

    long negativeHits() {
        return negativeHits.sum();
    }

    long admissionSkips() {
        return admissionSkips.sum();
    }

    long stitchNanos() {
        return stitchNanos.sum();
    }

    /** Number of entries currently retained (stitched classes + negative markers). */
    int size() {
        return cache.count();
    }

    /** Current total entry weight in bytes (class-file lengths + {@link #FAILURE_WEIGHT} per marker). */
    long weightBytes() {
        return cache.weight();
    }

    /** Test-only: a snapshot of the shape keys currently retained in the cache (stitched classes and negative markers). */
    List<Shape> entriesForTests() {
        List<Shape> shapes = new ArrayList<>();
        for (Shape shape : cache.keys()) {
            shapes.add(shape);
        }
        return shapes;
    }

    /** An immutable snapshot of the cache counters for diagnostics/telemetry. */
    FusionCacheStats stats() {
        Cache.Stats s = cache.stats();
        return new FusionCacheStats(
            s.getHits() - doorkeeperProbeHits.sum(),
            s.getMisses() - doorkeeperProbeMisses.sum(),
            evictions.sum(),
            classesDefined.sum(),
            admissionSkips.sum(),
            stitchNanos.sum(),
            cache.count(),
            cache.weight()
        );
    }

    /**
     * Test-only: rebuilds the backing {@link Cache} (so hit/miss/weight/count start at zero), zeroes every counter and
     * the doorkeeper, and clears {@link #injectFailureForPathForTests} plus the planner's test hooks.
     * {@link #minSightings} is left unchanged so a doorkeeper test can reset counters mid-test without re-arming
     * admission; the plan-time singleton keeps its static default of {@code 1}.
     */
    void resetForTests() {
        cache = buildCache();
        classesDefined.reset();
        stitchNanos.reset();
        evictions.reset();
        failures.reset();
        negativeHits.reset();
        admissionSkips.reset();
        doorkeeperProbeHits.reset();
        doorkeeperProbeMisses.reset();
        Arrays.fill(doorkeeper, 0L);
        injectFailureForPathForTests = null;
        findStaticCallsForTests.set(0);
        FusionPlanner.compilation().compileBlockLoopForTests = null;
        FusionPlanner.resetBakedDivisorsForTests();
    }
}
