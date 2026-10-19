package com.company.campaign.readiness.postgres;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration for real PostgreSQL readiness storage.
 *
 * <p>Nothing here has a default that could accidentally enable a database: {@link #isEnabled()} is
 * {@code false} unless the host sets it, and the URL, user and password have no value at all. Values are
 * supplied by the host through explicit configuration such as the relaxed-binding environment variables
 * {@code CAMPAIGN_READINESS_POSTGRES_ENABLED}, {@code CAMPAIGN_READINESS_POSTGRES_URL},
 * {@code CAMPAIGN_READINESS_POSTGRES_USERNAME} and {@code CAMPAIGN_READINESS_POSTGRES_PASSWORD}; no
 * credential, URL or production-like value is committed to this repository.
 *
 * <p>Every bound is validated before a pool is built, and {@link #toString()} redacts the credential and the
 * URL so a stray log line or framework diagnostic cannot disclose either.
 */
@ConfigurationProperties(prefix = "campaign.readiness.postgres")
public class ReadinessPostgresProperties {

    /** The migration assets are the committed pinned set; a host cannot redirect the run elsewhere. */
    public static final String MIGRATION_LOCATIONS = "classpath:db/migration";

    /** The schema pattern a dedicated readiness boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable readiness boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    private static final int MAXIMUM_POOL_SIZE = 16;
    private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;

    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private String schema = "campaign_readiness";
    private int maximumPoolSize = 4;
    private int minimumIdle = 0;
    private int connectionTimeoutMs = 5_000;
    private int validationTimeoutMs = 3_000;
    private int statementTimeoutMs = 5_000;
    private int lockTimeoutMs = 2_000;

    /** True only when the host explicitly enabled PostgreSQL mode. */
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

    /** The dedicated schema the migration set and the readiness store share. */
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

    /**
     * Validates the whole configuration, refusing an enabled mode with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never
     * fails because a host left PostgreSQL off.
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
    }

    /** Uses the canonical datasource when this deprecated prefix has no explicit connection target. */
    public void inheritFrom(CampaignDatasourceProperties datasource) {
        if (!datasource.isEnabled() || (url != null && !url.isBlank())) {
            return;
        }
        url = datasource.getUrl();
        username = datasource.getUsername();
        password = datasource.getPassword();
        schema = datasource.getSchema();
        maximumPoolSize = datasource.getMaximumPoolSize();
        minimumIdle = datasource.getMinimumIdle();
        connectionTimeoutMs = datasource.getConnectionTimeoutMs();
        validationTimeoutMs = datasource.getValidationTimeoutMs();
        statementTimeoutMs = datasource.getStatementTimeoutMs();
        lockTimeoutMs = datasource.getLockTimeoutMs();
    }

    private void requireSchema() {
        if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
            throw invalid("schema");
        }
        if (RESERVED_SCHEMAS.contains(schema)) {
            throw invalid("schema");
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

    private static ReadinessPostgresConfigurationException invalid(String field) {
        return new ReadinessPostgresConfigurationException(
                ReadinessPostgresErrorCodes.READINESS_POSTGRESQL_CONFIGURATION_INVALID,
                "The opt-in PostgreSQL readiness configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: neither the credential nor the JDBC URL is printed. */
    @Override
    public String toString() {
        return "ReadinessPostgresProperties[enabled=" + enabled
                + ", schema=" + schema
                + ", url=<redacted>"
                + ", username=<redacted>"
                + ", password=<redacted>"
                + ", maximumPoolSize=" + maximumPoolSize
                + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", validationTimeoutMs=" + validationTimeoutMs
                + ", statementTimeoutMs=" + statementTimeoutMs
                + ", lockTimeoutMs=" + lockTimeoutMs
                + ']';
    }
}
