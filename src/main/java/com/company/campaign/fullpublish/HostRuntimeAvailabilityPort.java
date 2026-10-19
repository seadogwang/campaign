package com.company.campaign.fullpublish;

import java.util.Optional;

/**
 * The host-only port a full publication asks whether the runtime it would schedule work for is available.
 *
 * <p>It is the T21 replacement for relabelling a T19 preview-only capability as production-ready: the host —
 * never a caller, a command field, a model output or a Tool payload — answers for one exact runtime schema
 * version, and an absent, thrown or unavailable answer fails the operation closed.
 *
 * <p>Tests may supply an explicit bounded host fixture to exercise the atomic path, but that fixture is
 * development evidence and is never relabelled as production Gate evidence.
 */
@FunctionalInterface
public interface HostRuntimeAvailabilityPort {

    /**
     * The host's current runtime-availability pin for one exact runtime schema version.
     *
     * @return the pin, or empty when the host owns no evidence for that version at all
     */
    Optional<RuntimeAvailabilityPin> currentAvailability(String runtimeSchemaVersion);
}