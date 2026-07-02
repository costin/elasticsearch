/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.FusionNode;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.junit.After;
import org.junit.Before;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Cross-module linkage acceptance for the BLOCK path (binding rule #1, block-path counterpart to
 * {@link StitcherCrossModuleTests}). The {@code Stitcher} lives in the named {@code :...:compute} module, but the
 * real {@code Add.processDoubles} kernel body inlines {@code NumericUtils.asFiniteNumber} — a target in
 * {@code org.elasticsearch.xpack.esql.core}, outside {@code java.base}. The block emitter splices that body into a
 * position-by-position loop whose kernel invoke sits under the fused method's try/catch. By passing a lookup from
 * <b>this</b> (esql-proper) module — which owns {@code Add} and reads esql-core (for {@code NumericUtils}) and
 * compute (for the public {@code *Block}/{@code BlockFactory} API the loop calls) — the fused block class links and
 * runs over real breaker-backed {@code DoubleBlock}s carrying nulls and multi-values.
 *
 * <p>This would fail at {@code defineHiddenClass} time with a
 * {@link org.elasticsearch.compute.operator.fusion.Stitcher.StitchingException} wrapping a
 * {@code NoClassDefFoundError}/{@code IllegalAccessError} if the block path could not link a non-{@code java.base}
 * kernel callee from an esql-proper caller. It asserts fused == unfused {@code Add.processDoubles} position-by-position
 * including null positions (nulls/multi-values propagate to null).
 */
public class StitcherBlockCrossModuleTests extends ESTestCase {

    private static final String DOUBLE_BINARY_TYPE = "(DD)D";

    private CircuitBreakerService breakerService;
    private CircuitBreaker breaker;
    private BlockFactory blockFactory;

    @Before
    public void setUpBlockFactory() {
        breakerService = newLimitedBreakerService(ByteSizeValue.ofGb(1));
        breaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
        blockFactory = BlockFactory.builder(new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, breakerService)).build();
    }

    @After
    public void checkBreakerReleased() {
        assertThat(breaker.getUsed(), is(0L));
    }

    public void testFuseRealAddProcessDoublesBlockPathCrossModule() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        // Mirrors the descriptor the annotation processor emits onto AddDoublesEvaluator$Factory.
        FusionDescriptor descriptor = new FusionDescriptor(Add.class, "processDoubles", DOUBLE_BINARY_TYPE, true, true);
        // add(in0, in1) over two DoubleBlocks.
        FusionNode tree = new FusionNode.Kernel(descriptor, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));

        Class<?> fused = stitcher.compileBlockLoop(lookup, tree);
        // The hidden class is defined into this esql-proper test package so it can link NumericUtils under the loop.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodType type = MethodType.methodType(
            DoubleBlock.class,
            BlockFactory.class,
            Warnings[].class,
            int.class,
            DoubleBlock.class,
            DoubleBlock.class
        );
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, type);

        // Reference: the real (package-private) Add.processDoubles reflectively. Add and this test share the module
        // org.elasticsearch.xpack.esql, so setAccessible is permitted.
        Method unfused = Add.class.getDeclaredMethod("processDoubles", double.class, double.class);
        unfused.setAccessible(true);

        int positionCount = 512;
        // Per-position value arrays: length 0 => null, 1 => single, >1 => multi-value (must propagate to null).
        double[][] lhs = new double[positionCount][];
        double[][] rhs = new double[positionCount][];
        for (int p = 0; p < positionCount; p++) {
            lhs[p] = slot();
            rhs[p] = slot();
        }

        DoubleBlock lhsBlock = null, rhsBlock = null, result = null;
        try {
            lhsBlock = buildDoubleBlock(positionCount, lhs);
            rhsBlock = buildDoubleBlock(positionCount, rhs);
            result = (DoubleBlock) handle.invokeWithArguments(blockFactory, noopWarnings(tree), positionCount, lhsBlock, rhsBlock);
            assertThat(result.getPositionCount(), equalTo(positionCount));
            for (int p = 0; p < positionCount; p++) {
                boolean nullPos = lhs[p].length != 1 || rhs[p].length != 1;
                if (nullPos) {
                    assertThat("p=" + p, result.isNull(p), is(true));
                } else {
                    assertThat("p=" + p, result.isNull(p), is(false));
                    double expected = (double) unfused.invoke(null, lhs[p][0], rhs[p][0]);
                    assertThat("p=" + p, result.getDouble(result.getFirstValueIndex(p)), equalTo(expected));
                }
            }
        } finally {
            Releasables.closeExpectNoException(lhsBlock, rhsBlock, result);
        }
    }

    /** A no-op {@code Warnings[]} sized to the tree's warning-source slots — this Add tree cannot overflow here. */
    private static Warnings[] noopWarnings(FusionNode tree) {
        Warnings[] w = new Warnings[Stitcher.warningsSourceCount(tree)];
        Arrays.fill(w, Warnings.NOOP_WARNINGS);
        return w;
    }

    /** A random position payload: ~20% null (0 values), ~10% multi-value (2-3), else a single finite value. */
    private double[] slot() {
        int r = randomIntBetween(0, 9);
        if (r < 2) {
            return new double[0];
        }
        if (r == 2) {
            int n = randomIntBetween(2, 3);
            double[] vv = new double[n];
            for (int i = 0; i < n; i++) {
                vv[i] = randomDouble();
            }
            return vv;
        }
        // randomDouble() is in [0, 1) so lhs + rhs is finite and NumericUtils.asFiniteNumber never throws.
        return new double[] { randomDouble() };
    }

    private DoubleBlock buildDoubleBlock(int positionCount, double[][] values) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                double[] vv = values[p];
                if (vv.length == 0) {
                    builder.appendNull();
                } else if (vv.length == 1) {
                    builder.appendDouble(vv[0]);
                } else {
                    builder.beginPositionEntry();
                    for (double x : vv) {
                        builder.appendDouble(x);
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }
}
