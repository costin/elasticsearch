/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import org.elasticsearch.compute.ann.Evaluator;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Test-only processor that captures {@link ExecutableElement} metadata for {@link EvaluatorImplementer} unit tests.
 */
@SupportedAnnotationTypes("org.elasticsearch.compute.ann.Evaluator")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class TestElementCaptureProcessor extends AbstractProcessor {
    static final AtomicReference<Captured> CAPTURED = new AtomicReference<>();

    record Captured(Elements elements, Types types, ExecutableElement method) {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        for (var element : roundEnv.getElementsAnnotatedWith(Evaluator.class)) {
            if (element instanceof ExecutableElement executableElement) {
                ProcessingEnvironment env = processingEnv;
                CAPTURED.set(new Captured(env.getElementUtils(), env.getTypeUtils(), executableElement));
            }
        }
        return false;
    }
}
