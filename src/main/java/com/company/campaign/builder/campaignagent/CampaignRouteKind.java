package com.company.campaign.builder.campaignagent;

/**
 * The closed Campaign routing vocabulary the bounded routing model may select from.
 *
 * <p>A route kind says only what the user's immediate goal is. It can never name a Tool, a capability
 * payload, a Proposal operation, a lifecycle action or a Provider call: those are host-owned stages the
 * model has no vocabulary for.
 */
public enum CampaignRouteKind {

    /** The user describes or extends a Campaign. */
    CAMPAIGN_REQUEST,

    /** The immediate goal is audience selection or preview. */
    AUDIENCE_REQUEST,

    /** The user adds another Campaign requirement after a prior result. */
    CONTINUE_CAMPAIGN,

    /** The user explicitly asks to finish and build the Proposal. */
    FINALIZE_CAMPAIGN,

    /** The goal or the required Skill cannot yet be selected safely. */
    NEEDS_CLARIFICATION,

    /** No Campaign Builder action is supported for this request. */
    OUT_OF_SCOPE;

    /** The exact enum name, matched case-sensitively; no alias or case normalization is accepted. */
    public static CampaignRouteKind fromWireName(String name) {
        if (name == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "routeKind", "A route kind is required.");
        }
        for (CampaignRouteKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        throw CampaignAgentException.invalid(
                CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "routeKind", "The route kind is not server-owned.");
    }

    /** True when this route kind asks the Skills it names to run. */
    public boolean selectsSkills() {
        return this == CAMPAIGN_REQUEST || this == AUDIENCE_REQUEST || this == CONTINUE_CAMPAIGN;
    }
}