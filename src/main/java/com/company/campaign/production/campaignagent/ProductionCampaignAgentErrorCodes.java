package com.company.campaign.production.campaignagent;

/**
 * Stable machine-readable codes of the default-off production Campaign Agent host.
 *
 * <p>They are startup and boundary codes only: no code carries a credential, a URL, a schema name, a model id,
 * an identity value, a prompt or an output.
 */
public final class ProductionCampaignAgentErrorCodes {

    /** The opt-in production Campaign Agent host was enabled but its required configuration is missing. */
    public static final String CONFIGURATION_INVALID = "PRODUCTION_CAMPAIGN_AGENT_CONFIGURATION_INVALID";

    /** The production identity boundary is not enabled, so no production trusted context can be resolved. */
    public static final String PRODUCTION_SECURITY_REQUIRED =
            "PRODUCTION_CAMPAIGN_AGENT_PRODUCTION_SECURITY_REQUIRED";

    /** No durable Campaign conversation store is wired. */
    public static final String DURABLE_STORE_REQUIRED =
            "PRODUCTION_CAMPAIGN_AGENT_DURABLE_STORE_REQUIRED";

    /** More than one durable Campaign conversation store is wired. */
    public static final String DURABLE_STORE_AMBIGUOUS =
            "PRODUCTION_CAMPAIGN_AGENT_DURABLE_STORE_AMBIGUOUS";

    /**
     * The durable Campaign conversation store cannot serve conversation history, so the left-sidebar
     * enumeration and read route cannot be published without substituting an invented list.
     */
    public static final String HISTORY_STORE_REQUIRED =
            "PRODUCTION_CAMPAIGN_AGENT_HISTORY_STORE_REQUIRED";

    /** No durable Campaign conversation audit sink is wired. */
    public static final String DURABLE_AUDIT_REQUIRED =
            "PRODUCTION_CAMPAIGN_AGENT_DURABLE_AUDIT_REQUIRED";

    /** More than one durable Campaign conversation audit sink is wired. */
    public static final String DURABLE_AUDIT_AMBIGUOUS =
            "PRODUCTION_CAMPAIGN_AGENT_DURABLE_AUDIT_AMBIGUOUS";

    /** No production structured model is wired, or more than one is. */
    public static final String MODEL_REQUIRED = "PRODUCTION_CAMPAIGN_AGENT_MODEL_REQUIRED";

    /** No genuine Segment Preview port is wired, or more than one is. */
    public static final String PREVIEW_REQUIRED = "PRODUCTION_CAMPAIGN_AGENT_PREVIEW_REQUIRED";

    /** No production Campaign asset port is wired, or more than one is. */
    public static final String ASSETS_REQUIRED = "PRODUCTION_CAMPAIGN_AGENT_ASSETS_REQUIRED";

    /** No trusted database-backed Campaign business-resource Tool is wired. */
    public static final String RESOURCE_CATALOG_REQUIRED =
            "PRODUCTION_CAMPAIGN_AGENT_RESOURCE_CATALOG_REQUIRED";

    /** No production Segment asset port is wired, or more than one is. */
    public static final String SEGMENT_ASSETS_REQUIRED = "PRODUCTION_CAMPAIGN_AGENT_SEGMENT_ASSETS_REQUIRED";

    /** The selected production structured model cannot also serve as the Segment intent model. */
    public static final String SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE =
            "PRODUCTION_CAMPAIGN_AGENT_SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE";

    /** The real production Segment source could not run the accepted Segment Agent for this utterance. */
    public static final String SEGMENT_SOURCE_FAILED =
            "PRODUCTION_CAMPAIGN_AGENT_SEGMENT_SOURCE_FAILED";

    /**
     * The real production Segment source ran the accepted Segment Agent, but the artifacts it observed were
     * missing, duplicated, reordered or not the ones the genuine result pins.
     */
    public static final String SEGMENT_EVIDENCE_INCOMPLETE =
            "PRODUCTION_CAMPAIGN_AGENT_SEGMENT_EVIDENCE_INCOMPLETE";

    /** The production Campaign host and the development Campaign host cannot be enabled together. */
    public static final String DEVELOPMENT_HOST_CONFLICT =
            "PRODUCTION_CAMPAIGN_AGENT_DEVELOPMENT_HOST_CONFLICT";

    private ProductionCampaignAgentErrorCodes() {
    }
}
