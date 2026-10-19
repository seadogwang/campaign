package com.company.campaign.dev.preview.postgres;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the development PostgreSQL Preview integration.
 *
 * <p>Nothing here has a default that could accidentally enable a database: {@code enabled} is false, the URL,
 * user, password, host schema, sample key and storage-catalog resource have no value at all, and the numeric
 * bounds default to plainly bounded development values. Values are supplied by the host through explicit
 * configuration or the equivalent relaxed-binding environment variables; no credential, URL, key or
 * production-like value is committed to this repository.
 *
 * <p>Every bound is validated before a pool is built, the URL must be a genuine PostgreSQL JDBC URL, and
 * {@link #toString()} redacts the credential, the URL, the user and the sample key so a stray log line or
 * framework diagnostic cannot disclose any of them.
 */
@ConfigurationProperties(prefix = "campaign.agent.development.preview.postgres")
public class PreviewPostgresProperties {

    /** The dedicated-boundary pattern a host schema must satisfy before it can be a Preview target. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** The only JDBC URL prefix this integration accepts. */
    public static final String POSTGRESQL_URL_PREFIX = "jdbc:postgresql:";

    /** The smallest server-owned sample key, in UTF-8 bytes; a shorter key would weaken enumeration resistance. */
    public static final int MINIMUM_SAMPLE_KEY_BYTES = 32;

    private static final int MAXIMUM_POOL_SIZE = 16;
    private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;

    private boolean enabled;
    private String url;
    private String username;
    private String password;
    private String schema;
    private String sampleKey;
    private String storageCatalogResource;
    private String backendVersion = "1";
    private int maximumPoolSize = 4;
    private int minimumIdle = 0;
    private int connectionTimeoutMs = 5_000;
    private int validationTimeoutMs = 3_000;
    private int statementTimeoutMs = 10_000;
    private int lockTimeoutMs = 2_000;
    private int idleTransactionTimeoutMs = 10_000;
    private int queryTimeoutSeconds = 10;
    private int fetchSize = 500;
    private int maxRows = 100_000;
    private long maxDataWatermarkAgeSeconds = 86_400L;
    private String requiredDatabaseProduct = "PostgreSQL";
    private int minimumDatabaseMajorVersion = 12;

    /** True only when the host explicitly enabled the PostgreSQL Preview integration. */
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

    /** The existing allowlisted host schema the Preview statements read; it is never created or migrated. */
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    /** The host-owned server-side sample-HMAC key; it is never committed and never printed. */
    public String getSampleKey() {
        return sampleKey;
    }

    public void setSampleKey(String sampleKey) {
        this.sampleKey = sampleKey;
    }

    /**
     * The optional classpath resource of the server-owned storage catalog, or {@code null} to use the explicit
     * development physical mapping of the frozen Core Loyalty profile.
     */
    public String getStorageCatalogResource() {
        return storageCatalogResource;
    }

    public void setStorageCatalogResource(String storageCatalogResource) {
        this.storageCatalogResource = storageCatalogResource;
    }

    /** The server-owned backend version discriminator bound into every published result. */
    public String getBackendVersion() {
        return backendVersion;
    }

    public void setBackendVersion(String backendVersion) {
        this.backendVersion = backendVersion;
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

    public int getIdleTransactionTimeoutMs() {
        return idleTransactionTimeoutMs;
    }

    public void setIdleTransactionTimeoutMs(int idleTransactionTimeoutMs) {
        this.idleTransactionTimeoutMs = idleTransactionTimeoutMs;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public long getMaxDataWatermarkAgeSeconds() {
        return maxDataWatermarkAgeSeconds;
    }

    public void setMaxDataWatermarkAgeSeconds(long maxDataWatermarkAgeSeconds) {
        this.maxDataWatermarkAgeSeconds = maxDataWatermarkAgeSeconds;
    }

    public String getRequiredDatabaseProduct() {
        return requiredDatabaseProduct;
    }

    public void setRequiredDatabaseProduct(String requiredDatabaseProduct) {
        this.requiredDatabaseProduct = requiredDatabaseProduct;
    }

    public int getMinimumDatabaseMajorVersion() {
        return minimumDatabaseMajorVersion;
    }

    public void setMinimumDatabaseMajorVersion(int minimumDatabaseMajorVersion) {
        this.minimumDatabaseMajorVersion = minimumDatabaseMajorVersion;
    }

    /** The exact execution policy this configuration declares, through its own bounded construction. */
    public PreviewPostgresExecutionPolicy executionPolicy() {
        return new PreviewPostgresExecutionPolicy(
                queryTimeoutSeconds, fetchSize, maxRows, maxDataWatermarkAgeSeconds,
                requiredDatabaseProduct, minimumDatabaseMajorVersion);
    }

    /**
     * Validates the whole configuration, refusing enablement with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the Preview database off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        requireText(url, "url");
        if (!url.startsWith(POSTGRESQL_URL_PREFIX)) {
            // A non-PostgreSQL URL is refused before any driver sees it; the rejected value is never echoed.
            throw invalid("url");
        }
        requireText(username, "username");
        requireText(password, "password");
        requireText(sampleKey, "sampleKey");
        if (sampleKey.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SAMPLE_KEY_BYTES) {
            throw invalid("sampleKey");
        }
        requireText(backendVersion, "backendVersion");
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
        requireRange(idleTransactionTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "idleTransactionTimeoutMs");
        requireRange(queryTimeoutSeconds, 1, PreviewPostgresExecutionPolicy.MAX_QUERY_TIMEOUT_SECONDS,
                "queryTimeoutSeconds");
        requireRange(fetchSize, 1, PreviewPostgresExecutionPolicy.MAX_FETCH_SIZE, "fetchSize");
        requireRange(maxRows, 1, PreviewPostgresExecutionPolicy.MAX_ROWS_BOUND, "maxRows");
        if (maxDataWatermarkAgeSeconds < 1
                || maxDataWatermarkAgeSeconds > PreviewPostgresExecutionPolicy.MAX_DATA_WATERMARK_AGE_SECONDS) {
            throw invalid("maxDataWatermarkAgeSeconds");
        }
        // The policy's own construction re-proves the product name and the minimum major version.
        executionPolicy();
    }

    private void requireSchema() {
        if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
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

    private static PreviewPostgresConfigurationException invalid(String field) {
        return new PreviewPostgresConfigurationException(
                PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CONFIGURATION_INVALID,
                "The opt-in PostgreSQL Preview configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: neither the credential, the URL, the user nor the sample key is printed. */
    @Override
    public String toString() {
        return "PreviewPostgresProperties[enabled=" + enabled
                + ", schema=" + schema
                + ", url=<redacted>"
                + ", username=<redacted>"
                + ", password=<redacted>"
                + ", sampleKey=<redacted>"
                + ", storageCatalogResource=" + storageCatalogResource
                + ", backendVersion=" + backendVersion
                + ", maximumPoolSize=" + maximumPoolSize
                + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", statementTimeoutMs=" + statementTimeoutMs
                + ", lockTimeoutMs=" + lockTimeoutMs
                + ", idleTransactionTimeoutMs=" + idleTransactionTimeoutMs
                + ", queryTimeoutSeconds=" + queryTimeoutSeconds
                + ", fetchSize=" + fetchSize
                + ", maxRows=" + maxRows
                + ", maxDataWatermarkAgeSeconds=" + maxDataWatermarkAgeSeconds
                + ']';
    }
}