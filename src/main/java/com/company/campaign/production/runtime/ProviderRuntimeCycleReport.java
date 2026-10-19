package com.company.campaign.production.runtime;

import java.util.List;

/**
 * The bounded, aggregate result of one production cycle.
 *
 * <p>It carries counts and bounded machine codes and nothing else: no tenant, member, recipient, work-item or
 * provider identity, and no raw exception text. One failed item is isolated — the remaining items still run — and
 * is reported only through its bounded code and the failed count.
 */
public record ProviderRuntimeCycleReport(
        ProviderRuntimeCycleStatus status,
        int tenantsScanned,
        int runtimeProcessed,
        int actionProcessed,
        int runtimeFailed,
        int actionFailed,
        List<String> failureCodes
) {

    /** The largest number of distinct bounded failure codes one report retains. */
    public static final int MAX_FAILURE_CODES = 16;

    public ProviderRuntimeCycleReport {
        if (status == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A cycle report must carry a closed status.");
        }
        if (tenantsScanned < 0 || runtimeProcessed < 0 || actionProcessed < 0
                || runtimeFailed < 0 || actionFailed < 0) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A cycle report must carry non-negative counts.");
        }
        failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
        if (failureCodes.size() > MAX_FAILURE_CODES) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "A cycle report carries too many bounded failure codes.");
        }
    }

    /** True when any item failed or the cycle could not complete. */
    public boolean anyFailure() {
        return runtimeFailed > 0 || actionFailed > 0 || status == ProviderRuntimeCycleStatus.FAILED;
    }

    /** Redacted diagnostics: aggregate counts and bounded codes only. */
    @Override
    public String toString() {
        return "ProviderRuntimeCycleReport[status=" + status
                + ", tenantsScanned=" + tenantsScanned
                + ", runtimeProcessed=" + runtimeProcessed
                + ", actionProcessed=" + actionProcessed
                + ", runtimeFailed=" + runtimeFailed
                + ", actionFailed=" + actionFailed
                + ", failureCodes=" + failureCodes + ']';
    }
}