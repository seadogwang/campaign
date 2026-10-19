package com.company.campaign.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the unified campaign business datasource.
 *
 * <p>Nothing here has a default that could accidentally enable a database: {@link #isEnabled()} is {@code false}
 * unless the host sets {@code campaign.datasource.enabled=true}, and the URL, user and password have no value at
 * all. Values are supplied by the host through explicit configuration or the equivalent relaxed-binding
 * environment variables; no credential, URL or production-like value is committed to this repository.
 *
 * <p>Every bound is validated before a pool is built, and {@link #toString()} redacts the credential and the
 * URL so a stray log line or framework diagnostic cannot disclose either.
 */
@ConfigurationProperties(prefix = "campaign.datasource")
public class CampaignDatasourceProperties {

    /**
     * The one committed unified business migration location.
     *
     * <p>The unified location carries every business slice — readiness, conversation, production membership and
     * production audit — re-numbered into one globally unique, expand-only, idempotent version sequence, so a
     * single Flyway runtime can never meet two migrations that declare the same version. Readiness keeps its
     * accepted {@code V1}-{@code V3} numbers (the bounded storage probe pins those exact versions), while the
     * other slices carry higher unique versions. A host cannot redirect the run.
     */
    public static final String MIGRATION_LOCATIONS = "classpath:db/campaign-unified-migration";

    /**
     * The dedicated Flyway history table of the unified business migration run.
     *
     * <p>The unified runner owns its own history table instead of the default {@code flyway_schema_history}, so
     * it never validates or migrates against a legacy readiness, conversation, membership or audit history that
     * a host may already carry in the same schema. A legacy history therefore needs no repair and no clean.
     */
    public static final String FLYWAY_HISTORY_TABLE = "flyway_campaign_unified_schema_history";

    /** The schema pattern a dedicated business boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable business boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    /** The prefix for all bean names in this unified configuration. */
    public static final String BEAN_PREFIX = "campaignBusiness";

    /** The one bounded unified business pool. */
    public static final String DATASOURCE_BEAN_NAME = "campaignBusinessDataSource";

    /** The one MyBatis-Plus SQL session factory over the business pool. */
    public static final String SQL_SESSION_FACTORY_BEAN_NAME = "campaignBusinessSqlSessionFactory";

    /** The one primary transaction manager over the business pool. */
    public static final String TRANSACTION_MANAGER_BEAN_NAME = "campaignBusinessTransactionManager";

    /** The base package scanned for MyBatis mapper interfaces. */
    public static final String MAPPER_SCAN_BASE_PACKAGE = "com.company.campaign.persistence.mapper";

    private static final int MAXIMUM_POOL_SIZE = 16;
    private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;

    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private String schema = "campaign";
    private int maximumPoolSize = 8;
    private int minimumIdle = 0;
    private int connectionTimeoutMs = 5_000;
    private int validationTimeoutMs = 3_000;
    private int statementTimeoutMs = 10_000;
    private int lockTimeoutMs = 5_000;
    private int idleTransactionTimeoutMs = 30_000;

    /** True only when the host explicitly enabled the unified business datasource. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** The dedicated schema the business migration set and the mapper pool share. */
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getValidationTimeoutMs() {
        return validationTimeoutMs;
    }

    public void setValidationTimeoutMs(int validationTimeoutMs) {
        this.validationTimeoutMs = validationTimeoutMs;
    }

    public int getStatementTimeoutMs() {
        return statementTimeoutMs;
    }

    public void setStatementTimeoutMs(int statementTimeoutMs) {
        this.statementTimeoutMs = statementTimeoutMs;
    }

    public int getLockTimeoutMs() {
        return lockTimeoutMs;
    }

    public void setLockTimeoutMs(int lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
    }

    /** The idle-in-transaction timeout for the audit ledger's session. */
    public int getIdleTransactionTimeoutMs() {
        return idleTransactionTimeoutMs;
    }

    public void setIdleTransactionTimeoutMs(int idleTransactionTimeoutMs) {
        this.idleTransactionTimeoutMs = idleTransactionTimeoutMs;
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never
     * fails because a host left the unified business datasource off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        requireText(url, "url");
        requireText(username, "username");
        requireText(password, "password");
        requireSchema();
        requireRange(maximumPoolSize, 1, MAXIMUM_POOL_SIZE, "maximumPoolSize");
        requireRange(minimumIdle, 0, maximumPoolSize, "minimumIdle");
        requireRange(connectionTimeoutMs, 250, 30_000, "connectionTimeoutMs");
        requireRange(validationTimeoutMs, 250, 10_000, "validationTimeoutMs");
        if (validationTimeoutMs > connectionTimeoutMs) {
            throw invalid("validationTimeoutMs");
        }
        requireRange(statementTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "statementTimeoutMs");
        requireRange(lockTimeoutMs, 100, 10_000, "lockTimeoutMs");
        requireRange(idleTransactionTimeoutMs, 1_000, MAXIMUM_TIMEOUT_MILLIS, "idleTransactionTimeoutMs");
    }

    /**
     * Validates that legacy property values do not conflict with the unified business datasource.
     *
     * <p>When the unified datasource is enabled, this method checks that none of the supplied legacy values
     * is both non-blank and different from the corresponding unified value. A non-blank legacy value that
     * matches the unified value is silently accepted as consistent. A legacy value that is blank is ignored,
     * because the host is not using that legacy property at all.
     *
     * <p>This is called by the configuration class before any pool is built, so a legacy conflict fails
     * fast and with one bounded code.
     *
     * @param legacyUrl      the legacy JDBC URL, or {@code null} if not configured
     * @param legacyUsername the legacy username, or {@code null} if not configured
     * @param legacyPassword the legacy password, or {@code null} if not configured
     * @param legacySchema   the legacy schema, or {@code null} if not configured
     * @throws CampaignDatasourceConfigurationException if any non-blank legacy value conflicts
     */
    public void validateForLegacy(String legacyUrl, String legacyUsername,
                                   String legacyPassword, String legacySchema) {
        if (!enabled) {
            return;
        }
        assertNoConflict(legacyUrl, url, "url");
        assertNoConflict(legacyUsername, username, "username");
        assertNoConflict(legacyPassword, password, "password");
        assertNoConflict(legacySchema, schema, "schema");
    }

    private void assertNoConflict(String legacyValue, String unifiedValue, String field) {
        if (legacyValue == null || legacyValue.isBlank()) {
            return;
        }
        if (!legacyValue.equals(unifiedValue)) {
            throw new CampaignDatasourceConfigurationException(
                    CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_LEGACY_CONFLICT,
                    "The legacy " + field
                            + " conflicts with the unified business datasource " + field
                            + "; only one source must be set.");
        }
    }

    private void requireSchema() {
        if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
            throw new CampaignDatasourceConfigurationException(
                    CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_SCHEMA_INVALID,
                    "The unified business datasource schema is not a valid schema name.");
        }
        if (RESERVED_SCHEMAS.contains(schema)) {
            throw new CampaignDatasourceConfigurationException(
                    CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_SCHEMA_INVALID,
                    "The unified business datasource schema is reserved and is not an acceptable business boundary.");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field);
        }
    }

    private void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw invalid(field);
        }
    }

    private static CampaignDatasourceConfigurationException invalid(String field) {
        return new CampaignDatasourceConfigurationException(
                CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_CONFIGURATION_INVALID,
                "The opt-in unified business datasource configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: neither the credential nor the JDBC URL is printed. */
    @Override
    public String toString() {
        return "CampaignDatasourceProperties[enabled=" + enabled
                + ", schema=" + schema
                + ", url=<redacted>"
                + ", username=<redacted>"
                + ", password=<redacted>"
                + ", maximumPoolSize=" + maximumPoolSize
                + ", minimumIdle=" + minimumIdle
                + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", validationTimeoutMs=" + validationTimeoutMs
                + ", statementTimeoutMs=" + statementTimeoutMs
                + ", lockTimeoutMs=" + lockTimeoutMs
                + ", idleTransactionTimeoutMs=" + idleTransactionTimeoutMs
                + ']';
    }
}