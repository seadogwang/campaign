package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditRecord;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The environment-gated real-PostgreSQL verification of the durable audit ledger.
 *
 * <p>It is skipped unless {@code CAMPAIGN_PRODUCTION_AUDIT_URL} (plus a user and a password) is supplied, and
 * when it does run it uses only real PostgreSQL: there is no test double, no embedded engine and no H2 fallback
 * anywhere on this path — a missing setting means the run does not happen, never that a substitute runs instead.
 * It applies the committed audit migration into an isolated schema it owns, then exercises the committed
 * migration and all five accepted audit ports end to end.
 *
 * <p>Without an executed run of this test there is <strong>no live PostgreSQL evidence</strong> for the ledger,
 * and no readiness claim may be inferred from the scripted JDBC boundary the other tests use.
 */
@EnabledIfEnvironmentVariable(
        named = ProductionAuditPostgresTestEnvironment.URL_VARIABLE,
        matches = ".+",
        disabledReason = "real PostgreSQL production audit database is not configured")
class ProductionAuditPostgresLiveIntegrationTest {

    private static final String COUNT_SQL =
            "select count(*) from campaign_production_audit_ledger";

    private static final String DOMAINS_SQL =
            "select domain from campaign_production_audit_ledger order by domain";

    private static final String TENANT_SEARCH_SQL =
            "select count(*) from campaign_production_audit_ledger where source_document like ?";

    @Test
    void realPostgresqlMigratesAndServesAllFivePorts() throws Exception {
        Assumptions.assumeTrue(ProductionAuditPostgresTestEnvironment.mayCreateSchema(),
                "the configured schema is not an isolated verifier schema");
        String schema = ProductionAuditPostgresTestEnvironment.schema();

        try {
            dropSchema(schema);
            try (HikariDataSource dataSource = ProductionAuditPostgresTestEnvironment.dataSource()) {
                ProductionAuditProperties properties = ProductionAuditPostgresTestEnvironment.properties();
                Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .createSchemas(true)
                        .locations(ProductionAuditProperties.MIGRATION_LOCATIONS)
                        .failOnMissingLocations(true)
                        .cleanDisabled(true)
                        .load()
                        .migrate();

                MyBatisPlusProductionAuditLedger ledger = new MyBatisPlusProductionAuditLedger(
                        MyBatisTestSupport.sessionFactory(dataSource), properties.getLedger());

                new ProductionSecurityAuditSink(ledger).record(ProductionAuditTestFixtures.securityEvent());
                new ProductionConversationAuditSink(ledger)
                        .record(ProductionAuditTestFixtures.conversationRecord());
                new ProductionLifecycleAuditSink(ledger).record(ProductionAuditTestFixtures.lifecycleEvent());
                new ProductionRuntimeAuditSink(ledger).record(ProductionAuditTestFixtures.runtimeRecord());
                ProductionActionAuditSink action = new ProductionActionAuditSink(ledger);
                List<ActionAuditRecord> firstActionBatch = List.of(ProductionAuditTestFixtures.actionRecord());
                action.recordAll(firstActionBatch);
                action.commitAll(firstActionBatch);

                assertThat(rowCount(dataSource)).isEqualTo(5);
                assertThat(domains(dataSource))
                        .containsExactlyInAnyOrder("SECURITY", "CONVERSATION", "LIFECYCLE", "RUNTIME", "ACTION");
                assertThat(storedDocumentsContain(dataSource, ProductionAuditTestFixtures.TENANT))
                        .as("no stored row carries a raw tenant identifier")
                        .isFalse();

                // An exact replay of every port writes nothing and is not a conflict.
                new ProductionSecurityAuditSink(ledger).record(ProductionAuditTestFixtures.securityEvent());
                new ProductionConversationAuditSink(ledger)
                        .record(ProductionAuditTestFixtures.conversationRecord());
                new ProductionLifecycleAuditSink(ledger).record(ProductionAuditTestFixtures.lifecycleEvent());
                new ProductionRuntimeAuditSink(ledger).record(ProductionAuditTestFixtures.runtimeRecord());
                assertThat(rowCount(dataSource)).isEqualTo(5);

                // The same logical event with changed material is a bounded conflict, and the batch is atomic.
                new ProductionRuntimeAuditSink(ledger).record(
                        ProductionAuditTestFixtures.runtimeRecord("record-9", "EXECUTION_CREATED", 0L, 1L));
                assertThat(rowCount(dataSource)).isEqualTo(6);
                assertThatThrownBy(() -> new ProductionRuntimeAuditSink(ledger).recordAll(List.of(
                                ProductionAuditTestFixtures.runtimeRecord("record-8", "EXECUTION_CREATED", 0L, 1L),
                                ProductionAuditTestFixtures.runtimeRecord("record-9", "EXECUTION_CREATED", 0L, 2L))))
                        .isInstanceOf(ProductionAuditException.class)
                        .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                                .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFLICT));
                assertThat(rowCount(dataSource))
                        .as("the conflicted batch committed none of its rows")
                        .isEqualTo(6);

                // The Action port compensates an uncommitted batch with a real rollback, never a delete.
                List<ActionAuditRecord> compensated = List.of(
                        ProductionAuditTestFixtures.actionRecord("record-10", "ACTION_RESERVED", 0L, 1L));
                action.recordAll(compensated);
                action.rollbackAll(compensated);
                assertThat(rowCount(dataSource))
                        .as("a compensated batch leaves committed history untouched")
                        .isEqualTo(6);

                // The Action port commits its staged batch at the accepted boundary.
                List<ActionAuditRecord> secondActionBatch = List.of(
                        ProductionAuditTestFixtures.actionRecord("record-11", "ACTION_RESERVED", 0L, 1L));
                action.recordAll(secondActionBatch);
                action.commitAll(secondActionBatch);
                assertThat(rowCount(dataSource)).isEqualTo(7);
            }
        } finally {
            dropSchema(schema);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long rowCount(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static List<String> domains(HikariDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DOMAINS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            List<String> domains = new ArrayList<>();
            while (resultSet.next()) {
                domains.add(resultSet.getString(1));
            }
            return domains;
        }
    }

    private static boolean storedDocumentsContain(HikariDataSource dataSource, String value) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TENANT_SEARCH_SQL)) {
            statement.setString(1, "%" + value + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = ProductionAuditPostgresTestEnvironment.schemaConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        }
    }
}
