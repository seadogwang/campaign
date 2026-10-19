package com.company.campaign.production.audit;

import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * The real-PostgreSQL settings the environment-gated durable audit verifier reads.
 *
 * <p>Everything comes from the environment: no credential, URL, database name or audit fact is committed. The
 * verifier is skipped entirely when {@code CAMPAIGN_PRODUCTION_AUDIT_URL} is absent, and an absent or malformed
 * setting is refused rather than defaulted, so a run either proves the ledger against real PostgreSQL or reports
 * an explicit blocker. Schema creation is only permitted on an isolated schema plainly named for this verifier,
 * so a run can never create or drop anything in a host's own schema.
 */
final class ProductionAuditPostgresTestEnvironment {

    static final String URL_VARIABLE = "CAMPAIGN_PRODUCTION_AUDIT_URL";
    static final String USERNAME_VARIABLE = "CAMPAIGN_PRODUCTION_AUDIT_USERNAME";
    static final String PASSWORD_VARIABLE = "CAMPAIGN_PRODUCTION_AUDIT_PASSWORD";
    static final String SCHEMA_VARIABLE = "CAMPAIGN_PRODUCTION_AUDIT_SCHEMA";

    static final String DEFAULT_SCHEMA = "campaign_production_audit_t35a2_it";

    private static final Pattern ISOLATED_SCHEMA = Pattern.compile("^campaign_production_audit(_[a-z0-9_]+)?$");

    private ProductionAuditPostgresTestEnvironment() {
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

    /** The validated durable audit properties for the configured environment. */
    static ProductionAuditProperties properties() {
        ProductionAuditProperties properties = new ProductionAuditProperties();
        properties.setEnabled(true);
        ProductionAuditProperties.Ledger ledger = properties.getLedger();
        ledger.setUrl(text(URL_VARIABLE));
        ledger.setUsername(text(USERNAME_VARIABLE));
        ledger.setPassword(text(PASSWORD_VARIABLE));
        ledger.setSchema(schema());
        properties.validate();
        return properties;
    }

    /** A bounded pool in the dedicated schema for the configured environment. */
    static HikariDataSource dataSource() {
        ProductionAuditProperties properties = properties();
        ReadinessFlywayLog.registerSecret(properties.getLedger().getPassword());
        ReadinessFlywayLog.registerSecret(properties.getLedger().getUrl());
        return MyBatisTestSupport.dataSource(
                "campaign-audit-it", properties.getLedger().getUrl(), properties.getLedger().getUsername(),
                properties.getLedger().getPassword(), properties.getLedger().getSchema());
    }

    /** A plain connection used only to create and drop this verifier's own isolated schema. */
    static Connection schemaConnection() throws SQLException {
        ProductionAuditProperties properties = properties();
        return java.sql.DriverManager.getConnection(
                properties.getLedger().getUrl(),
                properties.getLedger().getUsername(),
                properties.getLedger().getPassword());
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}
