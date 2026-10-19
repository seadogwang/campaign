package com.company.campaign.production.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The one bounded, host-supplied record of a live verification of a production model configuration.
 *
 * <p>It is the only channel through which {@link ProductionModelEvidence#lastLiveVerificationStatus()} can
 * become anything other than {@link ProductionModelEvidenceStatus#NOT_VERIFIED}. An environment-gated operator
 * run — or an environment-gated live contract test — publishes exactly one such bean naming the exact
 * configuration hash it exercised and the instant it observed the result at. Nothing in the repository can
 * synthesize it: a fixture, a scripted server or a configuration value produces no such bean.
 *
 * <p>The record carries no endpoint, credential, prompt, schema or raw output.
 */
public record ProductionModelLiveVerification(
        ProductionModelEvidenceStatus status,
        Instant verifiedAt,
        ProductionModelProperties configuration) {

    public ProductionModelLiveVerification {
        Objects.requireNonNull(status, "status");
        if (status != ProductionModelEvidenceStatus.NOT_VERIFIED && verifiedAt == null) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_EVIDENCE_INVALID,
                    "A live verification that ran must carry the instant it observed its result at.");
        }
        Objects.requireNonNull(configuration, "configuration");
    }

    /** True when this record describes the exact given configuration, whether it succeeded or failed. */
    public boolean describes(com.company.campaign.shared.canonical.ContentHash configurationHash) {
        return configurationHash.equals(configuration.configurationHash());
    }
}