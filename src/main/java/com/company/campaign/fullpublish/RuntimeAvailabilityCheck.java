package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;

import java.util.Optional;

/**
 * The one place the T21 full-publication boundary proves the host runtime availability it freezes.
 *
 * <p>Everything fails closed and none of the failures is distinguishable from the others outward: a missing
 * port, a port that cannot answer, an empty answer, an answer for another runtime schema version and a
 * truthful {@link RuntimeAvailabilityState#UNAVAILABLE} answer all refuse the operation with the same stable
 * code. That is deliberate — a caller must not be able to probe which part of the runtime evidence is
 * missing, and the boundary must never fall back to assuming availability.
 */
final class RuntimeAvailabilityCheck {

    private static final String MESSAGE =
            "No trustworthy host runtime-availability evidence exists for this exact runtime schema version.";

    private RuntimeAvailabilityCheck() {
    }

    /**
     * Requires an available host runtime-availability pin for the exact runtime schema version.
     *
     * @throws LifecycleException with {@link LifecycleErrorCodes#LIFECYCLE_FULL_RUNTIME_UNAVAILABLE} for every
     *     absent, unavailable or mismatched answer, so the operation fails closed
     */
    static RuntimeAvailabilityPin require(
            HostRuntimeAvailabilityPort port, String runtimeSchemaVersion) {
        if (port == null || runtimeSchemaVersion == null) {
            throw unavailable("runtimeAvailabilityWiring");
        }
        Optional<RuntimeAvailabilityPin> loaded;
        try {
            loaded = port.currentAvailability(runtimeSchemaVersion);
        } catch (RuntimeException exception) {
            // A host that cannot answer is indistinguishable from one that answers "unavailable".
            throw unavailable("runtimeAvailability");
        }
        if (loaded == null || loaded.isEmpty()) {
            throw unavailable("runtimeAvailabilityAbsent");
        }
        RuntimeAvailabilityPin pin = loaded.get();
        if (!pin.available() || !runtimeSchemaVersion.equals(pin.runtimeSchemaVersion())) {
            throw unavailable("runtimeAvailabilityState");
        }
        return pin;
    }

    private static LifecycleException unavailable(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_RUNTIME_UNAVAILABLE, invariant, MESSAGE);
    }
}