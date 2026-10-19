package com.company.campaign.localhealth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The specification-compatible envelope of {@code GET /api/v1/health}.
 *
 * <p>A success response is {@code {"success":true,"data":{...},"requestId":"..."}} and an error response is
 * {@code {"success":false,"error":{...},"requestId":"..."}}. The unused branch is omitted, so the wire shape
 * is exact and never carries a half-populated counterpart.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalHealthEnvelope(
        boolean success,
        LocalHealthData data,
        LocalHealthErrorBody error,
        String requestId
) {

    /** Builds the exact success envelope. */
    public static LocalHealthEnvelope success(LocalHealthData data, String requestId) {
        return new LocalHealthEnvelope(true, data, null, requestId);
    }

    /** Builds the exact error envelope for the one closed code. */
    public static LocalHealthEnvelope failure(String code, String requestId) {
        return new LocalHealthEnvelope(false, null, LocalHealthErrorBody.of(code), requestId);
    }
}