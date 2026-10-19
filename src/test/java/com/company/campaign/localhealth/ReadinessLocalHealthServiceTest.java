package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessStatus;
import com.company.campaign.readiness.ReadinessStorageMode;
import com.company.campaign.readiness.ReadinessStorageProbe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T26 storage-mode reporting tests: the local health answer is derived from the probe, is
 * deterministic, and reports {@code READY} for exactly the one state where real PostgreSQL was verified.
 */
class ReadinessLocalHealthServiceTest {

    @Test
    void inMemoryStorageReportsBothStorageBlockersAndBlocked() {
        LocalHealthData data = snapshotOf(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN);

        assertThat(data.process()).isEqualTo("UP");
        assertThat(data.storageMode()).isEqualTo(ReadinessStorageMode.IN_MEMORY);
        assertThat(data.postgresqlVerification()).isEqualTo(ReadinessPostgresVerification.NOT_RUN);
        assertThat(data.readiness().status()).isEqualTo(ReadinessStatus.BLOCKED.name());
        assertThat(data.readiness().blockers()).containsExactly(
                ReadinessReasonCodes.MIGRATIONS_NOT_APPLIED,
                ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED);
        assertThat(data.readiness().evaluatedRequirements()).containsExactly(
                "LOCAL_POSTGRESQL_INTEGRATION", "LOCAL_ORDERED_MIGRATIONS");
    }

    @Test
    void aConfiguredButUnverifiedOrFailedEngineReportsBlocked() {
        for (ReadinessPostgresVerification verification : List.of(
                ReadinessPostgresVerification.NOT_RUN, ReadinessPostgresVerification.FAILED)) {
            LocalHealthData data = snapshotOf(ReadinessStorageMode.POSTGRESQL, verification);

            assertThat(data.readiness().status()).isEqualTo(ReadinessStatus.BLOCKED.name());
            assertThat(data.readiness().blockers()).containsExactly(ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED);
        }
    }

    @Test
    void onlyAVerifiedPostgresqlRunReportsReady() {
        LocalHealthData data = snapshotOf(ReadinessStorageMode.POSTGRESQL, ReadinessPostgresVerification.PASSED);

        assertThat(data.storageMode()).isEqualTo(ReadinessStorageMode.POSTGRESQL);
        assertThat(data.postgresqlVerification()).isEqualTo(ReadinessPostgresVerification.PASSED);
        assertThat(data.readiness().status()).isEqualTo(ReadinessStatus.READY.name());
        assertThat(data.readiness().blockers()).isEmpty();
    }

    @Test
    void theSameProbeStateAlwaysProducesIdenticalOutput() {
        ReadinessLocalHealthService service = new ReadinessLocalHealthService(
                probe(ReadinessStorageMode.IN_MEMORY, ReadinessPostgresVerification.NOT_RUN));

        assertThat(service.snapshot()).isEqualTo(service.snapshot());
        assertThat(service.snapshot().toString()).doesNotContain("jdbc", "password", "tenant", "127.0.0.1");
    }

    private static LocalHealthData snapshotOf(
            ReadinessStorageMode mode, ReadinessPostgresVerification verification) {
        return new ReadinessLocalHealthService(probe(mode, verification)).snapshot();
    }

    private static ReadinessStorageProbe probe(
            ReadinessStorageMode mode, ReadinessPostgresVerification verification) {
        return new ReadinessStorageProbe() {
            @Override
            public ReadinessStorageMode storageMode() {
                return mode;
            }

            @Override
            public ReadinessPostgresVerification postgresqlVerification() {
                return verification;
            }
        };
    }
}