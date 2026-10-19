package com.company.campaign.segment.reference;

/**
 * The explicit host policy that governs what happens when the final hold on a referenced version is released.
 *
 * <p>Releasing a hold never deletes anything by itself. It only makes the referenced version eligible for a
 * later hard deletion, and whether that eligibility leads anywhere is a host decision rather than an implicit
 * one. Modelling it as an explicit policy value is what keeps "the last dependent went away" from silently
 * meaning "the audience is gone".
 */
public enum RetentionReleasePolicy {

    /** The referenced version is retained indefinitely; a release only removes the hold. */
    RETAIN_INDEFINITELY,

    /** The referenced version becomes deletable once no hold remains; a hard deletion is still explicit. */
    ALLOW_DELETION_WHEN_UNHELD;

    /** True when a hard deletion may be attempted once no hold remains. */
    public boolean permitsDeletionWhenUnheld() {
        return this == ALLOW_DELETION_WHEN_UNHELD;
    }
}