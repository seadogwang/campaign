package com.company.campaign.metadata.domain;

/** Closed time-window kinds a metric may support. */
public enum TimeWindowKind {

    /** A window ending at evaluation time and reaching back a bounded duration. */
    ROLLING,

    /** A window covering all observed history. */
    LIFETIME,

    /** A window with an explicit fixed start and end. */
    FIXED,

    /** A bounded window whose endpoints are offsets from a permitted temporal Attribute. */
    RELATIVE_TO_ATTRIBUTE,
    SELECTED_EVENT,
    RELATIVE_TO_EVENT;

    /** True when the kind has boundaries and therefore needs explicit boundary semantics. */
    public boolean isBounded() {
        return this != LIFETIME;
    }
}
