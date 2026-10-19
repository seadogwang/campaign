package com.company.campaign.builder.campaignagent.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The one bounded success/error envelope of the Campaign Plan route.
 *
 * <p>Exactly one of {@code data} and {@code error} is present. Every response body is this
 * envelope: there is no Spring default timestamp/path body, and no raw exception value is ever
 * echoed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignPlanHttpEnvelope(
        boolean success,
        CampaignPlanSummaryResponse data,
        ErrorBody error,
        String requestId
) {

    /** The bounded error body; it carries closed codes only and never a value or a cause. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    /** One bounded success response. */
    public static CampaignPlanHttpEnvelope success(CampaignPlanSummaryResponse data, String requestId) {
        return new CampaignPlanHttpEnvelope(true, data, null, requestId);
    }

    /** One bounded failure response. */
    public static CampaignPlanHttpEnvelope failure(String code, String message, String requestId) {
        return new CampaignPlanHttpEnvelope(false, null, new ErrorBody(code, message, Map.of()), requestId);
    }
}