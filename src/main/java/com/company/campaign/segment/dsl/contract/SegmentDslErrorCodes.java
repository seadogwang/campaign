package com.company.campaign.segment.dsl.contract;

/**
 * Stable machine-readable failure codes owned by the T05 Segment DSL contract.
 *
 * <p>These values are part of the wire contract: callers, the validator, the planner and the refinement
 * task all branch on them, so an existing code is never renamed or reused for a different meaning. A new
 * failure mode gets a new code. Failure details never echo full input JSON or sensitive values.
 */
public final class SegmentDslErrorCodes {

    /** A declared Segment DSL schema version is malformed or is not the exact supported version. */
    public static final String SCHEMA_VERSION_UNSUPPORTED = "SEGMENT_DSL_SCHEMA_VERSION_UNSUPPORTED";

    /** A JSON document is syntactically invalid. */
    public static final String JSON_MALFORMED = "SEGMENT_DSL_JSON_MALFORMED";

    /** A JSON document contains the same object key more than once. */
    public static final String JSON_DUPLICATE_KEY = "SEGMENT_DSL_JSON_DUPLICATE_KEY";

    /** A JSON object carries a field that is not part of the closed shape being read. */
    public static final String UNKNOWN_FIELD = "SEGMENT_DSL_UNKNOWN_FIELD";

    /** A predicate {@code type}, value {@code kind}, window {@code type} or operator name is not a known member. */
    public static final String UNKNOWN_DISCRIMINATOR = "SEGMENT_DSL_UNKNOWN_DISCRIMINATOR";

    /** A known discriminator is paired with the wrong JSON shape, arity or required-field set. */
    public static final String SHAPE_INVALID = "SEGMENT_DSL_SHAPE_INVALID";

    /** A Metadata Snapshot binding is incomplete or does not match the snapshot it is pinned against. */
    public static final String METADATA_BINDING_INVALID = "SEGMENT_DSL_METADATA_BINDING_INVALID";

    /** A stable node ID is missing, null or outside the bounded reference grammar. */
    public static final String NODE_ID_INVALID = "SEGMENT_DSL_NODE_ID_INVALID";

    /** Two predicate nodes in one tree share a node ID, including across branches or inside EXISTS. */
    public static final String DUPLICATE_NODE_ID = "SEGMENT_DSL_DUPLICATE_NODE_ID";

    /** An operator is paired with a missing, forbidden or wrong-shaped condition value. */
    public static final String OPERATOR_VALUE_SHAPE_INVALID = "SEGMENT_DSL_OPERATOR_VALUE_SHAPE_INVALID";

    /** A TypedValue is malformed, outside its lexical grammar or carries an unknown kind. */
    public static final String TYPED_VALUE_INVALID = "SEGMENT_DSL_TYPED_VALUE_INVALID";

    /** A TimeWindow is malformed, uses a rejected unit or has an invalid boundary pair. */
    public static final String TIME_WINDOW_INVALID = "SEGMENT_DSL_TIME_WINDOW_INVALID";

    /** An input byte, nesting, individual text or collection bound was exceeded. */
    public static final String LIMIT_EXCEEDED = "SEGMENT_DSL_LIMIT_EXCEEDED";

    /** A declared {@code editableDslHash} is not the canonical hash of the editable document. */
    public static final String EDITABLE_HASH_MISMATCH = "SEGMENT_DSL_EDITABLE_HASH_MISMATCH";

    /** An operator name is not part of the closed T04 {@code ComparisonOperator} vocabulary. */
    public static final String UNKNOWN_OPERATOR = "SEGMENT_DSL_UNKNOWN_OPERATOR";

    /** A root subject is missing or is not part of the closed T04 {@code SubjectKind} vocabulary. */
    public static final String SUBJECT_INVALID = "SEGMENT_DSL_SUBJECT_INVALID";

    /** A bounded reference identifier (attribute, metric, relation, snapshot ID or version) is invalid. */
    public static final String REFERENCE_ID_INVALID = "SEGMENT_DSL_REFERENCE_ID_INVALID";

    /**
     * An {@code AUDIENCE_REFERENCE} node is missing an exact version pin or carries a malformed version hash.
     *
     * <p>Only the three pin parts can cause this: a mutable {@code current}/{@code latest} pointer, a name, a
     * query or any owner/scope/permission field is not part of the shape at all and fails as an unknown field
     * or an unknown discriminator instead.
     */
    public static final String AUDIENCE_REFERENCE_INVALID = "SEGMENT_DSL_AUDIENCE_REFERENCE_INVALID";

    private SegmentDslErrorCodes() {
    }
}