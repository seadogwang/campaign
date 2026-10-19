package com.company.campaign.builder.campaignagent.http;

/**
 * The bounded service port of {@code POST /api/v1/campaign-plans}.
 *
 * <p>It accepts the raw body bytes and the explicit {@code Idempotency-Key} header, and
 * returns the compiled plan projection wrapped in the bounded response. The caller owns
 * the HTTP transport; this port owns only the compile and the plan store.
 *
 * <p>Every failure — a malformed body, a revoked authorization, a stale base revision,
 * a rejected capability contract, a store conflict — is reported as a
 * {@link CampaignPlanHttpException} whose code maps to exactly one HTTP status.
 */
public interface CampaignPlanCompileService {

    /**
     * Compiles one proposal into a Campaign Plan and stores it.
     *
     * @param body the raw UTF-8 JSON body (a {@code CampaignProposalPatch})
     * @param idempotencyKey the explicit Idempotency-Key header, or null
     * @return the bounded plan summary ready for the HTTP success envelope
     * @throws CampaignPlanHttpException on any bounded failure
     */
    CampaignPlanSummaryResponse compile(byte[] body, String idempotencyKey);
}