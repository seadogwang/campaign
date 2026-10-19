package com.company.campaign.dev.campaignagent;

/**
 * Stable machine-readable codes of the opt-in local Campaign Agent development host.
 *
 * <p>They are startup and boundary codes only: no code carries a credential, a URL, a model name, an identity
 * value, a prompt or an output.
 */
public final class DevelopmentCampaignAgentErrorCodes {

    /** The opt-in development host was enabled but its required configuration is missing or out of bounds. */
    public static final String CONFIGURATION_INVALID = "CAMPAIGN_AGENT_DEVELOPMENT_CONFIGURATION_INVALID";

    /** The shared development model settings could not be read, so the development host must not start. */
    public static final String MODEL_CONFIGURATION_UNAVAILABLE =
            "CAMPAIGN_AGENT_DEVELOPMENT_MODEL_CONFIGURATION_UNAVAILABLE";

    /** A Skill input resolution produced an out-of-contract value and failed closed. */
    public static final String SKILL_INPUT_REJECTED = "CAMPAIGN_AGENT_DEVELOPMENT_SKILL_INPUT_REJECTED";

    /**
     * The real development Segment source ran the accepted Segment Agent, but the artifacts it observed were
     * missing, duplicated, reordered or not the ones the genuine result pins.
     */
    public static final String SEGMENT_EVIDENCE_INCOMPLETE =
            "CAMPAIGN_AGENT_DEVELOPMENT_SEGMENT_EVIDENCE_INCOMPLETE";

    /** The real development Segment source could not run the accepted Segment Agent for this utterance. */
    public static final String SEGMENT_SOURCE_FAILED =
            "CAMPAIGN_AGENT_DEVELOPMENT_SEGMENT_SOURCE_FAILED";

    /** The selected structured transport cannot also serve as the Segment intent model. */
    public static final String SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE =
            "CAMPAIGN_AGENT_DEVELOPMENT_SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE";

    /**
     * A conversation identifier could not be used as a bounded, contained development-transcript file name, so
     * the transcript refused it instead of building a path that could leave the configured directory.
     */
    public static final String TRANSCRIPT_PATH_UNSAFE = "CAMPAIGN_AGENT_DEVELOPMENT_TRANSCRIPT_PATH_UNSAFE";

    private DevelopmentCampaignAgentErrorCodes() {
    }
}