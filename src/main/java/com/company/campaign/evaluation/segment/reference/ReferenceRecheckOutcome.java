package com.company.campaign.evaluation.segment.reference;

/**
 * The closed set of outcomes a re-check can observe.
 *
 * <p>They are kept apart for the same reason the reference core keeps its own outcomes apart: "the reference
 * is still usable", "the reference is now refused, with these findings" and "the permission and version still
 * hold but the retention hold that protected the bytes is gone" demand different reactions, and collapsing
 * them would hide the drift a re-check exists to detect.
 */
public enum ReferenceRecheckOutcome {

    /** The reference still resolves and no finding was produced: nothing drifted. */
    CLEAN,

    /** The re-check refused the reference and reported at least one blocking finding. */
    REJECTED,

    /** The reference still resolves, but the retention evidence no longer holds the version alive. */
    HOLD_RELEASED;
}