package com.company.campaign.readiness;

/**
 * The deterministic status of one readiness assessment.
 *
 * <p>There is no optimistic default and no "unknown": every assessment is one of these three, and only
 * {@link #READY} is a readiness claim.
 *
 * <ul>
 *   <li>{@link #READY} — every requirement of the level is satisfied by accepted evidence.</li>
 *   <li>{@link #BLOCKED} — at least one requirement is absent, expired, out of origin or otherwise not
 *       satisfied, and no evidence for the level is invalid.</li>
 *   <li>{@link #INVALID} — at least one requirement received evidence that is itself contradictory,
 *       duplicated or unusable, so the level cannot be assessed at all.</li>
 * </ul>
 */
public enum ReadinessStatus {

    READY,
    BLOCKED,
    INVALID;

    /** True only for {@link #READY}, the single status that is a readiness claim. */
    public boolean ready() {
        return this == READY;
    }
}