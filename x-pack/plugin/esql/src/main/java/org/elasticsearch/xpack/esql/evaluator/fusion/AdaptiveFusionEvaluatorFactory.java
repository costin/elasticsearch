/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.function.Supplier;

/**
 * An {@link ExpressionEvaluator.Factory} that serves a fusable subtree <b>adaptively</b>: every evaluator it hands out
 * starts on the ordinary unfused chain and only switches to a stitched fused evaluator once it has processed at least
 * {@code minRows} rows. This is operator-level tiered compilation — the analogue of the JVM running in the interpreter
 * until a method is hot enough to JIT — gated by {@code esql.fusion.adaptive.min_rows} (see {@link FusionSettings}).
 *
 * <h2>Why defer the stitch</h2>
 * Stitching + {@code defineHiddenClass} + JVM verification is a fixed cold-path cost that only pays off when it is
 * amortised over many rows. A one-page/one-row query would pay the whole cost and evaluate almost nothing through the
 * fused loop. By running unfused until the threshold, cold and tiny queries never pay to stitch; only data-heavy
 * operators — where fusion actually helps — cross the threshold and switch. The stitch itself is deferred behind
 * {@code fusedSupplier} and memoised (once per plan; the process-wide {@link FusedClassCache} dedupes across plans),
 * so the first evaluator to cross the threshold pays the stitch and every later one reuses the cached class.
 *
 * <h2>Additivity &amp; fallback</h2>
 * The unfused chain is always built (it is the starting evaluator and the permanent fallback). If the deferred stitch
 * fails, {@code fusedSupplier} yields {@code null}; the evaluator records the miss ({@link FusionTelemetry}) and stays
 * unfused for the rest of its life — never breaking the query. When {@code minRows == 0} the planner does not use this
 * factory at all; it stitches eagerly at plan time exactly as before.
 */
final class AdaptiveFusionEvaluatorFactory implements ExpressionEvaluator.Factory {

    private static final Logger logger = LogManager.getLogger(AdaptiveFusionEvaluatorFactory.class);

    private final ExpressionEvaluator.Factory unfused;
    /** Supplies the fused factory on first demand, or {@code null} if the deferred stitch failed. Memoised here. */
    private final Supplier<ExpressionEvaluator.Factory> fusedSupplier;
    private final long minRows;
    private final String shape;

    // Memoised single stitch attempt shared by every driver's evaluator: resolved==false until the first threshold
    // crossing anywhere, then resolvedFused holds the fused factory (or null if the stitch failed). volatile for the
    // lock-free fast read; the one-time resolution is serialised on this monitor.
    private volatile boolean resolved;
    private volatile ExpressionEvaluator.Factory resolvedFused;

    AdaptiveFusionEvaluatorFactory(
        ExpressionEvaluator.Factory unfused,
        Supplier<ExpressionEvaluator.Factory> fusedSupplier,
        long minRows,
        String shape
    ) {
        assert minRows > 0 : "adaptive factory requires a positive row threshold; minRows=" + minRows;
        this.unfused = unfused;
        this.fusedSupplier = fusedSupplier;
        this.minRows = minRows;
        this.shape = shape;
    }

    /** Resolves (once) and returns the fused factory, or {@code null} if the deferred stitch failed. */
    private ExpressionEvaluator.Factory resolveFused() {
        if (resolved == false) {
            synchronized (this) {
                if (resolved == false) {
                    resolvedFused = fusedSupplier.get();
                    resolved = true;
                }
            }
        }
        return resolvedFused;
    }

    @Override
    public ExpressionEvaluator get(DriverContext context) {
        return new AdaptiveFusionEvaluator(unfused.get(context), this::resolveFused, context, minRows, shape);
    }

    @Override
    public boolean eagerEvalSafeInLazy() {
        // The adaptive evaluator computes the exact same expression as the unfused chain, so it inherits its lazy-eval
        // safety (relevant to CASE-style short-circuiting).
        return unfused.eagerEvalSafeInLazy();
    }

    @Override
    public String toString() {
        return "Adaptive[minRows=" + minRows + ", shape=" + shape + ", unfused=" + unfused + "]";
    }

    /**
     * The runtime evaluator. Delegates to the unfused chain, counting rows, until the {@code minRows} threshold is
     * crossed; it then resolves the fused factory (paying the deferred stitch once) and switches to a fused evaluator
     * for the current and all subsequent pages. A failed deferred stitch latches fusion off so it stays unfused.
     */
    private static final class AdaptiveFusionEvaluator implements ExpressionEvaluator {

        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(AdaptiveFusionEvaluator.class);

        private final ExpressionEvaluator unfused;
        private final Supplier<ExpressionEvaluator.Factory> resolveFused;
        private final DriverContext driverContext;
        private final long minRows;
        private final String shape;

        private long rowsSeen;
        // Non-null once the threshold is crossed and the stitch succeeded; from then on every page routes here.
        private ExpressionEvaluator fused;
        // Latched true if the threshold was crossed but the deferred stitch failed: stay unfused, don't retry.
        private boolean fusionAbandoned;

        AdaptiveFusionEvaluator(
            ExpressionEvaluator unfused,
            Supplier<ExpressionEvaluator.Factory> resolveFused,
            DriverContext driverContext,
            long minRows,
            String shape
        ) {
            this.unfused = unfused;
            this.resolveFused = resolveFused;
            this.driverContext = driverContext;
            this.minRows = minRows;
            this.shape = shape;
        }

        @Override
        public Block eval(Page page) {
            if (fused == null && fusionAbandoned == false) {
                rowsSeen += page.getPositionCount();
                if (rowsSeen >= minRows) {
                    ExpressionEvaluator.Factory fusedFactory = resolveFused.get();
                    if (fusedFactory == null) {
                        // The deferred stitch failed (already logged + counted by tryCreateFusedOrNull). Stay unfused.
                        fusionAbandoned = true;
                        FusionTelemetry.recordAdaptiveAbandoned();
                        logger.debug("adaptive fusion abandoned for shape [{}] after threshold; staying unfused", shape);
                    } else {
                        fused = fusedFactory.get(driverContext);
                        FusionTelemetry.recordAdaptiveSwitch();
                        logger.debug("adaptive fusion switched to fused evaluator for shape [{}] after [{}] rows", shape, rowsSeen);
                    }
                }
            }
            return (fused != null ? fused : unfused).eval(page);
        }

        @Override
        public long baseRamBytesUsed() {
            long ram = BASE_RAM_BYTES_USED + unfused.baseRamBytesUsed();
            if (fused != null) {
                ram += fused.baseRamBytesUsed();
            }
            return ram;
        }

        @Override
        public void close() {
            // Both evaluators are kept alive for the driver's life (the unfused chain is not closed at switch time), so
            // release both here.
            org.elasticsearch.core.Releasables.closeExpectNoException(unfused, fused);
        }

        @Override
        public String toString() {
            return "Adaptive[shape=" + shape + ", switched=" + (fused != null) + "]";
        }
    }
}
