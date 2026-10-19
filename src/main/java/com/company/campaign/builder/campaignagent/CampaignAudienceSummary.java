package com.company.campaign.builder.campaignagent;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded audience result card of one accepted Segment artifact.
 *
 * <p>It carries safe semantics only: the accepted preview status, the matched count, the trusted {@code asOf}
 * instant, timezone and data watermark that say how fresh the count is, and the accepted preview hash that
 * pins it. It carries no member identifier, no hashed sample, no condition statistic value, no DSL, no SQL and
 * no Metadata label: showing an audience is not disclosing one.
 */
public record CampaignAudienceSummary(
        String status,
        int matchedCount,
        String asOf,
        String timezone,
        String dataWatermark,
        ContentHash previewHash
) {

    public CampaignAudienceSummary {
        if (status == null || asOf == null || timezone == null || dataWatermark == null || previewHash == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "audience",
                    "An audience summary requires its safe facts and pins.");
        }
        if (matchedCount < 0) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "audience",
                    "An audience summary count must not be negative.");
        }
    }

    /** A bounded diagnostic: the status, the count and the freshness instant, never an identity. */
    @Override
    public String toString() {
        return "CampaignAudienceSummary[status=" + status
                + ", matchedCount=" + matchedCount
                + ", asOf=" + asOf + ']';
    }
}