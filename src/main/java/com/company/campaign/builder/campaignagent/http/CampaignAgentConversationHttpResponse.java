package com.company.campaign.builder.campaignagent.http;

/**
 * The bounded wire response of one Campaign conversation creation.
 *
 * <p>It carries exactly the server-issued conversation and run identity plus the fresh revision zero. It
 * carries no campaign, tenant, program, user, permission, trusted fact, request value or model byte: a caller
 * receives only what it must name on its next turn, and it never receives the trusted boundary the identity was
 * issued against.
 */
public record CampaignAgentConversationHttpResponse(
        String conversationId,
        String runId,
        long revision
) {
}