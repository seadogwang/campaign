package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The immutable host runtime-availability pin a full publication freezes.
 *
 * <p>It records the exact runtime schema version the host answered for, the closed availability state and the
 * non-reversible hash of the evidence the host rested that answer on. It carries no runtime handle, no worker,
 * no provider client and no trusted security context: it is evidence, not a capability.
 *
 * <p>Construction re-proves {@link #pinHash()} against its own canonical material, so a pin whose bytes do not
 * match its own hash is not representable. Only an {@link RuntimeAvailabilityState#AVAILABLE} pin may be
 * frozen; the check that enforces that lives in the boundary, so a host can still answer truthfully with an
 * unavailable pin and be refused rather than ignored.
 */
public record RuntimeAvailabilityPin(
        String formatVersion,
        String runtimeSchemaVersion,
        RuntimeAvailabilityState state,
        ContentHash evidenceHash,
        ContentHash pinHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeAvailabilityPin {
        if (!FullPublishVersions.RUNTIME_AVAILABILITY_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE, "runtimeAvailabilityFormatVersion");
        }
        if (!FullPublishVersions.RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE, "runtimeSchemaVersion");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidenceHash, "evidenceHash");
        Objects.requireNonNull(pinHash, "pinHash");
        if (!pinHash.equals(CANONICAL_JSON.hash(new Material(
                formatVersion, runtimeSchemaVersion, state, evidenceHash)))) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE, "runtimeAvailabilityPinHash");
        }
    }

    /** Builds one runtime-availability pin and stamps it with its own canonical hash. */
    public static RuntimeAvailabilityPin of(
            String runtimeSchemaVersion, RuntimeAvailabilityState state, ContentHash evidenceHash) {
        return new RuntimeAvailabilityPin(
                FullPublishVersions.RUNTIME_AVAILABILITY_FORMAT_VERSION,
                runtimeSchemaVersion, state, evidenceHash,
                CANONICAL_JSON.hash(new Material(
                        FullPublishVersions.RUNTIME_AVAILABILITY_FORMAT_VERSION,
                        runtimeSchemaVersion, state, evidenceHash)));
    }

    /** True when this pin is the one state a full publication may freeze from. */
    public boolean available() {
        return state.available();
    }

    /** Redacted diagnostics: no host evidence value is printed. */
    @Override
    public String toString() {
        return "RuntimeAvailabilityPin[state=" + state
                + ", runtimeSchemaVersion=" + runtimeSchemaVersion
                + ", pinHash=" + pinHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String runtimeSchemaVersion,
            RuntimeAvailabilityState state,
            ContentHash evidenceHash) {
    }
}