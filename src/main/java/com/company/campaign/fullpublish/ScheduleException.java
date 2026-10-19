package com.company.campaign.fullpublish;

/**
 * The bounded, machine-coded failure of one schedule compile or occurrence planning evaluation.
 *
 * <p>It carries a stable {@link ScheduleErrorCodes} code and a bounded invariant name — never a rejected
 * payload value, a timezone token, a date, an offset or an unrestricted exception message — so a failure can
 * be branched on and audited without any external value becoming observable.
 */
public final class ScheduleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;
    private final transient String invariant;

    public ScheduleException(String code, String invariant, String message) {
        super(message);
        this.code = code;
        this.invariant = invariant;
    }

    /** The stable bounded machine code of this failure. */
    public String code() {
        return code;
    }

    /** The bounded invariant name this failure is attributed to. */
    public String invariant() {
        return invariant;
    }

    @Override
    public String toString() {
        return "ScheduleException[code=" + code + ", invariant=" + invariant + ']';
    }
}