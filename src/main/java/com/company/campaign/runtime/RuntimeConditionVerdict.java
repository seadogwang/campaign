package com.company.campaign.runtime;

/**
 * The closed verdict of one Runtime Flow Condition evaluation.
 *
 * <p>{@link #TRUE} and {@link #FALSE} are the only two verdicts that select an edge. {@link #UNKNOWN} is a
 * real, distinct verdict that is never collapsed into {@code FALSE}: a Runtime worker cannot fork a durable
 * Participant, so an unresolved fact stops local advancement and needs a decision. {@link #ERROR} is a
 * fail-closed termination, never a branch.
 */
public enum RuntimeConditionVerdict {

    /** The predicate is deterministically true. */
    TRUE,

    /** The predicate is deterministically false. */
    FALSE,

    /** The predicate cannot be resolved; it is never treated as false. */
    UNKNOWN,

    /** The predicate failed closed; local advancement stops with an explicit bounded reason. */
    ERROR;

    /** True when this verdict selects an edge rather than stopping local advancement. */
    public boolean selectsEdge() {
        return this == TRUE || this == FALSE;
    }
}