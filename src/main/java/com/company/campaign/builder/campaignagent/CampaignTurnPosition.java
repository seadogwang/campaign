package com.company.campaign.builder.campaignagent;

/**
 * The host-derived position of one turn inside a conversation.
 *
 * <p>The routing model never decides this: the host knows whether the turn is the first accepted turn,
 * whether a pending clarification is being answered, or whether an accepted result already exists. The
 * position is host evidence, not model output.
 */
public enum CampaignTurnPosition {

    /** The first accepted turn of the session. */
    INITIAL,

    /** A turn that answers the pending typed clarification. */
    ANSWER,

    /** A turn that follows at least one accepted result. */
    FOLLOW_UP;
}