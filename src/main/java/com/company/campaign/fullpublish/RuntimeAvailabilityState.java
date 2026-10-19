package com.company.campaign.fullpublish;

/**
 * The closed set of runtime-availability states a host may pin for one exact runtime schema version.
 *
 * <p>It exists so the T21 boundary can ask a host whether the runtime that would deliver and execute the work
 * a publication emits is actually available, instead of assuming that the T19 preview-only declaration
 * silently became production-ready. {@link #UNAVAILABLE} is a first-class answer: a host that truthfully
 * reports it refuses the publication rather than being mistaken for an absent port.
 */
public enum RuntimeAvailabilityState {

    /**
     * The host owns live runtime evidence for the exact runtime schema version it pinned.
     *
     * <p>Only this state permits a full publication to freeze its Journey and Schedule.</p>
     */
    AVAILABLE,

    /**
     * The host owns no live runtime evidence for that exact version, or reports that it cannot execute.
     *
     * <p>T21/T22/T23 have no production Runtime, so this is the honest default and it fails closed.</p>
     */
    UNAVAILABLE;

    /** True only for {@link #AVAILABLE}, the single state a full publication may freeze from. */
    public boolean available() {
        return this == AVAILABLE;
    }
}