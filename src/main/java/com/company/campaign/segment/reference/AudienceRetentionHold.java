package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One active retention hold: a published dependent version keeping an exact referenced version alive.
 *
 * <p>A hold is identified by a caller-supplied stable {@code holdId} rather than by the referenced version,
 * because several published dependents can hold the same version at once and each must be releasable on its
 * own. Acquiring the same {@code holdId} twice is idempotent, so a retried repository transaction cannot
 * silently double-count a dependent.
 *
 * <p>Nothing here is reachable from a model or a Tool: holds are acquired and released only through host
 * lifecycle policy, and no Tool name for retaining, releasing or deleting a version exists.
 */
public record AudienceRetentionHold(
        String holdId,
        AudienceVersionRef reference,
        String holderAudienceId,
        String holderVersionId,
        Instant acquiredAt
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public AudienceRetentionHold {
        holdId = requireId(holdId, "holdId");
        holderAudienceId = requireId(holderAudienceId, "holderAudienceId");
        holderVersionId = requireId(holderVersionId, "holderVersionId");
        if (reference == null) {
            throw ReferenceException.invalid("A retention hold requires the exact version it holds");
        }
        if (acquiredAt == null) {
            throw ReferenceException.invalid("A retention hold requires the instant it was acquired");
        }
    }

    /** The exact version this hold keeps alive. */
    public AudienceVersionRef heldReference() {
        return reference;
    }

    private static String requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw ReferenceException.invalid("A bounded '" + field + "' identifier is required");
        }
        return value;
    }
}