package com.company.campaign.conversation.postgres;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the durable Campaign conversation store.
 *
 * <p>It lives under the local Agent configuration because that is where the store is selected, and nothing here
 * has a default that could accidentally enable a database: {@link Mode#IN_MEMORY} is the default, the URL, user
 * and password have no value at all, and the dedicated schema defaults to a plainly non-reserved name. Values
 * are supplied by the host through explicit configuration or the equivalent relaxed-binding environment
 * variables; no credential, URL or production-like value is committed to this repository.
 *
 * <p>Every bound is validated before a pool is built, and {@link #toString()} redacts the credential and the URL
 * so a stray log line or framework diagnostic cannot disclose either.
 */
@ConfigurationProperties(prefix = "campaign.agent.development.store")
public class ConversationPostgresProperties {

    /** The migration assets are the committed Campaign conversation set; a host cannot redirect the run. */
    public static final String MIGRATION_LOCATIONS = "classpath:db/campaign-conversation-migration";

    /** The schema pattern a dedicated Campaign conversation boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable Campaign conversation boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    private static final int MAXIMUM_POOL_SIZE = 16;
    private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;

    /** The two, and only two, store modes the local Agent host may select. */
    public enum Mode {

        /** The development in-memory registry; the default, and the only mode with no database at all. */
        IN_MEMORY,

        /** The durable PostgreSQL store, which requires the complete database settings below. */
        POSTGRESQL;
    }

    private Mode mode = Mode.IN_MEMORY;
    private String url;
    private String username;
    private String password;
    private String schema = "campaign_conversation";
    private int maximumPoolSize = 4;
    private int minimumIdle = 0;
    private int connectionTimeoutMs = 5_000;
    private int validationTimeoutMs = 3_000;
    private int statementTimeoutMs = 5_000;
    private int lockTimeoutMs = 2_000;

    /** The selected store mode; {@link Mode#IN_MEMORY} unless the host explicitly selected PostgreSQL. */
    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** True only when the host explicitly selected the durable PostgreSQL store. */
    public boolean isPostgresql() {
        return mode == Mode.POSTGRESQL;
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

    /** The dedicated schema the Campaign conversation migration set and the store share. */
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
     * Validates the whole configuration, refusing PostgreSQL mode with a missing or out-of-bounds setting.
     *
     * <p>In-memory mode is always valid and validates nothing, so the normal application never fails because a
     * host left the durable store off.
     */
    public void validate() {
        if (!isPostgresql()) {
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

    /** Uses the canonical datasource when no legacy connection settings were supplied. */
    public void inheritFrom(CampaignDatasourceProperties datasource) {
        if (!isPostgresql() || !datasource.isEnabled() || (url != null && !url.isBlank())) {
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

    private static ConversationPostgresConfigurationException invalid(String field) {
        return new ConversationPostgresConfigurationException(
                ConversationPostgresErrorCodes.CONVERSATION_POSTGRESQL_CONFIGURATION_INVALID,
                "The opt-in PostgreSQL Campaign conversation configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: neither the credential nor the JDBC URL is printed. */
    @Override
    public String toString() {
        return "ConversationPostgresProperties[mode=" + mode
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
