package com.company.campaign.readiness.postgres;

import com.company.campaign.readiness.ReadinessEnvironment;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The real-PostgreSQL settings the environment-gated verifier reads.
 *
 * <p>Everything comes from the environment: no credential, URL or database name is committed. The verifier is
 * skipped entirely when {@code CAMPAIGN_READINESS_POSTGRES_URL} is absent, and an absent or malformed setting
 * is refused rather than defaulted, so a run either proves the boundary or reports an explicit blocker.
 *
 * <p>The dedicated schema rule is what keeps a run from touching anything but its own boundary: a reserved
 * schema is refused by the shared configuration, cleaning is only permitted on a schema whose name is plainly
 * this verifier's own (or when the operator explicitly allows it), and a production environment is never
 * cleaned.
 */
final class ReadinessPostgresTestEnvironment {

    static final String URL_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_URL";
    static final String USERNAME_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_USERNAME";
    static final String PASSWORD_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_PASSWORD";
    static final String SCHEMA_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_SCHEMA";
    static final String ENVIRONMENT_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_ENVIRONMENT";
    static final String ALLOW_CLEAN_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_ALLOW_CLEAN";
    static final String REPORT_VARIABLE = "CAMPAIGN_READINESS_POSTGRES_REPORT";

    static final String DEFAULT_SCHEMA = "campaign_readiness_t26_it";
    static final String DEFAULT_REPORT_REFERENCE = ".automation/runtime/readiness-postgres-report.json";

    private static final Pattern DEDICATED_SCHEMA = Pattern.compile("^campaign_readiness(_[a-z0-9_]+)?$");

    private ReadinessPostgresTestEnvironment() {
    }

    /** True when the environment supplies a connection target. */
    static boolean isConfigured() {
        return text(URL_VARIABLE) != null;
    }

    /** The readiness environment this run is executed in; an unknown value is refused. */
    static ReadinessEnvironment environment() {
        String value = text(ENVIRONMENT_VARIABLE);
        if (value == null) {
            return ReadinessEnvironment.LOCAL;
        }
        for (ReadinessEnvironment candidate : ReadinessEnvironment.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new IllegalStateException("CAMPAIGN_READINESS_POSTGRES_ENVIRONMENT is not a readiness environment");
    }

    /** The dedicated schema this run uses. */
    static String schema() {
        String value = text(SCHEMA_VARIABLE);
        return value == null ? DEFAULT_SCHEMA : value;
    }

    /** The validated properties for the configured environment. */
    static ReadinessPostgresProperties properties() {
        ReadinessPostgresProperties properties = new ReadinessPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl(text(URL_VARIABLE));
        properties.setUsername(text(USERNAME_VARIABLE));
        properties.setPassword(text(PASSWORD_VARIABLE));
        properties.setSchema(schema());
        properties.validate();
        return properties;
    }

    /** A bounded pool for the configured environment. */
    static HikariDataSource dataSource() {
        ReadinessPostgresProperties properties = properties();
        ReadinessFlywayLog.registerSecret(properties.getPassword());
        ReadinessFlywayLog.registerSecret(properties.getUrl());
        return MyBatisTestSupport.dataSource(
                "campaign-readiness-it", properties.getUrl(), properties.getUsername(),
                properties.getPassword(), properties.getSchema());
    }

    /**
     * Whether this run may clean its boundary.
     *
     * <p>Only a schema named plainly for this verifier, or an explicit operator opt-in, is cleanable, and a
     * production environment is never cleaned.
     */
    static boolean mayClean() {
        if (environment().production()) {
            return false;
        }
        if ("true".equalsIgnoreCase(text(ALLOW_CLEAN_VARIABLE))) {
            return true;
        }
        return DEDICATED_SCHEMA.matcher(schema()).matches();
    }

    /** The real Flyway runtime for the committed asset set, over the given boundary. */
    static Flyway flyway(DataSource dataSource, boolean cleanAllowed) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema())
                .defaultSchema(schema())
                .createSchemas(true)
                .locations(ReadinessPostgresProperties.MIGRATION_LOCATIONS)
                .failOnMissingLocations(true)
                .cleanDisabled(!cleanAllowed)
                .loggers(ReadinessFlywayLogCreator.class.getName())
                .load();
    }

    /** The applied migration versions, in order. */
    static List<Integer> appliedVersions(Flyway flyway) {
        List<Integer> versions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null) {
                versions.add(Integer.parseInt(info.getVersion().getVersion()));
            }
        }
        return List.copyOf(versions);
    }

    /** The bounded server identity of the engine that actually answered. */
    static String serverVersion(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT version()");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return "unknown";
            }
            String version = resultSet.getString(1);
            return version == null ? "unknown" : version.substring(0, Math.min(64, version.length()));
        } catch (SQLException exception) {
            throw new IllegalStateException("The PostgreSQL version could not be read", exception);
        }
    }

    /** The report path this run writes, bounded to the repository. */
    static java.nio.file.Path reportPath() {
        String configured = text(REPORT_VARIABLE);
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path path = configured == null
                ? root.resolve(DEFAULT_REPORT_REFERENCE)
                : java.nio.file.Path.of(configured).toAbsolutePath();
        if (!path.startsWith(root)) {
            throw new IllegalStateException("The PostgreSQL verification report path must be inside the repository");
        }
        return path;
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}
