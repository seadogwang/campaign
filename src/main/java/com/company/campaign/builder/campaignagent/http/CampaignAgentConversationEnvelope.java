package com.company.campaign.builder.campaignagent.http;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The one bounded success/error envelope of the Campaign conversation-create route.
 *
 * <p>It is the same strict envelope shape the turn route documents: exactly one of {@code data} and
 * {@code error} is present, and the error body carries closed codes only, never a value or a cause. A create
 * always answers with a success envelope when it issued or replayed an identity; only a malformed,
 * unauthenticated, unauthorized or over-limit request is a transport failure.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignAgentConversationEnvelope(
        boolean success,
        CampaignAgentConversationHttpResponse data,
        ErrorBody error,
        String requestId
) {

    /** The bounded error body; it carries closed codes only and never a value or a cause. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    /** One bounded success response. */
    public static CampaignAgentConversationEnvelope success(
            CampaignAgentConversationHttpResponse data, String requestId) {
        return new CampaignAgentConversationEnvelope(true, data, null, requestId);
    }

    /** One bounded failure response. */
    public static CampaignAgentConversationEnvelope failure(String code, String message, String requestId) {
        return new CampaignAgentConversationEnvelope(false, null, new ErrorBody(code, message, Map.of()), requestId);
    }
}