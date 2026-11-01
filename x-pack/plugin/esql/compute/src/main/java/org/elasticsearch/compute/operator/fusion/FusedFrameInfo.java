/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Default-trace carrier for an exception that escaped fused code. Hidden-class frames are omitted from
 * {@link Throwable} stack traces unless {@code -XX:+ShowHiddenFrames}, so {@code SourceFile},
 * {@code LineNumberTable} and {@link Stitcher#KERNEL_MAP_FIELD_NAME} never appear in production traces.
 * The per-page catch attaches one of these as a suppressed exception; the message names the fused shape
 * and the kernel map. No stack is captured ({@code writableStackTrace=false}) — this is cold-path only.
 */
public final class FusedFrameInfo extends RuntimeException {

    public FusedFrameInfo(String shape, String kernelMap) {
        super("fused shape=[" + shape + "] kernels=[" + kernelMap + "]", null, false, false);
    }

    /**
     * Reads {@link Stitcher#KERNEL_MAP_FIELD_NAME} from the hidden class that declared {@code handle}.
     * Empty when the handle is null or the field cannot be read. Cold-path only (factory construction).
     */
    public static String kernelMapOf(MethodHandles.Lookup lookup, MethodHandle handle) {
        if (handle == null) {
            return "";
        }
        try {
            Class<?> fusedClass = lookup.revealDirect(handle).getDeclaringClass();
            MethodHandle getter = lookup.findStaticGetter(fusedClass, Stitcher.KERNEL_MAP_FIELD_NAME, String.class);
            String value = (String) getter.invoke();
            return value != null ? value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
