package com.company.campaign.production.provider;

import java.util.Set;

/**
 * The closed set of bounded, non-disclosing failure codes of the production Provider adapters.
 *
 * <p>Every failure — a rejected configuration, a refused endpoint, a redirect, an authentication refusal, a
 * rate limit, a timeout, a transport fault, an oversized, empty, malformed or non-UTF-8 body, an unexpected
 * content type, an unsafe value and a contract violation — maps to exactly one of these codes. No code carries
 * a credential, an endpoint, a host name, a tenant, a recipient reference, a routing pin, a Provider reference,
 * a request payload or a raw error text.
 *
 * <p>Every code is also a legal {@code ActionProviderException} machine code, so a runtime transport failure can
 * be thrown with the accepted exception type and reach the Action machine as a bounded reason code without ever
 * disclosing a Provider value.
 */
public final class ProductionProviderErrorCodes {

    /** The opt-in production Provider configuration is missing or out of bounds. */
    public static final String PROVIDER_CONFIGURATION_INVALID = "PRODUCTION_PROVIDER_CONFIGURATION_INVALID";

    /** The outbound request is out of contract, so nothing is ever sent. */
    public static final String PROVIDER_REQUEST_INVALID = "PRODUCTION_PROVIDER_REQUEST_INVALID";

    /** The idempotency key header and the body key disagree, so nothing is ever sent. */
    public static final String PROVIDER_KEY_MISMATCH = "PRODUCTION_PROVIDER_KEY_MISMATCH";

    /** The configured endpoint is not an allowlisted HTTPS endpoint. */
    public static final String PROVIDER_ENDPOINT_REJECTED = "PRODUCTION_PROVIDER_ENDPOINT_REJECTED";

    /** The Provider answered with a redirect, which is never followed. */
    public static final String PROVIDER_REDIRECT_REJECTED = "PRODUCTION_PROVIDER_REDIRECT_REJECTED";

    /** The Provider refused the configured credential. */
    public static final String PROVIDER_AUTHENTICATION_FAILED = "PRODUCTION_PROVIDER_AUTHENTICATION_FAILED";

    /** The Provider rate-limited the request. */
    public static final String PROVIDER_RATE_LIMITED = "PRODUCTION_PROVIDER_RATE_LIMITED";

    /** The Provider was unavailable. */
    public static final String PROVIDER_UNAVAILABLE = "PRODUCTION_PROVIDER_UNAVAILABLE";

    /** The request timed out. */
    public static final String PROVIDER_TIMEOUT = "PRODUCTION_PROVIDER_TIMEOUT";

    /** The transport failed before any accepted response was observed. */
    public static final String PROVIDER_TRANSPORT_FAILED = "PRODUCTION_PROVIDER_TRANSPORT_FAILED";

    /** The Provider answered with an HTTP status outside the closed accepted status mapping. */
    public static final String PROVIDER_PROTOCOL_FAILED = "PRODUCTION_PROVIDER_PROTOCOL_FAILED";

    /** The response body exceeds the configured byte bound. */
    public static final String PROVIDER_RESPONSE_OVERSIZED = "PRODUCTION_PROVIDER_RESPONSE_OVERSIZED";

    /** The response body is not a well-formed JSON document, or carries a duplicate field. */
    public static final String PROVIDER_RESPONSE_MALFORMED = "PRODUCTION_PROVIDER_RESPONSE_MALFORMED";

    /** The response body is not valid UTF-8. */
    public static final String PROVIDER_RESPONSE_NOT_UTF8 = "PRODUCTION_PROVIDER_RESPONSE_NOT_UTF8";

    /** The response body is empty. */
    public static final String PROVIDER_RESPONSE_EMPTY = "PRODUCTION_PROVIDER_RESPONSE_EMPTY";

    /** The response carries no single plain JSON content type, or is a stream, which is never accepted. */
    public static final String PROVIDER_RESPONSE_CONTENT_TYPE_INVALID =
            "PRODUCTION_PROVIDER_RESPONSE_CONTENT_TYPE_INVALID";

    /** The response carries an unsafe control character. */
    public static final String PROVIDER_RESPONSE_UNSAFE = "PRODUCTION_PROVIDER_RESPONSE_UNSAFE";

    /** The response is not exactly the strict wire contract: an unknown field, version, kind, key, outcome, instant, reference or reason. */
    public static final String PROVIDER_RESPONSE_CONTRACT_INVALID =
            "PRODUCTION_PROVIDER_RESPONSE_CONTRACT_INVALID";

    /** The published production Provider evidence is incomplete or structurally invalid. */
    public static final String PROVIDER_EVIDENCE_INVALID = "PRODUCTION_PROVIDER_EVIDENCE_INVALID";

    private static final Set<String> RETRYABLE = Set.of(
            PROVIDER_TIMEOUT, PROVIDER_TRANSPORT_FAILED, PROVIDER_UNAVAILABLE, PROVIDER_RATE_LIMITED);

    private ProductionProviderErrorCodes() {
    }

    /** True when the code is one an already-decided query retry may legitimately repeat. */
    public static boolean retryable(String code) {
        return code != null && RETRYABLE.contains(code);
    }
}
