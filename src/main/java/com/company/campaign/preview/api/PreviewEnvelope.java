package com.company.campaign.preview.api;

import com.company.campaign.preview.PreviewResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The T01-compatible Preview HTTP envelope.
 *
 * <p>A success response is {@code {"success":true,"data":{...},"requestId":"..."}} and an error response is
 * {@code {"success":false,"error":{"code":"...","message":"...","details":{}},"requestId":"..."}}. The
 * unused branch is omitted on the wire, so the shape is exact and never carries a half-populated
 * counterpart.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewEnvelope(
        boolean success,
        PreviewResponse data,
        PreviewErrorBody error,
        String requestId
) {

    /** Builds the exact success envelope. */
    public static PreviewEnvelope success(PreviewResponse data, String requestId) {
        return new PreviewEnvelope(true, data, null, requestId);
    }

    /** Builds the exact error envelope. */
    public static PreviewEnvelope failure(PreviewErrorBody error, String requestId) {
        return new PreviewEnvelope(false, null, error, requestId);
    }
}