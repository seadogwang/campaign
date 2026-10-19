package com.company.campaign.evaluation.segment.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The explicit retention program one retention case runs against the host store.
 *
 * <p>Every step is stated rather than implied, because the point of the category is that a hold, a release
 * and a deletion are three separate host decisions: keeping the version, taking a hold on it, releasing that
 * hold and deleting the bytes are all visible in the case, and so is the fact that a deletion may be refused.
 *
 * @param pin             the exact version the program acts on
 * @param retainFirst     whether the version is registered with the host store before anything else
 * @param holdId          the stable hold identity the program acquires, or null when it acquires none
 * @param acquireHold     whether a hold is acquired
 * @param acquireHoldTwice whether the same hold identity is acquired a second time, proving idempotence
 * @param releaseHold     whether the acquired hold is released before the deletion is attempted
 * @param hardDelete      whether a hard deletion of the exact version is attempted
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceRetentionRequest(
        ReferencePin pin,
        boolean retainFirst,
        String holdId,
        boolean acquireHold,
        boolean acquireHoldTwice,
        boolean releaseHold,
        boolean hardDelete
) {

    public ReferenceRetentionRequest {
        if (pin == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A retention program requires the exact version it acts on");
        }
        if (acquireHold && (holdId == null || holdId.isBlank())) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A retention program that acquires a hold must name its stable hold identity");
        }
        if (acquireHoldTwice && !acquireHold) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A retention program can acquire the same hold twice only if it acquires it once");
        }
        if (releaseHold && !acquireHold) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A retention program can release a hold only if it acquired one");
        }
        if (holdId != null && holdId.isBlank()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, "A declared hold identity must be bounded");
        }
    }

    /** The exact number of holds the program leaves active on the exact version. */
    public int expectedActiveHoldCount() {
        if (!acquireHold || releaseHold) {
            return 0;
        }
        // Acquiring the same stable hold identity twice is idempotent, so it never double-counts.
        return 1;
    }

    /** A bounded diagnostic naming only the program's shape, never a trusted fact. */
    @Override
    public String toString() {
        return "ReferenceRetentionRequest[pin=" + pin.identityKey()
                + ", retain=" + retainFirst
                + ", acquire=" + acquireHold
                + ", twice=" + acquireHoldTwice
                + ", release=" + releaseHold
                + ", delete=" + hardDelete
                + ']';
    }
}