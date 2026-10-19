package com.company.campaign.builder.campaignagent.http;

/**
 * Stable machine-readable codes of the Campaign Plan HTTP adapter.
 *
 * <p>Every code maps to exactly one HTTP status, and every message is fixed and value-free.
 * A compiler failure is reported as a stable bounded code, never as a raw exception or
 * a framework default body.
 */
public final class CampaignPlanHttpErrorCodes {

    /** The request body is not one well-formed JSON document. */
    public static final String REQUEST_MALFORMED = "CAMPAIGN_PLAN_HTTP_REQUEST_MALFORMED";

    /** The request body exceeds its declared byte bound. */
    public static final String REQUEST_LIMIT_EXCEEDED = "CAMPAIGN_PLAN_HTTP_REQUEST_LIMIT_EXCEEDED";

    /** The request body is not a valid CampaignProposalPatch. */
    public static final String REQUEST_INVALID = "CAMPAIGN_PLAN_HTTP_REQUEST_INVALID";

    /** The transport did not hand the controller a readable body. */
    public static final String REQUEST_UNREADABLE = "CAMPAIGN_PLAN_HTTP_REQUEST_UNREADABLE";

    /** The request used a method other than POST. */
    public static final String METHOD_NOT_ALLOWED = "CAMPAIGN_PLAN_HTTP_METHOD_NOT_ALLOWED";

    /** The request used a media type other than application/json. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "CAMPAIGN_PLAN_HTTP_UNSUPPORTED_MEDIA_TYPE";

    /** The compile could not be served at all; no cause or value is echoed. */
    public static final String INTERNAL_FAILURE = "CAMPAIGN_PLAN_HTTP_INTERNAL_FAILURE";

    /** Every mutating request requires a bounded Idempotency-Key header. */
    public static final String IDEMPOTENCY_KEY_REQUIRED = "CAMPAIGN_PLAN_HTTP_IDEMPOTENCY_KEY_REQUIRED";

    /** The supplied Idempotency-Key is malformed or over its declared bound. */
    public static final String IDEMPOTENCY_KEY_INVALID = "CAMPAIGN_PLAN_HTTP_IDEMPOTENCY_KEY_INVALID";

    /** No trusted identity could be resolved for the call. */
    public static final String UNAUTHENTICATED = "CAMPAIGN_PLAN_HTTP_UNAUTHENTICATED";

    /** The resolved identity lacks an active membership or a required permission. */
    public static final String FORBIDDEN = "CAMPAIGN_PLAN_HTTP_FORBIDDEN";

    /** The plan does not exist at this trusted boundary, or the caller may not see that it does. */
    public static final String NOT_FOUND = "CAMPAIGN_PLAN_HTTP_NOT_FOUND";

    /** An idempotency key was reused with a different request. */
    public static final String CONFLICT = "CAMPAIGN_PLAN_HTTP_CONFLICT";

    /** The proposal's base revisions do not match the frozen catalog or policy. */
    public static final String COMPILER_BASE_REVISIONS_STALE = "CAMPAIGN_PLAN_HTTP_COMPILER_BASE_REVISIONS_STALE";

    /** The compiler refused the proposal: the target campaign is unavailable. */
    public static final String COMPILER_TARGET_UNAVAILABLE = "CAMPAIGN_PLAN_HTTP_COMPILER_TARGET_UNAVAILABLE";

    /** The compiler refused the proposal: authorization was denied. */
    public static final String COMPILER_AUTHORIZATION_DENIED = "CAMPAIGN_PLAN_HTTP_COMPILER_AUTHORIZATION_DENIED";

    /** The compiler refused the proposal: a capability contract was invalid. */
    public static final String COMPILER_CONTRACT_INVALID = "CAMPAIGN_PLAN_HTTP_COMPILER_CONTRACT_INVALID";

    /** The proposal is not a CREATE and there is no existing campaign to EDIT. */
    public static final String COMPILER_TARGET_NOT_FOUND = "CAMPAIGN_PLAN_HTTP_COMPILER_TARGET_NOT_FOUND";

    /** The plan store rejected the plan. */
    public static final String PLAN_STORE_REJECTED = "CAMPAIGN_PLAN_HTTP_PLAN_STORE_REJECTED";

    /** The compile is not available in this host. */
    public static final String SERVICE_UNAVAILABLE = "CAMPAIGN_PLAN_HTTP_SERVICE_UNAVAILABLE";

    private CampaignPlanHttpErrorCodes() {
    }
}