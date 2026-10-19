package com.company.campaign.security.data;

/**
 * Data classification used to decide whether and how a field may be projected.
 *
 * <p>The order is the sensitivity order from the architecture: {@code PUBLIC} &lt; {@code INTERNAL} &lt;
 * {@code CONFIDENTIAL} &lt; {@code RESTRICTED}. {@link #RESTRICTED} covers member identifiers, PII,
 * transaction detail and credentials and is excluded from the model prompt by default.
 */
public enum DataClassification {

    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED;

    /** True for the classification that must not reach the model prompt or a raw retention store. */
    public boolean isRestricted() {
        return this == RESTRICTED;
    }

    /** True when this classification is strictly more sensitive than {@code other}. */
    public boolean isMoreSensitiveThan(DataClassification other) {
        return other != null && ordinal() > other.ordinal();
    }
}