package com.company.campaign.journey.simulation;

/**
 * The closed verdict of one pure Flow Condition evaluation.
 *
 * <p>{@link #TRUE} and {@link #FALSE} are the two selectable arms. {@link #UNKNOWN} is a real, distinct
 * verdict that must never collapse into {@code FALSE}: an unresolved fact stays unresolved until the
 * simulation forks it deterministically. {@link #ERROR} is a fail-closed termination, never a branch.
 */
public enum ConditionVerdict {

    /** The predicate is deterministically true. */
    TRUE,

    /** The predicate is deterministically false. */
    FALSE,

    /** The predicate cannot be resolved; it is never treated as false. */
    UNKNOWN,

    /** The predicate failed closed; the path terminates with an explicit simulated error. */
    ERROR
}
