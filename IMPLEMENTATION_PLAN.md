# Aggregate Pushdown for External Data Sources — EXECUTION PLAN

## Quick Architecture

**Issue #376 Pattern**: Move pushdown support directly to FormatReader interface (no separate registry).

```
FormatReader.aggregatePushdownSupport() → AggregatePushdownSupport
  ↓
PushAggregatesToExternalSource rule queries via FormatReaderRegistry
  ↓
Format reader executes: extract stats via existing SourceStatisticsSerializer
  ↓
Emit single-row result
```

## Stage 0: Add aggregatePushdownSupport() to FormatReader Interface

**Files to modify**:

1. `x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/datasources/spi/FormatReader.java`
   - Add method:
     ```java
     default AggregatePushdownSupport aggregatePushdownSupport() {
         return AggregatePushdownSupport.UNSUPPORTED;
     }
     ```
   - Default implementation returns UNSUPPORTED (opt-in per format)

2. Create `x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/datasources/spi/AggregatePushdownSupport.java`
   ```java
   public interface AggregatePushdownSupport {
       enum Pushability { YES, NO }

       Pushability canPushAggregates(List<AggregateFunction> aggs, List<Expression> grouping);
       AggregatePushdownResult pushAggregates(List<AggregateFunction> aggs, List<Expression> grouping);

       record AggregatePushdownResult(
           Object pushedHint,                      // Format-specific opaque hint
           List<AggregateFunction> remainder       // Aggregates that must stay in AggregateExec
       ) {}

       AggregatePushdownSupport UNSUPPORTED = new AggregatePushdownSupport() {
           @Override
           public Pushability canPushAggregates(List<AggregateFunction> aggs, List<Expression> grouping) {
               return Pushability.NO;
           }
           // ...
       };
   }
   ```

3. `x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/plan/physical/ExternalSourceExec.java`
   - Add field (via existing Builder if present, or add withPushedAggregate()):
     ```java
     Object pushedAggregate;  // opaque, not serialized
     ```
   - Ensure readFrom() leaves pushedAggregate as null (not deserialized)

4. `x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/datasources/spi/SourceOperatorContext.java`
   - Add field to builder: `Object pushedAggregate`
   - Forward to format reader

**Success**: FormatReader interface now declares pushdown support; implementations can override with concrete behavior.

---

## Stage 1: Implement PushAggregatesToExternalSource Optimizer Rule

**File to create**:

`x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/optimizer/rules/physical/local/PushAggregatesToExternalSource.java`

```java
public class PushAggregatesToExternalSource extends PhysicalOptimizerRules.OptimizerRule<AggregateExec> {

    private final FormatReaderRegistry formatReaderRegistry;

    public PushAggregatesToExternalSource(FormatReaderRegistry formatReaderRegistry) {
        this.formatReaderRegistry = formatReaderRegistry;
    }

    @Override
    protected PhysicalPlan rule(AggregateExec aggregateExec) {
        // Pattern: AggregateExec → ExternalSourceExec
        if (!(aggregateExec.child() instanceof ExternalSourceExec externalExec)) {
            return aggregateExec;
        }

        // Ungrouped only (Phase 1)
        if (!aggregateExec.groupings().isEmpty()) {
            return aggregateExec;
        }

        // Get format reader for this source type
        String sourceType = externalExec.sourceType();
        FormatReader formatReader = formatReaderRegistry.getFormatReader(sourceType);
        if (formatReader == null) {
            return aggregateExec;
        }

        // Query format reader's pushdown support
        AggregatePushdownSupport support = formatReader.aggregatePushdownSupport();
        if (support == AggregatePushdownSupport.UNSUPPORTED) {
            return aggregateExec;
        }

        // Extract AggregateFunction expressions from aggregates
        List<AggregateFunction> aggs = extractAggregates(aggregateExec.aggregates());

        // Can we push these?
        if (support.canPushAggregates(aggs, List.of()) != Pushability.YES) {
            return aggregateExec;
        }

        // Get pushed hint + remainder
        AggregatePushdownResult result = support.pushAggregates(aggs, List.of());

        // Create new ExternalSourceExec with pushedAggregate hint
        ExternalSourceExec pushed = externalExec.withPushedAggregate(result.pushedHint());

        // If all aggregates pushed, return just the external source
        if (result.remainder().isEmpty()) {
            return pushed;
        }

        // Otherwise, keep remaining aggregates in AggregateExec
        List<NamedExpression> remainingAggs = buildRemainderAggregates(aggregateExec.aggregates(), result.remainder());
        return new AggregateExec(
            aggregateExec.source(),
            aggregateExec.groupings(),
            remainingAggs,
            pushed
        );
    }

    private List<AggregateFunction> extractAggregates(List<? extends NamedExpression> aggregates) {
        // Extract the actual AggregateFunction from Alias wrappers
        // e.g., Alias(name="count", child=Count(*))
    }

    private List<NamedExpression> buildRemainderAggregates(
        List<? extends NamedExpression> original,
        List<AggregateFunction> remainder
    ) {
        // Rebuild aggregates list with only the ones in remainder
    }
}
```

**File to modify**:

`x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/optimizer/LocalPhysicalPlanOptimizer.java`

- Add PushAggregatesToExternalSource to rule batches
- **CRITICAL**: Place AFTER PushFiltersToSource
  ```java
  // Batch 1: Limit pushdown
  new PushLimitToExternalSource()

  // Batch 2: Filter pushdown (must run before aggregates)
  new PushFiltersToSource()

  // Batch 3: Aggregate pushdown (operates on filtered context)
  new PushAggregatesToExternalSource(formatReaderRegistry)
  ```

**Success**: Rule detects AggregateExec → ExternalSourceExec, queries FormatReader, creates pushedAggregate hint.

---

## Stage 2: Implement Parquet & ORC Aggregate Pushdown Support

### Parquet Implementation

**File to create**:

`x-pack/plugin/esql-datasource-parquet/src/main/java/org/elasticsearch/xpack/esql/datasource/parquet/ParquetAggregatePushdownSupport.java`

```java
public class ParquetAggregatePushdownSupport implements AggregatePushdownSupport {

    @Override
    public Pushability canPushAggregates(List<AggregateFunction> aggs, List<Expression> grouping) {
        if (!grouping.isEmpty()) {
            return Pushability.NO;  // Phase 1: ungrouped only
        }

        for (AggregateFunction agg : aggs) {
            // Pushable: COUNT(*), COUNT(col), MIN(col), MAX(col)
            if (agg instanceof Count || agg instanceof Min || agg instanceof Max) {
                continue;
            }
            // Unpushable: SUM, AVG, percentiles, etc.
            return Pushability.NO;
        }

        return Pushability.YES;
    }

    @Override
    public AggregatePushdownResult pushAggregates(List<AggregateFunction> aggs, List<Expression> grouping) {
        // For Phase 1, all-or-nothing: if canPush said YES, push all
        // Partial pushdown (some aggregates have stats, some don't) deferred to Phase 2
        return new AggregatePushdownResult(new ParquetAggregateHint(aggs), List.of());
    }
}
```

**Helper class**:

`x-pack/plugin/esql-datasource-parquet/src/main/java/org/elasticsearch/xpack/esql/datasource/parquet/ParquetAggregateHint.java`

```java
public record ParquetAggregateHint(List<AggregateFunction> aggregates) {}
```

**File to modify**:

`x-pack/plugin/esql-datasource-parquet/src/main/java/org/elasticsearch/xpack/esql/datasource/parquet/ParquetFormatReader.java`

- Override `aggregatePushdownSupport()`:
  ```java
  @Override
  public AggregatePushdownSupport aggregatePushdownSupport() {
      return new ParquetAggregatePushdownSupport();
  }
  ```

- In `read()` method, handle `context.pushedAggregate()`:
  ```java
  @Override
  public CloseableIterator<Page> read(StorageObject object, FormatReadContext context) throws IOException {
      if (context.pushedAggregate() != null) {
          ParquetAggregateHint hint = (ParquetAggregateHint) context.pushedAggregate();
          return computeAggregatesFromMetadata(object, hint);
      }
      // Normal scanning...
  }

  private CloseableIterator<Page> computeAggregatesFromMetadata(
      StorageObject object,
      ParquetAggregateHint hint
  ) throws IOException {
      // 1. Open Parquet file, extract metadata
      ParquetFileReader fileReader = ParquetFileReader.open(...);
      Map<String, Object> sourceMetadata = extractStatistics(...);  // Reuse existing code

      // 2. Extract aggregate values using SourceStatisticsSerializer
      List<Object> values = new ArrayList<>();
      for (AggregateFunction agg : hint.aggregates()) {
          if (agg instanceof Count count) {
              values.add(extractCount(sourceMetadata, count));
          } else if (agg instanceof Min min) {
              values.add(extractMin(sourceMetadata, min));
          } else if (agg instanceof Max max) {
              values.add(extractMax(sourceMetadata, max));
          }
      }

      // 3. Build result page
      Block[] blocks = buildBlocks(values);
      Page resultPage = new Page(blocks);

      return CloseableIterator.single(resultPage);
  }

  private Object extractCount(Map<String, Object> sourceMetadata, Count count) {
      // Reuse PushStatsToExternalSource logic (lines 98-115)
      if (count.hasFilter()) return null;
      if (count.field().foldable()) {
          return SourceStatisticsSerializer.extractRowCount(sourceMetadata)
              .map(Long::valueOf).orElse(null);
      }
      if (count.field() instanceof ReferenceAttribute ref) {
          OptionalLong rc = SourceStatisticsSerializer.extractRowCount(sourceMetadata);
          OptionalLong nc = SourceStatisticsSerializer.extractColumnNullCount(sourceMetadata, ref.name());
          if (rc.isPresent() && nc.isPresent()) {
              return rc.getAsLong() - nc.getAsLong();
          }
      }
      return null;
  }

  private Object extractMin(Map<String, Object> sourceMetadata, Min min) {
      // Reuse PushStatsToExternalSource logic (lines 117-127)
      if (min.hasFilter()) return null;
      if (min.field() instanceof ReferenceAttribute ref) {
          return SourceStatisticsSerializer.extractColumnMin(sourceMetadata, ref.name())
              .orElse(null);
      }
      return null;
  }

  private Object extractMax(Map<String, Object> sourceMetadata, Max max) {
      // Reuse PushStatsToExternalSource logic (lines 129-139)
      if (max.hasFilter()) return null;
      if (max.field() instanceof ReferenceAttribute ref) {
          return SourceStatisticsSerializer.extractColumnMax(sourceMetadata, ref.name())
              .orElse(null);
      }
      return null;
  }

  private Block[] buildBlocks(List<Object> values) {
      // Reuse PushStatsToExternalSource logic (lines 141-150+)
      // Already handles Long, Integer, Double, Bytes, etc.
  }
  ```

### ORC Implementation

**Mirror Parquet pattern**:

1. Create `OrcAggregatePushdownSupport` (identical logic to Parquet for canPush/push)
2. Create `OrcAggregateHint` (identical to ParquetAggregateHint)
3. Modify `OrcFormatReader`:
   - Override `aggregatePushdownSupport()` → new OrcAggregatePushdownSupport()
   - Add aggregate handling in `read()` method
   - Use existing `extractStatistics()` for metadata (lines 93-152)

**Success**: Format readers can now extract aggregates from metadata and emit single-row result without scanning data.

---

## Stage 3: Testing & Validation

**Integration tests** (in each format's test suite):

1. `ParquetFormatReaderTests`:
   - Test: COUNT(*) from Parquet metadata
   - Test: COUNT(col) with null counts
   - Test: MIN/MAX from row groups
   - Test: Multiple row groups aggregate correctly
   - Test: Normal scanning still works when no pushed aggregate

2. `OrcFormatReaderTests`:
   - Same tests for ORC stripe statistics

3. `PushAggregatesToExternalSourceTests`:
   - Test: Rule detects and pushes ungrouped COUNT/MIN/MAX
   - Test: Rule respects filter pushdown ordering
   - Test: Rule leaves grouped aggregates unchanged (deferred)

4. E2E tests:
   - Multi-file query with mixed pushability
   - Verify results match non-pushed baseline

---

## Key Implementation Points

### Type Conversion Already Exists

- **Parquet**: `stats.genericGetMin()` / `genericGetMax()` (line 126 ParquetFormatReader) returns typed Comparable objects
- **ORC**: `extractOrcMin()` / `extractOrcMax()` (lines 154-174 OrcFormatReader) already type-converts per ColumnStatistics class
- Both serialize to SourceStatistics → SourceStatisticsSerializer → Map<String, Object>
- **Action**: Just use SourceStatisticsSerializer.extract* methods (already type-safe)

### Reuse Existing Code Patterns

- Copy value extraction logic from `PushStatsToExternalSource` (lines 87-139)
- Copy block building logic from `PushStatsToExternalSource` (lines 141-150+)
- Reuse `SourceStatisticsSerializer.extractRowCount()`, `.extractColumnNullCount()`, `.extractColumnMin()`, `.extractColumnMax()`
- Reuse `PlannerUtils.NON_BREAKING_BLOCK_FACTORY` for block creation

### No Separate Infrastructure Needed

- ✅ No separate AggregatePushdownRegistry (use FormatReader interface)
- ✅ No DataSourcePlugin registration (override aggregatePushdownSupport() in each format)
- ✅ No custom type conversion (Parquet/ORC libraries already handle it)
- ✅ Statistics already serialized via SourceStatisticsSerializer

---

## Timeline (Realistic)

- **Stage 0**: 4-6 hours (add interface method, one opaque hint field)
- **Stage 1**: 6-8 hours (implement optimizer rule, understand rule ordering)
- **Stage 2 Parquet**: 4-6 hours (mostly copying from PushStatsToExternalSource + integrating with read())
- **Stage 2 ORC**: 2-4 hours (mirror Parquet)
- **Stage 3**: 4-6 hours (integration + E2E tests)
- **Total**: 20-30 hours (2.5-4 days)

---

## Risks & Mitigations

1. **Rule ordering**: Filter pushdown MUST run before aggregate pushdown. If not, statistics don't account for filters.
   - **Mitigation**: Document in LocalPhysicalPlanOptimizer batch ordering; add unit test verifying order

2. **Partial statistics** (Phase 2): Some files might have COUNT but not MIN statistics.
   - **Mitigation**: Phase 1 uses all-or-nothing. Phase 2 adds partial pushdown via AggregatePushdownResult.remainder

3. **Performance regression**: Adding pushdown check to every external source query.
   - **Mitigation**: Pushdown support lookup is O(1) (interface method); negligible overhead

4. **Filtered aggregates**: COUNT(field) WHERE ... not yet supported.
   - **Mitigation**: PushStatsToExternalSource already rejects filtered aggregates (hasFilter() check). Same pattern applies here.
