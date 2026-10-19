package com.company.campaign.dev.preview.postgres;

import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * The real-PostgreSQL settings the environment-gated Preview verifier reads.
 *
 * <p>Everything comes from the environment: no credential, URL, database name or table asset is committed. The
 * verifier is skipped entirely when {@code CAMPAIGN_PREVIEW_POSTGRES_URL} is absent, and an absent or malformed
 * setting is refused rather than defaulted, so a run either proves the boundary or reports an explicit blocker.
 *
 * <p>Schema creation is only permitted on an isolated schema plainly named for this verifier, so a run can
 * never create or drop anything in a host's own schema. The development-profile end-to-end proof additionally
 * requires the explicit {@code CAMPAIGN_PREVIEW_POSTGRES_ALLOW_PROFILE_DDL=true} opt-in, because the frozen
 * development catalog declares its own physical schema and creating those tables is only safe on a database an
 * operator has explicitly dedicated to this verifier.
 */
final class PreviewPostgresTestEnvironment {

    static final String URL_VARIABLE = "CAMPAIGN_PREVIEW_POSTGRES_URL";
    static final String USERNAME_VARIABLE = "CAMPAIGN_PREVIEW_POSTGRES_USERNAME";
    static final String PASSWORD_VARIABLE = "CAMPAIGN_PREVIEW_POSTGRES_PASSWORD";
    static final String SCHEMA_VARIABLE = "CAMPAIGN_PREVIEW_POSTGRES_SCHEMA";
    static final String ALLOW_PROFILE_DDL_VARIABLE = "CAMPAIGN_PREVIEW_POSTGRES_ALLOW_PROFILE_DDL";

    static final String DEFAULT_SCHEMA = "campaign_preview_t34c_it";

    private static final Pattern ISOLATED_SCHEMA = Pattern.compile("^campaign_preview(_[a-z0-9_]+)?$");

    private PreviewPostgresTestEnvironment() {
    }

    /** True when the environment supplies a connection target. */
    static boolean isConfigured() {
        return text(URL_VARIABLE) != null;
    }

    /** The dedicated schema this run owns. */
    static String schema() {
        String value = text(SCHEMA_VARIABLE);
        return value == null ? DEFAULT_SCHEMA : value;
    }

    /** Whether this run may create and drop the isolated schema it owns. */
    static boolean mayCreateSchema() {
        return ISOLATED_SCHEMA.matcher(schema()).matches();
    }

    /** Whether this run may create the frozen development profile's own physical tables. */
    static boolean mayCreateProfileSchema() {
        return "true".equalsIgnoreCase(text(ALLOW_PROFILE_DDL_VARIABLE));
    }

    /** The validated properties for the configured environment. */
    static PreviewPostgresProperties properties() {
        PreviewPostgresProperties properties = new PreviewPostgresProperties();
        properties.setEnabled(true);
        properties.setUrl(text(URL_VARIABLE));
        properties.setUsername(text(USERNAME_VARIABLE));
        properties.setPassword(text(PASSWORD_VARIABLE));
        properties.setSchema(schema());
        properties.setSampleKey("integration-sample-key-value-0123456789");
        properties.validate();
        return properties;
    }

    /** A bounded read-only pool for the configured environment. */
    static HikariDataSource dataSource() {
        PreviewPostgresProperties properties = properties();
        ReadinessFlywayLog.registerSecret(properties.getPassword());
        ReadinessFlywayLog.registerSecret(properties.getUrl());
        ReadinessFlywayLog.registerSecret(properties.getSampleKey());
        return PreviewPostgresDataSourceFactory.dataSource(properties);
    }

    /**
     * A plain, writable fixture connection used only to create this verifier's own tables.
     *
     * <p>It deliberately does not come from the read-only Preview pool: that pool runs every session with
     * {@code default_transaction_read_only=on}, so it can never be the thing that creates a schema or a table.
     */
    static Connection schemaConnection() throws SQLException {
        PreviewPostgresProperties properties = properties();
        return java.sql.DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
    }

    /**
     * Creates the isolated schema this run owns, refusing any schema that is not plainly dedicated to it.
     *
     * <p>The pool's {@code search_path} is irrelevant to every proof here, because the compiled artifact always
     * renders fully qualified {@code "schema"."table"} names.
     */
    static void createIsolatedSchema(Connection connection) throws SQLException {
        if (!mayCreateSchema()) {
            throw new IllegalStateException("The configured Preview schema is not an isolated verifier schema.");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema() + "\" CASCADE");
            statement.execute("CREATE SCHEMA \"" + schema() + "\"");
        }
    }

    /** The bounded server identity of the engine that actually answered. */
    static String serverVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT version()")) {
            if (!resultSet.next()) {
                return "unknown";
            }
            String version = resultSet.getString(1);
            return version == null ? "unknown" : version.substring(0, Math.min(64, version.length()));
        }
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}