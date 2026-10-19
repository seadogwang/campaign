package com.company.campaign.builder.campaignagent;

/**
 * Stable machine-readable error codes of the top-level Campaign Agent.
 *
 * <p>They are part of the conversation wire contract: a client branches on them, so an existing code is
 * never renamed or reused for a different meaning. Every code names a bounded condition and never carries a
 * model output, a Tool argument, a Provider fact or an exception message.
 */
public final class CampaignAgentErrorCodes {

    // ---- turn and session boundary ---------------------------------------------------------------

    /** The turn request itself is malformed, empty or over a declared bound. */
    public static final String REQUEST_INVALID = "CAMPAIGN_AGENT_REQUEST_INVALID";

    /** The caller's expected session revision is not the current one; nothing was changed. */
    public static final String STALE_REVISION = "CAMPAIGN_AGENT_STALE_REVISION";

    /** The session has reached a terminal invalid state and cannot accept a further turn. */
    public static final String SESSION_TERMINAL = "CAMPAIGN_AGENT_SESSION_TERMINAL";

    /** The conversation exceeds its bounded number of accepted turns. */
    public static final String TURN_LIMIT_EXCEEDED = "CAMPAIGN_AGENT_TURN_LIMIT_EXCEEDED";

    /** A clarification answer does not match the pending question. */
    public static final String CLARIFICATION_MISMATCH = "CAMPAIGN_AGENT_CLARIFICATION_MISMATCH";

    // ---- bounded routing model boundary ----------------------------------------------------------

    /** The injected routing model port failed or is absent. */
    public static final String MODEL_UNAVAILABLE = "CAMPAIGN_AGENT_MODEL_UNAVAILABLE";

    /** The routing model output was refused by the strict decoder and no repair was permitted. */
    public static final String MODEL_OUTPUT_REJECTED = "CAMPAIGN_AGENT_MODEL_OUTPUT_REJECTED";

    /** The routing model output exceeded the bounded maximum size. */
    public static final String MODEL_OUTPUT_OVERSIZED = "CAMPAIGN_AGENT_MODEL_OUTPUT_OVERSIZED";

    /** The structured requirement extractor rejected the turn; no requirement fact was changed. */
    public static final String REQUIREMENT_EXTRACTION_REJECTED =
            "CAMPAIGN_AGENT_REQUIREMENT_EXTRACTION_REJECTED";

    /** A later turn attempted to change the Campaign type already fixed for this conversation. */
    public static final String CAMPAIGN_TYPE_CONFLICT = "CAMPAIGN_AGENT_TYPE_CONFLICT";

    /** The decoded decision is schema-valid but the host refuses it for this turn and session. */
    public static final String ROUTE_REFUSED = "CAMPAIGN_AGENT_ROUTE_REFUSED";

    /**
     * A component task or component agent named a Skill its own component does not own.
     *
     * <p>It is a structural refusal: the closed ownership declared by {@link CampaignComponentCode} is the only
     * authority for which Skills a component may route, so no caller and no model can widen it.
     */
    public static final String COMPONENT_SKILL_NOT_OWNED = "CAMPAIGN_AGENT_COMPONENT_SKILL_NOT_OWNED";

    // ---- Skill and artifact boundary -------------------------------------------------------------

    /** A Skill runner failed unexpectedly; no raw error text is preserved. */
    public static final String SKILL_FAILED = "CAMPAIGN_AGENT_SKILL_FAILED";

    /** A Skill artifact produced by a runner was refused by the T27 assembly workspace. */
    public static final String ARTIFACT_REFUSED = "CAMPAIGN_AGENT_ARTIFACT_REFUSED";

    /** A Skill of the closed vocabulary has no accepted implementation in this build. */
    public static final String SKILL_UNAVAILABLE = "CAMPAIGN_AGENT_SKILL_UNAVAILABLE";

    /** The Message runner lacks the exact template reference it would need to produce an artifact. */
    public static final String MESSAGE_INPUT_REQUIRED = "CAMPAIGN_AGENT_MESSAGE_INPUT_REQUIRED";

    /** The user named a delivery channel the host catalog does not support. */
    public static final String CHANNEL_UNSUPPORTED = "CAMPAIGN_AGENT_CHANNEL_UNSUPPORTED";

    /** The host business-resource catalog could not be read safely. */
    public static final String RESOURCE_CATALOG_UNAVAILABLE = "CAMPAIGN_AGENT_RESOURCE_CATALOG_UNAVAILABLE";

    /**
     * The host business-resource catalog is wired but has zero selectable resources for the communication
     * channel type, so no channel the user names can be validated. The stated channels are preserved in the
     * brief but the communication component is blocked until configuration is available.
     */
    public static final String COMMUNICATION_CONFIG_UNAVAILABLE =
            "CAMPAIGN_AGENT_COMMUNICATION_CONFIG_UNAVAILABLE";

    /** The Promotion runner lacks the exact reward reference, quantity or ceiling it would need. */
    public static final String PROMOTION_INPUT_REQUIRED = "CAMPAIGN_AGENT_PROMOTION_INPUT_REQUIRED";

    /** The Schedule runner lacks the exact bounded {@code schedule.basic} declaration it would need. */
    public static final String SCHEDULE_INPUT_REQUIRED = "CAMPAIGN_AGENT_SCHEDULE_INPUT_REQUIRED";

    /** The Governance runner lacks the exact bounded {@code governance.limit} declaration it would need. */
    public static final String GOVERNANCE_INPUT_REQUIRED = "CAMPAIGN_AGENT_GOVERNANCE_INPUT_REQUIRED";

    /** The Segment runner has no host-supplied resolved-or-terminal Segment source for this utterance. */
    public static final String SEGMENT_SOURCE_REQUIRED = "CAMPAIGN_AGENT_SEGMENT_SOURCE_REQUIRED";

    /**
     * The Journey runner lacks the complete bounded node/topology declaration it would need.
     *
     * <p>It covers both "nothing usable was described" and "what was described did not form a valid Journey":
     * both are correctable on a later turn, so both ask a typed clarification rather than fabricating a graph.
     */
    public static final String JOURNEY_INPUT_REQUIRED = "CAMPAIGN_AGENT_JOURNEY_INPUT_REQUIRED";

    /** The host explicitly asked to finalize although the session is not ready to build a Proposal. */
    public static final String FINALIZE_NOT_READY = "CAMPAIGN_AGENT_FINALIZE_NOT_READY";

    // ---- component manifest and resource binding -------------------------------------------------

    /** A model-produced manifest named an unknown capability or usage decision. */
    public static final String MANIFEST_DECODING_REJECTED = "CAMPAIGN_AGENT_MANIFEST_DECODING_REJECTED";

    /** A selected resource code does not exist in the host business-resource catalog. */
    public static final String RESOURCE_CODE_INVALID = "CAMPAIGN_AGENT_RESOURCE_CODE_INVALID";

    /** A selected resource code exists but is not selectable (disabled, unavailable, or missing integration). */
    public static final String RESOURCE_NOT_SELECTABLE = "CAMPAIGN_AGENT_RESOURCE_NOT_SELECTABLE";

    /** A REQUIRED resource-binding capability has no bound resources and blocks READY. */
    public static final String RESOURCE_BINDING_INCOMPLETE = "CAMPAIGN_AGENT_RESOURCE_BINDING_INCOMPLETE";

    /** No selectable resource exists for a required capability type in the catalog. */
    public static final String RESOURCE_TYPE_UNAVAILABLE = "CAMPAIGN_AGENT_RESOURCE_TYPE_UNAVAILABLE";

    /** A component usage decision (REQUIRED or NOT_REQUIRED) is still pending from the user. */
    public static final String COMPONENT_DECISION_REQUIRED = "CAMPAIGN_AGENT_COMPONENT_DECISION_REQUIRED";

    // ---- strict routing-decision decoder ---------------------------------------------------------

    /** The routing output is not one well-formed JSON document. */
    public static final String ROUTING_MALFORMED = "CAMPAIGN_ROUTING_MALFORMED";

    /** The routing output carries an object key that is not part of the decision contract. */
    public static final String ROUTING_UNKNOWN_FIELD = "CAMPAIGN_ROUTING_UNKNOWN_FIELD";

    /** The routing output repeats an object key. */
    public static final String ROUTING_DUPLICATE_KEY = "CAMPAIGN_ROUTING_DUPLICATE_KEY";

    /** The routing output carries a field of the wrong JSON shape. */
    public static final String ROUTING_SHAPE_INVALID = "CAMPAIGN_ROUTING_SHAPE_INVALID";

    /** The routing output exceeds a declared count or size bound. */
    public static final String ROUTING_LIMIT_EXCEEDED = "CAMPAIGN_ROUTING_LIMIT_EXCEEDED";

    /** The routing output lists Skills, question codes or options in a non-canonical order. */
    public static final String ROUTING_ORDER_INVALID = "CAMPAIGN_ROUTING_ORDER_INVALID";

    /** The routing output names a Skill identifier that is not server-owned. */
    public static final String ROUTING_UNKNOWN_SKILL = "CAMPAIGN_ROUTING_UNKNOWN_SKILL";

    /** The routing output names a Tool, a Tool argument or a host-only capability. */
    public static final String ROUTING_FORBIDDEN_TOKEN = "CAMPAIGN_ROUTING_FORBIDDEN_TOKEN";

    private CampaignAgentErrorCodes() {
    }
}
