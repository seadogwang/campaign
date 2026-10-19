package com.company.campaign.action;

/**
 * The closed outward dispositions of one external-action operation.
 *
 * <p>It never collapses the four outcomes this layer exists to keep apart: an applied effect, a definitively
 * absent effect, an effect that is merely in flight, and an effect whose remote state is unknown and must be
 * reconciled. {@link #UNKNOWN_VERIFYING} and {@link #NEEDS_REVIEW} both leave the Execution unresolved, so a
 * caller may never summarize one as complete.
 */
public enum ActionDisposition {

    /** One exact handoff was reserved: the action, its key, its budget and its submission task, together. */
    RESERVED,

    /** One already-reserved action was presented again under the same frozen material and nothing was written. */
    DUPLICATE,

    /** One Provider submission or reconciliation established the effect as applied. */
    SUCCEEDED,

    /** One Provider submission or reconciliation established the effect as definitively absent or rejected. */
    DEFINITELY_FAILED,

    /** The remote state could not be established, so the action now verifies by its original key. */
    UNKNOWN_VERIFYING,

    /** One bounded safe retry reused the original action, key, request hash and budget reservation. */
    SAFE_RETRY_SCHEDULED,

    /** One more bounded reconciliation was scheduled without any Provider submission. */
    VERIFICATION_SCHEDULED,

    /** Every bounded attempt was spent without settling the remote state, so the action needs a decision. */
    NEEDS_REVIEW,

    /** An exact duplicate completion reused the committed logical result and wrote nothing. */
    DUPLICATE_COMPLETION;

    /** True when this disposition leaves the Execution unresolved. */
    public boolean leavesExecutionUnresolved() {
        return this == RESERVED || this == UNKNOWN_VERIFYING || this == SAFE_RETRY_SCHEDULED
                || this == VERIFICATION_SCHEDULED || this == NEEDS_REVIEW || this == DUPLICATE;
    }
}