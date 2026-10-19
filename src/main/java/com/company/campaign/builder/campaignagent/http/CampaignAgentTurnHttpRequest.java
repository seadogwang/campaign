package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded wire request of one Campaign conversation turn.
 *
 * <p>It carries the conversation and run identity, the caller's expected session revision, one bounded user
 * message and the optional typed clarification answers. It carries no campaign identity, no base revision, no
 * permission, no Proposal and no lifecycle action: a client cannot bind a trusted fact into the turn.
 */
public record CampaignAgentTurnHttpRequest(
        String conversationId,
        String runId,
        long expectedRevision,
        String message,
        List<CampaignClarificationAnswer> answers,
        String at
) {

    public CampaignAgentTurnHttpRequest {
        answers = answers == null ? List.of() : List.copyOf(new ArrayList<>(answers));
    }
}