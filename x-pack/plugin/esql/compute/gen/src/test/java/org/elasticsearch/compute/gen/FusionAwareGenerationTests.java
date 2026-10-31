/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import com.squareup.javapoet.JavaFile;

import org.elasticsearch.compute.ann.ConvertEvaluator;
import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.ann.Fusable;
import org.elasticsearch.test.ESTestCase;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Verifies that the {@code @Evaluator} annotation processor emits {@code FusionAware} metadata on the
 * generated {@code *Evaluator.Factory} when (and only when) the kernel {@code process} method also carries
 * {@link Fusable}.
 *
 * <p>Both scenarios drive the <em>real</em> production generator: a tiny kernel source is fed through a
 * {@code javax.tools} compilation task (running {@code -proc:only}) with an in-test processor that captures a
 * genuine {@link ExecutableElement}, constructs the production {@link EvaluatorImplementer} exactly as
 * {@link EvaluatorProcessor} does, and records {@link EvaluatorImplementer#sourceFile()}'s output. We assert on
 * that emitted text. The generator runs for real; only the Filer write-to-disk step is replaced (so javac does
 * not try to re-attribute the generated source against compute classes that aren't on this module's classpath).
 */
public class FusionAwareGenerationTests extends ESTestCase {

    /**
     * Positive case: {@code @Evaluator + @Fusable(overflowChecked = true)} on {@code long process(long, long)}.
     * The Factory must implement {@code FusionAware}, declare a {@code FUSION_DESCRIPTOR} with the JVM descriptor
     * {@code (JJ)J} and the propagated booleans, and implement {@code fusionDescriptor()}.
     */
    public void testFusableKernelEmitsFusionAwareFactory() {
        String source = """
            package test;
            import org.elasticsearch.compute.ann.Evaluator;
            import org.elasticsearch.compute.ann.Fusable;
            class FusableKernel {
                @Evaluator(warnExceptions = { ArithmeticException.class })
                @Fusable(overflowChecked = true)
                static long process(long lhs, long rhs) {
                    return lhs + rhs;
                }
            }
            """;

        String generated = generateEvaluator(source, "test.FusableKernelEvaluator");

        assertThat(generated, containsString("class Factory implements ExpressionEvaluator.Factory, FusionAware"));
        assertThat(generated, containsString("private static final FusionDescriptor FUSION_DESCRIPTOR = new FusionDescriptor("));
        // kernel class, method, descriptor (long, long) -> long, overflowChecked=true, allNullsIsNull=true, and the
        // overflow exception type derived from @Evaluator.warnExceptions (binding rule #3).
        assertThat(
            generated,
            containsString("FusableKernel.class, \"process\", \"(JJ)J\", true, true, \"java.lang.ArithmeticException\")")
        );
        assertThat(generated, containsString("public FusionDescriptor fusionDescriptor()"));
        assertThat(generated, containsString("return FUSION_DESCRIPTOR;"));
    }

    /**
     * Descriptor-derivation case: a mixed-primitive signature {@code double process(double, int)} must yield the
     * JVM descriptor {@code (DI)D}, and a {@code @Fusable} kernel without an explicit {@code overflowChecked}
     * must propagate {@code false}. This pins that the descriptor is computed from the signature, not hardcoded.
     */
    public void testFusableDescriptorDerivedFromSignature() {
        String source = """
            package test;
            import org.elasticsearch.compute.ann.Evaluator;
            import org.elasticsearch.compute.ann.Fusable;
            class MixedKernel {
                @Evaluator
                @Fusable
                static double process(double a, int b) {
                    return a + b;
                }
            }
            """;

        String generated = generateEvaluator(source, "test.MixedKernelEvaluator");

        assertThat(generated, containsString(", FusionAware"));
        // No overflowChecked and no warnExceptions -> empty overflow exception type.
        assertThat(generated, containsString("MixedKernel.class, \"process\", \"(DI)D\", false, true, \"\")"));
        assertThat(generated, containsString("public FusionDescriptor fusionDescriptor()"));
    }

    /**
     * Negative case: a plain {@code @Evaluator} kernel (no {@code @Fusable}) must produce a Factory with no
     * fusion metadata whatsoever — the additive invariant. Any drift here is a BLOCKER per the iter brief.
     */
    public void testNonFusableKernelEmitsUnchangedFactory() {
        String source = """
            package test;
            import org.elasticsearch.compute.ann.Evaluator;
            class PlainKernel {
                @Evaluator
                static long process(long lhs, long rhs) {
                    return lhs + rhs;
                }
            }
            """;

        String generated = generateEvaluator(source, "test.PlainKernelEvaluator");

        // The Factory is emitted with its pre-fusion superinterface list, unchanged.
        assertThat(generated, containsString("class Factory implements ExpressionEvaluator.Factory {"));
        assertThat(generated, not(containsString("FusionAware")));
        assertThat(generated, not(containsString("FUSION_DESCRIPTOR")));
        assertThat(generated, not(containsString("fusionDescriptor")));
        assertThat(generated, not(containsString("fusionFixedArgs")));
    }

    /**
     * D2: {@code @ConvertEvaluator + @Fusable} on a BytesRef kernel with a {@code @Fixed} SINGLETON param must emit a
     * Factory that implements {@code FusionAware}, a {@code FUSION_DESCRIPTOR} with {@code mapping=true},
     * {@code fusionDescriptor()}, and {@code fusionFixedArgs()} returning the fixed field(s) in kernel-parameter order.
     */
    public void testFusableConvertEmitsMappingAwareFactory() {
        String source = """
            package test;
            import org.apache.lucene.util.BytesRef;
            import org.elasticsearch.compute.ann.ConvertEvaluator;
            import org.elasticsearch.compute.ann.Fixed;
            import org.elasticsearch.compute.ann.Fusable;
            import java.util.Locale;
            class ConvertKernel {
                @ConvertEvaluator
                @Fusable
                static BytesRef process(BytesRef val, @Fixed Locale locale) {
                    return val;
                }
            }
            """;

        String generated = generateEvaluator(source, "test.ConvertKernelEvaluator");

        assertThat(generated, containsString("class Factory implements ExpressionEvaluator.Factory, FusionAware"));
        assertThat(generated, containsString("private static final FusionDescriptor FUSION_DESCRIPTOR = new FusionDescriptor("));
        assertThat(
            generated,
            containsString(
                "ConvertKernel.class, \"process\", "
                    + "\"(Lorg/apache/lucene/util/BytesRef;Ljava/util/Locale;)Lorg/apache/lucene/util/BytesRef;\", "
                    + "false, true, \"\", false, true)"
            )
        );
        assertThat(generated, containsString("public FusionDescriptor fusionDescriptor()"));
        assertThat(generated, containsString("public List<Object> fusionFixedArgs()"));
        assertThat(generated, containsString("return List.of(locale)"));
    }

    /**
     * D2 additive invariant: the same convert kernel without {@code @Fusable} must emit a Factory with no fusion
     * metadata whatsoever. Drift here is a BLOCKER.
     */
    public void testNonFusableConvertEmitsUnchangedFactory() {
        String source = """
            package test;
            import org.apache.lucene.util.BytesRef;
            import org.elasticsearch.compute.ann.ConvertEvaluator;
            import org.elasticsearch.compute.ann.Fixed;
            import java.util.Locale;
            class PlainConvert {
                @ConvertEvaluator
                static BytesRef process(BytesRef val, @Fixed Locale locale) {
                    return val;
                }
            }
            """;

        String generated = generateEvaluator(source, "test.PlainConvertEvaluator");

        assertThat(generated, containsString("class Factory implements ExpressionEvaluator.Factory {"));
        assertThat(generated, not(containsString("FusionAware")));
        assertThat(generated, not(containsString("FUSION_DESCRIPTOR")));
        assertThat(generated, not(containsString("fusionDescriptor")));
        assertThat(generated, not(containsString("fusionFixedArgs")));
    }

    /**
     * D2 admission: {@code @ConvertEvaluator + @Fusable + warnExceptions} is a compile error.
     */
    public void testFusableConvertWithWarnExceptionsIsCompileError() {
        String source = """
            package test;
            import org.apache.lucene.util.BytesRef;
            import org.elasticsearch.compute.ann.ConvertEvaluator;
            import org.elasticsearch.compute.ann.Fusable;
            class WarnConvert {
                @ConvertEvaluator(warnExceptions = { ArithmeticException.class })
                @Fusable
                static BytesRef process(BytesRef val) {
                    return val;
                }
            }
            """;

        List<String> errors = generateErrors(source);
        assertFalse("expected a compile error for @ConvertEvaluator + @Fusable + warnExceptions", errors.isEmpty());
        boolean mentionsWarn = false;
        for (String error : errors) {
            if (error.contains("warnExceptions") || error.contains("Fusable") || error.contains("failed to build")) {
                mentionsWarn = true;
                break;
            }
        }
        assertTrue("error should mention warnExceptions or Fusable, got " + errors, mentionsWarn);
    }

    /**
     * Drives the production {@link EvaluatorImplementer} for the single {@code @Evaluator} method in {@code source}
     * via a real annotation-processing round, returning the generated source for {@code generatedClassName}.
     */
    private String generateEvaluator(String source, String generatedClassName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("system Java compiler is required for this test", compiler);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///test/Kernel.java"), Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        CapturingProcessor processor = new CapturingProcessor();
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"), "-proc:only");

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(input));
            task.setProcessors(List.of(processor));
            boolean success = task.call();

            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(d.getMessage(null));
                }
            }
            assertTrue("annotation processing failed: " + errors, success);
            errors.addAll(processor.errors);
        } catch (Exception e) {
            throw new AssertionError("compilation setup failed", e);
        }

        String generated = processor.generated.get(generatedClassName);
        assertNotNull("generator did not produce [" + generatedClassName + "]; produced: " + processor.generated.keySet(), generated);
        return generated;
    }

    /** Like {@link #generateEvaluator} but returns processor/javac errors instead of generated source. */
    private List<String> generateErrors(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("system Java compiler is required for this test", compiler);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///test/Kernel.java"), Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        CapturingProcessor processor = new CapturingProcessor();
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"), "-proc:only");
        List<String> errors = new ArrayList<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, List.of(input));
            task.setProcessors(List.of(processor));
            task.call();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(d.getMessage(null));
                }
            }
            errors.addAll(processor.errors);
        } catch (Exception e) {
            errors.add(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return errors;
    }

    /**
     * In-test processor mirroring {@link EvaluatorProcessor}'s {@code @Evaluator} and {@code @ConvertEvaluator}
     * branches: it constructs the real implementer from a genuine {@link ExecutableElement} and stashes the generated
     * source text instead of writing it through the Filer.
     */
    @SupportedAnnotationTypes({ "org.elasticsearch.compute.ann.Evaluator", "org.elasticsearch.compute.ann.ConvertEvaluator" })
    private static final class CapturingProcessor extends AbstractProcessor {
        private final Map<String, String> generated = new HashMap<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (Element method : roundEnv.getElementsAnnotatedWith(Evaluator.class)) {
                Evaluator evaluatorAnn = method.getAnnotation(Evaluator.class);
                Fusable fusableAnn = method.getAnnotation(Fusable.class);
                List<TypeMirror> warnExceptions = Annotations.listAttributeValues(method, Set.of(Evaluator.class), "warnExceptions");
                JavaFile file = new EvaluatorImplementer(
                    processingEnv.getElementUtils(),
                    processingEnv.getTypeUtils(),
                    (ExecutableElement) method,
                    evaluatorAnn.extraName(),
                    warnExceptions,
                    evaluatorAnn.allNullsIsNull(),
                    fusableAnn != null,
                    fusableAnn != null && fusableAnn.overflowChecked()
                ).sourceFile();
                generated.put(file.packageName + "." + file.typeSpec.name, file.toString());
            }
            for (Element method : roundEnv.getElementsAnnotatedWith(ConvertEvaluator.class)) {
                ConvertEvaluator convertAnn = method.getAnnotation(ConvertEvaluator.class);
                Fusable fusableAnn = method.getAnnotation(Fusable.class);
                List<TypeMirror> warnExceptions = Annotations.listAttributeValues(method, Set.of(ConvertEvaluator.class), "warnExceptions");
                try {
                    JavaFile file = new ConvertEvaluatorImplementer(
                        processingEnv.getElementUtils(),
                        processingEnv.getTypeUtils(),
                        (ExecutableElement) method,
                        convertAnn.extraName(),
                        warnExceptions,
                        fusableAnn != null
                    ).sourceFile();
                    generated.put(file.packageName + "." + file.typeSpec.name, file.toString());
                } catch (RuntimeException e) {
                    errors.add(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            return true;
        }
    }
}
