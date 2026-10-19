package com.company.campaign.segment.reference;

import java.util.List;

/**
 * Host-only port that owns retention holds and hard deletion for referenced Audience Versions.
 *
 * <p>Its whole purpose is one invariant: a referenced immutable version must survive for as long as a
 * published dependent version holds it. A hard deletion with an active hold is therefore refused rather than
 * forced, and the refusal leaves every byte and every hold exactly as it was.
 *
 * <p>Holds are acquired and released only through explicit host lifecycle policy. No model, no Tool and no
 * client payload reaches this port, and there is deliberately no method for "retain the version the model
 * asked for": the only caller is a host publication or release transaction.
 *
 * <p>Implementations must be safe for concurrent use and must make each operation atomic with respect to the
 * others — acquiring a hold and deleting the same version must never both appear to succeed.
 */
public interface AudienceRetentionPort {

    /** Acquires a hold, or returns the existing one when the same hold ID is already active (idempotent). */
    AudienceRetentionHold acquire(AudienceRetentionHold hold);

    /** Releases one hold by ID; releasing an unknown or already released hold is a no-op. */
    void release(String holdId);

    /** The active holds on one exact version, in deterministic hold-ID order. */
    List<AudienceRetentionHold> activeHolds(AudienceVersionRef reference);

    /**
     * Removes one exact version's immutable bytes, unless a hold still protects it.
     *
     * <p>It never releases a hold and never ignores one: with any active hold the outcome is
     * {@link HardDeletionOutcome#REJECTED_ACTIVE_HOLD} and nothing changes.
     */
    HardDeletionOutcome hardDelete(AudienceVersionRef reference);

    /** The retention policy this store applies when the final hold on a version is released. */
    RetentionReleasePolicy releasePolicy();
}