package com.company.campaign.builder.campaignagent.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The one bounded success/error envelope of the Campaign conversation route.
 *
 * <p>Exactly one of {@code data} and {@code error} is present. A refused turn is still a {@code success}
 * response carrying a bounded {@code data} projection whose {@code accepted} flag is false: the turn contract
 * answered the request, and a client must not lose the transcript because a turn was refused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignAgentTurnEnvelope(
        boolean success,
        CampaignAgentTurnHttpResponse data,
        ErrorBody error,
        String requestId
) {

    /** The bounded error body; it carries closed codes only and never a value or a cause. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    /** One bounded success response. */
    public static CampaignAgentTurnEnvelope success(CampaignAgentTurnHttpResponse data, String requestId) {
        return new CampaignAgentTurnEnvelope(true, data, null, requestId);
    }

    /** One bounded failure response. */
    public static CampaignAgentTurnEnvelope failure(String code, String message, String requestId) {
        return new CampaignAgentTurnEnvelope(false, null, new ErrorBody(code, message, Map.of()), requestId);
    }
}