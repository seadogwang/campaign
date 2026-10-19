package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The bounded, redacted evidence one production Provider configuration publishes.
 *
 * <p>It carries the provider kind, a one-way fingerprint of the fixed provider id, the action kind, a one-way
 * canonical hash of the whole effective configuration, the adapter contract version, the hash of the exact
 * capability the adapter declares, the exact Gate pins, the status and instant of the last live verification and
 * the instant that verification stops being current evidence. It carries no endpoint, no credential, no host, no
 * owner tenant, no recipient reference, no routing pin, no idempotency key and no remote Provider reference.
 *
 * <p>Configuration alone is never production evidence: {@link #liveVerified()} is true only when the exact
 * configuration was exercised by an environment-gated live run, which is the only way
 * {@link #lastLiveVerificationStatus()} can be {@link ProductionProviderVerificationStatus#VERIFIED_LIVE}.
 */
public record ProductionProviderEvidence(
        ProductionProviderKind providerKind,
        String providerIdFingerprint,
        ActionKind actionKind,
        ContentHash configurationHash,
        String contractVersion,
        ContentHash capabilityHash,
        List<String> gatePins,
        ProductionProviderVerificationStatus lastLiveVerificationStatus,
        Instant lastLiveVerificationInstant,
        Instant expiry) {

    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public ProductionProviderEvidence {
        Objects.requireNonNull(providerKind, "providerKind");
        if (providerIdFingerprint == null || !FINGERPRINT.matcher(providerIdFingerprint).matches()) {
            throw invalid();
        }
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(configurationHash, "configurationHash");
        if (contractVersion == null || contractVersion.isBlank() || contractVersion.length() > 64) {
            throw invalid();
        }
        Objects.requireNonNull(capabilityHash, "capabilityHash");
        Objects.requireNonNull(lastLiveVerificationStatus, "lastLiveVerificationStatus");
        // A verification that ran — whether it succeeded or failed — carries the instant it observed its result
        // at; a configuration that was never verified carries none.
        boolean ran = lastLiveVerificationStatus.ran();
        if (ran != (lastLiveVerificationInstant != null)) {
            throw invalid();
        }
        // Only an accepted live verification is current evidence, so only it carries an expiry.
        if (lastLiveVerificationStatus.verified() != (expiry != null)) {
            throw invalid();
        }
        if (expiry != null && !expiry.isAfter(lastLiveVerificationInstant)) {
            throw invalid();
        }
        if (gatePins == null || gatePins.size() > 32) {
            throw invalid();
        }
        List<String> pins = new ArrayList<>(gatePins.size());
        for (String pin : gatePins) {
            if (pin == null || pin.isBlank() || pin.length() > 256) {
                throw invalid();
            }
            pins.add(pin);
        }
        gatePins = List.copyOf(pins);
    }

    /** The configuration-only evidence: no live verification was performed, so this is not production proof. */
    public static ProductionProviderEvidence unverified(
            ProductionProviderKind providerKind,
            String providerIdFingerprint,
            ActionKind actionKind,
            ContentHash configurationHash,
            String contractVersion,
            ContentHash capabilityHash,
            List<String> gatePins) {
        return new ProductionProviderEvidence(providerKind, providerIdFingerprint, actionKind, configurationHash,
                contractVersion, capabilityHash, gatePins, ProductionProviderVerificationStatus.NOT_VERIFIED,
                null, null);
    }

    /** True only when the exact configuration was exercised live and succeeded. */
    public boolean liveVerified() {
        return lastLiveVerificationStatus.verified();
    }

    /** True when this evidence is past its declared expiry at the given instant. */
    public boolean expiredAt(Instant instant) {
        return expiry != null && !expiry.isAfter(instant);
    }

    /**
     * True when this evidence describes exactly the given capability: the same action kind, the same provider
     * id and the same capability hash.
     *
     * <p>The projector and the host wiring both re-prove this before trusting a field, so evidence for one
     * Provider can never be read as evidence for another.
     */
    public boolean describes(ProviderCapability capability) {
        if (capability == null || capability.actionKind() != actionKind) {
            return false;
        }
        if (!capabilityHash.equals(capability.capabilityHash())) {
            return false;
        }
        return providerIdFingerprint.equals(fingerprint(capability.providerId()));
    }

    /** The exact pins this evidence is bound to, in the deterministic order they were declared. */
    @Override
    public List<String> gatePins() {
        return gatePins == null ? List.of() : gatePins;
    }

    /** The one-way fingerprint of a provider id, safe to publish as evidence. */
    public static String fingerprint(String providerId) {
        if (providerId == null) {
            return "";
        }
        return ContentHash.sha256(providerId.getBytes(StandardCharsets.UTF_8)).value();
    }

    private static ProductionProviderException invalid() {
        return ProductionProviderException.of(
                ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID,
                "The published production Provider evidence is incomplete or structurally invalid.");
    }

    /** Redacted diagnostics: no endpoint, credential, owner, key or Provider reference is representable here. */
    @Override
    public String toString() {
        return "ProductionProviderEvidence[providerKind=" + providerKind
                + ", providerIdFingerprint=" + providerIdFingerprint
                + ", actionKind=" + actionKind
                + ", configurationHash=" + configurationHash
                + ", contractVersion=" + contractVersion
                + ", capabilityHash=" + capabilityHash
                + ", gatePins=" + gatePins.size()
                + ", lastLiveVerificationStatus=" + lastLiveVerificationStatus
                + ", lastLiveVerificationInstant=" + lastLiveVerificationInstant
                + ", expiry=" + expiry + ']';
    }
}