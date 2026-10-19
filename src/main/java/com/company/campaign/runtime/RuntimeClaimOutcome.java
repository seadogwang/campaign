package com.company.campaign.runtime;

/**
 * The closed outcome of one attempt to claim the next RuntimeTask.
 *
 * <p>{@link #CLAIMED} is the only outcome that holds a lease. {@link #NONE_DUE} records that there was genuinely
 * nothing claimable. {@link #PAUSED} records that the host-owned Campaign state forbade starting new work, and
 * {@link #UNVERIFIED} records that the state could not be proved at all; neither claims anything, and neither
 * discloses whether a resource exists.
 */
public enum RuntimeClaimOutcome {

    /** One eligible task was leased to this worker. */
    CLAIMED,

    /** No task was due for the requested scope. */
    NONE_DUE,

    /** The Campaign is paused, so no new local node or handoff may start. */
    PAUSED,

    /** The Campaign's runtime state could not be verified, so nothing was claimed. */
    UNVERIFIED,

    /** Every candidate was lost to another worker before this claim committed. */
    CONTENDED
}