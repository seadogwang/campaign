package com.company.campaign.runtime;

/**
 * The closed outcome of one host fact read.
 *
 * <p>{@link #KNOWN}, {@link #UNKNOWN} and {@link #ERROR} are the exact T19/T20 fact shapes: a known fact
 * carries one typed canonical value of a T19 value kind, an unknown fact carries nothing else, and an error
 * carries only a bounded machine reason code. {@link #TIMEOUT} and {@link #UNAVAILABLE} are the two technical
 * outcomes a host dependency can produce without answering at all.
 *
 * <p>No outcome is ever a value pretending to be a decision: {@link #UNKNOWN} is never treated as
 * {@code FALSE}, and a technical outcome never selects an edge.
 */
public enum RuntimeFactOutcome {

    /** The fact is known and carries an exact typed canonical value. */
    KNOWN(false),

    /** The fact is explicitly unknown; it may never be treated as false. */
    UNKNOWN(false),

    /** The fact read failed with a bounded machine reason code and never branches. */
    ERROR(false),

    /** The host fact port did not answer within its bound; a bounded technical failure. */
    TIMEOUT(true),

    /** The host fact port could not answer at all; a bounded technical failure. */
    UNAVAILABLE(true);

    private final boolean technical;

    RuntimeFactOutcome(boolean technical) {
        this.technical = technical;
    }

    /** True when this outcome is a bounded technical dependency failure rather than a fact value. */
    public boolean isTechnical() {
        return technical;
    }

    /** True when this outcome carries one exact typed canonical fact value. */
    public boolean isKnown() {
        return this == KNOWN;
    }
}