package com.company.campaign.readiness.postgres;

import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessStorageMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T26 probe tests: the bounded probe reports {@code PASSED} only when the Flyway history records every
 * pinned migration and the readiness scope is readable, and it degrades to {@code FAILED} instead of throwing
 * for every way the boundary can be unavailable.
 *
 * <p>The boundary is scripted, so this is a decision-logic test and never PostgreSQL evidence.
 */
class PostgresReadinessStorageProbeTest {

    private static final ReadinessPostgresProperties PROPERTIES = properties();

    private static ReadinessPostgresProperties properties() {
        ReadinessPostgresProperties properties = new ReadinessPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl("jdbc:postgresql://127.0.0.1:5432/campaign");
        properties.setUsername("readiness-user");
        properties.setPassword("not-a-real-credential");
        properties.setSchema("campaign_readiness");
        return properties;
    }

    @Test
    void everyPinnedMigrationAppliedAndTheScopeReadableIsTheOnlyPassingState() {
        PostgresReadinessStorageProbe probe = new PostgresReadinessStorageProbe(
                ScriptedProbeDatabase.withAppliedVersions(List.of("1", "2", "3")).dataSource(), PROPERTIES);

        assertThat(probe.storageMode()).isEqualTo(ReadinessStorageMode.POSTGRESQL);
        assertThat(probe.postgresqlVerification()).isEqualTo(ReadinessPostgresVerification.PASSED);
    }

    @Test
    void anIncompleteHistoryNeverReportsPassed() {
        assertThat(probeFor(ScriptedProbeDatabase.withAppliedVersions(List.of("1", "2"))).postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
        assertThat(probeFor(ScriptedProbeDatabase.withAppliedVersions(List.of("1", "2", "4")))
                .postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
        assertThat(probeFor(ScriptedProbeDatabase.withEmptyHistory()).postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
    }

    @Test
    void anUnreadableScopeNeverReportsPassed() {
        assertThat(probeFor(ScriptedProbeDatabase.withUnreadableScope(List.of("1", "2", "3")))
                .postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
    }

    @Test
    void anUnreachableEngineReportsFailedWithoutThrowing() {
        PostgresReadinessStorageProbe probe = new PostgresReadinessStorageProbe(
                ScriptedProbeDatabase.unreachable().dataSource(), PROPERTIES);

        assertThat(probe.storageMode()).isEqualTo(ReadinessStorageMode.POSTGRESQL);
        assertThat(probe.postgresqlVerification()).isEqualTo(ReadinessPostgresVerification.FAILED);
    }

    @Test
    void aMissingDataSourceOrConfigurationReportsFailedWithoutThrowing() {
        assertThat(new PostgresReadinessStorageProbe(null, PROPERTIES).postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
        assertThat(new PostgresReadinessStorageProbe(
                ScriptedProbeDatabase.withAppliedVersions(List.of("1", "2", "3")).dataSource(), null)
                .postgresqlVerification())
                .isEqualTo(ReadinessPostgresVerification.FAILED);
    }

    private static PostgresReadinessStorageProbe probeFor(ScriptedProbeDatabase database) {
        return new PostgresReadinessStorageProbe(database.dataSource(), PROPERTIES);
    }
}