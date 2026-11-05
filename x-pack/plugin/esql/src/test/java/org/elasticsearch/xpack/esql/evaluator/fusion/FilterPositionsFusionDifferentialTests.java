/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.evaluator.fusion;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.PositionsEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Output;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedClassCache.Shape;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.LastEvalPath;
import org.elasticsearch.xpack.esql.evaluator.fusion.FusedExpressionEvaluatorFactory.VectorStrategy;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Left;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.Length;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.ToLower;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.planner.Layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Differential for the POSITIONS output of a fused WHERE predicate: a {@code FilterOperator} over the positions-writing
 * {@link FusedFilterEvaluatorFactory} must keep exactly the rows of a {@code FilterOperator} over the unfused predicate,
 * and of one over the boolean-output fused predicate (the WHERE plan before positions existed). Warnings must equal the
 * boolean-output fused predicate's and, per the ratified invariant, be a subset of the unfused predicate's.
 *
 * <p>The sweep covers comparisons over long/int/double arithmetic (with overflow), mixed-element casts,
 * {@code AND}/{@code OR}/{@code NOT}, and keyword predicates over plain, ordinal, constant, null and multivalued
 * columns. Targeted tests pin which runtime path each kind of page takes, and that one predicate compiled for EVAL and
 * for WHERE caches two distinct classes.
 */
public class FilterPositionsFusionDifferentialTests extends FusionDifferentialTestCase {

    private static final EnumSet<Structure> NUMERIC_STRUCTURES = EnumSet.of(
        Structure.DENSE_VECTOR,
        Structure.CONSTANT,
        Structure.BIG_ARRAY,
        Structure.NULLABLE_BLOCK,
        Structure.MULTIVALUE_BLOCK
    );

    /** A WHERE predicate over {@code columns}, in layout order. */
    private record Case(String name, Expression condition, List<FieldAttribute> columns) {
        Layout layout() {
            return FusionDifferentialTestCase.layout(columns.toArray(FieldAttribute[]::new));
        }
    }

    private static Literal longLit(long value) {
        return new Literal(Source.EMPTY, value, DataType.LONG);
    }

    private static Literal intLit(int value) {
        return new Literal(Source.EMPTY, value, DataType.INTEGER);
    }

    private static Literal keywordLit(String value) {
        return new Literal(Source.EMPTY, new BytesRef(value), DataType.KEYWORD);
    }

    private static Expression add(Expression left, Expression right) {
        return new Add(Source.EMPTY, left, right, EsqlTestUtils.TEST_CFG);
    }

    private static List<Case> cases() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        FieldAttribute i = field("i", DataType.INTEGER);
        FieldAttribute j = field("j", DataType.INTEGER);
        FieldAttribute k = field("k", DataType.INTEGER);
        FieldAttribute x = field("x", DataType.DOUBLE);
        FieldAttribute y = field("y", DataType.DOUBLE);
        FieldAttribute s = field("s", DataType.KEYWORD);
        FieldAttribute s2 = field("s2", DataType.KEYWORD);
        return List.of(
            new Case("a + b > c", new GreaterThan(Source.EMPTY, add(a, b), c), List.of(a, b, c)),
            new Case("a * 2 > b", new GreaterThan(Source.EMPTY, new Mul(Source.EMPTY, a, longLit(2)), b), List.of(a, b)),
            new Case("i + j < k", new LessThan(Source.EMPTY, add(i, j), k), List.of(i, j, k)),
            new Case(
                "x * y > 1.5",
                new GreaterThan(Source.EMPTY, new Mul(Source.EMPTY, x, y), new Literal(Source.EMPTY, 1.5, DataType.DOUBLE)),
                List.of(x, y)
            ),
            new Case("a + i > 5", new GreaterThan(Source.EMPTY, add(a, i), longLit(5)), List.of(a, i)),
            new Case(
                "a > b AND b < c",
                new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, b, c)),
                List.of(a, b, c)
            ),
            new Case(
                "NOT(a > b) OR c < 3",
                new Or(Source.EMPTY, new Not(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b)), new LessThan(Source.EMPTY, c, longLit(3))),
                List.of(a, b, c)
            ),
            new Case(
                "NOT(a > b AND a < c)",
                new Not(Source.EMPTY, new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, a, c))),
                List.of(a, b, c)
            ),
            new Case("LENGTH(s) > 3", new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, s), intLit(3)), List.of(s)),
            new Case("s == \"ab\"", new Equals(Source.EMPTY, s, keywordLit("ab")), List.of(s)),
            new Case(
                "LENGTH(TO_LOWER(s)) > 3",
                new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, EsqlTestUtils.TEST_CFG)), intLit(3)),
                List.of(s)
            ),
            new Case("s == LEFT(s2, 2)", new Equals(Source.EMPTY, s, new Left(Source.EMPTY, s2, intLit(2))), List.of(s, s2))
        );
    }

    /**
     * Random pages per case: every column independently a dense, constant, big-array, nullable or multivalued block
     * (plus ordinal for keywords), and sometimes values at the overflow boundary. The positions filter must keep the same
     * rows as both references and register the boolean-output fused predicate's warnings.
     */
    public void testPositionsFilterMatchesUnfusedAndBooleanFused() {
        for (Case testCase : cases()) {
            Layout layout = testCase.layout();
            ExpressionEvaluator.Factory positions = filterFactory(testCase.condition(), layout);
            assertFilterFuses(positions, testCase.name());
            ExpressionEvaluator.Factory booleanFused = fusedFactory(testCase.condition(), layout);
            assertFuses(booleanFused, testCase.name());
            ExpressionEvaluator.Factory unfused = unfusedFactory(testCase.condition(), layout);
            assertDoesNotFuse(unfused, testCase.name());
            for (int iteration = 0; iteration < 30; iteration++) {
                int positionCount = positionCountFor(iteration);
                Profile profile = randomBoolean() ? Profile.OVERFLOW : Profile.CLEAN;
                Block[] blocks = new Block[testCase.columns().size()];
                List<String> structures = new ArrayList<>();
                Page page = null;
                try {
                    for (int col = 0; col < blocks.length; col++) {
                        ElementKind kind = kindOf(testCase.columns().get(col).dataType());
                        Structure structure = positionCount == 0 ? Structure.DENSE_VECTOR
                            : kind == ElementKind.BYTES_REF ? randomFrom(Structure.values())
                            : randomFrom(NUMERIC_STRUCTURES);
                        structures.add(structure.name());
                        blocks[col] = kind == ElementKind.BYTES_REF
                            ? buildBytesRefColumn(positionCount, structure)
                            : buildColumn(kind, positionCount, structure, profile, false);
                    }
                    page = new Page(positionCount, blocks);
                    blocks = null;
                    assertSameRows(positions, booleanFused, unfused, page, testCase.name() + " " + profile + " " + structures);
                } finally {
                    if (page != null) {
                        page.releaseBlocks();
                    }
                    if (blocks != null) {
                        Releasables.closeExpectNoException(blocks);
                    }
                }
            }
        }
    }

    private void assertSameRows(
        ExpressionEvaluator.Factory positions,
        ExpressionEvaluator.Factory booleanFused,
        ExpressionEvaluator.Factory unfused,
        Page page,
        String ctx
    ) {
        CapturedFilter actual = filterCapturing(positions, page);
        CapturedFilter booleanReference = filterCapturing(booleanFused, page);
        CapturedFilter reference = filterCapturing(unfused, page);
        try {
            assertPagesEqual(actual.page(), reference.page(), ctx + " vs unfused");
            assertPagesEqual(actual.page(), booleanReference.page(), ctx + " vs boolean-output fused");
            assertThat(ctx + " warnings vs boolean-output fused", actual.warnings(), equalTo(booleanReference.warnings()));
            assertWarningsSubset(actual.warnings(), reference.warnings(), ctx);
        } finally {
            for (Page out : new Page[] { actual.page(), booleanReference.page(), reference.page() }) {
                if (out != null) {
                    out.releaseBlocks();
                }
            }
        }
    }

    private static ElementKind kindOf(DataType type) {
        return switch (type) {
            case LONG -> ElementKind.LONG;
            case INTEGER -> ElementKind.INT;
            case DOUBLE -> ElementKind.DOUBLE;
            case KEYWORD -> ElementKind.BYTES_REF;
            default -> throw new IllegalArgumentException("unsupported test column type [" + type + "]");
        };
    }

    /**
     * {@code a + b > c} (overflow-checked): an exact array-vector page takes ALL-RAW, a constant-vector page ALL-INTERFACE,
     * a nullable page the block path, and a dense page whose addition overflows abandons the vector path for the block
     * path, which nulls the row and registers the same overflow warning as the unfused predicate.
     */
    public void testComparisonPathPerPage() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        FieldAttribute c = field("c", DataType.LONG);
        Expression condition = new GreaterThan(Source.EMPTY, add(a, b), c);
        Layout layout = layout(a, b, c);
        FusedFilterEvaluatorFactory factory = assertFilterFuses(filterFactory(condition, layout), "a + b > c");
        assertThat(factory.vectorStrategy(), is(VectorStrategy.CHECKED_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(condition, layout);

        assertPath(factory, unfused, page(longs(1, 5, 9), longs(1, 1, 1), longs(3, 3, 3)), LastEvalPath.RAW, "array vectors");
        assertPath(
            factory,
            unfused,
            new Page(
                blockFactory.newConstantLongBlockWith(2, 3),
                blockFactory.newConstantLongBlockWith(3, 3),
                blockFactory.newLongArrayVector(new long[] { 1, 5, 9 }, 3).asBlock()
            ),
            LastEvalPath.INTERFACE,
            "constant vectors"
        );
        assertPath(
            factory,
            unfused,
            page(longColumn(new Long[] { 1L, null, 9L }), longs(1, 1, 1), longs(3, 3, 3)),
            LastEvalPath.BLOCK,
            "nulls"
        );
        List<String> warnings = assertPath(
            factory,
            unfused,
            page(longs(Long.MAX_VALUE, 5, 9), longs(1, 1, 1), longs(3, 3, 3)),
            LastEvalPath.BLOCK,
            "overflow abandons the vector path"
        );
        assertThat("the block re-run registers the overflow warning", containsWarning(warnings, "long overflow"), is(true));
    }

    /** A logical predicate is INTERFACE-only on dense pages and takes the 3VL block path with nulls or multivalues. */
    public void testLogicalPathPerPage() {
        FieldAttribute a = field("a", DataType.LONG);
        FieldAttribute b = field("b", DataType.LONG);
        Expression condition = new And(Source.EMPTY, new GreaterThan(Source.EMPTY, a, b), new LessThan(Source.EMPTY, a, longLit(7)));
        Layout layout = layout(a, b);
        FusedFilterEvaluatorFactory factory = assertFilterFuses(filterFactory(condition, layout), "a > b AND a < 7");
        assertThat(factory.vectorStrategy(), is(VectorStrategy.LOGICAL_VECTOR));
        ExpressionEvaluator.Factory unfused = unfusedFactory(condition, layout);
        assertPath(factory, unfused, page(longs(1, 5, 9), longs(0, 6, 1)), LastEvalPath.INTERFACE, "dense");
        assertPath(factory, unfused, page(multiValueLong(new long[] { 3, 4 }), singleLong(1)), LastEvalPath.BLOCK, "multivalue");
    }

    /**
     * {@code LENGTH(s) > 3}: ordinal and constant keyword pages are selected once per dictionary entry / once in total
     * (encoding-aware); a plain keyword page uses the block path.
     */
    public void testKeywordEncodingAwarePerPage() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression condition = new GreaterThan(Source.EMPTY, new Length(Source.EMPTY, s), intLit(3));
        Layout layout = layout(s);
        FusedFilterEvaluatorFactory factory = assertFilterFuses(filterFactory(condition, layout), "LENGTH(s) > 3");
        assertThat("keyword inputs have no vector path", factory.vectorStrategy(), is(VectorStrategy.NONE));
        ExpressionEvaluator.Factory unfused = unfusedFactory(condition, layout);
        assertPath(factory, unfused, new Page(buildBytesRefColumn(64, Structure.ORDINAL)), LastEvalPath.ENCODING_AWARE, "ordinal");
        assertPath(
            factory,
            unfused,
            new Page(blockFactory.newConstantBytesRefBlockWith(new BytesRef("abcdef"), 5)),
            LastEvalPath.ENCODING_AWARE,
            "constant selected"
        );
        assertPath(
            factory,
            unfused,
            new Page(blockFactory.newConstantBytesRefBlockWith(new BytesRef("ab"), 5)),
            LastEvalPath.ENCODING_AWARE,
            "constant dropped"
        );
        assertPath(factory, unfused, new Page(buildBytesRefColumn(64, Structure.NULLABLE_BLOCK)), LastEvalPath.BLOCK, "nullable");
    }

    /** A nested mapping keeps the unfused multivalue semantics: a multivalued page selects through the unfused predicate. */
    public void testNestedMappingMultivalueFallsBackToUnfused() {
        FieldAttribute s = field("s", DataType.KEYWORD);
        Expression condition = new GreaterThan(
            Source.EMPTY,
            new Length(Source.EMPTY, new ToLower(Source.EMPTY, s, EsqlTestUtils.TEST_CFG)),
            intLit(3)
        );
        Layout layout = layout(s);
        FusedFilterEvaluatorFactory factory = assertFilterFuses(filterFactory(condition, layout), "LENGTH(TO_LOWER(s)) > 3");
        ExpressionEvaluator.Factory unfused = unfusedFactory(condition, layout);
        assertPath(
            factory,
            unfused,
            new Page(buildBytesRefColumn(64, Structure.MULTIVALUE_BLOCK)),
            LastEvalPath.UNFUSED_FALLBACK,
            "multivalue"
        );
    }

    /**
     * The same predicate compiled for EVAL (boolean output) and for WHERE (positions) caches two distinct pairs of
     * classes under the same signature, element and paths, told apart only by {@link Output}: a shared entry would hand
     * one of them a handle of the other's method type. Re-planning either reuses its own entries, and both run correctly.
     */
    public void testSamePredicateInEvalAndWhereCachesDistinctClasses() {
        FieldAttribute x = field("x", DataType.LONG);
        Layout layout = layout(x);
        Expression condition = new GreaterThan(Source.EMPTY, add(x, longLit(1)), longLit(5));
        FusedClassCache cache = FusionPlanner.classCache();

        ExpressionEvaluator.Factory eval = fusedFactory(condition, layout);
        assertFuses(eval, "EVAL x + 1 > 5");
        List<Shape> evalEntries = cache.entriesForTests();
        assertThat("EVAL compiles its block and checked-vector classes", evalEntries, hasSize(2));
        assertThat(evalEntries.stream().map(Shape::output).toList(), everyItem(is(Output.VALUES)));

        ExpressionEvaluator.Factory where = filterFactory(condition, layout);
        assertFilterFuses(where, "WHERE x + 1 > 5");
        List<Shape> allEntries = cache.entriesForTests();
        assertThat("WHERE adds its own block and vector classes", allEntries, hasSize(4));
        List<Shape> positionsEntries = allEntries.stream().filter(shape -> shape.output() == Output.POSITIONS).toList();
        assertThat(positionsEntries, hasSize(2));
        Set<Shape> sameKeysAsValues = positionsEntries.stream()
            .map(shape -> new Shape(shape.signature(), shape.element(), shape.path()))
            .collect(Collectors.toSet());
        assertThat("only the output tells the two compilations apart", sameKeysAsValues, equalTo(Set.copyOf(evalEntries)));
        assertThat(
            allEntries.stream().map(Shape::path).toList(),
            containsInAnyOrder(
                FusedClassCache.Path.BLOCK,
                FusedClassCache.Path.CHECKED_VECTOR,
                FusedClassCache.Path.BLOCK,
                FusedClassCache.Path.CHECKED_VECTOR
            )
        );

        long defined = cache.classesDefined();
        assertFuses(fusedFactory(condition, layout), "EVAL again");
        assertFilterFuses(filterFactory(condition, layout), "WHERE again");
        assertThat("re-planning both reuses their cached classes", cache.classesDefined(), is(defined));

        ExpressionEvaluator.Factory unfused = unfusedFactory(condition, layout);
        try (ExpressionEvaluator evalEvaluator = eval.get(driverContext); ExpressionEvaluator whereEvaluator = where.get(driverContext)) {
            assertThat(evalEvaluator, not(instanceOf(PositionsEvaluator.class)));
            assertThat(whereEvaluator, instanceOf(PositionsEvaluator.class));
        }
        for (Structure structure : List.of(Structure.DENSE_VECTOR, Structure.NULLABLE_BLOCK)) {
            int positionCount = between(10, 100);
            runDifferential(
                eval,
                unfused,
                ElementKind.BOOLEAN,
                new Block[] { buildColumn(ElementKind.LONG, positionCount, structure, Profile.CLEAN, false) },
                positionCount,
                "EVAL " + structure
            );
            Page page = new Page(buildColumn(ElementKind.LONG, positionCount, structure, Profile.CLEAN, false));
            try {
                assertSameRows(where, eval, unfused, page, "WHERE " + structure);
            } finally {
                page.releaseBlocks();
            }
        }
    }

    /**
     * Runs {@code factory}'s positions evaluator over {@code page} (then releases it), asserts it took {@code expected}
     * and selected the unfused predicate's rows, and returns the warnings it registered.
     */
    private List<String> assertPath(
        FusedFilterEvaluatorFactory factory,
        ExpressionEvaluator.Factory unfused,
        Page page,
        LastEvalPath expected,
        String ctx
    ) {
        try {
            DriverContext context = freshDriverContext();
            int[] positions = new int[page.getPositionCount()];
            int count;
            LastEvalPath path;
            try (PositionsEvaluator evaluator = factory.getForTests(context, false)) {
                count = evaluator.filterPositions(page, positions);
                path = FusedFilterEvaluatorFactory.lastEvalPath(evaluator);
            }
            List<String> warnings = warningsOf(context);
            DriverContext unfusedContext = freshDriverContext();
            int[] reference = new int[page.getPositionCount()];
            int referenceCount;
            try (ExpressionEvaluator evaluator = unfused.get(unfusedContext); BooleanBlock mask = (BooleanBlock) evaluator.eval(page)) {
                referenceCount = PositionsEvaluator.selectedPositions(mask, reference);
            }
            assertThat(ctx + " path", path, is(expected));
            assertThat(ctx + " positions", Arrays.copyOf(positions, count), equalTo(Arrays.copyOf(reference, referenceCount)));
            assertThat(ctx + " warnings", warnings, equalTo(warningsOf(unfusedContext)));
            return warnings;
        } finally {
            page.releaseBlocks();
        }
    }

    private Block longs(long... values) {
        return blockFactory.newLongArrayVector(values, values.length).asBlock();
    }

    private static Page page(Block... blocks) {
        return new Page(blocks);
    }
}
