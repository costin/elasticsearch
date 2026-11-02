# I7 `dictionarySafe` classification

Rule: a kernel that can throw on any **legal** input value is not dictionary-safe. The encoding-aware path (`FusionPlanner.encodingAware` / `dictionarySafeTree`, `FusionPlan` javadoc) is taken only for a single `BYTES_REF` column input with a single input leaf where every kernel is `!warns && dictionarySafe`.

Source: `DictionarySafeClassificationTests` reads production `FusionPlanner` descriptor constants and generated `*Evaluator.Factory.FUSION_DESCRIPTOR` fields.

| kernel | dictionarySafe | reason |
|---|---|---|
| CONCAT (`Concat`) | false | Can throw (over 1MB) on legal `BytesRef` inputs, independent of `warnExceptions`; encoding-aware path must refuse it. |
| SPACE (`Space`) | false | Throws `IllegalArgumentException` on a negative/too-long legal int count; also `warns=true`. Not actually reachable from the single-`BYTES_REF` path (input is int), but `false` defensively. |
| TRIM | true | Unary `BytesRef→BytesRef`; legal `BYTES_REF` values are valid UTF-8; `UnicodeUtil` only throws on invalid UTF-8, which is not a legal input value. |
| LTRIM | true | Same UTF-8 reasoning as TRIM. |
| RTRIM | true | Same UTF-8 reasoning as TRIM. |
| LEFT | true | Same UTF-8 reasoning; length is an int operand, no throw on legal inputs. |
| RIGHT | true | Same UTF-8 reasoning; length is an int operand, no throw on legal inputs. |
| generated `@Fusable` `*Evaluator.Factory` (sampled: Length, ChangeCase, EqualsBytesRef, AddLongs, DivLongs, AbsLong, CastIntToLong, Tan) | true | Generated kernels only throw declared `warnExceptions` (caught as warnings, encoded in `warns`) or they do not throw. Curated representative set, not a full generated-class enumeration. |
