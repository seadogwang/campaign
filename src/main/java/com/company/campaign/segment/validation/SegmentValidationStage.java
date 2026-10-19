package com.company.campaign.segment.validation;

/**
 * The exact deterministic validation pipeline, in execution order.
 *
 * <p>The declaration order <em>is</em> the pipeline order: issues are ordered by {@link #ordinal()} first, so
 * a caller can observe that Schema runs before Metadata binding, which runs before definition/type checks,
 * and so on. Adding a stage means appending it, never reordering the existing block.
 */
public enum SegmentValidationStage {

    /** Schema and root invariants: supported DSL schema version and a self-consistent editable hash. */
    SCHEMA,

    /** Exact Metadata snapshot binding pins and snapshot-owner agreement with the trusted context. */
    METADATA_BINDING,

    /** Definition existence, kind, subject, type and operator compatibility. */
    DEFINITION_TYPE_OPERATOR,

    /** Time, currency, NULL and aggregate-empty-set semantics. */
    TIME_CURRENCY_NULL,

    /** Relation and EXISTS scope correctness. */
    RELATION_SCOPE,

    /** Authorized access to each referenced definition through the T02 boundary. */
    AUTHORIZATION,

    /** Complexity and safety policy limits. */
    COMPLEXITY,

    /**
     * Trusted Existing Audience Reference graph resolution: exact immutable pins, ownership, program policy,
     * segment authorization, effective DataScope intersection, cycles and the reference policy bounds.
     *
     * <p>It is appended after {@link #COMPLEXITY} rather than inserted, so the ordinals — and therefore the
     * observed pipeline order — of every existing stage are unchanged. A document with no reference produces
     * no issue in this stage at all, and one with a reference produces its findings only once the upstream
     * stages have had their say, so reference resolution can never mask an earlier, more fundamental failure.
     */
    REFERENCE_GRAPH
}