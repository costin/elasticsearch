/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.tree.MethodNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class TemplateRegistryTests extends ESTestCase {

    /**
     * A kernel-shaped fixture compiled into the test source set, so its {@code .class} file is
     * guaranteed to be on the test classpath and readable via {@code getResourceAsStream}. Using a
     * test-local class (rather than a JDK class) avoids module-isolation surprises around which
     * platform {@code .class} resources a class loader will surface.
     */
    public static final class TestKernel {
        public static long doAdd(long a, long b) {
            return a + b;
        }
    }

    private static final String DO_ADD_TYPE = "(JJ)J";

    public void testLookupReturnsMethodNodeForExistingKernel() {
        TemplateRegistry registry = new TemplateRegistry();
        MethodNode node = registry.methodNode(descriptor("doAdd", DO_ADD_TYPE));

        assertNotNull(node);
        assertThat(node.name, equalTo("doAdd"));
        assertThat(node.desc, equalTo(DO_ADD_TYPE));
        assertThat(node.instructions.size(), greaterThan(0));
    }

    public void testRepeatedLookupReturnsIndependentClones() {
        TemplateRegistry registry = new TemplateRegistry();
        FusionDescriptor descriptor = descriptor("doAdd", DO_ADD_TYPE);

        MethodNode first = registry.methodNode(descriptor);
        MethodNode second = registry.methodNode(descriptor);

        assertThat(first, not(sameInstance(second)));
        assertThat(first.instructions, not(sameInstance(second.instructions)));
        assertThat(first.instructions.size(), equalTo(second.instructions.size()));

        // Mutating one clone must not corrupt the cached template.
        first.instructions.clear();
        assertThat(first.instructions.size(), equalTo(0));

        MethodNode third = registry.methodNode(descriptor);
        assertThat(third.instructions.size(), greaterThan(0));
        assertThat(third.instructions.size(), equalTo(second.instructions.size()));
    }

    public void testMissingKernelMethodThrows() {
        TemplateRegistry registry = new TemplateRegistry();
        FusionDescriptor descriptor = descriptor("definitelyNotAMethod", DO_ADD_TYPE);

        IllegalStateException e = expectThrows(IllegalStateException.class, () -> registry.methodNode(descriptor));
        assertThat(e.getMessage(), containsString("kernel method not found"));
    }

    public void testConcurrentLookupParsesOnce() throws Exception {
        TemplateRegistry registry = new TemplateRegistry();
        FusionDescriptor descriptor = descriptor("doAdd", DO_ADD_TYPE);

        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReferenceArray<MethodNode> results = new AtomicReferenceArray<>(threads);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(threads);
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            int slot = i;
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    results.set(slot, registry.methodNode(descriptor));
                } catch (Throwable t) {
                    failures.set(slot, t);
                } finally {
                    done.countDown();
                }
            }, "template-registry-stampede-" + slot);
            workers[i].start();
        }

        assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS));
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        for (int i = 0; i < threads; i++) {
            assertNull("thread " + i + " failed", failures.get(i));
            MethodNode node = results.get(i);
            assertNotNull(node);
            assertThat(node.instructions.size(), greaterThan(0));
        }
        // computeIfAbsent guarantees the parse runs at most once for a single key, even under a stampede.
        assertThat(registry.parseCount(), lessThanOrEqualTo(1L));
    }

    private static FusionDescriptor descriptor(String method, String type) {
        return new FusionDescriptor(TestKernel.class, method, type, false, true);
    }
}
