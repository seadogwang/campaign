package com.company.campaign.builder.campaignagent.http;

/**
 * The host-supplied turn service behind the Campaign conversation route.
 *
 * <p>It is the only dependency of {@link CampaignAgentTurnController}, and the controller is registered only
 * when a host supplies one. There is no default implementation in this build: the application starts with no
 * Campaign conversation mapping at all rather than pretending that a production routing model provider, a
 * Segment Agent or a durable conversation store exists.
 *
 * <p>The idempotency key is passed through so a host may make a repeated turn safe; it is never part of the
 * turn's meaning and is never rendered.
 */
public interface CampaignAgentTurnService {

    /** Serves one bounded turn over one conversation. */
    CampaignAgentTurnHttpResponse turn(CampaignAgentTurnHttpRequest request, String idempotencyKey);
}