/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.math.Abs;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.List;

import static org.hamcrest.Matchers.is;

/**
 * Benchmark/test-only {@code esql.fusion.vector_reads} hook: default {@code auto} keeps ALL-RAW over exact
 * {@code *ArrayVector} inputs; flipping to {@code interface} (via {@link FusionSettings#setVectorReadsInterfaceForTests})
 * treats those same inputs as non-exact so eval takes ALL-INTERFACE, with unchanged values.
 */
public class FusionVectorReadsHookTests extends FusionDifferentialTestCase {

    public void testVectorReadsInterfaceTreatsExactArrayVectorsAsNonExact() {
        assertThat("default auto is not the interface force", FusionSettings.isVectorReadsInterface(), is(false));

        FieldAttribute x = field("x", DataType.DOUBLE);
        Layout layout = layout(x);
        Expression expr = new Abs(Source.EMPTY, new Abs(Source.EMPTY, x));
        FusedExpressionEvaluatorFactory fused = assertFuses(fusedFactory(expr, layout), "Abs(Abs(x))");
        assertThat(fused.vectorStrategy(), is(VectorStrategy.PLAIN_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(expr, layout);

        int n = 8;
        runDifferentialAssertingPath(
            fused,
            unfused,
            denseColumn(ElementKind.DOUBLE, n, 0),
            n,
            LastEvalPath.RAW,
            "auto: exact DoubleArrayVector -> RAW"
        );

        FusionSettings.setVectorReadsInterfaceForTests(true);
        try {
            runDifferentialAssertingPath(
                fused,
                unfused,
                denseColumn(ElementKind.DOUBLE, n, 0),
                n,
                LastEvalPath.INTERFACE,
                "interface: exact DoubleArrayVector -> INTERFACE"
            );
        } finally {
            FusionSettings.setVectorReadsInterfaceForTests(false);
        }
    }

    private void runDifferentialAssertingPath(
        FusedExpressionEvaluatorFactory fusedFactory,
        ExpressionEvaluator.Factory unfusedFactory,
        Block input,
        int positionCount,
        LastEvalPath expectedPath,
        String ctx
    ) {
        ExpressionEvaluator fused = fusedFactory.get(driverContext);
        ExpressionEvaluator reference = unfusedFactory.get(driverContext);
        Page page = null;
        Block fusedResult = null;
        Block referenceResult = null;
        try {
            page = new Page(positionCount, input);
            drainWarnings();
            fusedResult = fused.eval(page);
            List<String> fusedWarnings = drainWarnings();
            referenceResult = reference.eval(page);
            List<String> referenceWarnings = drainWarnings();

            assertThat(ctx + " lastEvalPath", FusedExpressionEvaluatorFactory.lastEvalPath(fused), is(expectedPath));
            assertResultsEqual(ElementKind.DOUBLE, fusedResult, referenceResult, positionCount, ctx);
            assertWarningsSubset(fusedWarnings, referenceWarnings, ctx);
        } finally {
            Releasables.closeExpectNoException(fusedResult, referenceResult, fused, reference);
            if (page != null) {
                page.releaseBlocks();
            }
        }
    }
}
