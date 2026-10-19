package com.company.campaign.production.security;

import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * The real-PostgreSQL settings the environment-gated production membership verifier reads.
 *
 * <p>Everything comes from the environment: no credential, URL, database name or membership fact is committed.
 * The verifier is skipped entirely when {@code CAMPAIGN_PRODUCTION_MEMBERSHIP_URL} is absent, and an absent or
 * malformed setting is refused rather than defaulted, so a run either proves the boundary or reports an explicit
 * blocker. Schema creation is only permitted on an isolated schema plainly named for this verifier, so a run can
 * never create or drop anything in a host's own schema.
 */
final class ProductionMembershipPostgresTestEnvironment {

    static final String URL_VARIABLE = "CAMPAIGN_PRODUCTION_MEMBERSHIP_URL";
    static final String USERNAME_VARIABLE = "CAMPAIGN_PRODUCTION_MEMBERSHIP_USERNAME";
    static final String PASSWORD_VARIABLE = "CAMPAIGN_PRODUCTION_MEMBERSHIP_PASSWORD";
    static final String SCHEMA_VARIABLE = "CAMPAIGN_PRODUCTION_MEMBERSHIP_SCHEMA";

    static final String DEFAULT_SCHEMA = "campaign_production_t35a1_it";

    private static final Pattern ISOLATED_SCHEMA = Pattern.compile("^campaign_production(_[a-z0-9_]+)?$");

    private ProductionMembershipPostgresTestEnvironment() {
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

    /** The validated production properties for the configured environment. */
    static ProductionSecurityProperties properties() {
        ProductionSecurityProperties properties = new ProductionSecurityProperties();
        properties.setEnabled(true);
        properties.getJwt().setIssuerUri("https://production.issuer.test");
        properties.getJwt().setAudience("campaign-production-api");
        properties.getMembership().setMode(ProductionMembershipMode.POSTGRESQL);
        properties.getMembership().setUrl(text(URL_VARIABLE));
        properties.getMembership().setUsername(text(USERNAME_VARIABLE));
        properties.getMembership().setPassword(text(PASSWORD_VARIABLE));
        properties.getMembership().setSchema(schema());
        properties.validate();
        return properties;
    }

    /** A bounded pool in the dedicated schema for the configured environment. */
    static HikariDataSource dataSource() {
        ProductionSecurityProperties properties = properties();
        ReadinessFlywayLog.registerSecret(properties.getMembership().getPassword());
        ReadinessFlywayLog.registerSecret(properties.getMembership().getUrl());
        return MyBatisTestSupport.dataSource(
                "campaign-membership-it", properties.getMembership().getUrl(),
                properties.getMembership().getUsername(), properties.getMembership().getPassword(),
                properties.getMembership().getSchema());
    }

    /** A plain connection used only to create and drop this verifier's own isolated schema. */
    static Connection schemaConnection() throws SQLException {
        ProductionSecurityProperties properties = properties();
        return java.sql.DriverManager.getConnection(
                properties.getMembership().getUrl(),
                properties.getMembership().getUsername(),
                properties.getMembership().getPassword());
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}
