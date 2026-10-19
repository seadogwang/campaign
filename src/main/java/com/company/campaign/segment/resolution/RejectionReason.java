package com.company.campaign.segment.resolution;

/**
 * Closed set of stable reasons a permitted definition was rejected for a clause.
 *
 * <p>Each reason names one incompatibility so a caller can tell an operator refusal from a window, currency,
 * aggregation or relation refusal. A rejected definition is always one that exists in the permitted catalog
 * and was actually compared: an absent or denied definition is never represented here, because the resolver
 * must not disclose whether it exists.
 */
public enum RejectionReason {

    KIND_MISMATCH,
    SUBJECT_MISMATCH,
    OPERATOR_NOT_ALLOWED,
    VALUE_TYPE_INCOMPATIBLE,
    VALUE_SHAPE_INCOMPATIBLE,
    NULL_INCLUSION_AMBIGUOUS,
    WINDOW_UNSUPPORTED,
    CURRENCY_MISSING,
    CURRENCY_INCOMPATIBLE,
    AGGREGATION_MISMATCH,
    RELATION_MISMATCH,
    SCOPE_FILTER_UNSUPPORTED,
    FILTER_SEMANTIC_MISMATCH,
    NEGATIVE_EXAMPLE_CONFLICT,
    SEMANTIC_POLICY_CONFLICT,
    UNSUPPORTED_CAPABILITY
}
