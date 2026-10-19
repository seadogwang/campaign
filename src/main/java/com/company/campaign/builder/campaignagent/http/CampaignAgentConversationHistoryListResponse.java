package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.conversation.CampaignConversationSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded wire list of a Campaign conversation history.
 *
 * <p>It carries only the accepted identity, revision, session flag, title, turn count and stored instants of
 * each live conversation of the caller's own trusted boundary. It carries no transcript, no model output, no
 * Tool artifact and no Proposal payload: opening one conversation is a separate, explicitly bounded read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignAgentConversationHistoryListResponse(List<Conversation> conversations) {

    public CampaignAgentConversationHistoryListResponse {
        conversations = conversations == null ? List.of() : List.copyOf(new ArrayList<>(conversations));
    }

    /** One bounded history entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conversation(
            String conversationId,
            String runId,
            long revision,
            boolean hasSession,
            String title,
            int turnCount,
            String createdAt,
            String updatedAt) {
    }

    /** Projects the accepted domain summaries into their wire form. */
    public static CampaignAgentConversationHistoryListResponse of(List<CampaignConversationSummary> summaries) {
        List<Conversation> conversations = new ArrayList<>();
        for (CampaignConversationSummary summary : summaries) {
            conversations.add(new Conversation(
                    summary.conversationId(),
                    summary.runId(),
                    summary.revision(),
                    summary.hasSession(),
                    summary.title(),
                    summary.turnCount(),
                    summary.createdAt().toString(),
                    summary.updatedAt().toString()));
        }
        return new CampaignAgentConversationHistoryListResponse(conversations);
    }
}