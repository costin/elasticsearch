/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.fusion;

import org.elasticsearch.compute.operator.fusion.BodyExtractorTests.KernelFixtures;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.objectweb.asm.ClassReader;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

/**
 * Proves the opt-in {@link FusionDump} hook (#1) exposes the bytes of every generated (hidden) fused class at the
 * single define site: an installed programmatic sink receives the internal name + a parseable copy of the bytes, and
 * with no sink installed (the default) stitching is unaffected.
 */
public class FusionDumpTests extends ESTestCase {

    @After
    public void clearSink() {
        FusionDump.clearSink();
    }

    private static FusionNode greaterThanTree() {
        FusionDescriptor gt = new FusionDescriptor(KernelFixtures.class, "processLongsGreaterThan", "(JJ)Z", false, true);
        return new FusionNode.Kernel(gt, List.of(new FusionNode.Input(0), new FusionNode.Input(1)));
    }

    public void testSinkReceivesParseableGeneratedClass() throws Exception {
        List<String> names = new ArrayList<>();
        AtomicInteger totalBytes = new AtomicInteger();
        FusionDump.setSink((name, bytes) -> {
            names.add(name);
            totalBytes.addAndGet(bytes.length);
            // The bytes handed to the sink must be a valid class the tooling can disassemble.
            assertThat(new ClassReader(bytes).getClassName(), equalTo(name));
        });

        Class<?> fused = new Stitcher(new TemplateRegistry()).compileVectorLoop(MethodHandles.lookup(), greaterThanTree());

        assertThat("exactly one class generated for a single-path compile", names, hasSize(1));
        assertThat(names.get(0), containsString("Fused"));
        assertThat(totalBytes.get(), greaterThan(0));
        assertNotNull(fused);
    }

    public void testSinkReceivesPrivateCopy() throws Exception {
        byte[][] captured = new byte[1][];
        FusionDump.setSink((name, bytes) -> {
            captured[0] = bytes;
            bytes[0] = 0; // mutating the sink's copy must not corrupt the class actually defined
        });
        Class<?> fused = new Stitcher(new TemplateRegistry()).compileVectorLoop(MethodHandles.lookup(), greaterThanTree());
        assertNotNull(fused);
        assertNotNull(captured[0]);
    }

    public void testNoSinkStitchesNormally() throws Exception {
        // Default state (no sink, dump flag off): emit() is a no-op and compilation is unaffected.
        assertNotNull(new Stitcher(new TemplateRegistry()).compileVectorLoop(MethodHandles.lookup(), greaterThanTree()));
    }
}
