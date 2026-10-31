# E1 fusion class-dump safety net

Byte-identical gate for Phase E refactors (E6, E7). Dump a fixed corpus of fused
class bytes, then re-dump after each E step and `diff -r`. A refactor step must
leave the diff empty.

The harness is a JUnit test in the compute fusion test package so it can reach
Stitcher's package-private `*Bytecode` seams (including `simdVectorLoopBytecode`).
Do **not** commit the harness to the code branch or any golden `.class` files.
This directory is the git-excluded evidence copy.

## Dump

The test JVM (not the Gradle JVM) must see the output directory:

```bash
# Baseline (before any E6/E7 edit)
rm -rf /tmp/fusion-dump-before
mkdir -p /tmp/fusion-dump-before
./gradlew :x-pack:plugin:esql:compute:test \
  --tests "org.elasticsearch.compute.operator.fusion.FusionClassDumpTests" \
  -Dtests.jvm.argline="-Desql.fusion.dump.dir=/tmp/fusion-dump-before"

# After an E step
rm -rf /tmp/fusion-dump-after
mkdir -p /tmp/fusion-dump-after
./gradlew :x-pack:plugin:esql:compute:test \
  --tests "org.elasticsearch.compute.operator.fusion.FusionClassDumpTests" \
  -Dtests.jvm.argline="-Desql.fusion.dump.dir=/tmp/fusion-dump-after"
```

Override with `-Desql.fusion.dump.dir=...` (preferred) or env `FUSION_DUMP_DIR`.
Default if unset: `build/fusion-dump`.

## Diff (must be empty for E6/E7)

```bash
# Determinism of the harness itself (dump twice, no code change)
diff -r /tmp/fusion-dump-before /tmp/fusion-dump-before-2

# Refactor gate
diff -r /tmp/fusion-dump-before /tmp/fusion-dump-after
```

Both must exit 0 with empty output. Non-empty output means a refactor changed
emitted bytecode — back that path out to a pure move.

## Corpus

34 shapes covering every surviving compile entry point (E4' deleted the
compute() split, so there is no split/unsplit dimension):

| file | entry point | coverage |
|---|---|---|
| `plain_long_add_mul` | `compileVectorLoop` | long, nested add/mul |
| `plain_int_mul` | `compileVectorLoop` | int |
| `plain_double_add` | `compileVectorLoop` | double |
| `plain_long_gt` | `compileVectorLoop` | boolean-root comparison |
| `plain_int_gt` | `compileVectorLoop` | int comparison |
| `plain_cast_int_to_long_add` | `compileVectorLoop` | cast-bridged mixed-width |
| `plain_runtime_constant` | `compileVectorLoop` | runtime constant |
| `plain_baked_div` | `compileVectorLoop` | baked constant |
| `checked_long_add_mul` | `compileVectorLoopChecked` | long, overflow-checked |
| `checked_double_add` | `compileVectorLoopChecked` | double |
| `checked_cast_int_to_long_add` | `compileVectorLoopChecked` | cast-bridged |
| `checked_runtime_constant` | `compileVectorLoopChecked` | runtime constant |
| `checked_baked_div` | `compileVectorLoopChecked` | baked constant |
| `block_long_add_mul` | `compileBlockLoop` | long |
| `block_int_mul` | `compileBlockLoop` | int |
| `block_double_add` | `compileBlockLoop` | double |
| `block_long_gt` | `compileBlockLoop` | boolean-root comparison |
| `block_bytesref_identity` | `compileBlockLoop` | BytesRef leaf |
| `block_bytesref_length_gt` | `compileBlockLoop` | BytesRef + LENGTH > k |
| `block_runtime_constant` | `compileBlockLoop` | runtime constant |
| `block_cast_int_to_long_add` | `compileBlockLoop` | cast-bridged |
| `logical_block_and` | `compileLogicalBlockLoop` | AND |
| `logical_block_or` | `compileLogicalBlockLoop` | OR |
| `logical_block_not` | `compileLogicalBlockLoop` | Not |
| `logical_block_nested` | `compileLogicalBlockLoop` | nested AND/OR/Not |
| `logical_vector_and` | `compileLogicalVectorLoop` | AND |
| `logical_vector_or` | `compileLogicalVectorLoop` | OR |
| `logical_vector_not` | `compileLogicalVectorLoop` | Not |
| `mapping_append_suffix` | `compileMappingBlockLoop` | mapping + constant |
| `simd_long_gt` | `compileVectorLoopSimd` | long GT |
| `simd_double_gt` | `compileVectorLoopSimd` | double GT |
| `simd_int_gt` | `compileVectorLoopSimd` | int GT |
| `filter_eval_cmp` | `compileFilterEvalBlockLoop` | comparison predicate + eval |
| `filter_eval_logical` | `compileFilterEvalBlockLoop` | logical AND predicate + eval |
