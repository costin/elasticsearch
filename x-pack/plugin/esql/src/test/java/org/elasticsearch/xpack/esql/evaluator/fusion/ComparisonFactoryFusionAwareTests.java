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
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class ComparisonFactoryFusionAwareTests extends ESTestCase {

    /**
     * Spot-checks that the iter 3 annotation processor wired {@code FusionAware} + a populated
     * {@code FUSION_DESCRIPTOR} field onto the generated comparison {@code *Evaluator$Factory}
     * classes once the iter 6 kernel annotations were added. One factory is checked per
     * comparison operator (Equals, NotEquals, LessThan, LessThanOrEqual, GreaterThan,
     * GreaterThanOrEqual), spread across the three annotated primitive shapes (int, long, double)
     * for diversity. The targeted Factory classes are loaded by fully-qualified inner-class name
     * because they are package-private and cannot be imported. Comparison kernels produce a
     * boolean via straight-line code with no throw paths, so the JVM return descriptor is
     * {@code Z} and {@code overflowChecked} is uniformly {@code false}.
     */

    public void testEqualsIntsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EqualsIntsEvaluator$Factory",
            Equals.class,
            "processInts",
            "(II)Z",
            false
        );
    }

    public void testNotEqualsLongsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEqualsLongsEvaluator$Factory",
            NotEquals.class,
            "processLongs",
            "(JJ)Z",
            false
        );
    }

    public void testLessThanDoublesFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanDoublesEvaluator$Factory",
            LessThan.class,
            "processDoubles",
            "(DD)Z",
            false
        );
    }

    public void testLessThanOrEqualIntsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqualIntsEvaluator$Factory",
            LessThanOrEqual.class,
            "processInts",
            "(II)Z",
            false
        );
    }

    public void testGreaterThanLongsFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanLongsEvaluator$Factory",
            GreaterThan.class,
            "processLongs",
            "(JJ)Z",
            false
        );
    }

    public void testGreaterThanOrEqualDoublesFactoryIsFusionAware() throws Exception {
        assertFusionDescriptor(
            "org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqualDoublesEvaluator$Factory",
            GreaterThanOrEqual.class,
            "processDoubles",
            "(DD)Z",
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
        // Comparison kernels never throw on overflow, so the exception type is empty and there is nothing to catch.
        assertEquals("", descriptor.overflowExceptionType());
        assertEquals(false, descriptor.hasOverflowException());
    }
}
