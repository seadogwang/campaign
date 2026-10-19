package com.company.campaign.production.runtime;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded, redacted topology evidence of one production provider runtime host.
 *
 * <p>It states two configuration facts — that both mutable execution boundaries are backed by accepted durable
 * stores, and that the cycle is deterministic and bounded — plus a configuration hash and a bounded opaque
 * subject. It is deliberately not production readiness evidence: it is derived from configuration alone, so it
 * can satisfy a LOCAL readiness requirement but can never satisfy a LIVE one. A host projects it into the
 * readiness catalog through {@code ProviderRuntimeEvidenceProjector}.
 *
 * <p>It carries no tenant, member, recipient, Provider, credential, URL or schema value.
 */
public record ProviderRuntimeTopologyEvidence(
        String formatVersion,
        String subject,
        boolean durableIsolationConfigured,
        boolean deterministicCycleCoverage,
        ContentHash configurationHash
) {

    /** The immutable topology evidence document format version. */
    public static final String FORMAT_VERSION = "campaign-provider-runtime-topology/1.0";

    public ProviderRuntimeTopologyEvidence {
        if (formatVersion == null || subject == null || configurationHash == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "The provider runtime topology evidence is incomplete.");
        }
    }

    /** Redacted diagnostics: two bounded facts, a bounded subject and a configuration hash. */
    @Override
    public String toString() {
        return "ProviderRuntimeTopologyEvidence[subject=" + subject
                + ", durableIsolationConfigured=" + durableIsolationConfigured
                + ", deterministicCycleCoverage=" + deterministicCycleCoverage
                + ", configurationHash=" + configurationHash + ']';
    }
}