package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The one bounded, host-supplied record of a live verification of a production Provider configuration.
 *
 * <p>It is the only channel through which {@link ProductionProviderEvidence#lastLiveVerificationStatus()} can
 * become anything other than {@link ProductionProviderVerificationStatus#NOT_VERIFIED}. An environment-gated
 * operator run — or an environment-gated live contract test — publishes exactly one such record naming the exact
 * configuration hash, capability hash, provider kind and action kind it exercised and the instant it observed
 * the result at. Nothing in the repository can synthesize it: a fixture, a scripted loopback server or a
 * configuration value produces no such record, so configuration and scripted behaviour can never become live
 * readiness evidence.
 *
 * <p>The record carries no endpoint, credential, owner tenant, recipient reference, routing pin, idempotency key
 * or remote Provider reference.
 */
public record ProductionProviderLiveVerification(
        ProductionProviderKind providerKind,
        ActionKind actionKind,
        ProductionProviderVerificationStatus status,
        Instant verifiedAt,
        Instant expiry,
        ContentHash configurationHash,
        ContentHash capabilityHash) {

    public ProductionProviderLiveVerification {
        Objects.requireNonNull(providerKind, "providerKind");
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(configurationHash, "configurationHash");
        Objects.requireNonNull(capabilityHash, "capabilityHash");
        if (status != ProductionProviderVerificationStatus.NOT_VERIFIED && verifiedAt == null) {
            throw ProductionProviderException.of(
                    ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID,
                    "A live verification that ran must carry the instant it observed its result at.");
        }
        if (status.verified() != (expiry != null)) {
            throw ProductionProviderException.of(
                    ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID,
                    "Only an accepted live verification may carry an expiry.");
        }
        if (expiry != null && !expiry.isAfter(verifiedAt)) {
            throw ProductionProviderException.of(
                    ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID,
                    "A live verification expiry must lie after the instant it was observed at.");
        }
    }

    /**
     * True when this record describes the exact given configuration: the same provider kind, action kind,
     * configuration hash and capability hash.
     */
    public boolean describes(
            ProductionProviderKind kind,
            ActionKind kindAction,
            ContentHash kindConfigurationHash,
            ContentHash kindCapabilityHash) {
        return providerKind == kind
                && actionKind == kindAction
                && configurationHash.equals(kindConfigurationHash)
                && capabilityHash.equals(kindCapabilityHash);
    }
}