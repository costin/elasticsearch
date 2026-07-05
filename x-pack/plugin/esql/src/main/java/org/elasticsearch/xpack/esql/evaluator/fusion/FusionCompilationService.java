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

/**
 * Owns the runtime <b>compilation and caching</b> of stitched hidden classes, separated from {@link FusionPlanner} so
 * the planner is pure plan-building: it decides <em>what</em> to fuse and builds a {@link FusionPlan}, while this
 * service decides <em>how</em> a shape is materialised (cache hit vs a fresh {@link Stitcher} stitch). It holds the
 * process-wide shared {@link Stitcher} (its {@link TemplateRegistry} is thread-safe) and the {@link FusedClassCache}.
 *
 * <p>{@link #compiler()} is the production {@link FusedClassCompiler}: each entry point looks the expression
 * {@linkplain FusedClassCache.Shape shape} up in the cache first and only invokes the {@code Stitcher} on a miss (or a
 * cleared reference), so repeated identical query shapes reuse one hidden class. The caller {@link MethodHandles.Lookup}
 * is deliberately excluded from the cache key: production always uses the single {@link FusedExpressionEvaluatorFactory}
 * module lookup, so keying by shape alone is safe. Test-injected compilers ({@code FusionPlanner.compilerForTests})
 * bypass this service entirely, keeping the raw stitch/fallback seams uncached and order-independent across tests.
 */
final class FusionCompilationService {

    private final Stitcher stitcher = new Stitcher(new TemplateRegistry());
    private final FusedClassCache cache = new FusedClassCache();

    private final FusedClassCompiler compiler = new FusedClassCompiler() {
        @Override
        public Class<?> compileBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return cache.get(shapeKey(tree, FusedClassCache.Path.BLOCK), () -> stitcher.compileBlockLoop(caller, tree));
        }

        @Override
        public Class<?> compileVectorLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return cache.get(shapeKey(tree, FusedClassCache.Path.PLAIN_VECTOR), () -> stitcher.compileVectorLoop(caller, tree));
        }

        @Override
        public Class<?> compileVectorLoopChecked(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return cache.get(shapeKey(tree, FusedClassCache.Path.CHECKED_VECTOR), () -> stitcher.compileVectorLoopChecked(caller, tree));
        }

        @Override
        public Class<?> compileLogicalBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return cache.get(shapeKey(tree, FusedClassCache.Path.LOGICAL_BLOCK), () -> stitcher.compileLogicalBlockLoop(caller, tree));
        }

        @Override
        public Class<?> compileVectorLoopSimd(MethodHandles.Lookup caller, FusionNode tree, String comparison)
            throws Stitcher.StitchingException {
            // The comparison operator is part of the tree's shape (distinct comparison kernels -> distinct shapeOf),
            // so the shape key already distinguishes a>b from a<b; keyed on the SIMD_VECTOR path.
            return cache.get(
                shapeKey(tree, FusedClassCache.Path.SIMD_VECTOR),
                () -> stitcher.compileVectorLoopSimd(caller, tree, comparison)
            );
        }

        @Override
        public Class<?> compileMappingBlockLoop(MethodHandles.Lookup caller, FusionNode tree) throws Stitcher.StitchingException {
            return cache.get(shapeKey(tree, FusedClassCache.Path.MAPPING_BLOCK), () -> stitcher.compileMappingBlockLoop(caller, tree));
        }
    };

    /** The production caching compiler consulted by {@link FusionPlanner} when no test override is installed. */
    FusedClassCompiler compiler() {
        return compiler;
    }

    /** The process-wide stitched-class cache (package-private so tests can assert reuse and reset counters). */
    FusedClassCache cache() {
        return cache;
    }

    /** The binding-independent cache key for {@code tree} on the given emit {@code path}. */
    private static FusedClassCache.Shape shapeKey(FusionNode tree, FusedClassCache.Path path) {
        return new FusedClassCache.Shape(FusionPlanner.shapeOf(tree), rootElement(tree), path);
    }

    /**
     * The OUTPUT element kind of a (depth &ge; 2) fused tree, read from its root kernel's return type (an arithmetic
     * root yields its homogeneous numeric element; a comparison or 3VL logical root yields {@link ElementKind#BOOLEAN}).
     * Part of the {@link FusedClassCache} key (the numeric input element is already implied by the kernel descriptors
     * encoded in {@code FusionPlanner.shapeOf}).
     */
    private static ElementKind rootElement(FusionNode tree) {
        if (tree instanceof FusionNode.Logical) {
            return ElementKind.BOOLEAN;
        }
        FusionNode.Kernel root = (FusionNode.Kernel) tree;
        String descriptor = root.descriptor().kernelType();
        return FusionPlanner.outputElementOf(descriptor.charAt(descriptor.indexOf(')') + 1));
    }
}
