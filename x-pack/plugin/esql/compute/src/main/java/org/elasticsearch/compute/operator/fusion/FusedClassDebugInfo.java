/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic-only metadata attached to every fused hidden class: a {@code SourceFile} naming the fused
 * shape, a {@code KERNEL_MAP} ConstantValue field ({@code index -> class#method}), and the kernel-index
 * lookup used to emit {@code LineNumberTable} entries at splice points.
 *
 * <p>These are class-file attributes and a {@code public static final String} ConstantValue — they add
 * no instructions to the per-row loop, do not change fused method signatures, and are not a cache key.
 * The shape rendering is Stitcher-local (compute module) and is deliberately not
 * {@code FusionPlanner.shapeOf}: it only needs to be human-meaningful and deterministic.
 * Hidden-class frames are omitted from {@code Throwable} stack traces unless
 * {@code -XX:+ShowHiddenFrames}, so {@code SourceFile} and {@code LineNumberTable} do not appear in
 * default production traces. Default traces name the fused shape and kernels via a suppressed
 * {@link FusedFrameInfo} attached at the per-page catch.
 */
final class FusedClassDebugInfo {

    /** Cap for {@code SourceFile} and {@code KERNEL_MAP}: a UTF-8 constant-pool entry over 64 KB makes ASM throw. */
    static final int MAX_UTF8_BYTES = 4096;
    static final String TRUNCATION_MARKER = "...[truncated]";

    private final String sourceFile;
    private final String kernelMap;
    private final Map<FusionNode.Kernel, Integer> indices;

    private FusedClassDebugInfo(String sourceFile, String kernelMap, Map<FusionNode.Kernel, Integer> indices) {
        this.sourceFile = capUtf8(sourceFile);
        this.kernelMap = capUtf8(kernelMap);
        this.indices = indices;
    }

    /**
     * Truncates {@code value} to {@link #MAX_UTF8_BYTES} UTF-8 bytes, appending {@link #TRUNCATION_MARKER}.
     * Walks back over UTF-8 continuation bytes so a multi-byte character is never split.
     */
    static String capUtf8(String value) {
        if (value == null) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UTF8_BYTES) {
            return value;
        }
        byte[] marker = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8);
        int keep = MAX_UTF8_BYTES - marker.length;
        while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) {
            keep--;
        }
        // keep starts at MAX_UTF8_BYTES - marker.length (4082) and the loop stops at 0, so it stays >= 0.
        assert keep >= 0;
        return new String(bytes, 0, keep, StandardCharsets.UTF_8) + TRUNCATION_MARKER;
    }

    /**
     * Depth-1 {@link Stitcher#compile} has a descriptor rather than a {@link FusionNode} tree: a single
     * kernel at index {@code 0}. The index map is empty ({@code Map.of()}) — there is no
     * {@link FusionNode.Kernel} to look up — so this path must emit its {@code LineNumberNode(0)}
     * directly rather than via {@link #indexOf} / {@code spliceKernelBody}.
     */
    static FusedClassDebugInfo ofDescriptor(String pathToken, FusionDescriptor descriptor) {
        String token = descriptor.kernelClass().getSimpleName() + "#" + descriptor.kernelMethod();
        String map = "0=" + descriptor.kernelClass().getName() + "#" + descriptor.kernelMethod();
        return new FusedClassDebugInfo(pathToken + "__" + token, map, Map.of());
    }

    /** Debug info for a single fused tree, named after the emit path's {@code Fused$$…} class token. */
    static FusedClassDebugInfo of(String pathToken, FusionNode tree) {
        List<FusionNode.Kernel> kernels = new ArrayList<>();
        StringBuilder shape = new StringBuilder();
        appendShape(tree, shape);
        collectKernels(tree, kernels);
        IdentityHashMap<FusionNode.Kernel, Integer> indices = new IdentityHashMap<>();
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < kernels.size(); i++) {
            FusionNode.Kernel kernel = kernels.get(i);
            indices.put(kernel, i);
            if (i > 0) {
                map.append(';');
            }
            FusionDescriptor descriptor = kernel.descriptor();
            map.append(i).append('=').append(descriptor.kernelClass().getName()).append('#').append(descriptor.kernelMethod());
        }
        return new FusedClassDebugInfo(pathToken + "__" + shape, map.toString(), indices);
    }

    /**
     * Post-order walk of {@link FusionNode.Kernel} nodes only ({@link FusionNode.Logical} and {@link FusionNode.Not}
     * are not kernels and are not numbered). Matches splice order in {@code StackKernelEmitter} / {@code BlockDfsEmitter}.
     */
    private static void collectKernels(FusionNode node, List<FusionNode.Kernel> out) {
        if (node instanceof FusionNode.Input || node instanceof FusionNode.Constant) {
            return;
        }
        if (node instanceof FusionNode.Logical logical) {
            collectKernels(logical.left(), out);
            collectKernels(logical.right(), out);
            return;
        }
        if (node instanceof FusionNode.Not not) {
            collectKernels(not.child(), out);
            return;
        }
        FusionNode.Kernel kernel = (FusionNode.Kernel) node;
        for (FusionNode child : kernel.children()) {
            collectKernels(child, out);
        }
        out.add(kernel);
    }

    /**
     * Filename-safe, deterministic shape token: kernel simple-name{@code #}method, {@code AND}/{@code OR}
     * for logical nodes, {@code NOT(...)} for Not, {@code #} for column inputs, {@code C<element>} for constants.
     * Diagnostic only.
     */
    private static void appendShape(FusionNode node, StringBuilder sb) {
        if (node instanceof FusionNode.Logical logical) {
            sb.append(logical.op()).append('(');
            appendShape(logical.left(), sb);
            sb.append(',');
            appendShape(logical.right(), sb);
            sb.append(')');
            return;
        }
        if (node instanceof FusionNode.Not not) {
            sb.append("NOT(");
            appendShape(not.child(), sb);
            sb.append(')');
            return;
        }
        if (node instanceof FusionNode.Kernel kernel) {
            FusionDescriptor descriptor = kernel.descriptor();
            sb.append(descriptor.kernelClass().getSimpleName()).append('#').append(descriptor.kernelMethod()).append('(');
            List<FusionNode> children = kernel.children();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendShape(children.get(i), sb);
            }
            sb.append(')');
            return;
        }
        if (node instanceof FusionNode.Constant constant) {
            sb.append('C').append(constant.element());
            return;
        }
        sb.append('#');
    }

    /**
     * {@code SourceFile} attribute value. Diagnostic only; hidden-class frames are omitted from default
     * traces (see {@link FusedFrameInfo}).
     */
    String sourceFile() {
        return sourceFile;
    }

    /** ConstantValue of {@link Stitcher#KERNEL_MAP_FIELD_NAME}: {@code 0=fqcn#method;1=…}. */
    String kernelMap() {
        return kernelMap;
    }

    /**
     * Line number (and KERNEL_MAP index) for this kernel; throws if the kernel was not in the tree.
     * Do not call this on a depth-1 {@link #ofDescriptor} result — that path has no per-kernel index
     * map and emits {@code LineNumberNode(0)} directly.
     */
    int indexOf(FusionNode.Kernel kernel) {
        Integer index = indices.get(kernel);
        if (index == null) {
            throw new IllegalStateException(
                "kernel not in fused debug map (depth-1 ofDescriptor has no index map; emit LineNumberNode(0) directly): "
                    + kernel.descriptor()
            );
        }
        return index;
    }

    FieldNode kernelMapField() {
        return new FieldNode(
            Opcodes.ASM9,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            Stitcher.KERNEL_MAP_FIELD_NAME,
            "Ljava/lang/String;",
            null,
            kernelMap
        );
    }
}
