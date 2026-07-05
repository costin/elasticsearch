/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.compute.operator.fusion.Stitcher;
import org.elasticsearch.compute.operator.fusion.TemplateRegistry;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static org.hamcrest.Matchers.equalTo;

/**
 * Cross-module linkage acceptance for binding rule #1. The {@code Stitcher} lives in the named
 * {@code :...:compute} module, but the real {@code Add.processDoubles} kernel body inlines
 * {@code NumericUtils.asFiniteNumber} — a target in {@code org.elasticsearch.xpack.esql.core}, outside
 * {@code java.base}. If the fused hidden class were defined with {@code :compute}'s own lookup it could not link
 * that call. By passing a lookup from <b>this</b> (esql-proper) module — the module that owns {@code Add} and can
 * read esql-core — the fused class links and runs. This test would fail at {@code defineHiddenClass} time with a
 * {@link org.elasticsearch.compute.operator.fusion.Stitcher.StitchingException} wrapping a
 * {@code NoClassDefFoundError}/{@code IllegalAccessError} if the caller-lookup contract regressed.
 */
public class StitcherCrossModuleTests extends ESTestCase {

    private static final String DOUBLE_BINARY_TYPE = "(DD)D";
    private static final MethodType DOUBLE_BINARY = MethodType.methodType(double.class, double.class, double.class);

    public void testFuseRealAddProcessDoublesCrossModule() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        // Mirrors the descriptor the annotation processor emits onto AddDoublesEvaluator$Factory.
        FusionDescriptor descriptor = new FusionDescriptor(Add.class, "processDoubles", DOUBLE_BINARY_TYPE, true, true);

        Class<?> fused = stitcher.compile(lookup, descriptor);

        // The hidden class is defined into this esql-proper test module/package so it can link NumericUtils.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, DOUBLE_BINARY);

        // Reference: invoke the real (package-private) Add.processDoubles reflectively. Add and this test share the
        // module org.elasticsearch.xpack.esql, so setAccessible is permitted regardless of package.
        Method unfused = Add.class.getDeclaredMethod("processDoubles", double.class, double.class);
        unfused.setAccessible(true);

        for (int i = 0; i < 100; i++) {
            double lhs = randomDouble();
            double rhs = randomDouble();
            double fusedResult = (double) handle.invokeExact(lhs, rhs);
            double expected = (double) unfused.invoke(null, lhs, rhs);
            assertThat("lhs=" + lhs + " rhs=" + rhs, fusedResult, equalTo(expected));
        }
    }
}
