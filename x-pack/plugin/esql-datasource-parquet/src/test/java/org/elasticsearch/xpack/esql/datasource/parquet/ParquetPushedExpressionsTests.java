/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasource.parquet;

import org.apache.lucene.util.BytesRef;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Types;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.expression.predicate.regex.WildcardPattern;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.WildcardLike;
import org.elasticsearch.xpack.esql.expression.predicate.Range;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.In;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.apache.parquet.schema.LogicalTypeAnnotation.dateType;
import static org.apache.parquet.schema.LogicalTypeAnnotation.timestampType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT96;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests for {@link ParquetPushedExpressions#toFilterPredicate(MessageType)} verifying
 * schema-aware translation of DATETIME columns across different Parquet physical types.
 */
public class ParquetPushedExpressionsTests extends ESTestCase {

    private BlockFactory blockFactory;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        blockFactory = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE).breaker(new NoopCircuitBreaker("none")).build();
    }

    // --- TIMESTAMP_MILLIS (INT64) ---

    public void testToFilterPredicateTimestampMillis() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS))
            .named("ts")
            .named("test");

        long millis = 1700000000000L;
        Expression expr = eq("ts", DataType.DATETIME, millis);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString("1700000000000"));
    }

    // --- TIMESTAMP_MICROS (INT64) ---

    public void testToFilterPredicateTimestampMicros() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS))
            .named("ts")
            .named("test");

        long millis = 1700000000000L;
        Expression expr = eq("ts", DataType.DATETIME, millis);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString(String.valueOf(millis * 1000)));
    }

    // --- TIMESTAMP_NANOS (INT64) ---

    public void testToFilterPredicateTimestampNanos() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS))
            .named("ts")
            .named("test");

        long millis = 1700000000000L;
        Expression expr = eq("ts", DataType.DATETIME, millis);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString(String.valueOf(millis * 1_000_000)));
    }

    // --- DATE (INT32) ---

    public void testToFilterPredicateDateInt32() {
        MessageType schema = Types.buildMessage().required(INT32).as(dateType()).named("d").named("test");

        long millis = 86400000L * 19723; // some date
        int expectedDays = (int) (millis / ParquetPushedExpressions.MILLIS_PER_DAY);
        Expression expr = eq("d", DataType.DATETIME, millis);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString(String.valueOf(expectedDays)));
    }

    // --- INT96 (skip pushdown) ---

    public void testToFilterPredicateInt96SkipsPushdown() {
        MessageType schema = Types.buildMessage().required(INT96).named("ts").named("test");

        Expression expr = eq("ts", DataType.DATETIME, 1700000000000L);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNull(fp);
    }

    // --- Column not in schema ---

    public void testToFilterPredicateColumnNotInSchema() {
        MessageType schema = Types.buildMessage().required(INT32).named("id").named("test");

        Expression expr = eq("missing_col", DataType.DATETIME, 1700000000000L);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNull(fp);
    }

    // --- Mixed types (DATETIME + INTEGER) ---

    public void testToFilterPredicateMixedTypes() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS))
            .named("ts")
            .required(INT32)
            .named("id")
            .named("test");

        Expression tsExpr = new GreaterThan(Source.EMPTY, attr("ts", DataType.DATETIME), datetimeLit(1000L), null);
        Expression idExpr = eq("id", DataType.INTEGER, 42);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(tsExpr, idExpr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        String repr = fp.toString();
        assertThat(repr, containsString("ts"));
        assertThat(repr, containsString("id"));
    }

    // --- Range on DATE schema ---

    public void testToFilterPredicateRangeOnDate() {
        MessageType schema = Types.buildMessage().required(INT32).as(dateType()).named("d").named("test");

        long lowerMillis = 86400000L * 100;
        long upperMillis = 86400000L * 200;
        int expectedLowerDays = 100;
        int expectedUpperDays = 200;

        Expression range = new Range(
            Source.EMPTY,
            attr("d", DataType.DATETIME),
            datetimeLit(lowerMillis),
            true,
            datetimeLit(upperMillis),
            true,
            ZoneOffset.UTC
        );
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(range));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        String repr = fp.toString();
        assertThat(repr, containsString(String.valueOf(expectedLowerDays)));
        assertThat(repr, containsString(String.valueOf(expectedUpperDays)));
    }

    // --- IN list on TIMESTAMP_MICROS ---

    public void testToFilterPredicateInListOnTimestampMicros() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS))
            .named("ts")
            .named("test");

        long millis1 = 1000L;
        long millis2 = 2000L;

        Expression inExpr = new In(Source.EMPTY, attr("ts", DataType.DATETIME), List.of(datetimeLit(millis1), datetimeLit(millis2)));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(inExpr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        String repr = fp.toString();
        assertThat(repr, containsString(String.valueOf(millis1 * 1000)));
        assertThat(repr, containsString(String.valueOf(millis2 * 1000)));
    }

    // --- Overflow protection ---

    public void testToFilterPredicateOverflowProtection() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS))
            .named("ts")
            .named("test");

        // A millis value that would overflow when multiplied by 1_000_000
        long hugeMillis = Long.MAX_VALUE / 1000;
        Expression expr = eq("ts", DataType.DATETIME, hugeMillis);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        // Should gracefully skip (return null) instead of throwing
        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNull(fp);
    }

    // --- Non-DATETIME types pass through unchanged ---

    public void testToFilterPredicateIntegerPassthrough() {
        MessageType schema = Types.buildMessage().required(INT32).named("id").named("test");

        Expression expr = eq("id", DataType.INTEGER, 42);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString("42"));
    }

    public void testToFilterPredicateLessThanOrEqualDatetime() {
        MessageType schema = Types.buildMessage()
            .required(INT64)
            .as(timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS))
            .named("ts")
            .named("test");

        long millis = 1700000000000L;
        Expression expr = new LessThanOrEqual(Source.EMPTY, attr("ts", DataType.DATETIME), datetimeLit(millis), null);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        assertThat(fp.toString(), containsString("lteq"));
    }

    // --- convertMillisToPhysical unit tests ---

    public void testConvertMillisToPhysicalMillis() {
        LogicalTypeAnnotation ts = timestampType(true, LogicalTypeAnnotation.TimeUnit.MILLIS);
        assertEquals(1234L, ParquetPushedExpressions.convertMillisToPhysical(1234L, ts));
    }

    public void testConvertMillisToPhysicalMicros() {
        LogicalTypeAnnotation ts = timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS);
        assertEquals(1234000L, ParquetPushedExpressions.convertMillisToPhysical(1234L, ts));
    }

    public void testConvertMillisToPhysicalNanos() {
        LogicalTypeAnnotation ts = timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS);
        assertEquals(1234000000L, ParquetPushedExpressions.convertMillisToPhysical(1234L, ts));
    }

    public void testConvertMillisToPhysicalNanosOverflow() {
        LogicalTypeAnnotation ts = timestampType(true, LogicalTypeAnnotation.TimeUnit.NANOS);
        expectThrows(ArithmeticException.class, () -> ParquetPushedExpressions.convertMillisToPhysical(Long.MAX_VALUE / 1000, ts));
    }

    public void testConvertMillisToPhysicalNoAnnotation() {
        assertEquals(5678L, ParquetPushedExpressions.convertMillisToPhysical(5678L, null));
    }

    // --- WildcardLike → prefix-range translation ---
    //
    // Translation makes WildcardLike pushable as a real Parquet FilterPredicate (gtEq AND lt)
    // when the pattern has a literal prefix, enabling row-group / page-index / dictionary
    // skipping that the late-mat evaluator alone cannot trigger. The exact LIKE still runs
    // in late-mat over the surviving rows so the result remains correct (the prefix range is
    // a sound over-approximation: every match starts with the prefix, but not every value in
    // the range matches).

    public void testWildcardLikeTrailingLiteralBecomesRange() {
        // "foo*bar" — prefix is "foo", surviving range is [foo, fop). Late-mat applies "*bar"
        // on the survivors. ReplaceRegexMatch does NOT rewrite this to StartsWith (the suffix
        // matters), so this branch fires.
        assertPrefixRange(wildcardLike("s", "foo*bar"), "s", "foo", "fop");
    }

    public void testWildcardLikeQuestionMarkPrefixBecomesRange() {
        // "foo?bar" — '?' is a 1-char wildcard, which terminates the literal-prefix scan.
        // Prefix is still "foo"; range [foo, fop) is sound (every match starts with "foo").
        assertPrefixRange(wildcardLike("s", "foo?bar"), "s", "foo", "fop");
    }

    public void testWildcardLikeMiddleAndTrailingWildcardBecomesRange() {
        // "https*google*" — the q22/q23-shape pattern. Prefix is "https"; range
        // [https, httpt). Late-mat then applies the *google* part. This is the
        // primary motivating case for the optimization.
        assertPrefixRange(wildcardLike("url", "https*google*"), "url", "https", "httpt");
    }

    public void testWildcardLikeEscapedStarKeptAsLiteral() {
        // "\*foo*" — the leading '*' is escaped so it's a literal. Prefix is "*foo".
        // Pins that escape handling matches AbstractStringPattern#extractPrefix's contract.
        assertPrefixRange(wildcardLike("s", "\\*foo*"), "s", "*foo", "*fop");
    }

    public void testWildcardLikeLeadingStarHasNoPrefix() {
        // "*google*" — no literal prefix; nothing to push as a Parquet FilterPredicate.
        // Late-mat still runs the LIKE; the only thing missing is row-group skipping.
        // This is a no-op — translateExpression returns null and the combined predicate
        // collapses to null too (toFilterPredicate returns null when nothing translates).
        MessageType schema = keywordSchema("url");
        Expression like = wildcardLike("url", "*google*");
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(like));

        assertNull("leading wildcard must not translate to a Parquet predicate", pushed.toFilterPredicate(schema));
    }

    public void testWildcardLikeLeadingQuestionMarkHasNoPrefix() {
        // "?foo*" — leading '?' (single-char wildcard) means no literal prefix exists.
        MessageType schema = keywordSchema("s");
        Expression like = wildcardLike("s", "?foo*");
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(like));

        assertNull(pushed.toFilterPredicate(schema));
    }

    public void testWildcardLikeCaseInsensitiveDoesNotPushRange() {
        // Case-insensitive "FoO*" matches "foo", "FOO", "fOo", etc., which lie outside
        // ["FoO", "FoO~"). Pushing the case-sensitive range would silently prune true matches
        // — a correctness bug, not just an inefficiency. translateWildcardLikePrefix gates on
        // wl.caseInsensitive() to keep the filter unpushable here; late-mat alone runs the
        // case-aware automaton.
        MessageType schema = keywordSchema("s");
        Expression like = new WildcardLike(Source.EMPTY, attr("s", DataType.KEYWORD), new WildcardPattern("FoO*"), true);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(like));

        assertNull("case-insensitive WildcardLike must not push a range predicate", pushed.toFilterPredicate(schema));
    }

    public void testWildcardLikePureLiteralBecomesRange() {
        // "exact" (no wildcards) — extractPrefix returns the whole string, so we push
        // ["exact", "exacu"). This is technically equivalent to Eq("exact") but the
        // logical-layer ReplaceRegexMatch already rewrites such patterns to Eq via
        // exactMatch(); we just stay safe here in case a non-rewritten pattern reaches us.
        assertPrefixRange(wildcardLike("s", "exact"), "s", "exact", "exacu");
    }

    public void testWildcardLikeNonKeywordDoesNotPushRange() {
        // The translateWildcardLikePrefix branch is gated on KEYWORD; other column types
        // wouldn't have reached pushFilters in the first place (canConvert filters them out),
        // but pin the type-guard at the translation layer too as a defense-in-depth.
        MessageType schema = Types.buildMessage().required(INT64).named("ts").named("test");
        // Note: this constructs an "invalid" WildcardLike on a DATETIME column to bypass the
        // canConvert gate. translateExpression should still refuse to emit a range predicate.
        Expression like = new WildcardLike(Source.EMPTY, attr("ts", DataType.DATETIME), new WildcardPattern("foo*"));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(like));

        assertNull(pushed.toFilterPredicate(schema));
    }

    public void testWildcardLikeRangeCombinesWithOtherPushedFilter() {
        // When a query has multiple pushable filters, toFilterPredicate ANDs them. Verify the
        // WildcardLike prefix range composes cleanly with another conjunct — otherwise a
        // future change to the AND-combining loop could silently drop one side.
        MessageType schema = Types.buildMessage()
            .required(INT32)
            .named("status")
            .required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType())
            .named("url")
            .named("test");

        Expression status = eq("status", DataType.INTEGER, 200);
        Expression like = wildcardLike("url", "https*google*");
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(status, like));

        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull(fp);
        String repr = fp.toString();
        assertThat(repr, containsString("eq(status, 200)"));
        // Range bytes on a binary column are rendered by Parquet as the byte-array literal
        // form (e.g. "Binary{5 constant bytes, [104, 116, 116, 112, 115]}" for "https").
        // Match by the stable substring — column name + comparator + the byte array prefix.
        assertThat(repr, containsString("gteq(url, " + binaryRepr("https")));
        assertThat(repr, containsString("lt(url, " + binaryRepr("httpt")));
    }

    public void testNotOverWildcardLikeDoesNotPushAnyPredicate() {
        // CRITICAL regression test: prefix-range is an *over*-approximation of LIKE matches.
        // Negating that range yields an *under*-approximation of NOT(LIKE), so pushing
        // NOT(prefixRange) to Parquet would skip row groups that may contain values which the
        // LIKE rejects but NOT(LIKE) accepts. Concrete: "food" lies in ["foo", "fop") so
        // NOT(prefixRange) excludes it, but NOT(LIKE 'foo*bar') accepts "food". Translation
        // must therefore decline to emit a Parquet predicate; late-mat handles negation
        // correctly via evaluateNotWildcardLike's TVL-aware path.
        MessageType schema = keywordSchema("s");
        Expression notLike = new Not(Source.EMPTY, wildcardLike("s", "foo*bar"));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(notLike));
        assertNull(
            "Not(WildcardLike) must not produce a Parquet predicate (over-approximation flips on negation)",
            pushed.toFilterPredicate(schema)
        );
    }

    public void testNotOverWildcardLikeStillBlocksPushdownEvenForLeadingWildcardPattern() {
        // The Not branch refuses to translate any Not(WildcardLike), even patterns like "*foo"
        // where the inner WildcardLike would itself produce no predicate (no literal prefix).
        // Pin this so a future change that "optimizes" the guard cannot accidentally let the
        // unsound case slip through.
        MessageType schema = keywordSchema("s");
        Expression notLike = new Not(Source.EMPTY, wildcardLike("s", "*foo"));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(notLike));
        assertNull(pushed.toFilterPredicate(schema));
    }

    public void testNotOverAndContainingWildcardLikeDoesNotPush() {
        // CRITICAL composite case: Not(And(WildcardLike, Eq)) must not produce a Parquet
        // predicate. Without the recursive contains-check, the And branch would build
        // and(prefixRange("foo"…"fop"), eq(n, 5)), then the Not branch would wrap it in
        // FilterApi.not(...). That predicate skips row groups containing values like
        // ("food", 5) — which NOT(LIKE 'foo*bar' AND n=5) is supposed to keep, since "food"
        // does NOT match LIKE 'foo*bar'. Late-mat would never see those rows because the row
        // group would be pruned at I/O time. Refusing to translate keeps FilterExec / late-mat
        // as the source of truth.
        MessageType schema = Types.buildMessage()
            .required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType())
            .named("s")
            .required(INT32)
            .named("n")
            .named("test");
        Expression and = new And(Source.EMPTY, wildcardLike("s", "foo*bar"), eq("n", DataType.INTEGER, 5));
        Expression notAnd = new Not(Source.EMPTY, and);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(notAnd));
        assertNull("Not(And(WildcardLike, X)) must not push a Parquet predicate", pushed.toFilterPredicate(schema));
    }

    public void testNotOverOrContainingWildcardLikeDoesNotPush() {
        // De Morgan's: Not(Or(WL, X)) ≡ And(Not(prefixRange), Not(X)). The And-of-Nots also
        // contains the unsound Not(prefixRange) factor, so the same blanket refusal applies
        // even for disjunctions under negation. Pin this so a clever future "optimization"
        // doesn't rewrite Not(Or(...)) into the And-of-Nots form and bypass the guard.
        MessageType schema = Types.buildMessage()
            .required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType())
            .named("s")
            .required(INT32)
            .named("n")
            .named("test");
        Expression or = new Or(Source.EMPTY, wildcardLike("s", "foo*bar"), eq("n", DataType.INTEGER, 5));
        Expression notOr = new Not(Source.EMPTY, or);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(notOr));
        assertNull("Not(Or(WildcardLike, X)) must not push a Parquet predicate", pushed.toFilterPredicate(schema));
    }

    public void testDoubleNotOverWildcardLikeDoesNotPush() {
        // Not(Not(WildcardLike)) is logically WildcardLike, but the optimizer may not have
        // simplified it by the time we see it. The contains-check catches this too: outer Not
        // walks the subtree, finds a WildcardLike, refuses to translate. The cost of being
        // conservative here is at most losing prefix-range pushdown for an already-rare
        // double-negation; correctness is not at risk because this path returning null just
        // falls back to the late-mat evaluator (which handles the LIKE correctly).
        MessageType schema = keywordSchema("s");
        Expression doubleNot = new Not(Source.EMPTY, new Not(Source.EMPTY, wildcardLike("s", "foo*bar")));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(doubleNot));
        assertNull(pushed.toFilterPredicate(schema));
    }

    public void testAndOfNotWildcardLikeAndEqualsKeepsEqualsPush() {
        // Sanity: when the WildcardLike is under a Not but the *sibling* of the Not in an And
        // is a sound, exact predicate (Eq), we should still get the Eq's predicate pushed.
        // This works because the And-combining loop in toFilterPredicate skips null sides:
        // the Not(WL) side returns null (correctly), and the And reduces to just the Eq side.
        MessageType schema = Types.buildMessage()
            .required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType())
            .named("s")
            .required(INT32)
            .named("n")
            .named("test");
        Expression and = new And(Source.EMPTY, new Not(Source.EMPTY, wildcardLike("s", "foo*bar")), eq("n", DataType.INTEGER, 5));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(and));
        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull("Eq side of the And must still push", fp);
        assertThat(fp.toString(), containsString("eq(n, 5)"));
    }

    public void testNotOverEqualsStillTranslates() {
        // Sanity check that the new Not(WildcardLike) guard doesn't regress unrelated negation
        // pushdown: NOT(Eq) translates to an exact Parquet NotEq, which is sound (Eq is exact,
        // not an over-approximation), so the Not branch must continue to recurse for non-LIKE
        // children.
        MessageType schema = keywordSchema("s");
        Expression notEq = new Not(Source.EMPTY, eq("s", DataType.KEYWORD, new BytesRef("foo")));
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(notEq));
        FilterPredicate fp = pushed.toFilterPredicate(schema);
        assertNotNull("Not(Equals) must still push as a Parquet not-equals", fp);
        // Sanity: the predicate references the column.
        assertThat(fp.toString(), containsString("s"));
    }

    public void testWildcardLikeRangeMatchesStartsWithRangeForSamePrefix() {
        // The prefixRange helper is shared by StartsWith and WildcardLike, so for the same
        // underlying prefix ("foo") both must emit byte-identical FilterPredicates. Pins the
        // shared-helper invariant — drift would mean a regression in one branch goes unnoticed
        // by the other branch's tests.
        MessageType schema = keywordSchema("s");
        Expression sw = new org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith(
            Source.EMPTY,
            attr("s", DataType.KEYWORD),
            lit(new BytesRef("foo"), DataType.KEYWORD)
        );
        Expression like = wildcardLike("s", "foo*bar"); // "foo*" alone would be rewritten to StartsWith upstream
        FilterPredicate fpStartsWith = new ParquetPushedExpressions(List.of(sw)).toFilterPredicate(schema);
        FilterPredicate fpLike = new ParquetPushedExpressions(List.of(like)).toFilterPredicate(schema);
        assertNotNull(fpStartsWith);
        assertNotNull(fpLike);
        // Both should produce the same gtEq/lt range against "foo" / "fop".
        assertEquals(fpStartsWith.toString(), fpLike.toString());
    }

    private static MessageType keywordSchema(String columnName) {
        return Types.buildMessage()
            .required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType())
            .named(columnName)
            .named("test");
    }

    private static Expression wildcardLike(String column, String pattern) {
        return new WildcardLike(Source.EMPTY, attr(column, DataType.KEYWORD), new WildcardPattern(pattern));
    }

    /**
     * Asserts the predicate translates to {@code col >= lowerInclusive AND col < upperExclusive}.
     * Parquet renders binary literals as their byte-array decomposition (e.g.
     * {@code Binary{3 constant bytes, [102, 111, 111]}} for {@code "foo"}), not as quoted
     * strings, so we cannot match on string literals — we match by the column name, comparator,
     * and the byte-array form of the bound.
     */
    private static void assertPrefixRange(Expression filter, String column, String lowerInclusive, String upperExclusive) {
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(filter));
        FilterPredicate fp = pushed.toFilterPredicate(keywordSchema(column));
        assertNotNull("expected " + filter + " to translate to a prefix range", fp);
        String repr = fp.toString();
        assertThat(repr, containsString("gteq(" + column + ", " + binaryRepr(lowerInclusive)));
        assertThat(repr, containsString("lt(" + column + ", " + binaryRepr(upperExclusive)));
        assertThat("must be an AND of the two bounds", repr, containsString("and("));
    }

    /**
     * Reproduces Parquet's {@code Binary.toString()} format for a byte-string literal so tests
     * can assert against it without depending on a quoted-string representation that the
     * library does not actually produce. Format: {@code Binary{N constant bytes, [b0, b1, ...]}}
     * with no closing brace so the substring matcher tolerates the trailing context (e.g. the
     * predicate's closing parens).
     */
    private static String binaryRepr(String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("Binary{").append(bytes.length).append(" constant bytes, [");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(bytes[i] & 0xff);
        }
        sb.append("]");
        return sb.toString();
    }

    // --- predicateColumnNames tests ---

    public void testPredicateColumnNamesSingleComparison() {
        Expression expr = new GreaterThan(Source.EMPTY, attr("status", DataType.LONG), lit(200L, DataType.LONG), null);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr));
        assertEquals(Set.of("status"), pushed.predicateColumnNames());
    }

    public void testPredicateColumnNamesAndTwoColumns() {
        Expression left = new GreaterThan(Source.EMPTY, attr("age", DataType.LONG), lit(18L, DataType.LONG), null);
        Expression right = new LessThan(Source.EMPTY, attr("score", DataType.LONG), lit(100L, DataType.LONG), null);
        Expression and = new And(Source.EMPTY, left, right);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(and));
        assertEquals(Set.of("age", "score"), pushed.predicateColumnNames());
    }

    public void testPredicateColumnNamesDeduplicated() {
        Expression expr1 = new GreaterThan(Source.EMPTY, attr("x", DataType.LONG), lit(1L, DataType.LONG), null);
        Expression expr2 = new LessThan(Source.EMPTY, attr("x", DataType.LONG), lit(10L, DataType.LONG), null);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(expr1, expr2));
        assertEquals(Set.of("x"), pushed.predicateColumnNames());
    }

    public void testPredicateColumnNamesNestedAndOrNot() {
        Expression a = new GreaterThan(Source.EMPTY, attr("col_a", DataType.LONG), lit(1L, DataType.LONG), null);
        Expression b = new LessThan(Source.EMPTY, attr("col_b", DataType.LONG), lit(5L, DataType.LONG), null);
        Expression c = new Equals(Source.EMPTY, attr("col_c", DataType.LONG), lit(0L, DataType.LONG), null);
        Expression and = new And(Source.EMPTY, a, b);
        Expression not = new Not(Source.EMPTY, c);
        Expression or = new Or(Source.EMPTY, and, not);
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of(or));
        assertEquals(Set.of("col_a", "col_b", "col_c"), pushed.predicateColumnNames());
    }

    public void testPredicateColumnNamesEmpty() {
        ParquetPushedExpressions pushed = new ParquetPushedExpressions(List.of());
        assertEquals(Set.of(), pushed.predicateColumnNames());
    }

    // --- helpers ---

    private static Expression eq(String name, DataType type, Object value) {
        return new Equals(Source.EMPTY, attr(name, type), lit(value, type), null);
    }

    private static Attribute attr(String name, DataType type) {
        return new ReferenceAttribute(Source.EMPTY, name, type);
    }

    private static Literal lit(Object value, DataType type) {
        return new Literal(Source.EMPTY, value, type);
    }

    private static Literal datetimeLit(long millis) {
        return new Literal(Source.EMPTY, millis, DataType.DATETIME);
    }
}
