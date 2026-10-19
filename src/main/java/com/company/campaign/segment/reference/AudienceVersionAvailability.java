package com.company.campaign.segment.reference;

/**
 * The closed availability states an Audience Version can be in when it is looked up or re-checked.
 *
 * <p>Availability is deliberately separate from retention: retention decides whether the immutable bytes may
 * be removed at all, while availability is what a resolve-time or execution-time lookup observes. A version
 * that is still retained can nonetheless be unavailable, and a deleted version is never available, so the
 * two facts are never conflated.
 */
public enum AudienceVersionAvailability {

    /** The version is published and usable for resolution and execution. */
    AVAILABLE,

    /** The version has been hard-deleted; only its identity remains for deterministic failure. */
    DELETED,

    /** The version exists but is not usable — for example it is still being built or was withdrawn. */
    UNAVAILABLE;

    /** True only for {@link #AVAILABLE}; every other state fails closed. */
    public boolean isUsable() {
        return this == AVAILABLE;
    }
}