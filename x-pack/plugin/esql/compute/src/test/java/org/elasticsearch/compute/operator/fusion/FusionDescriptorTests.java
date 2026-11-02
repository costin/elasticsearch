/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class FusionDescriptorTests extends ESTestCase {

    private static final String KERNEL_TYPE = "(JJ)J";

    public void testEqualsAndHashCodeForIdenticalComponents() {
        FusionDescriptor left = descriptor(KERNEL_TYPE, true, true);
        FusionDescriptor right = descriptor(KERNEL_TYPE, true, true);
        assertThat(left, is(right));
        assertThat(left.hashCode(), is(right.hashCode()));
    }

    public void testEqualsDiffersWhenKernelTypeDiffers() {
        FusionDescriptor left = descriptor(KERNEL_TYPE, true, true);
        FusionDescriptor right = descriptor("(JI)J", true, true);
        assertThat(left, not(right));
    }

    public void testKernelClassAndMethodSurviveRoundTrip() {
        FusionDescriptor descriptor = descriptor(KERNEL_TYPE, false, true);
        assertThat(descriptor.kernelClass(), is(FusionDescriptorTests.class));
        assertThat(descriptor.kernelMethod(), is("fixtureProcess"));
        assertThat(descriptor.kernelType(), is(KERNEL_TYPE));
        assertThat(descriptor.overflowChecked(), is(false));
        assertThat(descriptor.allNullsIsNull(), is(true));
    }

    public void testFusionAwareReturnsDescriptor() {
        FusionDescriptor expected = descriptor(KERNEL_TYPE, true, false);
        FusionAware fusionAware = () -> expected;
        assertThat(fusionAware.fusionDescriptor(), is(expected));
    }

    public void testOverflowCheckedDerivesOverflowExceptionType() {
        // The helper replicates the old 5-arg mapping: overflow-checked kernels throw ArithmeticException
        // (matching what the APT derives from warnExceptions); non-overflow kernels carry no exception type.
        assertThat(descriptor(KERNEL_TYPE, true, true).overflowExceptionType(), is("java.lang.ArithmeticException"));
        assertThat(descriptor(KERNEL_TYPE, true, true).hasOverflowException(), is(true));
        assertThat(descriptor(KERNEL_TYPE, false, true).overflowExceptionType(), is(""));
        assertThat(descriptor(KERNEL_TYPE, false, true).hasOverflowException(), is(false));
    }

    public void testExplicitOverflowExceptionTypeSurvivesAndAffectsEquals() {
        FusionDescriptor a = new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            KERNEL_TYPE,
            true,
            true,
            "java.lang.ArithmeticException",
            false,
            false,
            true,
            true
        );
        FusionDescriptor b = new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            KERNEL_TYPE,
            true,
            true,
            "",
            false,
            false,
            false,
            true
        );
        assertThat(a.overflowExceptionType(), is("java.lang.ArithmeticException"));
        assertThat(a.hasOverflowException(), is(true));
        assertThat(b.hasOverflowException(), is(false));
        assertThat(a, not(b));
    }

    public void testCanonicalConstructorStoresVariadicAndMapping() {
        FusionDescriptor plain = descriptor(KERNEL_TYPE, false, true);
        assertThat(plain.mapping(), is(false));
        assertThat(plain.variadic(), is(false));
        FusionDescriptor variadic = new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            KERNEL_TYPE,
            false,
            true,
            "",
            true,
            false,
            false,
            true
        );
        assertThat(variadic.variadic(), is(true));
        assertThat(variadic.mapping(), is(false));
        FusionDescriptor mapping = new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            KERNEL_TYPE,
            false,
            true,
            "",
            false,
            true,
            false,
            true
        );
        assertThat(mapping.mapping(), is(true));
        assertThat(mapping.variadic(), is(false));
    }

    public void testCanonicalConstructorStoresWarnsAndDictionarySafe() {
        FusionDescriptor overflow = descriptor(KERNEL_TYPE, true, true);
        assertThat(overflow.warns(), is(true));
        assertThat(overflow.dictionarySafe(), is(true));
        FusionDescriptor plain = descriptor(KERNEL_TYPE, false, true);
        assertThat(plain.warns(), is(false));
        assertThat(plain.dictionarySafe(), is(true));
        FusionDescriptor explicit = new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            KERNEL_TYPE,
            false,
            true,
            "",
            true,
            false,
            false,
            false
        );
        assertThat(explicit.warns(), is(false));
        assertThat(explicit.dictionarySafe(), is(false));
        assertThat(explicit.variadic(), is(true));
    }

    public void testFusionFixedArgsDefaultEmpty() {
        FusionAware fusionAware = () -> descriptor(KERNEL_TYPE, false, true);
        assertThat(fusionAware.fusionFixedArgs().isEmpty(), is(true));
    }

    /**
     * Builds via the canonical 10-arg constructor, replicating the deleted 5-arg derivation so existing
     * callers keep the same component values: ArithmeticException + {@code warns=true} when overflow-checked,
     * empty exception type + {@code warns=false} otherwise; not variadic, not mapping, {@code dictionarySafe=true}.
     */
    private static FusionDescriptor descriptor(String kernelType, boolean overflowChecked, boolean allNullsIsNull) {
        return new FusionDescriptor(
            FusionDescriptorTests.class,
            "fixtureProcess",
            kernelType,
            overflowChecked,
            allNullsIsNull,
            overflowChecked ? "java.lang.ArithmeticException" : "",
            false,
            false,
            overflowChecked,
            true
        );
    }
}
