package com.company.campaign.builder.campaignagent;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded, non-secret Journey summary one accepted Journey contribution reports for conversation
 * projection.
 *
 * <p>It carries the node count, the edge count, the entry component identity, the terminal count and the two
 * integrity hashes the accepted validation produced. It carries no node payload, no template body, no reward
 * data or secret, no member fact, no Provider response, no credential and no SQL: the entry identity is already
 * an identity the component projection exposes, and the hashes are integrity facts a reader may compare but
 * never submit.
 */
public record CampaignJourneySummary(
        int nodeCount,
        int edgeCount,
        String entryComponentId,
        int terminalCount,
        ContentHash topologyHash,
        ContentHash artifactHash) {

    public CampaignJourneySummary {
        if (nodeCount < 1 || edgeCount < 0 || terminalCount < 1
                || entryComponentId == null || topologyHash == null || artifactHash == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "journeySummary",
                    "A Journey summary requires its bounded counts, entry identity and integrity hashes.");
        }
    }

    /** A bounded diagnostic: the counts and hashes only, never a declaration value. */
    @Override
    public String toString() {
        return "CampaignJourneySummary[nodes=" + nodeCount
                + ", edges=" + edgeCount
                + ", terminals=" + terminalCount
                + ", topologyHash=" + topologyHash + ']';
    }
}