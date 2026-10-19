package com.company.campaign.dev.campaignagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The host-supplied opt-in configuration of the local Campaign Agent development host.
 *
 * <p>Nothing here has a default that could accidentally enable the host: {@link #isEnabled()} is {@code false}
 * unless the host sets {@code campaign.agent.development.enabled=true}. No token, base URL, model, identity,
 * tenant, program, DataScope, snapshot or time pin is committed here — the development model settings are read
 * through the narrow shared {@link com.company.campaign.dev.segment.DevelopmentModelConfiguration} boundary, and
 * the fixed development identity is supplied by a clearly named development provider.
 *
 * <p>Only bounded, non-secret operational limits live here: the registry capacity, the per-conversation
 * idempotency bound, the retention window and whether one schema-only repair is permitted. Every bound is
 * validated before a bean is created, and {@link #toString()} prints no value that could be a secret.
 */
@ConfigurationProperties(prefix = "campaign.agent.development")
public class DevelopmentCampaignAgentProperties {

    /** The largest number of conversations the in-memory registry keeps. */
    public static final int MAX_CONVERSATIONS = 1_024;

    /** The largest number of stored idempotency outcomes per conversation. */
    public static final int MAX_OUTCOMES_PER_CONVERSATION = 256;

    private boolean enabled = false;
    private int maxConversations = 64;
    private int maxOutcomesPerConversation = 64;
    private Duration retention = Duration.ofHours(2);
    private boolean schemaRepairEnabled = false;
    private Transcript transcript = new Transcript();

    /** True only when the host explicitly opted into the development host. */
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

    /**
     * The development-only conversation transcript, disabled by default and requiring an explicit directory.
     *
     * <p>It is the only place the raw model responses and the submitted user conversation of a development
     * session are durably recorded. It exists solely inside this opt-in development host, must never be enabled
     * in production, and never weakens the accepted production audit redaction contract.
     */
    public Transcript getTranscript() {
        return transcript;
    }

    public void setTranscript(Transcript transcript) {
        this.transcript = transcript == null ? new Transcript() : transcript;
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds limit.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the development host off — except that the transcript can never be enabled on its own:
     * a transcript without its development host would be a way to record a real session from outside the
     * development-only boundary, so it fails closed instead.
     */
    public void validate() {
        if (transcript != null && transcript.isEnabled()) {
            if (!enabled) {
                throw invalid("transcript requires campaign.agent.development.enabled=true");
            }
            String directory = transcript.getDirectory();
            if (directory == null || directory.isBlank()
                    || directory.length() > Transcript.MAX_DIRECTORY_LENGTH) {
                throw invalid("transcript.directory");
            }
        }
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

    private static DevelopmentCampaignAgentConfigurationException invalid(String field) {
        return new DevelopmentCampaignAgentConfigurationException(
                DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The opt-in Campaign Agent development configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: the bounds only, never a value that could be a secret. */
    @Override
    public String toString() {
        return "DevelopmentCampaignAgentProperties[enabled=" + enabled
                + ", maxConversations=" + maxConversations
                + ", maxOutcomesPerConversation=" + maxOutcomesPerConversation
                + ", retention=" + retention
                + ", schemaRepairEnabled=" + schemaRepairEnabled
                + ", transcriptEnabled=" + (transcript != null && transcript.isEnabled()) + ']';
    }

    /**
     * The explicit, default-off development transcript settings.
     *
     * <p>They bind to {@code campaign.agent.development.transcript.*}, which relaxed binding maps from
     * {@code CAMPAIGN_AGENT_DEVELOPMENT_TRANSCRIPT_ENABLED} and
     * {@code CAMPAIGN_AGENT_DEVELOPMENT_TRANSCRIPT_DIRECTORY}. No directory value is ever committed to this
     * repository, and {@link #toString()} prints neither the directory nor any other host value.
     *
     * <p><strong>Privacy.</strong> Enabling the transcript writes the user conversation and the raw model
     * responses of a development session to a durable file. That file is sensitive development evidence only: it
     * must never be enabled in production, never be shipped to an audit ledger, and never be treated as
     * production audit behavior.
     */
    public static final class Transcript {

        /** The bounded length of a configured transcript directory path. */
        public static final int MAX_DIRECTORY_LENGTH = 1_024;

        private boolean enabled = false;
        private String directory;

        /** True only when the host explicitly opted into the development transcript. */
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** The host-named directory the per-conversation JSON Lines files are created in. */
        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        /** Redacted diagnostics: the enablement only, never the host directory. */
        @Override
        public String toString() {
            return "Transcript[enabled=" + enabled + ']';
        }
    }
}