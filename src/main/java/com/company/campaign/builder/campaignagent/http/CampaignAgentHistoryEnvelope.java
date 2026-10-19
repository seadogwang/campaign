package com.company.campaign.builder.campaignagent.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The one bounded success/error envelope of the Campaign conversation-history routes.
 *
 * <p>It is the same strict envelope shape the create and turn routes document: exactly one of {@code data} and
 * {@code error} is present, and the error body carries closed codes only, never a value or a cause. The success
 * data is either one {@link CampaignAgentConversationHistoryListResponse} or one
 * {@link CampaignAgentTurnHttpResponse} projection, so a history read reuses the exact turn wire contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignAgentHistoryEnvelope(
        boolean success,
        Object data,
        ErrorBody error,
        String requestId
) {

    /** The bounded error body; it carries closed codes only and never a value or a cause. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    /** One bounded success response. */
    public static CampaignAgentHistoryEnvelope success(Object data, String requestId) {
        return new CampaignAgentHistoryEnvelope(true, data, null, requestId);
    }

    /** One bounded failure response. */
    public static CampaignAgentHistoryEnvelope failure(String code, String message, String requestId) {
        return new CampaignAgentHistoryEnvelope(false, null, new ErrorBody(code, message, Map.of()), requestId);
    }
}