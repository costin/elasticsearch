/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.ElementKind;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusionPlanner.FusedClassCompiler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Owns the runtime <b>compilation and caching</b> of stitched hidden classes, separated from {@link FusionPlanner} so
 * the planner is pure plan-building: it decides <em>what</em> to fuse and builds a {@link FusionPlan}, while this
 * service decides <em>how</em> a shape is materialised (cache hit vs a fresh {@link Stitcher} stitch). It holds the
 * process-wide shared {@link Stitcher} (its {@link TemplateRegistry} is thread-safe) and the {@link FusedClassCache}.
 *
 * <p>{@link #compiler()} is the production {@link FusedClassCompiler}: each entry point looks the expression
 * {@linkplain FusedClassCache.Shape shape} up in the cache first and only invokes the {@code Stitcher} on a miss (or a
 * cleared reference), so repeated identical query shapes reuse one hidden class. MethodType overloads also resolve the
 * factory's expected {@code fused*} methods <em>inside</em> that cached unit via
 * {@link FusedClassCache#stitchAndResolve}, so a {@code findStatic} miss is negatively cached (WARN once) rather than
 * storing the class as a success. The 2-arg compile entry points throw
 * {@link UnsupportedOperationException}: production always resolves. The caller {@link MethodHandles.Lookup}
 * is deliberately excluded from the cache key: production always uses the single {@link FusedExpressionEvaluatorFactory}
 * module lookup, so keying by shape alone is safe. Test-injected compilers ({@code FusionPlanner.compilerForTests})
 * bypass this service entirely, keeping the raw stitch/fallback seams uncached and order-independent across tests.
 */
final class FusionCompilationService {

    private final Stitcher stitcher = new Stitcher(new TemplateRegistry());

    // Volatile and rebuilt only by reconfigureCache at node start (the ES Cache weight ceiling is fixed at build time,
    // so honouring esql.fusion.cache.max_bytes means a fresh cache). Plan-time threads always read the current cache.
    private volatile FusedClassCache cache = new FusedClassCache();

    /**
     * Test-only override of {@link Stitcher#compileBlockLoop}. {@link Stitcher} is final, so tests inject the
     * stitch lambda here and still go through {@link #cached} (unlike {@code FusionPlanner.compilerForTests},
     * which bypasses this service). Never set in production; {@link FusedClassCache#resetForTests} clears it.
     */
    @FunctionalInterface
    interface BlockLoopOverride {
        Class<?> compile(Stitcher real, MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException;
    }

    volatile BlockLoopOverride compileBlockLoopForTests;

    private Stitcher.Fused stitchBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
        BlockLoopOverride override = compileBlockLoopForTests;
        if (override != null) {
            // Test double returns a bare Class with no byte length; weigh it as unknown (see FusedClassCache).
            return new Stitcher.Fused(override.compile(stitcher, caller, tree), 0);
        }
        return stitcher.compileBlockLoopSized(caller, tree);
    }

    private static Class<?> requireResolved() {
        throw new UnsupportedOperationException(
            "production FusionCompilationService requires MethodType resolution; use the 4-arg compile overload"
        );
    }

    private final FusedClassCompiler compiler = new FusedClassCompiler() {
        @Override
        public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return requireResolved();
        }

        @Override
        public FusedClassCache.CachedFused compileBlockLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return cached(shapeKey(tree, FusedClassCache.Path.BLOCK), () -> stitchBlockLoop(caller, tree), caller, type, methods);
        }

        @Override
        public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return requireResolved();
        }

        @Override
        public FusedClassCache.CachedFused compileVectorLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return cached(
                shapeKey(tree, FusedClassCache.Path.PLAIN_VECTOR),
                () -> stitcher.compileVectorLoopSized(caller, tree),
                caller,
                type,
                methods
            );
        }

        @Override
        public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return requireResolved();
        }

        @Override
        public FusedClassCache.CachedFused compileVectorLoopChecked(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return cached(
                shapeKey(tree, FusedClassCache.Path.CHECKED_VECTOR),
                () -> stitcher.compileVectorLoopCheckedSized(caller, tree),
                caller,
                type,
                methods
            );
        }

        @Override
        public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return requireResolved();
        }

        @Override
        public FusedClassCache.CachedFused compileLogicalBlockLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return cached(
                shapeKey(tree, FusedClassCache.Path.LOGICAL_BLOCK),
                () -> stitcher.compileLogicalBlockLoopSized(caller, tree),
                caller,
                type,
                methods
            );
        }

        @Override
        public Class<?> compileLogicalVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return requireResolved();
        }

        @Override
        public FusedClassCache.CachedFused compileLogicalVectorLoop(
            MethodHandles.Lookup caller,
            FusionNode tree,
            MethodType type,
            String... methods
        ) throws Stitcher.StitchingException {
            return cached(
                shapeKey(tree, FusedClassCache.Path.LOGICAL_VECTOR),
                () -> stitcher.compileLogicalVectorLoopSized(caller, tree),
                caller,
                type,
                methods
            );
        }
    };

    /**
     * Cache lookup whose stitcher lambda stitches <em>and</em> resolves {@code methods} against {@code type}.
     * WHY: a {@code findStatic} miss must run inside {@link FusedClassCache#getResolved} so the class is not stored
     * as a success. Production always passes a non-null {@code type}; the 2-arg compile entry points throw rather
     * than skip resolution. Returns the {@link FusedClassCache.CachedFused} unit so the factory can pin it.
     */
    private FusedClassCache.CachedFused cached(
        FusedClassCache.Shape shape,
        FusedClassCache.SizedStitch stitch,
        MethodHandles.Lookup caller,
        MethodType type,
        String... methods
    ) throws Stitcher.StitchingException {
        return cache.getResolved(shape, () -> FusedClassCache.stitchAndResolveSized(stitch, caller, type, methods));
    }

    /** The production caching compiler consulted by {@link FusionPlanner} when no test override is installed. */
    FusedClassCompiler compiler() {
        return compiler;
    }

    /** The process-wide stitched-class cache (package-private so tests can assert reuse and reset counters). */
    FusedClassCache cache() {
        return cache;
    }

    /**
     * Admission decision for an arithmetic/logical/BytesRef plan {@code tree}: delegates to the cache doorkeeper but
     * first lets a shape whose primary class is already cached fuse without consulting it (see
     * {@link FusedClassCache#admit}). The primary {@link FusedClassCache.Shape} is the block (or logical-block) path
     * that {@link FusedExpressionEvaluatorFactory} always compiles first — the one unconditional cache entry for the
     * plan — keyed on the plan-level {@link FusionPlanner#shapeOf} string.
     */
    boolean admit(FusionNode tree) {
        return cache.admit(FusionPlanner.shapeOf(tree), shapeKey(tree, primaryPathOf(tree)));
    }

    /** The always-compiled primary emit path for {@code tree}: logical-block for a 3VL tree, block otherwise. */
    private static FusedClassCache.Path primaryPathOf(FusionNode tree) {
        return FusionNode.isBooleanLogic(tree) ? FusedClassCache.Path.LOGICAL_BLOCK : FusedClassCache.Path.BLOCK;
    }

    /**
     * Rebuilds the cache with the node-configured byte-weight ceiling and seeds its admission threshold. Called once
     * at node start by {@link FusionSettings#initFromNodeSettings}, before any plan runs; the ES {@link FusedClassCache}
     * fixes its weight ceiling at construction, so changing {@code maxBytes} means a fresh (empty) cache.
     */
    void reconfigureCache(long maxBytes, int minSightings) {
        FusedClassCache rebuilt = new FusedClassCache(maxBytes);
        rebuilt.setMinSightings(minSightings);
        this.cache = rebuilt;
    }

    /** The binding-independent cache key for {@code tree} on the given emit {@code path}. */
    private static FusedClassCache.Shape shapeKey(FusionNode tree, FusedClassCache.Path path) {
        return new FusedClassCache.Shape(FusionPlanner.shapeOf(tree), rootElement(tree), path);
    }

    /**
     * The OUTPUT element kind of a (depth &ge; 2) fused tree, read from its root kernel's return type (an arithmetic
     * root yields its homogeneous numeric element; a comparison, 3VL logical, or Not root yields
     * {@link ElementKind#BOOLEAN}).
     * Part of the {@link FusedClassCache} key (the numeric input element is already implied by the kernel descriptors
     * encoded in {@code FusionPlanner.shapeOf}).
     */
    private static ElementKind rootElement(FusionNode tree) {
        if (FusionNode.isBooleanLogic(tree)) {
            return ElementKind.BOOLEAN;
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        String descriptor = root.descriptor().kernelType();
        return FusionPlanner.outputElementOf(descriptor.charAt(descriptor.indexOf(')') + 1));
    }
}
