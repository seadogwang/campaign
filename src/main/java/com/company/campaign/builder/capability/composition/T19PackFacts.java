package com.company.campaign.builder.capability.composition;

import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The frozen, machine-readable facts one T19 capability pack contributes to a {@link T19PackIndex}.
 *
 * <p>These are exactly the facts the composition validator must compare a resolved component against — the
 * canonical capability code, the exact enabled contract version, the exact frozen composition slot, whether
 * the pack is enabled for authoring, and the pack's declared runtime compatibility. Nothing here is a
 * behavior, a repository, a provider handle or a mutable setting: a fact set is immutable and comparable by
 * value, so the index it belongs to has deterministic bytes and one content hash.
 */
public record T19PackFacts(
        String capabilityCode,
        String contractVersion,
        CompositionSlot slot,
        boolean enabled,
        RuntimeCompatibility runtimeCompatibility) {

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");

    public T19PackFacts {
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new IllegalArgumentException("A T19 pack fact requires a canonical capability code.");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw new IllegalArgumentException("A T19 pack fact requires an exact contract version.");
        }
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
    }

    /** Reads the frozen facts of one concrete pack without holding onto the pack itself. */
    public static T19PackFacts of(CapabilityPackContract pack) {
        Objects.requireNonNull(pack, "pack");
        return new T19PackFacts(
                pack.capabilityCode(),
                pack.contractVersion(),
                pack.slot(),
                pack.enabled(),
                pack.runtimeCompatibility());
    }

    /** Redacted diagnostics: only already-safe machine facts. */
    @Override
    public String toString() {
        return "T19PackFacts[capabilityCode=" + capabilityCode
                + ", contractVersion=" + contractVersion
                + ", slot=" + slot.code()
                + ", enabled=" + enabled
                + ", runtime=" + runtimeCompatibility + ']';
    }
}