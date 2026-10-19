package com.company.campaign.production.model;

/**
 * The closed set of bounded, non-disclosing failure codes of the production structured-model adapter.
 *
 * <p>Every failure — a rejected configuration, a refused endpoint, a redirect, an authentication failure, a
 * rate limit, a timeout, a transport fault, an oversized or malformed response, a non-text or truncated
 * response and an unsafe output — maps to exactly one of these codes. No code carries a credential, an
 * endpoint, a host name, a model id, a prompt, a schema, a raw model output, a subject, a tenant or a driver
 * message.
 */
public final class ProductionModelErrorCodes {

    /** The opt-in production model configuration is missing or out of bounds. */
    public static final String MODEL_CONFIGURATION_INVALID = "PRODUCTION_MODEL_CONFIGURATION_INVALID";

    /** The outbound request itself is out of contract, so nothing is ever sent. */
    public static final String MODEL_REQUEST_INVALID = "PRODUCTION_MODEL_REQUEST_INVALID";

    /** The bounded prompt exceeds the configured input limit. */
    public static final String MODEL_INPUT_OVERSIZED = "PRODUCTION_MODEL_INPUT_OVERSIZED";

    /** The configured endpoint is not an allowlisted HTTPS endpoint. */
    public static final String MODEL_ENDPOINT_REJECTED = "PRODUCTION_MODEL_ENDPOINT_REJECTED";

    /** The provider answered with a redirect, which is never followed. */
    public static final String MODEL_REDIRECT_REJECTED = "PRODUCTION_MODEL_REDIRECT_REJECTED";

    /** The provider refused the credential. */
    public static final String MODEL_AUTHENTICATION_FAILED = "PRODUCTION_MODEL_AUTHENTICATION_FAILED";

    /** The provider rate-limited the request after every bounded retry. */
    public static final String MODEL_RATE_LIMITED = "PRODUCTION_MODEL_RATE_LIMITED";

    /** The provider was unavailable after every bounded retry. */
    public static final String MODEL_UNAVAILABLE = "PRODUCTION_MODEL_UNAVAILABLE";

    /** The request timed out after every bounded retry. */
    public static final String MODEL_TIMEOUT = "PRODUCTION_MODEL_TIMEOUT";

    /** The transport failed before any accepted response was observed. */
    public static final String MODEL_TRANSPORT_FAILED = "PRODUCTION_MODEL_TRANSPORT_FAILED";

    /** The response body exceeds the configured byte limit. */
    public static final String MODEL_RESPONSE_OVERSIZED = "PRODUCTION_MODEL_RESPONSE_OVERSIZED";

    /** The response body is not a well-formed JSON document. */
    public static final String MODEL_RESPONSE_MALFORMED = "PRODUCTION_MODEL_RESPONSE_MALFORMED";

    /** The response body is not valid UTF-8. */
    public static final String MODEL_RESPONSE_NOT_UTF8 = "PRODUCTION_MODEL_RESPONSE_NOT_UTF8";

    /** The response is not a single plain JSON text completion. */
    public static final String MODEL_RESPONSE_NOT_TEXT = "PRODUCTION_MODEL_RESPONSE_NOT_TEXT";

    /** The response was truncated by the provider's own token bound. */
    public static final String MODEL_RESPONSE_TRUNCATED = "PRODUCTION_MODEL_RESPONSE_TRUNCATED";

    /** The response text contains unsafe control characters. */
    public static final String MODEL_RESPONSE_UNSAFE = "PRODUCTION_MODEL_RESPONSE_UNSAFE";

    /** The response carries no text document at all. */
    public static final String MODEL_RESPONSE_EMPTY = "PRODUCTION_MODEL_RESPONSE_EMPTY";

    /** The published model evidence is incomplete or structurally invalid. */
    public static final String MODEL_EVIDENCE_INVALID = "PRODUCTION_MODEL_EVIDENCE_INVALID";

    private ProductionModelErrorCodes() {
    }
}