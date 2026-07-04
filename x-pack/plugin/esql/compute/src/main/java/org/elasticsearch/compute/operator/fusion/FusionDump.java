/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.objectweb.asm.ClassReader;

import java.util.Base64;
import java.util.function.BiConsumer;

/**
 * Opt-in debug/profiler visibility into the bytecode the {@link Stitcher} generates. Fused classes are
 * {@linkplain java.lang.invoke.MethodHandles.Lookup#defineHiddenClass hidden classes}, so they never touch disk and a
 * profiler or a developer cannot recover their {@code .class} the usual way. This hook exposes the bytes of every
 * generated fused class at the single define site, through two independent, entirely off-by-default channels:
 *
 * <ul>
 *   <li><b>Log dump (for {@code javap}).</b> When the system property {@code -Desql.fusion.dump=true} is set, each
 *       generated class is logged once as Base64 (with its internal name and size). Decode the Base64 to a
 *       {@code .class} file and run {@code javap -c -p} on it to read the emitted bytecode. Base64 keeps the log a
 *       single self-contained line (no binary in the log, no scratch files).</li>
 *   <li><b>Programmatic sink.</b> A profiler, test, or REST debug endpoint can {@link #setSink install} a
 *       {@code (internalName, classBytes)} consumer to capture the bytes in-process — e.g. to attach the generated
 *       source of a hot fused kernel to a query profile, or to assert on the emitted class in a test.</li>
 * </ul>
 *
 * <p>Both channels run only on the cold plan-time stitch path (once per fused class), never per row, so they add
 * nothing to the hot loop. When neither channel is active {@link #emit} is a single static-boolean + volatile read.
 */
public final class FusionDump {

    private static final Logger logger = LogManager.getLogger(FusionDump.class);

    /**
     * Read once at class init. {@code -Desql.fusion.dump=true} logs every generated fused class as Base64 so it can be
     * decoded and disassembled with {@code javap}. Off by default; a system property (not a node setting) because it is
     * a developer/diagnostic switch, independent of any running cluster.
     */
    private static final boolean DUMP_TO_LOG = Boolean.getBoolean("esql.fusion.dump");

    /** Programmatic capture, invoked with {@code (internalName, classBytes)} for each generated class; {@code null} = none. */
    private static volatile BiConsumer<String, byte[]> sink;

    private FusionDump() {}

    /**
     * Installs a sink invoked (on the cold stitch path) with the internal name and a private copy of the bytes of every
     * fused class generated afterwards. Intended for a profiler/debug endpoint or a test; pass {@code null} (or call
     * {@link #clearSink}) to remove it.
     */
    public static void setSink(BiConsumer<String, byte[]> newSink) {
        sink = newSink;
    }

    /** Removes any installed {@link #setSink sink}. */
    public static void clearSink() {
        sink = null;
    }

    /**
     * Offers a freshly generated fused class to the active debug channels. A no-op (one static-boolean read + one
     * volatile read) unless the log dump is enabled or a sink is installed. Never throws: a misbehaving sink is logged
     * and swallowed so debug tooling can never break a stitch.
     */
    static void emit(byte[] bytecode) {
        BiConsumer<String, byte[]> currentSink = sink;
        if (DUMP_TO_LOG == false && currentSink == null) {
            return;
        }
        String name;
        try {
            name = new ClassReader(bytecode).getClassName();
        } catch (RuntimeException e) {
            name = "<unparseable>";
        }
        if (DUMP_TO_LOG) {
            logger.info(
                "fused class [{}] ({} bytes) base64 (decode to a .class, then `javap -c -p`): {}",
                name,
                bytecode.length,
                Base64.getEncoder().encodeToString(bytecode)
            );
        }
        if (currentSink != null) {
            try {
                currentSink.accept(name, bytecode.clone());
            } catch (RuntimeException e) {
                logger.warn("fusion dump sink threw for [{}]; ignoring (debug tooling must not break a stitch)", name, e);
            }
        }
    }
}
