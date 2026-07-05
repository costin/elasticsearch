/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.objectweb.asm.Type;

/**
 * A running local-variable slot allocator for a fused host method. It hands out slots sequentially, sized by JVM
 * category width (a {@code long}/{@code double} takes two slots, everything else one), so a frame layout is
 * <b>non-overlapping by construction</b> — replacing the hand-rolled {@code int x = prev + width} chains the
 * {@link Stitcher} emit paths used to compute (the classic latent {@code VerifyError} surface: forget that a
 * {@code long} value slot needs two, and every later slot silently collides).
 *
 * <p>The allocator is a plain cursor: callers reserve the parameter region first (matching the method descriptor's
 * declaration order, so parameter slots land at their JVM-assigned indices), then the fixed scratch locals, then hand
 * {@link #mark()} to the per-kernel body emitter as its disjoint base. It is deliberately tiny and dependency-free;
 * its surface ({@link #slot(Type)}/{@link #reserve}/{@link #mark}) mirrors the JDK {@code ClassFile}
 * {@code CodeBuilder.allocateLocal} shape so a future migration off ASM is a thin adapter rather than a rewrite.
 */
final class FrameLayout {

    private int next;

    /** A layout whose first slot is {@code 0} (the usual start for a {@code static} method's frame). */
    FrameLayout() {
        this(0);
    }

    /** A layout whose first free slot is {@code base} (e.g. to allocate only the scratch region above the parameters). */
    FrameLayout(int base) {
        this.next = base;
    }

    /** Reserves one single-width slot ({@code int}/{@code boolean}/{@code float}/reference) and returns it. */
    int slot() {
        return next++;
    }

    /** Reserves a slot sized for {@code type} (a {@code long}/{@code double} takes two) and returns its base. */
    int slot(Type type) {
        return reserve(type.getSize());
    }

    /** Reserves {@code width} (1 or 2) consecutive slots and returns the base of the first. */
    int reserve(int width) {
        int slot = next;
        next += width;
        return slot;
    }

    /** Reserves {@code count} consecutive single-width slots and returns the base of the first. */
    int slots(int count) {
        int base = next;
        next += count;
        return base;
    }

    /** The next free slot: the base a per-kernel body's own (disjoint) local range starts at. */
    int mark() {
        return next;
    }

    /** Advances the cursor by {@code width} slots (e.g. past a spliced kernel body's {@code maxLocals}). */
    void advance(int width) {
        next += width;
    }
}
