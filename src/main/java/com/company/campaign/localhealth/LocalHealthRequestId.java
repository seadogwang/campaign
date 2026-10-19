package com.company.campaign.localhealth;

import com.company.campaign.shared.canonical.ContentHash;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Deterministic, non-sensitive request identifier for the local health endpoint.
 *
 * <p>It is a truncated digest of the request method and target, so repeated identical requests produce the
 * same identifier and no randomness, wall clock or sequence is read. The target is hashed rather than echoed,
 * so an unusual query string cannot inject text, and the identifier is never used as an execution fact.
 */
public final class LocalHealthRequestId {

    private static final String PREFIX = "req-";
    private static final int DIGEST_CHARACTERS = 32;
    private static final String UNAVAILABLE = "req-unavailable";

    private LocalHealthRequestId() {
    }

    /** The deterministic identifier of one request target. */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return UNAVAILABLE;
        }
        return of(request.getMethod(), request.getRequestURI(), request.getQueryString());
    }

    /** The deterministic identifier of one method and target. */
    public static String of(String method, String requestUri, String queryString) {
        String material = nullSafe(method) + "\n" + nullSafe(requestUri) + "\n" + nullSafe(queryString);
        String hex = ContentHash.sha256(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)).value();
        int start = "sha256:".length();
        return PREFIX + hex.substring(start, start + DIGEST_CHARACTERS);
    }

    /** The fixed identifier used when the transport made the request unavailable. */
    public static String unavailable() {
        return UNAVAILABLE;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}