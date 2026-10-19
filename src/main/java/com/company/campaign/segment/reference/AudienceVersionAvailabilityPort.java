package com.company.campaign.segment.reference;

/**
 * Host-only port that re-checks availability at execution time.
 *
 * <p>Resolving a reference once is not permanent authority: the exact version that was available when a
 * document was validated or planned can be deleted or withdrawn before it is executed. This port exists so an
 * execution guard can ask the host again, without holding any authority of its own and without being
 * satisfiable by a caller.
 *
 * <p>It must return {@link AudienceVersionAvailability#UNAVAILABLE} rather than a permissive guess when it
 * cannot reach a trustworthy decision — for example when the version is no longer visible at all. An
 * implementation that throws is treated exactly like that return value: the caller fails closed.
 */
@FunctionalInterface
public interface AudienceVersionAvailabilityPort {

    /** The availability of the exact pinned version right now; never null. */
    AudienceVersionAvailability currentAvailability(AudienceVersionRef reference);
}