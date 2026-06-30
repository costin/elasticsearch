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

    private static FusionDescriptor descriptor(String kernelType, boolean overflowChecked, boolean allNullsIsNull) {
        return new FusionDescriptor(FusionDescriptorTests.class, "fixtureProcess", kernelType, overflowChecked, allNullsIsNull);
    }
}
