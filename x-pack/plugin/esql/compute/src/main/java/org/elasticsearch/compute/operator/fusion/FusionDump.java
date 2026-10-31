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
import java.util.List;
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
 *   <li><b>Thread-local capture.</b> {@link #beginThreadCapture} / {@link #endThreadCapture} collect a private copy
 *       of every class this thread defines, used by verify-mode mismatch dumps without racing the process-wide
 *       sink.</li>
 * </ul>
 *
 * <p>All channels run only on the cold plan-time stitch path (once per fused class), never per row, so they add
 * nothing to the hot loop. When no channel is active {@link #emit} is a single static-boolean + volatile +
 * thread-local read.
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

    /** Per-thread capture list (verify-mode dumps); {@code null} = none. */
    private static final ThreadLocal<List<byte[]>> THREAD_CAPTURE = new ThreadLocal<>();

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
     * Starts collecting a private copy of every fused class this thread defines into {@code into}, until
     * {@link #endThreadCapture()}. Does not disturb the process-wide {@link #setSink sink}.
     */
    public static void beginThreadCapture(List<byte[]> into) {
        THREAD_CAPTURE.set(into);
    }

    /** Stops the current thread's {@link #beginThreadCapture capture}. */
    public static void endThreadCapture() {
        THREAD_CAPTURE.remove();
    }

    /**
     * Offers a freshly generated fused class to the active debug channels. Inactive fast path is one static-boolean
     * read + one volatile sink read + one thread-local capture read (cold stitch path only, never per row). Never
     * throws: a misbehaving sink or capture list is logged and swallowed so debug tooling can never break a stitch.
     */
    static void emit(byte[] bytecode) {
        BiConsumer<String, byte[]> currentSink = sink;
        List<byte[]> capture = THREAD_CAPTURE.get();
        if (DUMP_TO_LOG == false && currentSink == null && capture == null) {
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
        if (capture != null) {
            // Thread-local capture is exclusive of the process-wide sink so verify-mode dumps cannot race a
            // profiler/test sink (beginThreadCapture does not replace the sink; emit simply does not invoke it).
            try {
                capture.add(bytecode.clone());
            } catch (RuntimeException e) {
                logger.warn("fusion dump capture threw for [{}]; ignoring (debug tooling must not break a stitch)", name, e);
            }
        } else if (currentSink != null) {
            try {
                currentSink.accept(name, bytecode.clone());
            } catch (RuntimeException e) {
                logger.warn("fusion dump sink threw for [{}]; ignoring (debug tooling must not break a stitch)", name, e);
            }
        }
    }
}
