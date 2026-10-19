package com.company.campaign.production.provider;

/**
 * The closed status of the last live verification of one production Provider configuration.
 *
 * <p>It is deliberately three-valued: a configuration that has never been exercised against the real endpoint
 * is {@link #NOT_VERIFIED}, a verification that ran and succeeded is {@link #VERIFIED_LIVE}, and a verification
 * that ran and failed is {@link #FAILED_LIVE}. There is no "assumed", "configured" or "asserted" state, because
 * configuration alone is never production evidence — a scripted loopback server can never enter this type at all.
 */
public enum ProductionProviderVerificationStatus {

    /** The configured Provider endpoint has never been exercised by an environment-gated live verification. */
    NOT_VERIFIED,

    /** An environment-gated live verification exercised the exact configuration and succeeded. */
    VERIFIED_LIVE,

    /** An environment-gated live verification exercised the exact configuration and failed. */
    FAILED_LIVE;

    /** True only when the exact configuration was exercised live and succeeded. */
    public boolean verified() {
        return this == VERIFIED_LIVE;
    }

    /** True when a verification actually ran, whether it succeeded or failed. */
    public boolean ran() {
        return this != NOT_VERIFIED;
    }
}