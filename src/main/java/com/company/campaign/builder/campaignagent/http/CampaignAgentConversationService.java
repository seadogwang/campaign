package com.company.campaign.builder.campaignagent.http;

/**
 * The host-supplied conversation-create service behind the Campaign conversation route.
 *
 * <p>It is the only dependency of {@link CampaignAgentConversationController}, and the controller is registered
 * only when a host supplies one. There is no default implementation in this build: the application starts with
 * no conversation-create mapping at all rather than pretending a durable conversation store or a server-owned
 * identity source exists.
 *
 * <p>The create request carries no value at all — the host resolves the trusted context itself and issues the
 * identity, so the only input the boundary forwards is the idempotency key. It is never part of the create's
 * meaning and is never rendered.
 */
public interface CampaignAgentConversationService {

    /**
     * Creates one server-issued conversation identity, or replays the identity an earlier create with the same
     * idempotency key already reserved.
     */
    CampaignAgentConversationHttpResponse create(String idempotencyKey);
}