/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.operator.fusion.BodyExtractorTests.KernelFixtures;
import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

/**
 * Depth-1 sanity for the {@link Stitcher}: fuse a single {@code (JJ)J} kernel (a {@code Math.addExact}
 * delegate, reusing iter 8's {@link KernelFixtures}) into a hidden class, invoke it, and confirm it computes
 * exactly what the kernel would. The kernel body only calls {@code java.base} targets, so a same-module lookup
 * suffices here — the cross-module linkage guarantee (binding rule #1) is exercised separately in esql-proper's
 * {@code StitcherCrossModuleTests}. The JVM verifier runs at {@code defineHiddenClass} time, so a well-formed
 * stitch that loads and returns a correct value is itself proof the emitted bytecode verified.
 */
public class StitcherTests extends ESTestCase {

    private static final String LONG_BINARY_TYPE = "(JJ)J";
    private static final MethodType LONG_BINARY = MethodType.methodType(long.class, long.class, long.class);

    private static final String INT_BINARY_TYPE = "(II)I";
    private static final MethodType INT_BINARY = MethodType.methodType(int.class, int.class, int.class);

    /**
     * A single-slot {@code (II)I} kernel fixture. Unlike the {@code long}/{@code double} fixtures (each argument
     * occupies two local slots), an {@code int} argument occupies one, so this exercises the
     * {@code ILOAD}/{@code ISTORE} plumbing where {@code Type.getSize() == 1}.
     */
    public static final class IntKernel {
        public static int processIntsAdd(int lhs, int rhs) {
            return Math.addExact(lhs, rhs);
        }
    }

    public void testFuseAddDepth1() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        FusionDescriptor descriptor = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", LONG_BINARY_TYPE, true, true);

        Class<?> fused = stitcher.compile(lookup, descriptor);

        // Binding rule #1: the hidden class must be defined into the caller's module/package.
        assertThat(fused.getPackageName(), equalTo(getClass().getPackageName()));

        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, LONG_BINARY);

        for (int i = 0; i < 100; i++) {
            // Bound so Math.addExact cannot overflow: the fused body mirrors the kernel exactly, including throw
            // behaviour, but overflow semantics are an iter-12 concern.
            long lhs = randomLongBetween(-1_000_000_000L, 1_000_000_000L);
            long rhs = randomLongBetween(-1_000_000_000L, 1_000_000_000L);
            long fusedResult = (long) handle.invokeExact(lhs, rhs);
            assertThat("lhs=" + lhs + " rhs=" + rhs, fusedResult, equalTo(Math.addExact(lhs, rhs)));
        }
    }

    public void testFuseMulDepth1() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        FusionDescriptor descriptor = new FusionDescriptor(KernelFixtures.class, "processLongsMul", LONG_BINARY_TYPE, true, true);

        Class<?> fused = stitcher.compile(lookup, descriptor);
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, LONG_BINARY);

        for (int i = 0; i < 100; i++) {
            long lhs = randomLongBetween(-1_000_000L, 1_000_000L);
            long rhs = randomLongBetween(-1_000L, 1_000L);
            long fusedResult = (long) handle.invokeExact(lhs, rhs);
            assertThat("lhs=" + lhs + " rhs=" + rhs, fusedResult, equalTo(Math.multiplyExact(lhs, rhs)));
        }
    }

    public void testFuseAddIntsDepth1SingleSlot() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        FusionDescriptor descriptor = new FusionDescriptor(IntKernel.class, "processIntsAdd", INT_BINARY_TYPE, true, true);

        Class<?> fused = stitcher.compile(lookup, descriptor);
        MethodHandle handle = lookup.findStatic(fused, Stitcher.FUSED_METHOD_NAME, INT_BINARY);

        for (int i = 0; i < 100; i++) {
            // Single-slot inputs: verifies the getSize()==1 ILOAD/ISTORE plumbing, distinct from the 2-slot long/double paths.
            int lhs = randomIntBetween(-1_000_000, 1_000_000);
            int rhs = randomIntBetween(-1_000_000, 1_000_000);
            int fusedResult = (int) handle.invokeExact(lhs, rhs);
            assertThat("lhs=" + lhs + " rhs=" + rhs, fusedResult, equalTo(Math.addExact(lhs, rhs)));
        }
    }

    public void testMissingKernelSurfacesAsIllegalState() {
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        FusionDescriptor descriptor = new FusionDescriptor(KernelFixtures.class, "noSuchKernel", LONG_BINARY_TYPE, true, true);
        // A descriptor that names no real kernel fails while reading the template, before any bytecode is emitted.
        expectThrows(IllegalStateException.class, () -> stitcher.compile(MethodHandles.lookup(), descriptor));
    }

    public void testVerificationFailureSurfacesAsStitchingException() {
        Stitcher stitcher = new Stitcher(new TemplateRegistry());
        // Corrupt the assembled host method (declared (JJ)J) so it returns an int: the JVM verifier rejects the
        // return-type mismatch at defineHiddenClass time with a VerifyError (a subtype of LinkageError). This
        // drives binding rule #5's typed-failure fallback end-to-end, all the way through defineHiddenClass.
        stitcher.hostMethodInterceptor = host -> {
            host.instructions.clear();
            host.instructions.add(new InsnNode(Opcodes.ICONST_0));
            host.instructions.add(new InsnNode(Opcodes.IRETURN));
        };
        FusionDescriptor descriptor = new FusionDescriptor(KernelFixtures.class, "processLongsAdd", LONG_BINARY_TYPE, true, true);

        Stitcher.StitchingException e = expectThrows(
            Stitcher.StitchingException.class,
            () -> stitcher.compile(MethodHandles.lookup(), descriptor)
        );
        // The typed failure must wrap the underlying JVM verification error so callers can diagnose it.
        assertThat(e.getCause(), instanceOf(LinkageError.class));
    }
}
