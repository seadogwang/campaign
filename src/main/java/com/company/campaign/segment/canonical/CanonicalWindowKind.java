package com.company.campaign.segment.canonical;

/** The closed discriminator set for {@link CanonicalWindow}. */
public enum CanonicalWindowKind {
    ROLLING,
    LIFETIME,
    FIXED,
    RELATIVE_TO_ATTRIBUTE,
    SELECTED_EVENT,
    RELATIVE_TO_EVENT
}
