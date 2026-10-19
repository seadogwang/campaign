package com.company.campaign.production.runtime;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;

/**
 * The bounded, redacted internal snapshot of the production provider runtime host.
 *
 * <p>It carries a running flag, the last cycle's bounded status and time, aggregate processed/failed counts, the
 * four bounded backlog/stall counts and a configuration hash — and nothing else. There is deliberately no tenant,
 * member, recipient, Provider, work-item or exception value and no high-cardinality label, so the snapshot can be
 * exposed on an internal surface without disclosing a person or a provider.
 */
public record ProviderRuntimeSnapshot(
        String formatVersion,
        boolean running,
        ProviderRuntimeCycleStatus lastCycleStatus,
        Instant lastCycleAt,
        long tenantsScanned,
        long runtimeProcessed,
        long actionProcessed,
        long runtimeFailed,
        long actionFailed,
        long backlog,
        long leaseExpiries,
        long unknownActions,
        long needsReviewParticipants,
        ContentHash configurationHash
) {

    /** The immutable snapshot document format version. */
    public static final String FORMAT_VERSION = "campaign-provider-runtime-snapshot/1.0";

    /** The largest bounded backlog count any snapshot may publish, so a value is always bounded. */
    public static final long MAX_COUNT = 1_000_000_000L;

    public ProviderRuntimeSnapshot {
        if (lastCycleStatus == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A provider runtime snapshot must carry a closed cycle status.");
        }
        if (configurationHash == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A provider runtime snapshot must carry its configuration hash.");
        }
        requireBounded(tenantsScanned, "tenantsScanned");
        requireBounded(runtimeProcessed, "runtimeProcessed");
        requireBounded(actionProcessed, "actionProcessed");
        requireBounded(runtimeFailed, "runtimeFailed");
        requireBounded(actionFailed, "actionFailed");
        requireBounded(backlog, "backlog");
        requireBounded(leaseExpiries, "leaseExpiries");
        requireBounded(unknownActions, "unknownActions");
        requireBounded(needsReviewParticipants, "needsReviewParticipants");
    }

    /** True when the last cycle ran and isolated no failure. */
    public boolean healthy() {
        return running && lastCycleStatus == ProviderRuntimeCycleStatus.COMPLETED;
    }

    private static void requireBounded(long value, String field) {
        if (value < 0 || value > MAX_COUNT) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A provider runtime snapshot count is out of bounds: " + field);
        }
    }

    /** Redacted diagnostics: no owner, member, recipient or provider value is printed. */
    @Override
    public String toString() {
        return "ProviderRuntimeSnapshot[running=" + running
                + ", lastCycleStatus=" + lastCycleStatus
                + ", lastCycleAt=" + lastCycleAt
                + ", tenantsScanned=" + tenantsScanned
                + ", runtimeProcessed=" + runtimeProcessed
                + ", actionProcessed=" + actionProcessed
                + ", runtimeFailed=" + runtimeFailed
                + ", actionFailed=" + actionFailed
                + ", backlog=" + backlog
                + ", leaseExpiries=" + leaseExpiries
                + ", unknownActions=" + unknownActions
                + ", needsReviewParticipants=" + needsReviewParticipants
                + ", configurationHash=" + configurationHash + ']';
    }
}