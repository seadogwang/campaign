package com.company.campaign.readiness;

/**
 * The closed set of origins an evidence record may declare.
 *
 * <p>The distinction that matters is deliberate and narrow: exactly one origin is
 * {@link #productionCapable()}, and every non-production origin is explicitly a substitute rather than an
 * unlabelled "normal" value. A fixture, a simulated Provider, synthetic identity, a development-only
 * evaluation result and a T25 evaluation asset are all representable — and none of them can be relabelled
 * into production evidence, because the origin is a closed discriminator rather than a free string.
 */
public enum EvidenceOrigin {

    /** Real production infrastructure, real identity, real Provider, observed in a production environment. */
    PRODUCTION(true, false),

    /** Real software actually executed: a real database engine, a real browser, a real build, on a local or CI machine. */
    REAL_LOCAL(false, false),

    /** A simulated Provider or other simulated capability, explicitly local and non-production. */
    SIMULATED(false, true),

    /** Fixture-backed evidence. */
    FIXTURE(false, true),

    /** Synthetic identity or synthetic data. */
    SYNTHETIC(false, true),

    /** A development-only evaluation result, such as a Segment Gate run under a {@code DEVELOPMENT_ONLY} corpus. */
    DEVELOPMENT_ONLY(false, true),

    /** A frozen evaluation asset under {@code evaluation/...}, which is regression evidence only. */
    EVALUATION_ASSET(false, true);

    private final boolean productionCapable;
    private final boolean nonProductionSubstitute;

    EvidenceOrigin(boolean productionCapable, boolean nonProductionSubstitute) {
        this.productionCapable = productionCapable;
        this.nonProductionSubstitute = nonProductionSubstitute;
    }

    /** True only for {@link #PRODUCTION}. */
    public boolean productionCapable() {
        return productionCapable;
    }

    /** True for every origin that is explicitly a non-production substitute. */
    public boolean nonProductionSubstitute() {
        return nonProductionSubstitute;
    }
}