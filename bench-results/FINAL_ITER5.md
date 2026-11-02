# Iteration 5 report (merge-prep: Phases D, P0, P1)

Living document. Driven by the orchestrator; each production change is
implemented by an implementer subagent, reviewed by a fresh reviewer
subagent, then committed by the orchestrator. Delivery is a series of
byte-identity-verified commits on `esql/fix/compute-codegen-followup`;
the PR-shaped breakdown and the final `esql/fusion-main` rebase are
tracked separately (see MERGE/FINAL.md and MERGE/CHECK.md for the
per-commit D/P0 log).

Iteration-5 items (iter5 followup) map onto the merge-plan phases:
I5 -> D.0a, I7 -> D.3, I4/I2/I3/I8 -> P0.1, I6 -> P0.4, I1 -> deferred
(evidence-only), I9 -> P2 (separate session).

## I7. dictionarySafe must be chosen, not defaulted (done -- D.3, commit e74b469d9d9)

The four telescoping `FusionDescriptor` convenience constructors (which
defaulted `dictionarySafe=true`) are deleted; every descriptor is now
built from the canonical 10-argument constructor with `warns` and
`dictionarySafe` stated explicitly. The annotation processors already
emit the canonical form. `DictionarySafeClassificationTests`
(`org.elasticsearch.xpack.esql.evaluator.fusion`, same package as
`FusionPlanner`) reads the production descriptor constants and a curated
sample of generated `*Evaluator.Factory.FUSION_DESCRIPTOR` fields and
asserts the classification below.

Rule: a kernel that can throw on any **legal** input value is not
dictionary-safe. The encoding-aware path (`FusionPlanner.encodingAware` /
`dictionarySafeTree`, `FusionPlan` javadoc) is taken only for a single
`BYTES_REF` column input with a single input leaf where every kernel is
`!warns && dictionarySafe`.

| kernel | dictionarySafe | reason |
|---|---|---|
| CONCAT (`Concat`) | false | Can throw (over 1MB) on legal `BytesRef` inputs, independent of `warnExceptions`; encoding-aware path must refuse it. |
| SPACE (`Space`) | false | Throws `IllegalArgumentException` on a negative/too-long legal int count; also `warns=true`. Not actually reachable from the single-`BYTES_REF` path (input is int), but `false` defensively. |
| TRIM | true | Unary `BytesRef->BytesRef`; legal `BYTES_REF` values are valid UTF-8; `UnicodeUtil` only throws on invalid UTF-8, which is not a legal input value. |
| LTRIM | true | Same UTF-8 reasoning as TRIM. |
| RTRIM | true | Same UTF-8 reasoning as TRIM. |
| LEFT | true | Same UTF-8 reasoning; length is an int operand, no throw on legal inputs. |
| RIGHT | true | Same UTF-8 reasoning; length is an int operand, no throw on legal inputs. |
| generated `@Fusable` `*Evaluator.Factory` (sampled: Length, ChangeCase, EqualsBytesRef, AddLongs, DivLongs, AbsLong, CastIntToLong, Tan) | true | Generated kernels only throw declared `warnExceptions` (caught as warnings, encoded in `warns`) or they do not throw. Curated representative set, not a full generated-class enumeration. |

**Done when:**
- [x] No constructor defaults `dictionarySafe` (all 4 convenience ctors deleted; FusionDescriptor is 65 lines).
- [x] The classification test exists (`DictionarySafeClassificationTests`) and its table is copied here.

E1 fused-class corpus byte-identical to D.2 (`MERGE/e1/D3.txt` md5
98ff3ece..., 43 shapes); fusion gate BUILD SUCCESSFUL 5m44s. Non-blocking
follow-up noted by the reviewer: the generated-factory sample is read
reflectively (one `FUSION_DESCRIPTOR` static-field read) -- folded into
the P0.5 de-reflection pass.
