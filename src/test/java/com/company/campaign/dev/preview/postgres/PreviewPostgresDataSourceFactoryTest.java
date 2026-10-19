package com.company.campaign.dev.preview.postgres;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35D: the Preview pool hands over a session whose allowlisted schema and every server-side bound are already
 * applied, and which carries no active transaction the adapter's mandatory read-only switch would be refused by.
 *
 * <p>These are configuration proofs over the real pool factory, not database evidence: the environment-gated
 * integration test is what proves the same configuration against a live PostgreSQL server.
 */
class PreviewPostgresDataSourceFactoryTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/campaign_local";

    @Test
    void thePoolIsBoundedReadOnlyAndOpensEveryConnectionOutsideAutoCommit() {
        HikariConfig configuration = PreviewPostgresDataSourceFactory.configuration(properties());

        assertThat(configuration.getPoolName()).isEqualTo(PreviewPostgresDataSourceFactory.POOL_NAME);
        assertThat(configuration.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(configuration.getMaximumPoolSize()).isEqualTo(3);
        assertThat(configuration.getMinimumIdle()).isEqualTo(1);
        assertThat(configuration.getConnectionTimeout()).isEqualTo(4_000);
        assertThat(configuration.getValidationTimeout()).isEqualTo(2_000);
        assertThat(configuration.getInitializationFailTimeout()).isEqualTo(4_000);
        assertThat(configuration.isRegisterMbeans()).isFalse();
        assertThat(configuration.isAutoCommit()).isFalse();
        assertThat(configuration.isReadOnly()).isTrue();
    }

    @Test
    void theSchemaAndEveryServerSideBoundAreStartupOptionsAndNoInitSqlCanLeaveATransactionActive() {
        HikariConfig configuration = PreviewPostgresDataSourceFactory.configuration(properties());

        // The one construct that could begin a transaction on a non-auto-commit connection is absent: a
        // post-connect statement would leave the connection in an active transaction and PostgreSQL would then
        // refuse the adapter's read-only switch with SQLSTATE 25001 (active_sql_transaction).
        assertThat(configuration.getConnectionInitSql()).isNull();

        Properties dataSourceProperties = configuration.getDataSourceProperties();
        assertThat(dataSourceProperties.get("ApplicationName"))
                .isEqualTo(PreviewPostgresDataSourceFactory.POOL_NAME);
        assertThat(PreviewPostgresDataSourceFactory.searchPath("loyalty_host"))
                .isEqualTo("\"loyalty_host\",pg_catalog");

        String options = String.valueOf(dataSourceProperties.get("options"));
        assertThat(options)
                .as("the allowlisted schema search path and every server-side bound remain present")
                .contains("-c search_path=\"loyalty_host\",pg_catalog")
                .contains("-c statement_timeout=6000")
                .contains("-c lock_timeout=1500")
                .contains("-c idle_in_transaction_session_timeout=9000")
                .contains("-c default_transaction_read_only=on");
        assertThat(options)
                .as("only server-applied -c startup options, never a SQL statement that could begin a transaction")
                .startsWith("-c ")
                .doesNotContain(";")
                .doesNotContainIgnoringCase("set ");
    }

    private static PreviewPostgresProperties properties() {
        PreviewPostgresProperties properties = new PreviewPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl(URL);
        properties.setUsername("preview-user");
        properties.setPassword("preview-secret-value");
        properties.setSchema("loyalty_host");
        properties.setSampleKey("host-owned-sample-key-value-0123456789");
        properties.setMaximumPoolSize(3);
        properties.setMinimumIdle(1);
        properties.setConnectionTimeoutMs(4_000);
        properties.setValidationTimeoutMs(2_000);
        properties.setStatementTimeoutMs(6_000);
        properties.setLockTimeoutMs(1_500);
        properties.setIdleTransactionTimeoutMs(9_000);
        properties.validate();
        return properties;
    }
}