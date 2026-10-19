package com.company.campaign.production.runtime;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35C2: the bounded, redacted internal snapshot of the production provider runtime host.
 */
class ProviderRuntimeObservabilityTest {

    private static final ContentHash CONFIGURATION_HASH =
            ContentHash.sha256("provider-runtime-config".getBytes(StandardCharsets.UTF_8));

    @Test
    void aFreshSnapshotHasNeverRunAndIsNotRunning() {
        ProviderRuntimeObservability observability =
                new ProviderRuntimeObservability(ProviderRuntimeBacklog::empty, CONFIGURATION_HASH);

        ProviderRuntimeSnapshot snapshot = observability.snapshot();

        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.lastCycleStatus()).isEqualTo(ProviderRuntimeCycleStatus.NEVER_RUN);
        assertThat(snapshot.lastCycleAt()).isNull();
        assertThat(snapshot.runtimeProcessed()).isZero();
        assertThat(snapshot.configurationHash()).isEqualTo(CONFIGURATION_HASH);
        assertThat(snapshot.healthy()).isFalse();
    }

    @Test
    void recordingCyclesFoldsTheBoundedCountsIntoTheCumulativeProjection() {
        ProviderRuntimeObservability observability =
                new ProviderRuntimeObservability(ProviderRuntimeBacklog::empty, CONFIGURATION_HASH);
        observability.start();

        observability.recordCycle(report(ProviderRuntimeCycleStatus.COMPLETED, 2, 3, 1, 0, 0), null);
        observability.recordCycle(report(ProviderRuntimeCycleStatus.DEGRADED, 1, 2, 0, 1, 1), null);

        ProviderRuntimeSnapshot snapshot = observability.snapshot();
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.lastCycleStatus()).isEqualTo(ProviderRuntimeCycleStatus.DEGRADED);
        assertThat(snapshot.tenantsScanned()).isEqualTo(3);
        assertThat(snapshot.runtimeProcessed()).isEqualTo(5);
        assertThat(snapshot.actionProcessed()).isEqualTo(1);
        assertThat(snapshot.runtimeFailed()).isEqualTo(1);
        assertThat(snapshot.actionFailed()).isEqualTo(1);
    }

    @Test
    void theBacklogCountsComeFromTheNarrowHostPort() {
        ProviderRuntimeObservability observability = new ProviderRuntimeObservability(
                () -> new ProviderRuntimeBacklog(7L, 2L, 3L, 1L), CONFIGURATION_HASH);

        ProviderRuntimeSnapshot snapshot = observability.snapshot();

        assertThat(snapshot.backlog()).isEqualTo(7L);
        assertThat(snapshot.leaseExpiries()).isEqualTo(2L);
        assertThat(snapshot.unknownActions()).isEqualTo(3L);
        assertThat(snapshot.needsReviewParticipants()).isEqualTo(1L);
    }

    @Test
    void aThrowingHostPortIsReportedAsNoObservedBacklog() {
        ProviderRuntimeObservability observability = new ProviderRuntimeObservability(
                () -> {
                    throw new IllegalStateException("host detail that must never surface");
                }, CONFIGURATION_HASH);

        ProviderRuntimeSnapshot snapshot = observability.snapshot();

        assertThat(snapshot.backlog()).isZero();
        assertThat(snapshot.needsReviewParticipants()).isZero();
    }

    @Test
    void aStoppedHostReportsNoLongerRunning() {
        ProviderRuntimeObservability observability =
                new ProviderRuntimeObservability(ProviderRuntimeBacklog::empty, CONFIGURATION_HASH);
        observability.start();
        observability.stop();

        assertThat(observability.snapshot().running()).isFalse();
    }

    @Test
    void theSnapshotDisclosesNoOwnerMemberOrProviderValue() {
        ProviderRuntimeObservability observability = new ProviderRuntimeObservability(
                () -> new ProviderRuntimeBacklog(1L, 1L, 1L, 1L), CONFIGURATION_HASH);
        observability.recordCycle(report(ProviderRuntimeCycleStatus.COMPLETED, 1, 1, 1, 0, 0), null);

        assertThat(observability.snapshot().toString())
                .doesNotContain("tenant-1")
                .doesNotContain("recipient@example.test")
                .doesNotContain("provider-secret")
                .doesNotContain("member-1");
    }

    private static ProviderRuntimeCycleReport report(
            ProviderRuntimeCycleStatus status,
            int tenants,
            int runtimeProcessed,
            int actionProcessed,
            int runtimeFailed,
            int actionFailed) {
        return new ProviderRuntimeCycleReport(
                status, tenants, runtimeProcessed, actionProcessed, runtimeFailed, actionFailed, List.of());
    }
}
