package com.company.campaign.lifecycle;

/**
 * Outward classification of one recorded lifecycle decision.
 *
 * <p>{@link #NOT_FOUND} collapses an absent resource with one owned by another tenant, program or user,
 * matching the non-disclosing behaviour of the accepted {@code ResourceNotFoundException}. The internal
 * reason for a {@code NOT_FOUND} is preserved only on the audit event, never in the outward failure.
 */
public enum LifecycleDisposition {

    /**
     * The host command was accepted and its server-side material was committed as one unit.
     *
     * <p>What was committed is the command's own artifact set: a Draft revision plus its Apply receipt, a
     * Publish preparation, an immutable Published Definition plus its receipt, or a forked Draft. Nothing
     * else about the disposition changes — a rejected command still mutates nothing.
     */
    APPLIED,

    /** The host command was understood and refused; nothing was mutated. */
    REJECTED,

    /** The addressed resource is absent or outside the caller's boundary; nothing was mutated. */
    NOT_FOUND,

    /** No trustworthy decision could be made, so the operation failed closed; nothing was mutated. */
    UNAVAILABLE,

    /** The host command was structurally invalid; nothing was mutated. */
    INVALID;

    /** True only for {@link #APPLIED}, the single disposition that must carry committed material. */
    public boolean mutated() {
        return this == APPLIED;
    }
}