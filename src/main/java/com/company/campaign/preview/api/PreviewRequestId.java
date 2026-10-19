package com.company.campaign.preview.api;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * Deterministic, non-sensitive HTTP request identifier for the Preview endpoint.
 *
 * <p>The identifier is derived from the exact request bytes, so repeated identical requests produce the same
 * identifier and no randomness, wall clock or sequence is ever read. It is a truncated digest of the request
 * body — an opaque value that reveals nothing about the payload — and it is never used as an execution fact.
 */
public final class PreviewRequestId {

    private static final String PREFIX = "req-";
    private static final int DIGEST_CHARACTERS = 32;
    private static final String UNAVAILABLE = "req-unavailable";

    private PreviewRequestId() {
    }

    /** The deterministic identifier of one exact request body. */
    public static String from(byte[] requestBytes) {
        byte[] bytes = requestBytes == null ? new byte[0] : requestBytes;
        String hex = ContentHash.sha256(bytes).value();
        int start = "sha256:".length();
        return PREFIX + hex.substring(start, start + DIGEST_CHARACTERS);
    }

    /** The fixed identifier used when the transport made the exact request bytes unavailable. */
    public static String unavailable() {
        return UNAVAILABLE;
    }
}