package com.company.campaign.segment.validation;

/**
 * Stable machine-readable failure codes owned by the T07 validator and canonicalizer.
 *
 * <p>These values are part of the wire contract: callers, the API error envelope and later planning tasks
 * branch on them, so an existing code is never renamed or reused for a different meaning. A new failure mode
 * gets a new code. No code's message or details ever echo operand text, definition IDs, tenant/program IDs or
 * thrown causes.
 */
public final class SegmentValidationErrorCodes {

    /** The public boundary was called without a parsed Segment DSL document. */
    public static final String INPUT_MISSING = "SEGMENT_VALIDATION_INPUT_MISSING";

    /** No Metadata Snapshot was supplied, so nothing can be resolved or authorized. */
    public static final String METADATA_SNAPSHOT_MISSING = "SEGMENT_VALIDATION_METADATA_SNAPSHOT_MISSING";

    /** No trusted execution context was supplied, so ownership and authorization cannot be proven. */
    public static final String TRUSTED_CONTEXT_MISSING = "SEGMENT_VALIDATION_TRUSTED_CONTEXT_MISSING";

    /** No complexity policy was supplied, so complexity and safety cannot be bounded. */
    public static final String POLICY_MISSING = "SEGMENT_VALIDATION_POLICY_MISSING";

    /** A complexity policy value is non-positive or exceeds the absolute supported bound. */
    public static final String POLICY_INVALID = "SEGMENT_VALIDATION_POLICY_INVALID";

    /** A declared Segment DSL schema version is not the supported version. */
    public static final String DSL_SCHEMA_UNSUPPORTED = "SEGMENT_VALIDATION_DSL_SCHEMA_UNSUPPORTED";

    /** The document's declared editable hash is not the canonical hash of its editable material. */
    public static final String EDITABLE_HASH_MISMATCH = "SEGMENT_VALIDATION_EDITABLE_HASH_MISMATCH";

    /** A Metadata binding pin (snapshot ID, version, schema version or content hash) does not match. */
    public static final String METADATA_BINDING_MISMATCH = "SEGMENT_VALIDATION_METADATA_BINDING_MISMATCH";

    /** The snapshot tenant/program owner does not match the trusted execution context. */
    public static final String METADATA_OWNER_MISMATCH = "SEGMENT_VALIDATION_METADATA_OWNER_MISMATCH";

    /** A referenced definition ID does not exist in the bound snapshot. */
    public static final String DEFINITION_UNKNOWN = "SEGMENT_VALIDATION_DEFINITION_UNKNOWN";

    /** A referenced definition ID exists but belongs to another definition kind. */
    public static final String DEFINITION_KIND_MISMATCH = "SEGMENT_VALIDATION_DEFINITION_KIND_MISMATCH";

    /** A definition's subject does not match the scope in which it is referenced. */
    public static final String SUBJECT_MISMATCH = "SEGMENT_VALIDATION_SUBJECT_MISMATCH";

    /** A metric nested inside an EXISTS cannot be given an unambiguous scope. */
    public static final String NESTED_METRIC_UNSUPPORTED = "SEGMENT_VALIDATION_NESTED_METRIC_UNSUPPORTED";

    /** The referenced definition does not allow the exact comparison operator. */
    public static final String OPERATOR_NOT_ALLOWED = "SEGMENT_VALIDATION_OPERATOR_NOT_ALLOWED";

    /** A Metric row filter references an Attribute not allowlisted by that Metric. */
    public static final String METRIC_SCOPE_FILTER_NOT_ALLOWED =
            "SEGMENT_VALIDATION_METRIC_SCOPE_FILTER_NOT_ALLOWED";

    /** Metric-expression arithmetic combines incompatible semantic types, units or currency policies. */
    public static final String METRIC_EXPRESSION_TYPE_INCOMPATIBLE =
            "SEGMENT_VALIDATION_METRIC_EXPRESSION_TYPE_INCOMPATIBLE";

    /** A present operand is not a value kind the definition's data type admits. */
    public static final String VALUE_TYPE_INCOMPATIBLE = "SEGMENT_VALIDATION_VALUE_TYPE_INCOMPATIBLE";

    /** A LIST operand is not homogeneous, or a RANGE's bounds are not mutually compatible. */
    public static final String VALUE_SHAPE_INCOMPATIBLE = "SEGMENT_VALIDATION_VALUE_SHAPE_INCOMPATIBLE";

    /** An ENUM operand code is not a member of the referenced definition's declared enum values. */
    public static final String ENUM_CODE_UNKNOWN = "SEGMENT_VALIDATION_ENUM_CODE_UNKNOWN";

    /** A RANGE's bound values are semantically incompatible with each other. */
    public static final String RANGE_BOUNDS_INCOMPATIBLE = "SEGMENT_VALIDATION_RANGE_BOUNDS_INCOMPATIBLE";

    /** Two siblings of one ALL/ANY group have the same canonical meaning despite different node IDs. */
    public static final String SEMANTIC_DUPLICATE_SIBLING = "SEGMENT_VALIDATION_SEMANTIC_DUPLICATE_SIBLING";

    /** Two items of one LIST operand have the same canonical meaning. */
    public static final String SEMANTIC_DUPLICATE_LIST_ITEM = "SEGMENT_VALIDATION_SEMANTIC_DUPLICATE_LIST_ITEM";

    /** A metric does not support the window kind the predicate declares. */
    public static final String WINDOW_UNSUPPORTED = "SEGMENT_VALIDATION_WINDOW_UNSUPPORTED";

    /** A bounded relation window requires a relation event-time capability that is not declared. */
    public static final String RELATION_EVENT_TIME_REQUIRED = "SEGMENT_VALIDATION_RELATION_EVENT_TIME_REQUIRED";

    /** A MONEY operand was supplied for a definition without explicit monetary semantics. */
    public static final String CURRENCY_UNEXPECTED = "SEGMENT_VALIDATION_CURRENCY_UNEXPECTED";

    /** MONEY values inside one operand do not share a currency. */
    public static final String CURRENCY_MISMATCH = "SEGMENT_VALIDATION_CURRENCY_MISMATCH";

    /** A bound definition contradicts a T04 semantic invariant this pipeline relies on. */
    public static final String METADATA_DECLARATION_INCONSISTENT =
            "SEGMENT_VALIDATION_METADATA_DECLARATION_INCONSISTENT";

    /** A referenced Attribute, Metric or Relation was not explicitly allowed by the T02 boundary. */
    public static final String RESOURCE_NOT_ACCESSIBLE = "SEGMENT_VALIDATION_RESOURCE_NOT_ACCESSIBLE";

    /** The predicate tree exceeds the policy's total node budget. */
    public static final String COMPLEXITY_NODES_EXCEEDED = "SEGMENT_VALIDATION_COMPLEXITY_NODES_EXCEEDED";

    /** The predicate tree exceeds the policy's maximum depth. */
    public static final String COMPLEXITY_DEPTH_EXCEEDED = "SEGMENT_VALIDATION_COMPLEXITY_DEPTH_EXCEEDED";

    /** An ANY group's direct width exceeds the policy's maximum. */
    public static final String COMPLEXITY_ANY_WIDTH_EXCEEDED = "SEGMENT_VALIDATION_COMPLEXITY_ANY_WIDTH_EXCEEDED";

    /** An ALL group's direct width exceeds the policy's maximum. */
    public static final String COMPLEXITY_ALL_WIDTH_EXCEEDED = "SEGMENT_VALIDATION_COMPLEXITY_ALL_WIDTH_EXCEEDED";

    /** Nested EXISTS depth exceeds the policy's maximum relation nesting. */
    public static final String COMPLEXITY_RELATION_DEPTH_EXCEEDED =
            "SEGMENT_VALIDATION_COMPLEXITY_RELATION_DEPTH_EXCEEDED";

    /** A validated document could not be canonicalized; no partial AST is published. */
    public static final String CANONICALIZATION_FAILED = "SEGMENT_VALIDATION_CANONICALIZATION_FAILED";

    private SegmentValidationErrorCodes() {
    }
}
