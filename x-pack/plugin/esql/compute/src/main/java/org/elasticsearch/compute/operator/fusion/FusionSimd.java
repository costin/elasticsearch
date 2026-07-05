/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.apache.lucene.util.Constants;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime availability + module plumbing for the opt-in Panama Vector-API (SIMD) fusion path — the same approach
 * Lucene ({@code VectorizationProvider}) and Elasticsearch ({@code libs/simdvec ESVectorizationProvider}) use, so no
 * shipped module declares {@code requires jdk.incubator.vector} and no end-user JVM flag is needed:
 *
 * <ul>
 *   <li><b>Presence.</b> The default ES distribution already resolves {@code jdk.incubator.vector} into the boot
 *       {@link ModuleLayer} via {@code --add-modules=jdk.incubator.vector} in its shipped {@code jvm.options} (JDK 20+),
 *       so the module is normally present at runtime. {@link #AVAILABLE} additionally gates on a HotSpot C2 VM and a
 *       tested JDK feature range — SIMD is refused (falling back to the scalar fast path) otherwise.</li>
 *   <li><b>Readability.</b> {@code --add-modules} makes the module <em>resolved</em> but grants no read edge. A class
 *       that calls {@code jdk.incubator.vector} must have its module {@linkplain Module#addReads read} it. Since a
 *       {@code defineHiddenClass} class is a member of its defining lookup's module, {@link #enableReadsFrom} adds that
 *       edge once per host module <b>before</b> the SIMD hidden class is defined, so its {@code IntVector.*} calls link
 *       (while its esql-kernel calls still link because it stays in the caller's module).</li>
 * </ul>
 *
 * <p>The {@link Stitcher} emits the Vector-API calls purely as ASM string references, so this module needs no
 * compile-time dependency on {@code jdk.incubator.vector}; only the runtime read edge here is required.
 */
public final class FusionSimd {

    private FusionSimd() {}

    /** Internal names of the Vector-API types the SIMD emitter references (string-only; no compile-time dependency). */
    static final String INT_VECTOR = "jdk/incubator/vector/IntVector";
    static final String LONG_VECTOR = "jdk/incubator/vector/LongVector";
    static final String DOUBLE_VECTOR = "jdk/incubator/vector/DoubleVector";
    static final String VECTOR = "jdk/incubator/vector/Vector";
    static final String VECTOR_SPECIES = "jdk/incubator/vector/VectorSpecies";
    static final String VECTOR_MASK = "jdk/incubator/vector/VectorMask";
    static final String VECTOR_OPERATORS = "jdk/incubator/vector/VectorOperators";
    static final String COMPARISON = "jdk/incubator/vector/VectorOperators$Comparison";

    private static final Module VECTOR_MODULE = ModuleLayer.boot().findModule("jdk.incubator.vector").orElse(null);

    /**
     * Whether SIMD generation may be attempted at all: the incubator module is present in the boot layer, the VM is
     * HotSpot (C2 — the Vector API is a no-op/slow on others), and the JDK feature version is in the tested
     * {@code [21, 26]} range (the API incubates and can change between releases, à la Lucene's upper bound). A scalar
     * fallback always covers the {@code false} case.
     */
    private static final boolean AVAILABLE = VECTOR_MODULE != null
        && Constants.IS_HOTSPOT_VM
        && Runtime.version().feature() >= 21
        && Runtime.version().feature() <= 26;

    /** Host modules already granted a read edge to {@code jdk.incubator.vector} (the edge is idempotent + cheap). */
    private static final Set<Module> READS_GRANTED = ConcurrentHashMap.newKeySet();

    /** Whether the SIMD path is usable in this runtime (see {@link #AVAILABLE}). */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Grants {@code host} a read edge to {@code jdk.incubator.vector} (idempotent) so a hidden class defined in
     * {@code host}'s module can link Vector-API calls. Must be called before that class is defined. No-op when SIMD is
     * unavailable.
     */
    public static void enableReadsFrom(Module host) {
        if (AVAILABLE && READS_GRANTED.add(host)) {
            host.addReads(VECTOR_MODULE);
        }
    }
}
