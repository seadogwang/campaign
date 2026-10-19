package com.company.campaign.production.audit;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the default-off production durable audit ledger.
 *
 * <p>Nothing here has a default that could accidentally enable durable audit or reach a database:
 * {@link #isEnabled()} is {@code false} unless the host sets {@code campaign.production.audit.enabled=true},
 * and the connection string, the username, the credential and the schema boundary have no committed value at
 * all. The ledger is inert unless <em>both</em> {@code campaign.production.enabled=true} and
 * {@code campaign.production.audit.enabled=true}, so the accepted T35A1 production identity boundary can be
 * enabled on its own without creating a pool, a migration run or an appended row.
 *
 * <p>Every bound is validated before a pool, a migration run or a writer is created. {@link #toString()}
 * redacts the JDBC URL, the username and the credential so a stray log line or framework diagnostic cannot
 * disclose any of them.
 */
@ConfigurationProperties(prefix = "campaign.production.audit")
public class ProductionAuditProperties {

    /** The schema pattern a dedicated durable audit boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable durable audit boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    /** The committed expand-only migration location; a host cannot redirect the run. */
    public static final String MIGRATION_LOCATIONS = "classpath:db/campaign-production-audit-migration";

    private boolean enabled = false;

    private final Ledger ledger = new Ledger();

    /** True only when the host explicitly opted into the durable audit ledger. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Ledger getLedger() {
        return ledger;
    }

    /** Uses the canonical datasource when the deprecated ledger connection fields are absent. */
    public void inheritDatasourceFrom(CampaignDatasourceProperties datasource) {
        if (!enabled || !datasource.isEnabled() || (ledger.url != null && !ledger.url.isBlank())) {
            return;
        }
        ledger.url = datasource.getUrl();
        ledger.username = datasource.getUsername();
        ledger.password = datasource.getPassword();
        ledger.schema = datasource.getSchema();
        ledger.maximumPoolSize = datasource.getMaximumPoolSize();
        ledger.minimumIdle = datasource.getMinimumIdle();
        ledger.connectionTimeoutMs = datasource.getConnectionTimeoutMs();
        ledger.validationTimeoutMs = datasource.getValidationTimeoutMs();
        ledger.statementTimeoutMs = datasource.getStatementTimeoutMs();
        ledger.lockTimeoutMs = datasource.getLockTimeoutMs();
        ledger.idleTransactionTimeoutMs = datasource.getIdleTransactionTimeoutMs();
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left durable audit off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        ledger.validate();
    }

    /** The dedicated PostgreSQL pool and writer bounds of the durable audit ledger. */
    public static class Ledger {

        private static final int MAXIMUM_POOL_SIZE = 16;
        private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;
        private static final int MAXIMUM_BATCH_RECORDS = 256;
        private static final int MAXIMUM_SOURCE_DOCUMENT_CHARS = 8_192;

        private String url;
        private String username;
        private String password;
        private String schema = "campaign_production_audit";
        private int maximumPoolSize = 4;
        private int minimumIdle = 0;
        private int connectionTimeoutMs = 5_000;
        private int validationTimeoutMs = 3_000;
        private int statementTimeoutMs = 5_000;
        private int lockTimeoutMs = 2_000;
        private int idleTransactionTimeoutMs = 10_000;
        private int queryTimeoutMs = 3_000;
        private int maxBatchRecords = 64;
        private int maxSourceDocumentChars = MAXIMUM_SOURCE_DOCUMENT_CHARS;

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

        /** The dedicated schema the committed durable audit migration set and the ledger share. */
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

        public int getIdleTransactionTimeoutMs() {
            return idleTransactionTimeoutMs;
        }

        public void setIdleTransactionTimeoutMs(int idleTransactionTimeoutMs) {
            this.idleTransactionTimeoutMs = idleTransactionTimeoutMs;
        }

        /** The JDBC statement query timeout, in milliseconds. */
        public int getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        public void setQueryTimeoutMs(int queryTimeoutMs) {
            this.queryTimeoutMs = queryTimeoutMs;
        }

        /** The maximum number of entries one batch may carry before it is refused before any insert. */
        public int getMaxBatchRecords() {
            return maxBatchRecords;
        }

        public void setMaxBatchRecords(int maxBatchRecords) {
            this.maxBatchRecords = maxBatchRecords;
        }

        /** The maximum canonical source document length, in characters. */
        public int getMaxSourceDocumentChars() {
            return maxSourceDocumentChars;
        }

        public void setMaxSourceDocumentChars(int maxSourceDocumentChars) {
            this.maxSourceDocumentChars = maxSourceDocumentChars;
        }

        void validate() {
            requireText(url, "ledger.url");
            requireText(username, "ledger.username");
            requireText(password, "ledger.password");
            requireSchema();
            requireRange(maximumPoolSize, 1, MAXIMUM_POOL_SIZE, "ledger.maximumPoolSize");
            requireRange(minimumIdle, 0, maximumPoolSize, "ledger.minimumIdle");
            requireRange(connectionTimeoutMs, 250, 30_000, "ledger.connectionTimeoutMs");
            requireRange(validationTimeoutMs, 250, 10_000, "ledger.validationTimeoutMs");
            if (validationTimeoutMs > connectionTimeoutMs) {
                throw invalid("ledger.validationTimeoutMs");
            }
            requireRange(statementTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "ledger.statementTimeoutMs");
            requireRange(lockTimeoutMs, 100, 10_000, "ledger.lockTimeoutMs");
            requireRange(idleTransactionTimeoutMs, 1_000, MAXIMUM_TIMEOUT_MILLIS, "ledger.idleTransactionTimeoutMs");
            requireRange(queryTimeoutMs, 100, MAXIMUM_TIMEOUT_MILLIS, "ledger.queryTimeoutMs");
            requireRange(maxBatchRecords, 1, MAXIMUM_BATCH_RECORDS, "ledger.maxBatchRecords");
            requireRange(maxSourceDocumentChars, 256, MAXIMUM_SOURCE_DOCUMENT_CHARS, "ledger.maxSourceDocumentChars");
        }

        private void requireSchema() {
            if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
                throw invalid("ledger.schema");
            }
            if (RESERVED_SCHEMAS.contains(schema)) {
                throw new ProductionAuditConfigurationException(
                        ProductionAuditErrorCodes.PRODUCTION_AUDIT_SCHEMA_RESERVED,
                        "The durable audit schema is reserved and is not an acceptable audit boundary.");
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

        private static ProductionAuditConfigurationException invalid(String field) {
            return new ProductionAuditConfigurationException(
                    ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID,
                    "The opt-in durable audit configuration is missing or out of bounds: " + field);
        }

        /** Redacted diagnostics: neither the credential, the JDBC URL nor the username is printed. */
        @Override
        public String toString() {
            return "Ledger[url=<redacted>"
                    + ", username=<redacted>"
                    + ", password=<redacted>"
                    + ", schema=" + schema
                    + ", maximumPoolSize=" + maximumPoolSize
                    + ", connectionTimeoutMs=" + connectionTimeoutMs
                    + ", queryTimeoutMs=" + queryTimeoutMs
                    + ", maxBatchRecords=" + maxBatchRecords
                    + ", maxSourceDocumentChars=" + maxSourceDocumentChars + ']';
        }
    }

    /** Redacted diagnostics: no credential, JDBC URL or username is printed. */
    @Override
    public String toString() {
        return "ProductionAuditProperties[enabled=" + enabled + ", ledger=" + ledger + ']';
    }
}
