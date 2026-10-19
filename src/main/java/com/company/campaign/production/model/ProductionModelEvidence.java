package com.company.campaign.production.model;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The bounded, redacted provider evidence one production model configuration publishes.
 *
 * <p>It carries the provider kind, a one-way fingerprint of the model id, the adapter contract version, a
 * one-way canonical hash of the whole effective configuration, the status and instant of the last live
 * verification and the exact Gate pins the configuration is asserted against. It carries no credential, no
 * endpoint, no host name, no raw model id, no prompt, no schema, no raw output, no subject and no tenant.
 *
 * <p>Configuration alone is never production evidence: {@link #liveVerified()} is true only when the exact
 * configuration was exercised by an environment-gated live run, which is the only way
 * {@link #lastLiveVerificationStatus()} can be {@link ProductionModelEvidenceStatus#VERIFIED_LIVE}.
 */
public record ProductionModelEvidence(
        String providerKind,
        String modelIdFingerprint,
        String contractVersion,
        ContentHash configurationHash,
        ProductionModelEvidenceStatus lastLiveVerificationStatus,
        Instant lastLiveVerificationInstant,
        List<String> gatePins) {

    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public ProductionModelEvidence {
        if (providerKind == null || providerKind.isBlank() || providerKind.length() > 64) {
            throw invalid();
        }
        if (modelIdFingerprint == null || !FINGERPRINT.matcher(modelIdFingerprint).matches()) {
            throw invalid();
        }
        if (contractVersion == null || contractVersion.isBlank() || contractVersion.length() > 64) {
            throw invalid();
        }
        Objects.requireNonNull(configurationHash, "configurationHash");
        Objects.requireNonNull(lastLiveVerificationStatus, "lastLiveVerificationStatus");
        // A verification that ran — whether it succeeded or failed — carries the instant it was observed at;
        // a configuration that was never verified carries none.
        boolean ran = lastLiveVerificationStatus != ProductionModelEvidenceStatus.NOT_VERIFIED;
        if (ran != (lastLiveVerificationInstant != null)) {
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
    public static ProductionModelEvidence unverified(
            String providerKind,
            String modelIdFingerprint,
            String contractVersion,
            ContentHash configurationHash,
            List<String> gatePins) {
        return new ProductionModelEvidence(providerKind, modelIdFingerprint, contractVersion, configurationHash,
                ProductionModelEvidenceStatus.NOT_VERIFIED, null, gatePins);
    }

    /** True only when the exact configuration was exercised live and succeeded. */
    public boolean liveVerified() {
        return lastLiveVerificationStatus.verified();
    }

    /** The exact pins this evidence is bound to, in the deterministic order they were declared. */
    public List<String> gatePins() {
        return gatePins == null ? List.of() : gatePins;
    }

    private static ProductionModelException invalid() {
        return ProductionModelException.of(
                ProductionModelErrorCodes.MODEL_EVIDENCE_INVALID,
                "The published production model evidence is incomplete or structurally invalid.");
    }

    /** Redacted diagnostics: no endpoint, model id, credential or prompt is representable here. */
    @Override
    public String toString() {
        return "ProductionModelEvidence[providerKind=" + providerKind
                + ", modelIdFingerprint=" + modelIdFingerprint
                + ", contractVersion=" + contractVersion
                + ", configurationHash=" + configurationHash
                + ", lastLiveVerificationStatus=" + lastLiveVerificationStatus
                + ", lastLiveVerificationInstant=" + lastLiveVerificationInstant
                + ", gatePins=" + gatePins.size() + ']';
    }
}