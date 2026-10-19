package com.company.campaign.conversation;

import com.company.campaign.builder.campaignagent.CampaignAgentTurnResult;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * One stored idempotent outcome of a Campaign conversation turn.
 *
 * <p>It pairs the canonical fingerprint of the exact request that produced the turn with the bounded turn
 * result it produced. Replaying the same idempotency key with the same fingerprint returns this stored result
 * without another model or Tool call, so a retried delivery cannot create a second effect. A different request
 * under the same key is refused as a bounded conflict instead of overwriting this one.
 */
public record CampaignStoredOutcome(ContentHash requestFingerprint, CampaignAgentTurnResult result) {

    public CampaignStoredOutcome {
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(result, "result");
    }

    /** A bounded diagnostic: the fingerprint and the disposition only, never a value. */
    @Override
    public String toString() {
        return "CampaignStoredOutcome[fingerprint=" + requestFingerprint
                + ", accepted=" + result.accepted() + ']';
    }
}