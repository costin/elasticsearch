/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.OrdinalBytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * C2 verify-mode A/B: when {@link FusionSettings#isVerifyEnabled()} the fused evaluators evaluate the unfused
 * fallback on the same page and fail on any difference. This class holds the comparison, encoding check, and
 * mismatch dump. The hot path in production is a single {@code static final} read of {@link FusionSettings#isVerifyEnabled()}
 * that the JIT folds away; nothing here is reached without {@code -ea} (or {@code -Desql.fusion.verify=true}).
 *
 * <h2>Typed equality</h2>
 * Long/int/boolean/BytesRef use the compute-data {@code *Block.equals} contracts (positions, nulls, multi-values,
 * values, encoding-independent). {@link DoubleBlock#equals(DoubleBlock, DoubleBlock)} is <b>not</b> used: it compares
 * with {@code !=}, so {@code NaN} never equals {@code NaN} and {@code -0.0} equals {@code +0.0}. Fusion kernels
 * preserve IEEE bits (existing differentials already pin {@code doubleToLongBits}); verify walks doubles with
 * {@link Double#doubleToLongBits} so a NaN vs finite (and sign-of-zero) divergence is a test failure.
 * {@code doubleToLongBits} canonicalizes NaN payloads — two NaNs compare equal, matching the existing
 * differentials; we do not use {@code doubleToRawLongBits}.
 *
 * <h2>Encoding</h2>
 * If the unfused block is ordinal-encoded ({@link BytesRefBlock#asOrdinals()} / {@link BytesRefVector#asOrdinals()} /
 * {@link OrdinalBytesRefVector}) or a constant ({@link Vector#isConstant()} on any typed {@code Constant*Vector},
 * including numeric) and the fused block is not, {@link #describeMismatch} reports lost encoding even when values
 * match. A constant fused output is a <b>stronger</b> encoding than ordinal, so constant-fused vs ordinal-unfused is
 * accepted (not a mismatch). The verify hot path ({@link #compareAndReturn}) still fails BytesRef ordinal/constant
 * loss (the 448× dictionary fast path) but does <b>not</b> fail numeric {@code Constant*Vector} vs a value-equal
 * array: fused arithmetic always writes an array vector (see
 * {@code FusionDifferentialTests#testCheckedVectorNoOverflowPageReturnsLongArrayVector}).
 *
 * <h2>Warnings</h2>
 * {@link org.elasticsearch.common.logging.HeaderWarning#addWarning} stores headers via
 * {@link org.elasticsearch.common.util.concurrent.ThreadContext#addResponseHeader}, which <b>deduplicates identical
 * values</b> ("Any duplicate value is ignored."). {@code Warnings.registerException} even documents that location is
 * prepended "since the headers are deduplicated". Identical texts therefore collapse, but the ratified fused⊆unfused
 * <b>subset</b> means the union is the unfused set: running the unfused fallback into the driver's collector would
 * add short-circuit-dropped warnings and break CsvIT / differential assertions that the fused path did <em>not</em>
 * emit a warning. Verify therefore evaluates the unfused fallback through
 * {@link org.elasticsearch.compute.operator.DriverContext#ignoringWarnings()} ({@code Warnings.NOOP_WARNINGS}) — the
 * narrowest existing seam, not a new warnings framework. Warning parity stays with the existing differential tests.
 *
 * <h2>Mismatch dump</h2>
 * Hidden-class bytes captured at stitch time (via
 * {@link org.elasticsearch.compute.operator.fusion.FusionDump#beginThreadCapture}) are written to
 * {@code ${java.io.tmpdir}/esql-fusion-dumps/<shape-hash>.class} for {@code javap -c -p} when class bytes were
 * captured, or {@code .txt} explaining the miss when they were not.
 */
final class FusionVerify {

    /**
     * In-process seam for the one unit test that must observe eval with comparison forced off. Production never
     * reads this: it lives behind {@code if (FusionSettings.isVerifyEnabled())}, which is {@code static final false}
     * without {@code -ea}. Prefer {@code -Desql.fusion.verify=false} to disable for a whole test JVM.
     */
    static volatile boolean skipForTests;

    /**
     * Shape → class bytes captured at stitch (block then vector, in compile order). Bounded LRU so a long verify-mode
     * JVM cannot retain every stitched shape unbounded; {@link #classBytesForTests} / {@link #bytesFor} still see the
     * most recently recorded shapes.
     */
    private static final int MAX_CAPTURED_SHAPES = 256;

    @SuppressWarnings("serial")
    private static final Map<String, byte[][]> BYTES_BY_SHAPE = Collections.synchronizedMap(
        new LinkedHashMap<>(MAX_CAPTURED_SHAPES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[][]> eldest) {
                return size() > MAX_CAPTURED_SHAPES;
            }
        }
    );

    /**
     * Test-only override of the mismatch-dump directory. Production never sets this; tests point it at an unwritable
     * path (e.g. a regular file) so dump failure is deterministic.
     */
    static volatile Path dumpDirectoryForTests;

    private FusionVerify() {}

    /**
     * Evaluates {@code unfused} on {@code page}, compares with {@code fused}, closes the unfused block, and returns
     * {@code fused}. On mismatch dumps the hidden class, closes <b>both</b> blocks, and throws {@link AssertionError}.
     */
    static Block verifyPage(ExpressionEvaluator unfused, Page page, Block fused, String shape, VectorStrategy strategy, LastEvalPath path) {
        if (skipForTests) {
            return fused;
        }
        Block reference;
        try {
            reference = unfused.eval(page);
        } catch (Throwable t) {
            Releasables.closeExpectNoException(fused);
            throw t;
        }
        return compareAndReturn(fused, reference, shape, strategy == null ? "?" : strategy.name(), path == null ? "BLOCK" : path.name());
    }

    /**
     * Compares {@code fused} against {@code unfused}, closes {@code unfused} on success (returning {@code fused}),
     * and on mismatch dumps bytes, closes both, and throws.
     */
    static Block compareAndReturn(Block fused, Block unfused, String shape, String strategy, String path) {
        String mismatch;
        try {
            // Numeric Constant*Vector vs array is reported by describeMismatch (unit tests) but is not a verify
            // failure: fused arithmetic writes arrays by design. BytesRef ordinal/constant loss still fails here.
            mismatch = describeMismatch(fused, unfused, false);
        } catch (Throwable t) {
            // describeMismatch / valueAt must not leak either block (e.g. utf8ToString AssertionError on IP/VERSION).
            Releasables.closeExpectNoException(fused, unfused);
            throw t;
        }
        if (mismatch == null) {
            // Values/encoding match: release the comparison block and return the fused result as the real output.
            Releasables.closeExpectNoException(unfused);
            return fused;
        }
        return failMismatch(fused, unfused, shape, strategy, path, mismatch);
    }

    /**
     * Filter-eval A/B: compare survivor indices then projection blocks. Restores {@code fusedSurvivors} into
     * {@code positions} on every path so the operator filters with fused indices. Closes both blocks on mismatch
     * or any unexpected failure (the restore must not leak if {@code fusedCount} exceeds {@code positions.length}).
     */
    static Block compareFilterEvalAndReturn(
        Block fused,
        Block unfused,
        int[] fusedSurvivors,
        int fusedCount,
        int[] positions,
        String shape
    ) {
        try {
            String survivorMismatch = describeSurvivorMismatch(fusedSurvivors, fusedCount, positions, unfused.getPositionCount());
            if (survivorMismatch != null) {
                return failMismatch(fused, unfused, shape, "FILTER_EVAL", "BLOCK", survivorMismatch);
            }
        } catch (Throwable t) {
            // describeSurvivorMismatch never throws AssertionError; the only AE out of this try is the already-released
            // one from failMismatch, so the double-close guard (`t instanceof AssertionError == false`) relies on that.
            if (t instanceof AssertionError == false) {
                Releasables.closeExpectNoException(fused, unfused);
            }
            throw t;
        } finally {
            restoreSurvivors(fusedSurvivors, fusedCount, positions);
        }
        return compareAndReturn(fused, unfused, shape, "FILTER_EVAL", "BLOCK");
    }

    /**
     * Dumps the fused class, closes both blocks, and throws. Shared by value/encoding and survivor-index mismatches
     * so the breaker-to-zero contract is identical.
     */
    static Block failMismatch(Block fused, Block unfused, String shape, String strategy, String path, String mismatch) {
        String dumpNote;
        Throwable dumpFailure = null;
        try {
            Path dump = dumpOnMismatch(shape, path);
            dumpNote = "dumped to " + dump + " (javap -c -p)";
        } catch (Throwable t) {
            dumpFailure = t;
            dumpNote = "dump failed: " + t;
        } finally {
            // Mismatch: release both so the breaker returns to zero even though eval throws (the operator never sees
            // a result to close), including when the dump itself throws. Do not double-release on the success path —
            // fused is handed to the caller.
            Releasables.closeExpectNoException(fused, unfused);
        }
        throw new AssertionError(
            "fused vs unfused mismatch: shape=" + shape + ", strategy=" + strategy + ", path=" + path + ", " + mismatch + "; " + dumpNote,
            dumpFailure
        );
    }

    /**
     * Copies fused survivors back into {@code positions} without throwing, even if {@code fusedCount} exceeds
     * the array length (that case is already a survivor mismatch).
     */
    private static void restoreSurvivors(int[] fusedSurvivors, int fusedCount, int[] positions) {
        int n = Math.min(fusedCount, Math.min(fusedSurvivors.length, positions.length));
        if (n > 0) {
            System.arraycopy(fusedSurvivors, 0, positions, 0, n);
        }
    }

    /**
     * Test-only reporting variant ({@code numericConstantEncoding=true}) for unit coverage. Not on any production
     * path — production uses the 3-arg {@code false} form in {@link #compareAndReturn}.
     */
    static String describeMismatch(Block fused, Block unfused) {
        return describeMismatch(fused, unfused, true);
    }

    /**
     * @param numericConstantEncoding {@code true} to report numeric {@code Constant*Vector} vs array as lost encoding
     *                                ({@link #describeMismatch(Block, Block)}); {@code false} for the verify hot path,
     *                                which still flags BytesRef constant/ordinal loss.
     */
    static String describeMismatch(Block fused, Block unfused, boolean numericConstantEncoding) {
        if (fused == unfused) {
            return null;
        }
        int fusedCount = fused.getPositionCount();
        int unfusedCount = unfused.getPositionCount();
        boolean unfusedOrdinal = isOrdinalEncoded(unfused);
        boolean unfusedConstant = isConstantEncoded(unfused);
        boolean fusedOrdinal = isOrdinalEncoded(fused);
        boolean fusedConstant = isConstantEncoded(fused);
        // A constant fused output is stronger than ordinal, so do not flag constant-fused vs ordinal-unfused as lost.
        if (unfusedOrdinal && fusedOrdinal == false && fusedConstant == false) {
            return encodingMismatch("ordinal", fusedCount, fused, unfused);
        }
        if (unfusedConstant && fusedConstant == false) {
            boolean bytesRef = fused instanceof BytesRefBlock || unfused instanceof BytesRefBlock;
            if (numericConstantEncoding || bytesRef) {
                return encodingMismatch("constant", fusedCount, fused, unfused);
            }
        }
        if (fusedCount != unfusedCount) {
            int p = firstDiffPosition(fused, unfused);
            return "positionCount="
                + fusedCount
                + " vs "
                + unfusedCount
                + ", firstDiff@"
                + p
                + " fused="
                + valueAt(fused, p)
                + " unfused="
                + valueAt(unfused, p);
        }
        if (valuesEqual(fused, unfused) == false) {
            int p = firstDiffPosition(fused, unfused);
            return "positionCount=" + fusedCount + ", firstDiff@" + p + " fused=" + valueAt(fused, p) + " unfused=" + valueAt(unfused, p);
        }
        return null;
    }

    /**
     * Compares fused vs unfused Filter-eval survivor indices. {@code null} if both counts and
     * {@code [0..count)} values match. Does not throw if {@code fusedCount} exceeds array length.
     */
    static String describeSurvivorMismatch(int[] fusedSurvivors, int fusedCount, int[] unfusedPositions, int unfusedCount) {
        int fusedBound = Math.min(Math.max(fusedCount, 0), fusedSurvivors.length);
        int unfusedBound = Math.min(Math.max(unfusedCount, 0), unfusedPositions.length);
        int n = Math.min(fusedBound, unfusedBound);
        int firstDiff = -1;
        for (int p = 0; p < n; p++) {
            if (fusedSurvivors[p] != unfusedPositions[p]) {
                firstDiff = p;
                break;
            }
        }
        if (firstDiff < 0 && fusedCount != unfusedCount) {
            firstDiff = n;
        }
        if (firstDiff < 0 && fusedCount > fusedSurvivors.length) {
            firstDiff = fusedSurvivors.length;
        }
        if (firstDiff < 0) {
            return null;
        }
        return "survivorCount="
            + fusedCount
            + " vs "
            + unfusedCount
            + ", firstDiff@"
            + firstDiff
            + " fused="
            + survivorAt(fusedSurvivors, fusedBound, firstDiff)
            + " unfused="
            + survivorAt(unfusedPositions, unfusedBound, firstDiff);
    }

    private static String survivorAt(int[] indices, int bound, int p) {
        if (p < 0 || p >= bound) {
            return "<out-of-range>";
        }
        return Integer.toString(indices[p]);
    }

    private static String encodingMismatch(String kind, int positionCount, Block fused, Block unfused) {
        int p = firstDiffPosition(fused, unfused);
        String values = p >= 0
            ? (", firstDiff@" + p + " fused=" + valueAt(fused, p) + " unfused=" + valueAt(unfused, p))
            : ", values equal";
        return "positionCount=" + positionCount + ", unfused is " + kind + "-encoded but fused is not (lost encoding fast path)" + values;
    }

    /**
     * Ordinal encoding predicate used by the unfused BytesRef fast path
     * ({@link BytesRefBlock#asOrdinals()}, {@link BytesRefVector#asOrdinals()}, {@link OrdinalBytesRefVector}).
     */
    static boolean isOrdinalEncoded(Block block) {
        if (block instanceof BytesRefBlock bytes) {
            if (bytes.asOrdinals() != null) {
                return true;
            }
            BytesRefVector vector = bytes.asVector();
            return vector != null && vector.asOrdinals() != null;
        }
        Vector vector = block.asVector();
        return vector instanceof OrdinalBytesRefVector;
    }

    /**
     * Constant-encoding predicate ({@link Vector#isConstant()}): the unfused mapping / constant-input short-circuit,
     * covering numeric {@code Constant*Vector} as well as {@code ConstantBytesRefVector}.
     */
    static boolean isConstantEncoded(Block block) {
        Vector vector = block.asVector();
        return vector != null && vector.isConstant();
    }

    /**
     * Value equality independent of encoding. Doubles use {@link Double#doubleToLongBits} rather than
     * {@link DoubleBlock#equals(DoubleBlock, DoubleBlock)} (see class Javadoc).
     */
    static boolean valuesEqual(Block fused, Block unfused) {
        if (fused instanceof DoubleBlock fusedDouble && unfused instanceof DoubleBlock unfusedDouble) {
            return doublesEqual(fusedDouble, unfusedDouble);
        }
        if (fused instanceof LongBlock fusedLong && unfused instanceof LongBlock unfusedLong) {
            return LongBlock.equals(fusedLong, unfusedLong);
        }
        if (fused instanceof IntBlock fusedInt && unfused instanceof IntBlock unfusedInt) {
            return IntBlock.equals(fusedInt, unfusedInt);
        }
        if (fused instanceof BooleanBlock fusedBoolean && unfused instanceof BooleanBlock unfusedBoolean) {
            return BooleanBlock.equals(fusedBoolean, unfusedBoolean);
        }
        if (fused instanceof BytesRefBlock fusedBytes && unfused instanceof BytesRefBlock unfusedBytes) {
            return BytesRefBlock.equals(fusedBytes, unfusedBytes);
        }
        return fused.getClass() == unfused.getClass() && fused.equals(unfused);
    }

    /**
     * NaN vs finite / {@code -0.0}-aware double equality. {@code DoubleBlock.equals} uses {@code !=}, which reports
     * two NaNs as unequal and {@code -0.0} as equal to {@code +0.0}. We use {@link Double#doubleToLongBits} (not
     * {@code doubleToRawLongBits}): two NaNs compare equal (payloads canonicalized), NaN vs finite and sign-of-zero
     * diverge — matching the existing differentials.
     */
    static boolean doublesEqual(DoubleBlock a, DoubleBlock b) {
        int positions = a.getPositionCount();
        if (positions != b.getPositionCount()) {
            return false;
        }
        for (int p = 0; p < positions; p++) {
            if (doublePositionDiffers(a, b, p)) {
                return false;
            }
        }
        return true;
    }

    static int firstDiffPosition(Block fused, Block unfused) {
        int n = Math.min(fused.getPositionCount(), unfused.getPositionCount());
        for (int p = 0; p < n; p++) {
            if (positionDiffers(fused, unfused, p)) {
                return p;
            }
        }
        if (fused.getPositionCount() != unfused.getPositionCount()) {
            return n;
        }
        return -1;
    }

    private static boolean positionDiffers(Block fused, Block unfused, int p) {
        if (p >= fused.getPositionCount() || p >= unfused.getPositionCount()) {
            return true;
        }
        if (fused instanceof DoubleBlock fusedDouble && unfused instanceof DoubleBlock unfusedDouble) {
            return doublePositionDiffers(fusedDouble, unfusedDouble, p);
        }
        if (fused.isNull(p) != unfused.isNull(p)) {
            return true;
        }
        if (fused.isNull(p)) {
            return false;
        }
        int fusedCount = fused.getValueCount(p);
        if (fusedCount != unfused.getValueCount(p)) {
            return true;
        }
        int fusedFirst = fused.getFirstValueIndex(p);
        int unfusedFirst = unfused.getFirstValueIndex(p);
        if (fused instanceof LongBlock fusedLong && unfused instanceof LongBlock unfusedLong) {
            for (int v = 0; v < fusedCount; v++) {
                if (fusedLong.getLong(fusedFirst + v) != unfusedLong.getLong(unfusedFirst + v)) {
                    return true;
                }
            }
            return false;
        }
        if (fused instanceof IntBlock fusedInt && unfused instanceof IntBlock unfusedInt) {
            for (int v = 0; v < fusedCount; v++) {
                if (fusedInt.getInt(fusedFirst + v) != unfusedInt.getInt(unfusedFirst + v)) {
                    return true;
                }
            }
            return false;
        }
        if (fused instanceof BooleanBlock fusedBoolean && unfused instanceof BooleanBlock unfusedBoolean) {
            for (int v = 0; v < fusedCount; v++) {
                if (fusedBoolean.getBoolean(fusedFirst + v) != unfusedBoolean.getBoolean(unfusedFirst + v)) {
                    return true;
                }
            }
            return false;
        }
        if (fused instanceof BytesRefBlock fusedBytes && unfused instanceof BytesRefBlock unfusedBytes) {
            BytesRef fs = new BytesRef();
            BytesRef rs = new BytesRef();
            for (int v = 0; v < fusedCount; v++) {
                if (fusedBytes.getBytesRef(fusedFirst + v, fs).equals(unfusedBytes.getBytesRef(unfusedFirst + v, rs)) == false) {
                    return true;
                }
            }
            return false;
        }
        return fused.equals(unfused) == false;
    }

    private static boolean doublePositionDiffers(DoubleBlock a, DoubleBlock b, int p) {
        if (a.isNull(p) != b.isNull(p)) {
            return true;
        }
        if (a.isNull(p)) {
            return false;
        }
        int count = a.getValueCount(p);
        if (count != b.getValueCount(p)) {
            return true;
        }
        int aFirst = a.getFirstValueIndex(p);
        int bFirst = b.getFirstValueIndex(p);
        for (int v = 0; v < count; v++) {
            if (Double.doubleToLongBits(a.getDouble(aFirst + v)) != Double.doubleToLongBits(b.getDouble(bFirst + v))) {
                return true;
            }
        }
        return false;
    }

    static String valueAt(Block block, int p) {
        if (p < 0 || p >= block.getPositionCount()) {
            return "<out-of-range>";
        }
        if (block.isNull(p)) {
            return "null";
        }
        int count = block.getValueCount(p);
        int first = block.getFirstValueIndex(p);
        if (block instanceof LongBlock longs) {
            return formatValues(count, v -> Long.toString(longs.getLong(first + v)));
        }
        if (block instanceof IntBlock ints) {
            return formatValues(count, v -> Integer.toString(ints.getInt(first + v)));
        }
        if (block instanceof DoubleBlock doubles) {
            return formatValues(count, v -> {
                double d = doubles.getDouble(first + v);
                return d + "/bits=0x" + Long.toHexString(Double.doubleToLongBits(d));
            });
        }
        if (block instanceof BooleanBlock booleans) {
            return formatValues(count, v -> Boolean.toString(booleans.getBoolean(first + v)));
        }
        if (block instanceof BytesRefBlock bytes) {
            BytesRef scratch = new BytesRef();
            // Hex form ([ff fe]): utf8ToString() asserts on IP/VERSION / other non-UTF-8 payloads and would hide the
            // real mismatch while leaking both blocks.
            return formatValues(count, v -> bytes.getBytesRef(first + v, scratch).toString());
        }
        return block.getClass().getSimpleName();
    }

    private static String formatValues(int count, IntFunction<String> value) {
        if (count == 1) {
            return value.apply(0);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int v = 0; v < count; v++) {
            if (v > 0) {
                sb.append(',');
            }
            sb.append(value.apply(v));
        }
        return sb.append(']').toString();
    }

    static void recordClassBytes(String shape, List<byte[]> captured) {
        if (captured == null || captured.isEmpty()) {
            return;
        }
        BYTES_BY_SHAPE.put(shape, captured.toArray(byte[][]::new));
    }

    /**
     * Test-only: class bytes captured at stitch for {@code shape} (block then vector, in compile order), or
     * {@code null} if none. Used to assert a DOUBLE divisor is a trailing parameter rather than an {@code LDC2_W}.
     */
    static byte[][] classBytesForTests(String shape) {
        return BYTES_BY_SHAPE.get(shape);
    }

    static Path dumpPath(String shape) {
        return dumpFile(shape, ".class");
    }

    private static Path dumpDirectory() {
        Path override = dumpDirectoryForTests;
        if (override != null) {
            return override;
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "esql-fusion-dumps");
    }

    private static Path dumpFile(String shape, String extension) {
        String hash = Integer.toUnsignedString(shape.hashCode(), 16);
        return dumpDirectory().resolve(hash + extension);
    }

    static Path dumpOnMismatch(String shape, String path) throws IOException {
        byte[] bytes = bytesFor(shape, path);
        boolean hasBytes = bytes != null && bytes.length > 0;
        Path file = dumpFile(shape, hasBytes ? ".class" : ".txt");
        Files.createDirectories(file.getParent());
        if (hasBytes) {
            Files.write(file, bytes);
        } else {
            Files.writeString(file, "no fused class bytes captured for shape hash " + file.getFileName());
        }
        return file;
    }

    private static byte[] bytesFor(String shape, String path) {
        byte[][] all = BYTES_BY_SHAPE.get(shape);
        if (all == null || all.length == 0) {
            return null;
        }
        if (all.length == 1) {
            return all[0];
        }
        // Compile order is block then vector. RAW/INTERFACE used the vector class (last); BLOCK used the first.
        if ("RAW".equals(path) || "INTERFACE".equals(path)) {
            return all[all.length - 1];
        }
        return all[0];
    }

}
