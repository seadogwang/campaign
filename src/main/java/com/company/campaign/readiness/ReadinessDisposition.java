package com.company.campaign.readiness;

/**
 * The closed outcome of one readiness append attempt.
 *
 * <p>It distinguishes the four outcomes a host must not confuse: a fresh write, an idempotent replay of the
 * exact same evidence identity, a conflicting reuse of an identity with different material, and a stale
 * optimistic revision that wrote nothing at all.
 */
public enum ReadinessDisposition {

    /** The record was appended and the scope revision advanced. */
    RECORDED,

    /** The exact same evidence identity and material was already stored; nothing was written again. */
    DUPLICATE,

    /** The evidence identity already exists with different material; nothing was written. */
    CONFLICT,

    /** The optimistic revision is behind the committed revision; nothing was written. */
    STALE_REVISION,

    /** The record was refused before reaching the store; nothing was written. */
    REJECTED;

    /** True only for {@link #RECORDED}, the single outcome that changed committed state. */
    public boolean committed() {
        return this == RECORDED;
    }

    /** True for every outcome that wrote nothing. */
    public boolean wroteNothing() {
        return !committed();
    }
}