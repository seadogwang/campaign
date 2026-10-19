package com.company.campaign.readiness.postgres;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.readiness.ReadinessLimits;
import com.company.campaign.readiness.ReadinessPostgresVerification;
import com.company.campaign.readiness.ReadinessStorageMode;
import com.company.campaign.readiness.ReadinessStorageProbe;
import com.company.campaign.readiness.migration.MigrationAsset;
import com.company.campaign.readiness.migration.MigrationCatalog;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The bounded probe that decides whether the configured engine was really exercised.
 *
 * <p>It runs once, while the application starts and after the ordered migration run, and it answers two
 * bounded questions:
 *
 * <ul>
 *   <li>does the Flyway history of the dedicated schema record every pinned migration version as successful?
 *       The expected versions come from {@link MigrationCatalog}, so a missing, renamed or reordered asset
 *       cannot be reported as applied;</li>
 *   <li>is the readiness evidence table readable in the same session, so the scheme the store addresses is the
 *       one that was created?</li>
 * </ul>
 *
 * <p>It reads no row content, selects no identifier, keeps the result as one immutable enum and never throws:
 * an unreachable engine, a missing history table and a failed read all become
 * {@link ReadinessPostgresVerification#FAILED}, so the local health endpoint stays bounded and no request can
 * make it disclose a driver error. The result is computed once, so the unauthenticated endpoint cannot be
 * used to drive repeated database work.
 */
public final class PostgresReadinessStorageProbe implements ReadinessStorageProbe {

    private static final String SELECT_APPLIED_VERSIONS =
            "SELECT version FROM " + CampaignDatasourceProperties.FLYWAY_HISTORY_TABLE
                    + " WHERE success = TRUE ORDER BY installed_rank ASC";

    private static final String SELECT_SCOPE_READABLE = "SELECT 1 FROM readiness_scope LIMIT 1";

    private static final int MAX_HISTORY_ROWS = 64;

    private final ReadinessPostgresVerification verification;

    public PostgresReadinessStorageProbe(DataSource dataSource, ReadinessPostgresProperties properties) {
        this.verification = probe(dataSource, properties);
    }

    @Override
    public ReadinessStorageMode storageMode() {
        return ReadinessStorageMode.POSTGRESQL;
    }

    @Override
    public ReadinessPostgresVerification postgresqlVerification() {
        return verification;
    }

    private static ReadinessPostgresVerification probe(
            DataSource dataSource, ReadinessPostgresProperties properties) {
        if (dataSource == null || properties == null) {
            return ReadinessPostgresVerification.FAILED;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!historyCarriesEveryPinnedMigration(connection)) {
                return ReadinessPostgresVerification.FAILED;
            }
            if (!scopeIsReadable(connection)) {
                return ReadinessPostgresVerification.FAILED;
            }
            return ReadinessPostgresVerification.PASSED;
        } catch (SQLException | RuntimeException exception) {
            return ReadinessPostgresVerification.FAILED;
        }
    }

    private static boolean historyCarriesEveryPinnedMigration(Connection connection) throws SQLException {
        Set<String> expected = new LinkedHashSet<>();
        for (MigrationAsset asset : MigrationCatalog.pinned()) {
            expected.add(Integer.toString(asset.version()));
        }
        Set<String> applied = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_APPLIED_VERSIONS)) {
            statement.setQueryTimeout(ReadinessLimits.JDBC_STATEMENT_TIMEOUT_SECONDS);
            statement.setMaxRows(MAX_HISTORY_ROWS);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    applied.add(resultSet.getString(1));
                }
            }
        }
        return applied.containsAll(expected);
    }

    private static boolean scopeIsReadable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_SCOPE_READABLE)) {
            statement.setQueryTimeout(ReadinessLimits.JDBC_STATEMENT_TIMEOUT_SECONDS);
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return true;
            }
        }
    }
}