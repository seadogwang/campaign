package com.company.campaign.segment.resolution;

import com.company.campaign.segment.intent.IntentAmbiguity;

/**
 * Closed set of stable clarification question codes.
 *
 * <p>Logic keys only on these codes and the exact hashes, never on the presentation text. Every recorded
 * ambiguity maps to exactly one code, and an ambiguous definition choice maps to {@link #DISAMBIGUATE_DEFINITION}.
 */
public enum QuestionCode {

    SELECT_DEFINITION,
    DISAMBIGUATE_DEFINITION,
    MISSING_CURRENCY,
    AMBIGUOUS_GROUPING,
    AMBIGUOUS_METRIC_MEANING,
    AMBIGUOUS_NULL_INCLUSION,
    AMBIGUOUS_RELATION_SCOPE,
    AMBIGUOUS_FILTER_SCOPE,
    MISSING_METADATA_DEFINITION,
    SELECT_WINDOW,
    UNSUPPORTED_REQUIREMENT,

    /** Several audiences match the request; exactly one safe audience choice must be selected. */
    SELECT_AUDIENCE_REFERENCE,

    /** One audience has several eligible published versions; exactly one immutable version must be selected. */
    SELECT_AUDIENCE_VERSION,

    /** No audience could be resolved for the request and the reference is therefore unavailable. */
    AUDIENCE_REFERENCE_UNAVAILABLE;

    /**
     * True only for the question codes whose answer is a selection from the offered options, so they alone may
     * expose options and be applied by {@code ClarificationChoiceService}. Every other code (grouping,
     * currency, NULL inclusion, window, relation scope, metric meaning and an unavailable reference) requires a
     * revised Intent or a future dedicated typed answer contract, and a choice offered against it fails as
     * stale.
     *
     * <p>The two audience-reference codes are selections exactly like the definition codes: an ambiguous
     * audience or version is answered by picking one offered, host-vetted, immutable option, and the choice is
     * applied by re-resolving rather than by trusting the model's answer.
     */
    public boolean isDefinitionSelection() {
        return this == SELECT_DEFINITION || this == DISAMBIGUATE_DEFINITION
                || this == SELECT_AUDIENCE_REFERENCE || this == SELECT_AUDIENCE_VERSION;
    }

    /** The code a recorded Intent ambiguity produces. */
    public static QuestionCode forAmbiguity(IntentAmbiguity ambiguity) {
        return switch (ambiguity) {
            case AMBIGUOUS_GROUPING -> AMBIGUOUS_GROUPING;
            case MISSING_CURRENCY -> MISSING_CURRENCY;
            case AMBIGUOUS_METRIC_MEANING -> AMBIGUOUS_METRIC_MEANING;
            case AMBIGUOUS_NULL_INCLUSION -> AMBIGUOUS_NULL_INCLUSION;
            case AMBIGUOUS_RELATION_SCOPE -> AMBIGUOUS_RELATION_SCOPE;
            case AMBIGUOUS_FILTER_SCOPE -> AMBIGUOUS_FILTER_SCOPE;
            case MISSING_METADATA_DEFINITION -> MISSING_METADATA_DEFINITION;
            case AMBIGUOUS_WINDOW -> SELECT_WINDOW;
            case AMBIGUOUS_DEFINITION -> DISAMBIGUATE_DEFINITION;
        };
    }
}
