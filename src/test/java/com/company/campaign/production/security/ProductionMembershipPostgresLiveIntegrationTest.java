package com.company.campaign.production.security;

import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The environment-gated real-PostgreSQL verification of the production membership adapter.
 *
 * <p>It is skipped unless {@code CAMPAIGN_PRODUCTION_MEMBERSHIP_URL} (plus a user and a password) is supplied,
 * and when it does run it uses only real PostgreSQL: there is no test double, no embedded engine and no H2
 * fallback anywhere on this path — a missing setting means the run does not happen, never that a substitute runs
 * instead. It applies the committed membership migration into an isolated schema it owns, inserts server-owned
 * membership rows and proves the exact pins and the duplicate-active-authority constraint.
 */
@EnabledIfEnvironmentVariable(
        named = ProductionMembershipPostgresTestEnvironment.URL_VARIABLE,
        matches = ".+",
        disabledReason = "real PostgreSQL production membership database is not configured")
class ProductionMembershipPostgresLiveIntegrationTest {

    private static final String ISSUER = "https://production.issuer.test";
    private static final String SUBJECT = "user-live-1";
    private static final String SELECTOR = "selector-live-1";

    @Test
    void realPostgresqlMigratesResolvesAndRefusesDuplicateActiveAuthority() throws Exception {
        Assumptions.assumeTrue(ProductionMembershipPostgresTestEnvironment.mayCreateSchema(),
                "the configured schema is not an isolated verifier schema");
        // PostgreSQL timestamptz round-trips at microsecond precision, while Instant.now() carries nanoseconds.
        // The round-trip instant is created at exactly that documented precision so an inserted as_of reads back
        // identical; production membership timestamps themselves are unchanged.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String schema = ProductionMembershipPostgresTestEnvironment.schema();

        try {
            dropSchema(schema);
            try (HikariDataSource dataSource = ProductionMembershipPostgresTestEnvironment.dataSource()) {
                ProductionSecurityProperties properties = ProductionMembershipPostgresTestEnvironment.properties();
                Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .createSchemas(true)
                        .locations(ProductionSecurityConfiguration.MigrationLocations.PRODUCTION_MEMBERSHIP)
                        .failOnMissingLocations(true)
                        .cleanDisabled(true)
                        .load()
                        .migrate();

                insertMembership(dataSource, now);
                insertPermissions(dataSource);

                MyBatisPlusProductionMembershipRepository repository =
                        new MyBatisPlusProductionMembershipRepository(
                                MyBatisTestSupport.sessionFactory(dataSource), properties.getMembership());
                ResolvedMembership resolved = repository
                        .findActiveMembership(new VerifiedIdentity(SUBJECT, ISSUER, now), Optional.of(SELECTOR))
                        .orElseThrow();

                assertThat(resolved.membership().userId()).isEqualTo(SUBJECT);
                assertThat(resolved.membership().permissions()).hasSize(CampaignPermission.values().length);
                assertThat(resolved.reference().asOf()).isEqualTo(now);
                assertThat(resolved.reference().reportingCurrency()).isEqualTo("CNY");

                assertThatThrownBy(() -> insertMembership(dataSource, now))
                        .as("a second active row with the same issuer, subject and selector is refused")
                        .isInstanceOf(SQLException.class);
            }
        } finally {
            dropSchema(schema);
        }
    }

    private static void insertMembership(HikariDataSource dataSource, Instant now) throws SQLException {
        String sql = "insert into campaign_membership (membership_id, issuer, subject, user_id, tenant_id, "
                + "program_id, data_scope_id, selection_key, status, valid_from, valid_until, metadata_snapshot_id, "
                + "locale, timezone, reporting_currency, as_of, data_watermark) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, null, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "membership-live-1");
            statement.setString(2, ISSUER);
            statement.setString(3, SUBJECT);
            statement.setString(4, SUBJECT);
            statement.setString(5, "tenant-live");
            statement.setString(6, "program-live");
            statement.setString(7, "scope-live");
            statement.setString(8, SELECTOR);
            statement.setTimestamp(9, Timestamp.from(now.minusSeconds(3_600)));
            statement.setString(10, "core-loyalty-metadata");
            statement.setString(11, "zh-CN");
            statement.setString(12, "Asia/Shanghai");
            statement.setString(13, "CNY");
            statement.setTimestamp(14, Timestamp.from(now));
            statement.setTimestamp(15, Timestamp.from(now.minusSeconds(60)));
            statement.executeUpdate();
        }
    }

    private static void insertPermissions(HikariDataSource dataSource) throws SQLException {
        String sql = "insert into campaign_membership_permission (membership_id, permission) values (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CampaignPermission permission : CampaignPermission.values()) {
                statement.setString(1, "membership-live-1");
                statement.setString(2, permission.wireName());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = ProductionMembershipPostgresTestEnvironment.schemaConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        }
    }
}
