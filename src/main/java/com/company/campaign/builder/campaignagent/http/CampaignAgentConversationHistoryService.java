package com.company.campaign.builder.campaignagent.http;

/**
 * The host-supplied read-only conversation-history service behind the Campaign history route.
 *
 * <p>It is the only dependency of {@link CampaignAgentConversationHistoryController}, and the controller is
 * registered only when a host supplies one. There is no default implementation in this build: the application
 * starts with no history mapping at all rather than pretending a durable enumeration exists.
 *
 * <p>Both operations resolve the trusted context themselves. The list is scoped to the caller's own
 * tenant/program/user boundary and takes only a bounded limit; the read takes the bounded conversation and run
 * identity and can only ever open a conversation of that same boundary. Neither operation mutates anything and
 * neither runs a model or a Tool.
 */
public interface CampaignAgentConversationHistoryService {

    /** The declared bound on one history list request. */
    int MAX_LIST_LIMIT = 100;

    /** The default list bound when the caller supplies none. */
    int DEFAULT_LIST_LIMIT = 25;

    /** Lists the caller's own live conversations, most recently updated first. */
    CampaignAgentConversationHistoryListResponse list(int limit);

    /** Reads one stored conversation projection of the caller's own boundary. */
    CampaignAgentTurnHttpResponse read(String conversationId, String runId);
}