package com.company.campaign.production.campaignagent;

/**
 * The closed provenance of one set of host-supplied production Campaign assets.
 *
 * <p>It is deliberately two-valued. {@link #DEVELOPMENT_ONLY} is the honest state of a repository's frozen
 * development assets — including the Core Loyalty development profile — because no human Gate decision has
 * approved them for production readiness. {@link #GATE_APPROVED} is the only state that may be labelled ACTIVE,
 * and a host may declare it only when the exact human Gate approvals exist outside this repository.
 *
 * <p>There is no default that could relabel development assets as production-ready: a host must say
 * {@code GATE_APPROVED} explicitly, and every witness of this value reports it.
 */
public enum ProductionAssetProvenance {

    /** The assets are the repository's frozen development assets and are not production readiness evidence. */
    DEVELOPMENT_ONLY,

    /** The exact human Gate approvals for these exact assets exist outside this repository. */
    GATE_APPROVED;

    /** True only when the assets may be presented as an approved production readiness basis. */
    public boolean gateApproved() {
        return this == GATE_APPROVED;
    }
}