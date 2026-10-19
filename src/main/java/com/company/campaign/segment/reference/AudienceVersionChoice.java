package com.company.campaign.segment.reference;

import java.time.Instant;

/**
 * One eligible, immutable, published version of an audience the caller may reference.
 *
 * <p>It is what a clarification offers when one audience has several published versions: the exact immutable
 * pin, the instant it was published and one already-safe presentation label. It carries no {@code current} or
 * {@code latest} flag and no ordering hint, so "the newest" is never a value the model can select — the caller
 * selects an exact pin or nothing, and the host is the only component that knows publication order.
 *
 * <p>The choice set is produced only by the host-owned directory, which has already re-checked exact
 * availability and the caller's own access; the resolver never invents a choice.
 */
public record AudienceVersionChoice(AudienceVersionRef reference, Instant publishedAt, String displayLabel) {

    public AudienceVersionChoice {
        if (reference == null) {
            throw ReferenceException.invalid("An audience version choice requires its exact immutable pin");
        }
        if (publishedAt == null) {
            throw ReferenceException.invalid("An audience version choice requires its publication instant");
        }
        displayLabel = ReferenceLabels.requireLabel(
                displayLabel, "An audience version choice requires a safe label");
    }

    /** A bounded diagnostic: the pin identity and the publication instant, never the label text. */
    @Override
    public String toString() {
        return "AudienceVersionChoice[version=" + reference.identityKey()
                + ", publishedAt=" + publishedAt
                + ']';
    }
}