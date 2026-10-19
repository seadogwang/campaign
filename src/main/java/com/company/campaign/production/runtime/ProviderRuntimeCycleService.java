package com.company.campaign.production.runtime;

import com.company.campaign.runtime.RuntimeSupport;
import com.company.campaign.shared.error.ContractException;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The one explicit production cycle: it processes a bounded amount of Runtime and Action work per tenant and
 * returns only aggregate counts and bounded codes.
 *
 * <p>One call scans at most the configured number of tenants and, for each of them, at most the configured number
 * of Runtime items and the configured number of Action items. Nothing about a tenant, a participant, a recipient,
 * a Provider or a raw exception ever leaves this boundary: a failed item is isolated — the remaining items still
 * run — and is reported only through a bounded code and a failed count.
 *
 * <p>It never reads a browser or model request, never chooses which tenant to scan from a request value (the
 * tenant list comes only from the trusted {@link ProviderRuntimeTenantSource}) and never logs anything. A tenant
 * list that is blank, duplicated or over its bound fails the whole cycle closed before any work is claimed.
 *
 * <p>It runs work through two narrow item runners rather than the concrete workers, so the exact bounded-cycle
 * and failure-isolation behaviour is deterministically verifiable without a durable database or a live Provider.
 */
public final class ProviderRuntimeCycleService implements ProviderRuntimeCycle {

    private final RuntimeItemRunner runtimeItems;
    private final ActionItemRunner actionItems;
    private final ProviderRuntimeTenantSource tenants;
    private final int maxTenants;
    private final int maxRuntimeItemsPerCycle;
    private final int maxActionItemsPerCycle;

    public ProviderRuntimeCycleService(
            RuntimeItemRunner runtimeItems,
            ActionItemRunner actionItems,
            ProviderRuntimeTenantSource tenants,
            int maxTenants,
            int maxRuntimeItemsPerCycle,
            int maxActionItemsPerCycle) {
        this.runtimeItems = Objects.requireNonNull(runtimeItems, "runtimeItems");
        this.actionItems = Objects.requireNonNull(actionItems, "actionItems");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        if (maxTenants < 1 || maxTenants > ProductionProviderRuntimeProperties.MAX_TENANTS) {
            throw invalid("maxTenants");
        }
        if (maxRuntimeItemsPerCycle < 1
                || maxRuntimeItemsPerCycle > ProductionProviderRuntimeProperties.MAX_ITEMS_PER_CYCLE) {
            throw invalid("maxRuntimeItemsPerCycle");
        }
        if (maxActionItemsPerCycle < 1
                || maxActionItemsPerCycle > ProductionProviderRuntimeProperties.MAX_ITEMS_PER_CYCLE) {
            throw invalid("maxActionItemsPerCycle");
        }
        this.maxTenants = maxTenants;
        this.maxRuntimeItemsPerCycle = maxRuntimeItemsPerCycle;
        this.maxActionItemsPerCycle = maxActionItemsPerCycle;
    }

    @Override
    public ProviderRuntimeCycleReport runCycle(String workerId) {
        RuntimeSupport.requireIdentifier(workerId, "workerId");
        List<String> tenantList;
        try {
            tenantList = ProviderRuntimeTenantSource.bounded(tenants, maxTenants);
        } catch (RuntimeException unusable) {
            return new ProviderRuntimeCycleReport(
                    ProviderRuntimeCycleStatus.FAILED, 0, 0, 0, 0, 0,
                    List.of(codeOf(unusable, ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID)));
        }

        int runtimeProcessed = 0;
        int actionProcessed = 0;
        int runtimeFailed = 0;
        int actionFailed = 0;
        TreeSet<String> codes = new TreeSet<>();
        for (String tenantId : tenantList) {
            for (int index = 0; index < maxRuntimeItemsPerCycle; index++) {
                try {
                    if (!runtimeItems.runOnce(tenantId, workerId)) {
                        break;
                    }
                    runtimeProcessed++;
                } catch (RuntimeException failure) {
                    runtimeFailed++;
                    addBounded(codes, codeOf(failure, ProductionProviderRuntimeErrorCodes.CYCLE_ITEM_FAILED));
                    break;
                }
            }
            for (int index = 0; index < maxActionItemsPerCycle; index++) {
                try {
                    if (!actionItems.runOnce(tenantId, workerId)) {
                        break;
                    }
                    actionProcessed++;
                } catch (RuntimeException failure) {
                    actionFailed++;
                    addBounded(codes, codeOf(failure, ProductionProviderRuntimeErrorCodes.CYCLE_ITEM_FAILED));
                    break;
                }
            }
        }
        ProviderRuntimeCycleStatus status =
                runtimeFailed > 0 || actionFailed > 0
                        ? ProviderRuntimeCycleStatus.DEGRADED
                        : ProviderRuntimeCycleStatus.COMPLETED;
        return new ProviderRuntimeCycleReport(
                status, tenantList.size(), runtimeProcessed, actionProcessed,
                runtimeFailed, actionFailed, List.copyOf(codes));
    }

    /**
     * The bounded machine code of one isolated item failure.
     *
     * <p>A contract failure already carries a bounded code; anything else is reported as the one generic bounded
     * code, so no raw exception message, type name or cause can ever leave this boundary.
     */
    private static String codeOf(RuntimeException failure, String fallback) {
        return failure instanceof ContractException contract ? contract.code() : fallback;
    }

    private static void addBounded(TreeSet<String> codes, String code) {
        if (code != null && codes.size() < ProviderRuntimeCycleReport.MAX_FAILURE_CODES) {
            codes.add(code);
        }
    }

    private static ProductionProviderRuntimeConfigurationException invalid(String field) {
        return new ProductionProviderRuntimeConfigurationException(
                ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                "The production provider runtime cycle is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: the bounds only, never a tenant or an owner value. */
    @Override
    public String toString() {
        return "ProviderRuntimeCycleService[maxTenants=" + maxTenants
                + ", maxRuntimeItemsPerCycle=" + maxRuntimeItemsPerCycle
                + ", maxActionItemsPerCycle=" + maxActionItemsPerCycle + ']';
    }

    /** One bounded Runtime item scan and process step of one exact tenant. */
    @FunctionalInterface
    public interface RuntimeItemRunner {

        /** Processes at most the next due Runtime item of one tenant, or {@code false} when there is none. */
        boolean runOnce(String tenantId, String workerId);
    }

    /** One bounded Action item ingest/scan and process step of one exact tenant. */
    @FunctionalInterface
    public interface ActionItemRunner {

        /** Processes at most the next due external-action item of one tenant, or {@code false} when none. */
        boolean runOnce(String tenantId, String workerId);
    }
}
