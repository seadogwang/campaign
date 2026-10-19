package com.company.campaign.builder.campaignagent.http;

/**
 * One bounded compile failure that maps to exactly one HTTP status via
 * {@link CampaignPlanHttpResponses#statusOf(String)}.
 *
 * <p>The code is fixed and value-free; the message is stable and never echoes
 * a rejected value, a model output or a Provider fact.
 */
public final class CampaignPlanHttpException extends RuntimeException {

    private final String code;

    public CampaignPlanHttpException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CampaignPlanHttpException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}