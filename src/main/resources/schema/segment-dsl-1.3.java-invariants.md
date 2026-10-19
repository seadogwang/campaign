# Segment DSL 1.3 — Java-only invariants

`segment-dsl-1.3.schema.json` is a checked-in Draft 2020-12 JSON Schema for the editable Segment DSL
document. It is a structural description only. The Java contracts under `com.company.campaign.segment.dsl`
are authoritative, and the following invariants are enforced there because JSON Schema cannot express them:

1. **Editable hash verification.** The `editableDslHash` value must equal the canonical SHA-256 hash of the
   document material with `editableDslHash` excluded. The schema can check the hash *format* only.
2. **Unique node IDs across the whole tree.** Every `nodeId` must be unique, including across separate
   branches and inside `EXISTS`. JSON Schema has no cross-node identity constraint.
3. **Predicate nesting depth, input byte limits and strict UTF-8 decoding.** The parser rejects input above
   `SegmentDslLimits.MAX_INPUT_BYTES`, predicate nesting above `MAX_PREDICATE_DEPTH`, and any byte payload
   that is not valid UTF-8 (a malformed sequence is refused, never replaced).
4. **Metadata binding completeness.** The four binding pins must match a retained T04 `MetadataSnapshot` when
   verified through `MetadataBinding.verify`, and the Metadata schema version must be exactly `1.0`.
5. **Calendar and clock validity.** Beyond the lexical form the schema patterns enforce, a DATE or DATETIME
   must name a real calendar day, time and offset: `2026-13-01`, `2026-02-30T00:00:00Z` and
   `2026-01-01T25:00:00Z` match a pattern but are not real instants, so they are rejected only in Java
   (`TemporalGrammar`). The same exact grammar governs FIXED window boundaries.
6. **Scalar bounds and non-lexical rules.** INTEGER is a signed 64-bit value with no fractional/exponent
   form; DECIMAL/MONEY are plain decimals with bounded scale and precision; STRING is bounded and must be
   well-formed Unicode (an unpaired surrogate is refused). The schema approximates the lexical part with
   patterns, ranges and length limits.
7. **Cross-field RANGE ordering.** When both bounds share a kind with a representation-intrinsic order
   (INTEGER, DECIMAL, DATE, DATETIME, same-currency MONEY) an inverted or empty range is rejected.
8. **FIXED window ordering.** `start` must be strictly before `end` on the instant timeline.
9. **Relative window ordering and anchor semantics.** `startOffset` must be strictly less than `endOffset`;
   the referenced anchor must resolve to an authorized DATE/TIMESTAMP Attribute on the Metric subject and
   schema version. A relative window is accepted only on a Metric and is interpreted as
   `[anchor + startOffset, anchor + endOffset)`.
10. **Typed Metric expressions.** `METRIC_EXPRESSION` is a closed tree of `METRIC_REF`, `ADD`, `SUBTRACT`,
    `MULTIPLY`, and `DIVIDE`. Every leaf inherits one common rolling, fixed, or lifetime window and must
    resolve to an authorized Metric. Depth is at most 8 and total expression nodes are at most 32. Addition
    and subtraction require compatible units and currency policy; division requires explicit `EXCLUDED`
    zero-denominator behavior. Free-form formulas, SQL fragments, per-leaf windows, and dynamic Metadata
    creation are not representable.
11. **Existing Audience Reference pins.** `AUDIENCE_REFERENCE` is a leaf whose entire meaning is one exact
    immutable Audience Version. The schema checks the five-field shape and the hash *format* only. Java owns
    the rest: `audienceId` and `versionId` must satisfy the bounded reference grammar, the node must be
    structurally composable under `ALL`/`ANY`/`NOT` and inside `EXISTS`, and its stable `nodeId` must be
    unique across the whole tree like any other node. Nothing about owner, permission, program, DataScope,
    availability, retention or dependencies is representable here — those are trusted host facts resolved by
    T07 through the version catalog, the program policy, the T02 authorization boundary and the DataScope
    intersection port, never read from the document. A document containing a reference is refused unless the
    host supplies that trusted context, and it is never expanded into the editable parent.

The operator/value shape matrix is **not** Java-only: the schema expresses it directly, as a closed
partition of the operator vocabulary with `if`/`then` branches — `IS_NULL`/`IS_NOT_NULL` forbid `value`,
`IN`/`NOT_IN` require a `valueList`, `BETWEEN` requires a `valueRange`, and every other operator requires a
`scalarValue`. `OperatorValueRules` enforces the same matrix on the typed domain model.

T05 validates structure and representation only. Metadata lookup, allowed-operator/type compatibility, enum
membership, relation/subject compatibility, authorization, complexity limits, semantic value normalization,
commutative child sorting and `canonicalAstHash` are owned by T07 and are deliberately absent here.
