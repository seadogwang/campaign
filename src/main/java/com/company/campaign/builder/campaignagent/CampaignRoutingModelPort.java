package com.company.campaign.builder.campaignagent;

import java.util.List;

/**
 * The one host-injected bounded routing model port.
 *
 * <p>It receives an immutable {@link CampaignRoutingRequest} and returns bounded bytes. It is the only place
 * a model is reached, and it has no channel through which it could observe the workspace, a Tool, a
 * capability payload, a permission or a Proposal. Its output is never trusted: it is strictly decoded by
 * {@link CampaignRoutingDecisionDecoder} and then re-judged by the host against the session.
 *
 * <p>{@link #repair} exists so a host may opt into at most one schema-only repair attempt. The default
 * re-invokes the same call, which keeps the port usable as a lambda in a test double; a host that wants a
 * genuinely different repair prompt overrides it.
 */
public interface CampaignRoutingModelPort {

    /** Returns one bounded routing-model output for the request. */
    byte[] route(CampaignRoutingRequest request);

    /**
     * Returns one bounded retry of the same request, which the host may use for a single schema-only repair.
     *
     * <p>The issue codes are bounded machine codes of the decoder; no rejected model text is echoed here.
     */
    default byte[] repair(CampaignRoutingRequest request, List<String> issueCodes) {
        return route(request);
    }
}