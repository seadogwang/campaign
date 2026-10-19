package com.company.campaign.segment.intent;

/**
 * Closed set of ambiguity codes the model must record instead of resolving by guesswork.
 *
 * <p>Recording an ambiguity is not an error: it is the honest statement that the utterance does not
 * determine one meaning. The resolver turns any recorded ambiguity into {@code NEEDS_INPUT} with a stable
 * question code and never lets confidence override it.
 */
public enum IntentAmbiguity {

    /** Natural-language AND/OR precedence is unresolved. */
    AMBIGUOUS_GROUPING,
    /** A money clause names no currency and no registered policy default applies. */
    MISSING_CURRENCY,
    /** A term matches several Metrics whose aggregate meaning differs. */
    AMBIGUOUS_METRIC_MEANING,
    /** Whether a negative comparison should include NULL members is unresolved. */
    AMBIGUOUS_NULL_INCLUSION,
    /** A related-entity clause does not determine the relation join scope. */
    AMBIGUOUS_RELATION_SCOPE,
    /** A row-level filter may apply to one fact only or also constrain a coordinated aggregate. */
    AMBIGUOUS_FILTER_SCOPE,
    /** The requested window does not determine one bounded window. */
    AMBIGUOUS_WINDOW,
    /** Several permitted definitions are equally compatible. */
    AMBIGUOUS_DEFINITION,
    /** The requested business property has no explicit definition in the permitted Metadata. */
    MISSING_METADATA_DEFINITION
}
