package com.company.campaign.production.campaignagent;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-supplied opt-in configuration of the default-off production Campaign Agent host.
 *
 * <p>Nothing here has a default that could accidentally enable the host: {@link #isEnabled()} is {@code false}
 * unless the host sets {@code campaign.production.campaign-agent.enabled=true}. No credential, URL or schema
 * name is committed: the durable conversation store it must publish is supplied through the nested {@link Store}
 * configuration or by a host-supplied durable store bean.
 *
 * <p>Only bounded, non-secret operational limits live here: the registry capacity, the per-conversation
 * idempotency bound, the retention window and whether one schema-only repair is permitted. Every bound is
 * validated before a bean is created, and {@link #toString()} prints no value that could be a secret.
 */
@ConfigurationProperties(prefix = "campaign.production.campaign-agent")
public class ProductionCampaignAgentProperties {

    /** The largest number of conversations the durable store keeps. */
    public static final int MAX_CONVERSATIONS = 1_024;

    /** The largest number of stored idempotency outcomes per conversation. */
    public static final int MAX_OUTCOMES_PER_CONVERSATION = 256;

    /** The committed Campaign conversation migration set; a host cannot redirect the run. */
    public static final String MIGRATION_LOCATIONS = "classpath:db/campaign-conversation-migration";

    /** The schema pattern a dedicated production Campaign conversation boundary must satisfy. */
    public static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,40}$");

    /** Schema names that are never an acceptable production Campaign conversation boundary. */
    public static final Set<String> RESERVED_SCHEMAS = Set.of(
            "public", "postgres", "template0", "template1", "pg_catalog", "information_schema", "pg_toast");

    private boolean enabled = false;
    private int maxConversations = 64;
    private int maxOutcomesPerConversation = 64;
    private Duration retention = Duration.ofHours(2);
    private boolean schemaRepairEnabled = false;
    private final Store store = new Store();

    /** True only when the host explicitly opted into the production Campaign Agent host. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConversations() {
        return maxConversations;
    }

    public void setMaxConversations(int maxConversations) {
        this.maxConversations = maxConversations;
    }

    public int getMaxOutcomesPerConversation() {
        return maxOutcomesPerConversation;
    }

    public void setMaxOutcomesPerConversation(int maxOutcomesPerConversation) {
        this.maxOutcomesPerConversation = maxOutcomesPerConversation;
    }

    /** The inactivity/retention window after which an untouched conversation is evicted. */
    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    /** Whether the host permits one schema-only repair of a rejected routing output. */
    public boolean isSchemaRepairEnabled() {
        return schemaRepairEnabled;
    }

    public void setSchemaRepairEnabled(boolean schemaRepairEnabled) {
        this.schemaRepairEnabled = schemaRepairEnabled;
    }

    /** The dedicated durable conversation store settings. */
    public Store getStore() {
        return store;
    }

    /** Uses the canonical datasource when the deprecated store connection fields are absent. */
    public void inheritDatasourceFrom(CampaignDatasourceProperties datasource) {
        if (!enabled || !datasource.isEnabled() || (store.url != null && !store.url.isBlank())) {
            return;
        }
        store.url = datasource.getUrl();
        store.username = datasource.getUsername();
        store.password = datasource.getPassword();
        store.schema = datasource.getSchema();
        store.maximumPoolSize = datasource.getMaximumPoolSize();
        store.minimumIdle = datasource.getMinimumIdle();
        store.connectionTimeoutMs = datasource.getConnectionTimeoutMs();
        store.validationTimeoutMs = datasource.getValidationTimeoutMs();
        store.statementTimeoutMs = datasource.getStatementTimeoutMs();
        store.lockTimeoutMs = datasource.getLockTimeoutMs();
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the production host off.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (maxConversations < 1 || maxConversations > MAX_CONVERSATIONS) {
            throw invalid("maxConversations");
        }
        if (maxOutcomesPerConversation < 1 || maxOutcomesPerConversation > MAX_OUTCOMES_PER_CONVERSATION) {
            throw invalid("maxOutcomesPerConversation");
        }
        if (retention == null || retention.isZero() || retention.isNegative()
                || retention.compareTo(Duration.ofDays(7)) > 0) {
            throw invalid("retention");
        }
    }

    private static ProductionCampaignAgentConfigurationException invalid(String field) {
        return new ProductionCampaignAgentConfigurationException(
                ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The opt-in production Campaign Agent configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: the bounds only, never a value that could be a secret. */
    @Override
    public String toString() {
        return "ProductionCampaignAgentProperties[enabled=" + enabled
                + ", maxConversations=" + maxConversations
                + ", maxOutcomesPerConversation=" + maxOutcomesPerConversation
                + ", retention=" + retention
                + ", schemaRepairEnabled=" + schemaRepairEnabled
                + ", store=" + store + ']';
    }

    /**
     * The host-supplied PostgreSQL settings of the durable production Campaign conversation store.
     *
     * <p>Nothing here has a default that could accidentally connect: the URL, the user and the password have no
     * value at all, and the dedicated schema defaults to a plainly non-reserved name.
     */
    public static class Store {

        private static final int MAXIMUM_POOL_SIZE = 16;
        private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;

        private String url;
        private String username;
        private String password;
        private String schema = "campaign_production_conversation";
        private int maximumPoolSize = 4;
        private int minimumIdle = 0;
        private int connectionTimeoutMs = 5_000;
        private int validationTimeoutMs = 3_000;
        private int statementTimeoutMs = 5_000;
        private int lockTimeoutMs = 2_000;

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

        /** Validates every database setting; a missing or out-of-bounds value fails the start. */
        public void validate() {
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

        private void requireSchema() {
            if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()
                    || RESERVED_SCHEMAS.contains(schema)) {
                throw invalid("store.schema");
            }
        }

        private void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw invalid("store." + field);
            }
        }

        private void requireRange(int value, int minimum, int maximum, String field) {
            if (value < minimum || value > maximum) {
                throw invalid("store." + field);
            }
        }

        private static ProductionCampaignAgentConfigurationException invalid(String field) {
            return new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                    "The opt-in production Campaign Agent configuration is missing or out of bounds: " + field);
        }

        /** Redacted diagnostics: neither the credential nor the JDBC URL is printed. */
        @Override
        public String toString() {
            return "Store[url=<redacted>"
                    + ", username=<redacted>"
                    + ", password=<redacted>"
                    + ", schema=" + schema
                    + ", maximumPoolSize=" + maximumPoolSize
                    + ", connectionTimeoutMs=" + connectionTimeoutMs
                    + ", statementTimeoutMs=" + statementTimeoutMs
                    + ", lockTimeoutMs=" + lockTimeoutMs + ']';
        }
    }
}
