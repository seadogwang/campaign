package com.company.campaign.segment.resolution;

/**
 * Closed set of positive evidence codes attached to a candidate.
 *
 * <p>Evidence is a machine code, never free text: the resolver must be able to explain <em>why</em> a
 * definition was considered without echoing a label. The set distinguishes the exact identification evidence
 * (stable-id hint, versioned concept-policy mapping, display match, alias match and positive-example match),
 * each per-slot compatibility check, and the semantic conflicts, so a caller can prove the resolver compared
 * every populated intent slot <em>and</em> had a grounded reason to accept a definition.
 */
public enum EvidenceCode {

    EXACT_STABLE_ID_HINT,
    CONCEPT_POLICY_MATCH,
    EXACT_DISPLAY_MATCH,
    EXACT_ALIAS_MATCH,
    SOURCE_TERM_MATCH,
    FILTER_DOMAIN_MATCH,
    FILTER_IDENTIFIER_KIND_MATCH,
    ENUM_VALUE_MATCH,
    POSITIVE_EXAMPLE_MATCH,
    KIND_MATCH,
    SUBJECT_MATCH,
    OPERATOR_COMPATIBLE,
    VALUE_TYPE_COMPATIBLE,
    VALUE_SHAPE_COMPATIBLE,
    WINDOW_COMPATIBLE,
    CURRENCY_COMPATIBLE,
    AGGREGATION_COMPATIBLE,
    RELATION_COMPATIBLE,
    NEGATIVE_EXAMPLE_CONFLICT,
    SEMANTIC_POLICY_CONFLICT
}
