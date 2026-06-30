/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.test.ESTestCase;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Verifies that {@link EvaluatorImplementer} emits {@code FusionAware} metadata on generated
 * {@code *Evaluator.Factory} classes when the kernel {@code process} method carries {@code @Fusable}.
 */
public class FusionAwareGenerationTests extends ESTestCase {

    private static final String FUSABLE_KERNEL = """
        package org.elasticsearch.compute.gen.test;

        import org.elasticsearch.compute.ann.Evaluator;
        import org.elasticsearch.compute.ann.Fusable;

        public class FusableKernel {
            @Evaluator
            @Fusable(overflowChecked = true)
            static long process(long lhs, long rhs) {
                return lhs + rhs;
            }
        }
        """;

    private static final String NON_FUSABLE_KERNEL = """
        package org.elasticsearch.compute.gen.test;

        import org.elasticsearch.compute.ann.Evaluator;

        public class NonFusableKernel {
            @Evaluator
            static long process(long lhs, long rhs) {
                return lhs + rhs;
            }
        }
        """;

    public void testFactoryIsFusionAwareWhenKernelIsFusable() throws Exception {
        String generated = generateEvaluatorSource("org.elasticsearch.compute.gen.test.FusableKernel", FUSABLE_KERNEL);
        assertTrue(generated, generated.contains("implements ExpressionEvaluator.Factory, FusionAware"));
        assertTrue(generated, generated.contains("FUSION_DESCRIPTOR"));
        assertTrue(generated, generated.contains("(JJ)J"));
        assertTrue(generated, generated.contains("fusionDescriptor()"));
        assertTrue(generated, generated.contains("FusableKernel.class"));
        assertTrue(generated, generated.contains("\"process\""));
        assertTrue(generated, generated.contains("true"));
    }

    public void testFactoryIsNotFusionAwareWhenKernelLacksFusable() throws Exception {
        String generated = generateEvaluatorSource("org.elasticsearch.compute.gen.test.NonFusableKernel", NON_FUSABLE_KERNEL);
        assertFalse(generated, generated.contains("FusionAware"));
        assertFalse(generated, generated.contains("FUSION_DESCRIPTOR"));
        assertFalse(generated, generated.contains("fusionDescriptor()"));
    }

    private static String generateEvaluatorSource(String className, String source) throws Exception {
        TestElementCaptureProcessor.CAPTURED.set(null);
        compileKernel(className, source);
        TestElementCaptureProcessor.Captured captured = TestElementCaptureProcessor.CAPTURED.get();
        assertNotNull("failed to capture @Evaluator method from " + className, captured);

        var evaluatorAnn = captured.method().getAnnotation(org.elasticsearch.compute.ann.Evaluator.class);
        assertNotNull(evaluatorAnn);
        var warnExceptions = Annotations.listAttributeValues(
            captured.method(),
            Set.of(org.elasticsearch.compute.ann.Evaluator.class),
            "warnExceptions"
        );
        return new EvaluatorImplementer(
            captured.elements(),
            captured.types(),
            captured.method(),
            evaluatorAnn.extraName(),
            warnExceptions,
            evaluatorAnn.allNullsIsNull()
        ).sourceFile().toString();
    }

    @SuppressForbidden(
        reason = "StandardJavaFileManager.setLocation() requires java.io.File; no NIO alternative exists in the javax.tools API"
    )
    private static void compileKernel(String className, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System Java compiler not available", compiler);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        String classpath = System.getProperty("java.class.path");

        JavaFileObject sourceFile = new SimpleJavaFileObject(
            URI.create("string:///" + className.replace('.', '/') + ".java"),
            JavaFileObject.Kind.SOURCE
        ) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        Path outputDir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "fusion-aware-gen-test");
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));

            List<String> options = new ArrayList<>();
            options.add("-classpath");
            options.add(classpath);
            options.add("-processor");
            options.add(TestElementCaptureProcessor.class.getName());

            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(sourceFile));
            assertTrue("kernel compilation failed: " + formatDiagnostics(diagnostics), task.call());
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder builder = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            builder.append(diagnostic.getKind()).append(": ").append(diagnostic.getMessage(null)).append('\n');
        }
        return builder.toString();
    }
}
