/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Plan-time cache of fusable kernel bytecode, exposed as ASM {@link MethodNode}s.
 *
 * <p>The stitcher splices a kernel's instruction list into a host method. To do that without
 * re-reading and re-parsing the kernel's {@code .class} resource on every stitch, this registry
 * parses each distinct kernel method exactly once (keyed by {@code (kernelClass, kernelMethod,
 * kernelType)}) and retains the resulting {@code MethodNode} as an immutable template.
 *
 * <p>Each {@link #methodNode(FusionDescriptor)} call returns an <b>independent clone</b> of the
 * cached template. ASM tree nodes are mutable and the stitcher remaps slots and rewrites
 * instructions in place; handing out the shared template would corrupt the cache for the next
 * caller. Cloning isolates every caller from every other.
 *
 * <p>This class lives on the cold (plan-time) path — invoked once per expression-shape stitch, not
 * per row — so the parse + clone cost is not in the per-row inlining budget.
 *
 * <p>Thread-safe: {@link ConcurrentHashMap#computeIfAbsent} guarantees the underlying
 * {@link ClassReader} parse happens at most once per key even under a concurrent stampede, and the
 * clone-on-lookup means concurrent callers never share a mutable node.
 */
public final class TemplateRegistry {

    /**
     * Cache key — the descriptor's identifying triple. {@code kernelType} (the JVM method
     * descriptor, e.g. {@code (JJ)J}) is part of the key so overloaded kernel methods that share a
     * name resolve to distinct templates.
     */
    private record TemplateKey(Class<?> kernelClass, String kernelMethod, String kernelType) {}

    private final ConcurrentHashMap<TemplateKey, MethodNode> templates = new ConcurrentHashMap<>();

    /**
     * Number of {@link ClassReader} parses performed. Used by tests to assert the
     * {@code computeIfAbsent} spin-once guarantee under concurrency; not consulted by production
     * code.
     */
    private final LongAdder parseCount = new LongAdder();

    public TemplateRegistry() {}

    /**
     * Returns an independent {@link MethodNode} clone of the kernel identified by {@code descriptor}.
     * The first request for a given {@code (kernelClass, kernelMethod, kernelType)} triple parses
     * the kernel's {@code .class} resource; subsequent requests clone the cached template.
     *
     * @throws IllegalStateException if the kernel class bytecode cannot be located on the classpath,
     *                               or no method matches the descriptor's name and type
     */
    // Package-private on purpose: the ASM MethodNode return type must not leak across the module
    // boundary (asm-tree is a non-transitive `implementation` dependency). The only consumer, the
    // Stitcher, lives in this package.
    MethodNode methodNode(FusionDescriptor descriptor) {
        TemplateKey key = new TemplateKey(descriptor.kernelClass(), descriptor.kernelMethod(), descriptor.kernelType());
        MethodNode template = templates.computeIfAbsent(key, this::parse);
        return clone(template);
    }

    /**
     * Parses the kernel class resource and extracts the matching method as an immutable template.
     * Reads with {@link ClassReader#SKIP_FRAMES}: stack-map frames are recomputed by the stitcher
     * when it emits the host class, so retaining the kernel's original frames would be wasted work.
     * {@code SKIP_CODE} is deliberately not used — the instruction list is the whole point.
     */
    private MethodNode parse(TemplateKey key) {
        parseCount.increment();
        Class<?> kernelClass = key.kernelClass();
        String resource = "/" + kernelClass.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = kernelClass.getResourceAsStream(resource)) {
            if (bytecode == null) {
                throw new IllegalStateException("kernel class bytecode unavailable: " + kernelClass);
            }
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_FRAMES);
            for (MethodNode method : classNode.methods) {
                if (method.name.equals(key.kernelMethod()) && method.desc.equals(key.kernelType())) {
                    return method;
                }
            }
            throw new IllegalStateException(
                "kernel method not found: " + kernelClass.getName() + "#" + key.kernelMethod() + key.kernelType()
            );
        } catch (IOException e) {
            throw new IllegalStateException("failed to read kernel class bytecode: " + kernelClass, e);
        }
    }

    /**
     * Produces an independent deep copy of {@code src}. {@code src.accept(copy)} replays the source
     * node through the visitor API, rebuilding the instruction list, try/catch blocks, local
     * variables, and annotations into a fresh node. {@link Opcodes#ASM9} is passed explicitly: the
     * no-arg {@code MethodNode()} constructor defaults to {@code ASM4}, which rejects modern
     * bytecode features.
     */
    private static MethodNode clone(MethodNode src) {
        MethodNode copy = new MethodNode(
            Opcodes.ASM9,
            src.access,
            src.name,
            src.desc,
            src.signature,
            src.exceptions.toArray(String[]::new)
        );
        src.accept(copy);
        return copy;
    }

    /**
     * Test-only: total {@link ClassReader} parses performed across all keys. Production code never
     * reads this; it exists so concurrency tests can assert the spin-once cache guarantee.
     */
    long parseCount() {
        return parseCount.sum();
    }
}
