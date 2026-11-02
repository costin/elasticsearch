/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.operator.fusion.FusionDescriptor;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.AbsLongEvaluator;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.CastIntToLongEvaluator;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.TanEvaluator;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ChangeCaseEvaluator;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.LengthEvaluator;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.AddLongsEvaluator;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.DivLongsEvaluator;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EqualsBytesRefEvaluator;

import java.lang.reflect.Field;

import static org.hamcrest.Matchers.is;

/**
 * I7: {@code dictionarySafe} classification for every {@code @Fusable} kernel reachable from the
 * encoding-aware path.
 *
 * <p>Rule: a kernel that can throw on any <em>legal</em> input value is not dictionary-safe. The
 * encoding-aware path ({@link FusionPlanner} {@code encodingAware}/{@code dictionarySafeTree},
 * {@link FusionPlan} javadoc) is taken only for a single {@code BYTES_REF} column input with a
 * single input leaf where every kernel is {@code !warns && dictionarySafe}. Evaluating a dictionary
 * can otherwise throw or warn for entries no row references, in a different order.
 *
 * <p>Hand-written descriptors are read from the production {@link FusionPlanner} constants (not
 * reconstructed here). Generated {@code @Fusable} kernels are sampled via their Factory
 * {@code FUSION_DESCRIPTOR} field: generated kernels only throw declared {@code warnExceptions}
 * (caught as warnings, encoded in {@code warns}) so {@code dictionarySafe} is always {@code true}.
 * The sample is a curated representative set (string/BytesRef-reachable plus numeric overflow and
 * non-overflow), not a full generated-class enumeration.
 */
public class DictionarySafeClassificationTests extends ESTestCase {

    public void testConcatIsNotDictionarySafe() {
        FusionDescriptor concat = FusionPlanner.CONCAT_DESCRIPTOR;
        assertThat("CONCAT can throw (over 1MB) on legal BytesRef inputs", concat.dictionarySafe(), is(false));
        assertThat(concat.warns(), is(false));
    }

    public void testSpaceIsNotDictionarySafe() {
        FusionDescriptor space = FusionPlanner.SPACE_DESCRIPTOR;
        assertThat("SPACE throws IllegalArgumentException on a negative/too-long legal int count", space.dictionarySafe(), is(false));
        assertThat(space.warns(), is(true));
    }

    public void testTrimFamilyIsDictionarySafe() {
        assertUnaryBytesRefSafe(FusionPlanner.TRIM_DESCRIPTOR, "TRIM");
        assertUnaryBytesRefSafe(FusionPlanner.LTRIM_DESCRIPTOR, "LTRIM");
        assertUnaryBytesRefSafe(FusionPlanner.RTRIM_DESCRIPTOR, "RTRIM");
    }

    public void testLeftAndRightAreDictionarySafe() {
        assertThat(
            "LEFT: legal BYTES_REF values are valid UTF-8; UnicodeUtil only throws on invalid UTF-8",
            FusionPlanner.LEFT_DESCRIPTOR.dictionarySafe(),
            is(true)
        );
        assertThat(FusionPlanner.LEFT_DESCRIPTOR.warns(), is(false));
        assertThat(
            "RIGHT: same UTF-8 reasoning; length is an int operand, no throw on legal inputs",
            FusionPlanner.RIGHT_DESCRIPTOR.dictionarySafe(),
            is(true)
        );
        assertThat(FusionPlanner.RIGHT_DESCRIPTOR.warns(), is(false));
    }

    /**
     * Generated {@code @Fusable} {@code *Evaluator.Factory} descriptors: any throw they make is a
     * declared {@code warnExceptions} entry (caught as a warning) or they do not throw.
     */
    public void testGeneratedFusableKernelsAreDictionarySafe() throws Exception {
        Class<?>[] evaluators = {
            LengthEvaluator.class,
            ChangeCaseEvaluator.class,
            EqualsBytesRefEvaluator.class,
            AddLongsEvaluator.class,
            DivLongsEvaluator.class,
            AbsLongEvaluator.class,
            CastIntToLongEvaluator.class,
            TanEvaluator.class };
        for (Class<?> evaluator : evaluators) {
            FusionDescriptor descriptor = generatedDescriptor(evaluator);
            assertThat(
                evaluator.getSimpleName() + " generated kernel must be dictionarySafe (throws are warnExceptions)",
                descriptor.dictionarySafe(),
                is(true)
            );
        }
    }

    private static void assertUnaryBytesRefSafe(FusionDescriptor descriptor, String name) {
        assertThat(name + ": unary BytesRef→BytesRef; legal BYTES_REF values are valid UTF-8", descriptor.dictionarySafe(), is(true));
        assertThat(descriptor.warns(), is(false));
    }

    private static FusionDescriptor generatedDescriptor(Class<?> evaluatorClass) throws Exception {
        Class<?> factory = Class.forName(evaluatorClass.getName() + "$Factory");
        Field field = factory.getDeclaredField("FUSION_DESCRIPTOR");
        field.setAccessible(true);
        FusionDescriptor descriptor = (FusionDescriptor) field.get(null);
        if (descriptor == null) {
            throw new AssertionError(evaluatorClass.getSimpleName() + ".Factory.FUSION_DESCRIPTOR is null");
        }
        return descriptor;
    }
}
