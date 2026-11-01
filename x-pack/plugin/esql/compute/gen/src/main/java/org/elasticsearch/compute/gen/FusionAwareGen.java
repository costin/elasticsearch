/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.gen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;

import org.elasticsearch.compute.gen.argument.Argument;
import org.elasticsearch.compute.gen.argument.FixedArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import static org.elasticsearch.compute.gen.Types.FUSION_DESCRIPTOR;

/**
 * Shared JavaPoet emission for {@code FusionAware} metadata on a generated {@code *Evaluator.Factory}.
 * Both {@link EvaluatorImplementer} ({@code @Evaluator} kernels) and {@link ConvertEvaluatorImplementer}
 * ({@code @ConvertEvaluator} kernels) call these helpers so the descriptor field, {@code fusionDescriptor()}
 * method, JVM type descriptor, and (for converters) {@code fusionFixedArgs()} stay in one place.
 */
class FusionAwareGen {
    private FusionAwareGen() {}

    /**
     * The static {@code FUSION_DESCRIPTOR} field. Always emitted as the 10-arg canonical constructor so
     * {@code warns} (warnExceptions non-empty, independent of {@code overflowChecked}) and
     * {@code dictionarySafe} are explicit. Generated kernels are never variadic; {@code mapping} is
     * {@code true} only for {@code @ConvertEvaluator}. Generated kernels are {@code dictionarySafe}
     * because any throw they make is a declared {@code warnExceptions} entry (caught as a warning).
     */
    static FieldSpec fusionDescriptorField(
        Elements elements,
        TypeElement declarationType,
        ExecutableElement kernelMethod,
        boolean overflowChecked,
        boolean allNullsIsNull,
        String overflowExceptionType,
        boolean mapping,
        boolean warns,
        boolean dictionarySafe
    ) {
        FieldSpec.Builder builder = FieldSpec.builder(
            FUSION_DESCRIPTOR,
            "FUSION_DESCRIPTOR",
            Modifier.PRIVATE,
            Modifier.STATIC,
            Modifier.FINAL
        );
        builder.initializer(
            "new $T($T.class, $S, $S, $L, $L, $S, $L, $L, $L, $L)",
            FUSION_DESCRIPTOR,
            ClassName.get(declarationType),
            kernelMethod.getSimpleName().toString(),
            kernelTypeDescriptor(elements, kernelMethod),
            overflowChecked,
            allNullsIsNull,
            overflowExceptionType,
            false,
            mapping,
            warns,
            dictionarySafe
        );
        return builder.build();
    }

    static MethodSpec fusionDescriptorMethod() {
        return MethodSpec.methodBuilder("fusionDescriptor")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(FUSION_DESCRIPTOR)
            .addStatement("return FUSION_DESCRIPTOR")
            .build();
    }

    /**
     * {@code fusionFixedArgs()} returning the Factory's {@code @Fixed} SINGLETON fields in kernel-parameter
     * order, skipping the leading input argument and any non-{@link FixedArgument}. Empty {@code List.of()}
     * when there are no fixed params.
     */
    static MethodSpec fusionFixedArgsMethod(List<Argument> args) {
        List<String> names = new ArrayList<>();
        boolean first = true;
        for (Argument a : args) {
            if (first) {
                first = false;
                continue;
            }
            if (a instanceof FixedArgument fa) {
                names.add(fa.name());
            }
        }
        MethodSpec.Builder builder = MethodSpec.methodBuilder("fusionFixedArgs")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(Object.class)));
        if (names.isEmpty()) {
            builder.addStatement("return $T.of()", List.class);
        } else {
            builder.addStatement("return $T.of(" + names.stream().collect(Collectors.joining(", ")) + ")", List.class);
        }
        return builder.build();
    }

    /**
     * The JVM method descriptor of the kernel {@code process} method (e.g. {@code (JJ)J} for
     * {@code static long process(long lhs, long rhs)}), derived from the annotated method's signature
     * rather than hardcoded. The stitcher uses this descriptor to locate the exact kernel method when
     * reading its compiled bytecode template.
     */
    static String kernelTypeDescriptor(Elements elements, ExecutableElement method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (VariableElement parameter : method.getParameters()) {
            appendTypeDescriptor(elements, descriptor, parameter.asType());
        }
        descriptor.append(')');
        appendTypeDescriptor(elements, descriptor, method.getReturnType());
        return descriptor.toString();
    }

    static void appendTypeDescriptor(Elements elements, StringBuilder descriptor, TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN -> descriptor.append('Z');
            case BYTE -> descriptor.append('B');
            case CHAR -> descriptor.append('C');
            case SHORT -> descriptor.append('S');
            case INT -> descriptor.append('I');
            case LONG -> descriptor.append('J');
            case FLOAT -> descriptor.append('F');
            case DOUBLE -> descriptor.append('D');
            case VOID -> descriptor.append('V');
            case ARRAY -> {
                descriptor.append('[');
                appendTypeDescriptor(elements, descriptor, ((ArrayType) type).getComponentType());
            }
            case DECLARED -> {
                TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
                descriptor.append('L').append(elements.getBinaryName(element).toString().replace('.', '/')).append(';');
            }
            default -> throw new IllegalArgumentException("cannot derive JVM descriptor for kernel type [" + type + "]");
        }
    }
}
