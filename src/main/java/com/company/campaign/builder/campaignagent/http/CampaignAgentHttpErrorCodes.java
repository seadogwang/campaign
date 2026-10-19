package com.company.campaign.builder.campaignagent.http;

/**
 * Stable machine-readable codes of the Campaign conversation HTTP adapter.
 *
 * <p>The request-side codes are HTTP transport facts. A turn-level refusal is reported inside the success
 * envelope as the conversation disposition, never as a transport failure: a refused turn is a valid answer to
 * a valid request.
 */
public final class CampaignAgentHttpErrorCodes {

    /** The request body is not one well-formed JSON document. */
    public static final String REQUEST_MALFORMED = "CAMPAIGN_AGENT_HTTP_REQUEST_MALFORMED";

    /** The request body repeats an object key. */
    public static final String REQUEST_DUPLICATE_KEY = "CAMPAIGN_AGENT_HTTP_DUPLICATE_KEY";

    /** The request body carries a field that is not part of the turn contract. */
    public static final String REQUEST_UNKNOWN_FIELD = "CAMPAIGN_AGENT_HTTP_UNKNOWN_FIELD";

    /** The request body carries a field of the wrong JSON shape or value. */
    public static final String REQUEST_INVALID = "CAMPAIGN_AGENT_HTTP_REQUEST_INVALID";

    /** The request body exceeds its declared bound. */
    public static final String REQUEST_LIMIT_EXCEEDED = "CAMPAIGN_AGENT_HTTP_REQUEST_LIMIT_EXCEEDED";

    /** The transport did not hand the controller a readable body. */
    public static final String REQUEST_UNREADABLE = "CAMPAIGN_AGENT_HTTP_REQUEST_UNREADABLE";

    /** The request used a method other than POST. */
    public static final String METHOD_NOT_ALLOWED = "CAMPAIGN_AGENT_HTTP_METHOD_NOT_ALLOWED";

    /** The request used a media type other than application/json. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "CAMPAIGN_AGENT_HTTP_UNSUPPORTED_MEDIA_TYPE";

    /** The turn could not be served at all; no cause or value is echoed. */
    public static final String INTERNAL_FAILURE = "CAMPAIGN_AGENT_HTTP_INTERNAL_FAILURE";

    /** Every mutating turn requires a bounded Idempotency-Key header. */
    public static final String IDEMPOTENCY_KEY_REQUIRED = "CAMPAIGN_AGENT_HTTP_IDEMPOTENCY_KEY_REQUIRED";

    /** The supplied Idempotency-Key is malformed or over its declared bound. */
    public static final String IDEMPOTENCY_KEY_INVALID = "CAMPAIGN_AGENT_HTTP_IDEMPOTENCY_KEY_INVALID";

    /** No trusted identity could be resolved for the call. */
    public static final String UNAUTHENTICATED = "CAMPAIGN_AGENT_HTTP_UNAUTHENTICATED";

    /** The resolved identity lacks an active membership or a required permission. */
    public static final String FORBIDDEN = "CAMPAIGN_AGENT_HTTP_FORBIDDEN";

    /** The conversation does not exist at this trusted boundary, or the caller may not see that it does. */
    public static final String NOT_FOUND = "CAMPAIGN_AGENT_HTTP_NOT_FOUND";

    /** An idempotency key was reused with a different request. */
    public static final String CONFLICT = "CAMPAIGN_AGENT_HTTP_CONFLICT";

    /**
     * The host could not issue a distinct conversation identity within its explicit bounded retry policy, so
     * the create failed closed instead of reusing an existing conversation.
     */
    public static final String CREATE_IDENTITY_EXHAUSTED = "CAMPAIGN_AGENT_HTTP_CREATE_IDENTITY_EXHAUSTED";

    /** The development host is enabled but its bounded model/registry boundary is unavailable. */
    public static final String SERVICE_UNAVAILABLE = "CAMPAIGN_AGENT_HTTP_SERVICE_UNAVAILABLE";

    private CampaignAgentHttpErrorCodes() {
    }
}