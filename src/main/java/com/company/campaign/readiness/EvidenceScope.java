package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The exact partition one readiness state lives in: tenant, program and host-declared environment.
 *
 * <p>It is a derived value, never a payload field: {@link ReadinessHostContext#scope()} produces it, and a
 * caller cannot widen or narrow it because the host context is the only source of all three components.
 * Partitioning by environment is what stops a staging or simulated record from ever being read as a
 * production one.
 */
public record EvidenceScope(
        String tenantId,
        String programId,
        ReadinessEnvironment environment) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public EvidenceScope {
        ReadinessSupport.requireId(tenantId, "tenantId");
        ReadinessSupport.requireId(programId, "programId");
        ReadinessSupport.requireNonNull(environment, "environment");
    }

    /** True when this scope is the production partition. */
    public boolean production() {
        return environment.production();
    }

    /** A non-reversible scope fingerprint, safe to use as a bounded audit or metric label. */
    public ContentHash fingerprint() {
        return CANONICAL_JSON.hash(new Material(tenantId, programId, environment.name()));
    }

    /** Redacted diagnostics: the tenant and program are not printed. */
    @Override
    public String toString() {
        return "EvidenceScope[environment=" + environment + ", fingerprint=" + fingerprint() + ']';
    }

    /** Package-private hash material. */
    record Material(String tenantId, String programId, String environment) {
    }
}