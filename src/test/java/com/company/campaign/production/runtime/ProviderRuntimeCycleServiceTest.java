package com.company.campaign.production.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T35C2: the explicit bounded cycle over the trusted tenant source.
 *
 * <p>Every run uses deterministic item runners, so the exact bounded-work and failure-isolation behaviour is
 * proved without a durable database, a live Provider or a sleeping thread.
 */
class ProviderRuntimeCycleServiceTest {

    private static final String WORKER_ID = "prw-00000000000000000000000000000000";
    private static final String TENANT = "tenant-1";

    @Test
    void theCycleProcessesAtMostTheConfiguredItemsPerTenantPerCycle() {
        int[] runtimeCalls = {0};
        int[] actionCalls = {0};
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> ++runtimeCalls[0] <= 10,
                (tenantId, workerId) -> ++actionCalls[0] <= 10,
                () -> List.of(TENANT), 8, 3, 2);

        ProviderRuntimeCycleReport report = cycle.runCycle(WORKER_ID);

        assertThat(report.status()).isEqualTo(ProviderRuntimeCycleStatus.COMPLETED);
        assertThat(report.tenantsScanned()).isEqualTo(1);
        assertThat(report.runtimeProcessed()).isEqualTo(3);
        assertThat(report.actionProcessed()).isEqualTo(2);
        assertThat(report.runtimeFailed()).isZero();
        assertThat(report.actionFailed()).isZero();
    }

    @Test
    void everyConfiguredTenantIsScannedOnce() {
        List<String> scanned = new ArrayList<>();
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> {
                    scanned.add(tenantId);
                    return false;
                },
                (tenantId, workerId) -> false,
                () -> List.of("tenant-1", "tenant-2"), 8, 8, 1);

        ProviderRuntimeCycleReport report = cycle.runCycle(WORKER_ID);

        assertThat(report.tenantsScanned()).isEqualTo(2);
        assertThat(scanned).containsExactlyInAnyOrder("tenant-1", "tenant-2");
    }

    @Test
    void oneFailedItemStopsItsTenantQueueButOtherQueuesAndTenantsStillRun() {
        List<String> runtimeCalls = new ArrayList<>();
        List<String> actionCalls = new ArrayList<>();
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> {
                    runtimeCalls.add(tenantId);
                    if (tenantId.equals("tenant-1")) {
                        throw new IllegalStateException("raw transport detail that must never leak");
                    }
                    return false;
                },
                (tenantId, workerId) -> {
                    actionCalls.add(tenantId);
                    return false;
                },
                () -> List.of("tenant-1", "tenant-2"), 8, 8, 1);

        ProviderRuntimeCycleReport report = cycle.runCycle(WORKER_ID);

        assertThat(report.status()).isEqualTo(ProviderRuntimeCycleStatus.DEGRADED);
        assertThat(report.runtimeProcessed()).isZero();
        assertThat(report.runtimeFailed()).isEqualTo(1);
        assertThat(runtimeCalls).containsExactly("tenant-1", "tenant-2");
        assertThat(actionCalls).containsExactly("tenant-1", "tenant-2");
        assertThat(report.failureCodes())
                .containsExactly(ProductionProviderRuntimeErrorCodes.CYCLE_ITEM_FAILED);
        assertThat(report.toString()).doesNotContain("raw transport detail");
    }

    @Test
    void aContractFailureSurfacesOnlyItsBoundedCode() {
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> {
                    throw new ProductionProviderRuntimeConfigurationException("BOUNDED_ITEM_CODE", "x");
                },
                (tenantId, workerId) -> false,
                () -> List.of(TENANT), 8, 8, 1);

        ProviderRuntimeCycleReport report = cycle.runCycle(WORKER_ID);

        assertThat(report.failureCodes()).containsExactly("BOUNDED_ITEM_CODE");
        assertThat(report.runtimeFailed()).isEqualTo(1);
    }

    @Test
    void aBlankDuplicateOrOverLimitTenantListFailsTheWholeCycleClosed() {
        assertThat(failedReport(List.of("tenant-1", "tenant-1"))).isTrue();
        assertThat(failedReport(List.of(" "))).isTrue();
        assertThat(failedReport(List.of("tenant-1", "tenant-2", "tenant-3"))).isTrue();
    }

    @Test
    void aFailedCycleClaimsNoWorkAndDisclosesNoOwnerValue() {
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> {
                    throw new AssertionError("no work may be claimed for an unusable tenant list");
                },
                (tenantId, workerId) -> {
                    throw new AssertionError("no work may be claimed for an unusable tenant list");
                },
                () -> List.of("tenant-1", "tenant-1"), 8, 8, 1);

        ProviderRuntimeCycleReport report = cycle.runCycle(WORKER_ID);

        assertThat(report.status()).isEqualTo(ProviderRuntimeCycleStatus.FAILED);
        assertThat(report.runtimeProcessed()).isZero();
        assertThat(report.actionProcessed()).isZero();
        assertThat(report.failureCodes())
                .containsExactly(ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID);
        assertThat(report.toString()).doesNotContain("tenant-1");
    }

    @Test
    void anOutOfBoundsCycleConfigurationIsRefused() {
        assertThatThrownBy(() -> cycle(
                (tenantId, workerId) -> false, (tenantId, workerId) -> false,
                () -> List.of(TENANT), 8, 0, 1))
                .isInstanceOf(ProductionProviderRuntimeConfigurationException.class);
        assertThatThrownBy(() -> cycle(
                (tenantId, workerId) -> false, (tenantId, workerId) -> false,
                () -> List.of(TENANT), 8, 8, 0))
                .isInstanceOf(ProductionProviderRuntimeConfigurationException.class);
    }

    private static boolean failedReport(List<String> tenants) {
        ProviderRuntimeCycleService cycle = cycle(
                (tenantId, workerId) -> false, (tenantId, workerId) -> false, () -> tenants, 2, 8, 1);
        return cycle.runCycle(WORKER_ID).status() == ProviderRuntimeCycleStatus.FAILED;
    }

    private static ProviderRuntimeCycleService cycle(
            ProviderRuntimeCycleService.RuntimeItemRunner runtimeItems,
            ProviderRuntimeCycleService.ActionItemRunner actionItems,
            ProviderRuntimeTenantSource tenants,
            int maxTenants,
            int maxRuntimeItems,
            int maxActionItems) {
        return new ProviderRuntimeCycleService(
                runtimeItems, actionItems, tenants, maxTenants, maxRuntimeItems, maxActionItems);
    }
}
