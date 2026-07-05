/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionAware;
import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Neg;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class ArithmeticFactoryFusionAwareTests extends ESTestCase {

    /**
     * Spot-checks that the annotation processor wired {@code FusionAware} + a populated
     * {@code FUSION_DESCRIPTOR} field onto a representative subset of generated
     * {@code *Evaluator$Factory} classes. The targeted Factory classes are loaded by
     * fully-qualified inner-class name because they are package-private and cannot be
     * imported. The presence of these 5 cases (long+overflow, double+overflow, int+overflow,
     * unary long, unary double w/o overflow) is sufficient to demonstrate that the iter 3
     * annotation processor wiring works end-to-end against the iter 5 kernel annotations.
     */

    public void testAddLongsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.AddLongsEvaluator$Factory",
            Add.class,
            "processLongs",
            "(JJ)J",
            true
        );
    }

    public void testAddDoublesFactoryIsFusionAware() throws Exception {
        // Add.processDoubles uses NumericUtils.asFiniteNumber → throws ArithmeticException on NaN/Inf,
        // so overflowChecked=true is the correct propagation.
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.AddDoublesEvaluator$Factory",
            Add.class,
            "processDoubles",
            "(DD)D",
            true
        );
    }

    public void testMulIntsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.MulIntsEvaluator$Factory",
            Mul.class,
            "processInts",
            "(II)I",
            true
        );
    }

    public void testNegLongsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.NegLongsEvaluator$Factory",
            Neg.class,
            "processLongs",
            "(J)J",
            true
        );
    }

    public void testAbsDoubleFactoryIsFusionAware() throws Exception {
        // Math.abs(double) never throws (NaN/Inf pass through); overflowChecked=false.
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.function.scalar.math.AbsDoubleEvaluator$Factory",
            Abs.class,
            "process",
            "(D)D",
            false
        );
    }

    private static void assertFusionDescriptor(
        String factoryFqn,
        Class<?> expectedKernelClass,
        String expectedKernelMethod,
        String expectedKernelType,
        boolean expectedOverflowChecked
    ) throws Exception {
        Class<?> factory = Class.forName(factoryFqn);

        boolean implementsFusionAware = false;
        for (Class<?> iface : factory.getInterfaces()) {
            if (iface == FusionAware.class) {
                implementsFusionAware = true;
                break;
            }
        }
        assertTrue(factoryFqn + " must implement FusionAware", implementsFusionAware);

        Field field = factory.getDeclaredField("FUSION_DESCRIPTOR");
        int mods = field.getModifiers();
        assertTrue(factoryFqn + ".FUSION_DESCRIPTOR must be private", Modifier.isPrivate(mods));
        assertTrue(factoryFqn + ".FUSION_DESCRIPTOR must be static", Modifier.isStatic(mods));
        assertTrue(factoryFqn + ".FUSION_DESCRIPTOR must be final", Modifier.isFinal(mods));
        assertEquals(FusionDescriptor.class, field.getType());

        field.setAccessible(true);
        FusionDescriptor descriptor = (FusionDescriptor) field.get(null);
        assertNotNull(factoryFqn + ".FUSION_DESCRIPTOR must be non-null", descriptor);
        assertEquals(expectedKernelClass, descriptor.kernelClass());
        assertEquals(expectedKernelMethod, descriptor.kernelMethod());
        assertEquals(expectedKernelType, descriptor.kernelType());
        assertEquals(expectedOverflowChecked, descriptor.overflowChecked());
        // binding rule #3: overflow-checked kernels declare the exception type (ArithmeticException) the stitcher
        // must catch; non-overflow kernels carry the empty string.
        assertEquals(expectedOverflowChecked ? "java.lang.ArithmeticException" : "", descriptor.overflowExceptionType());
        assertEquals(expectedOverflowChecked, descriptor.hasOverflowException());
    }
}
