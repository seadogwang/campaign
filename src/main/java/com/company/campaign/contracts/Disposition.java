package com.company.campaign.contracts;

/**
 * Canonical terminal disposition of a semantic resolution, validation or Tool call.
 *
 * <p>The set is closed and ordered as defined by the Segment specification: any result that does not
 * reach {@link #RESOLVED} must say which of the four non-resolved dispositions applies, and the four are
 * not interchangeable.
 *
 * <ul>
 *   <li>{@link #RESOLVED} — the request was fully understood and the result is usable.</li>
 *   <li>{@link #NEEDS_INPUT} — more user input is required; the system must not guess.</li>
 *   <li>{@link #UNSUPPORTED} — the capability does not exist in this system.</li>
 *   <li>{@link #DENIED} — the capability exists but this principal or DataScope cannot use it; distinct
 *       from {@code UNSUPPORTED} and must not disclose whether the target exists.</li>
 *   <li>{@link #INVALID} — the request was understood but violates a contract.</li>
 * </ul>
 *
 * <p>The enum name is the wire value; no aliases are accepted so that one disposition has one spelling.
 */
public enum Disposition {

    RESOLVED,
    NEEDS_INPUT,
    UNSUPPORTED,
    DENIED,
    INVALID;

    /** True only for {@link #RESOLVED}, the single disposition that may carry a result value. */
    public boolean isResolved() {
        return this == RESOLVED;
    }

    /**
     * Whether a failure with this disposition may be described as retryable.
     *
     * <p>Only {@link #NEEDS_INPUT} may: the missing input can arrive and the same call can then succeed.
     * {@link #UNSUPPORTED}, {@link #DENIED} and {@link #INVALID} are deterministic verdicts about the
     * request itself, so repeating the identical call would repeat the identical refusal and
     * {@code retryable} would be a false statement rather than a hint. {@link #RESOLVED} never carries a
     * failure at all, so the question does not arise.
     */
    public boolean allowsRetryableFailure() {
        return this == NEEDS_INPUT;
    }
}