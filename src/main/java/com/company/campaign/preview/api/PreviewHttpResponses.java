package com.company.campaign.preview.api;

import com.company.campaign.preview.PreviewErrorCodes;
import com.company.campaign.preview.PreviewException;
import com.company.campaign.preview.PreviewResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * The one bounded mapping from a closed Preview code to an HTTP response.
 *
 * <p>Malformed, validation, stale, denied, not-found and limit client failures map to appropriate 4xx
 * statuses; unexpected, backend-unavailable and timeout failures map to bounded 5xx statuses. The response
 * body is always the exact T01-compatible envelope and never contains an exception message or cause.
 */
public final class PreviewHttpResponses {

    private PreviewHttpResponses() {
    }

    /** The exact success envelope and status. */
    public static ResponseEntity<PreviewEnvelope> success(PreviewResponse data, String requestId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(PreviewEnvelope.success(data, requestId));
    }

    /** The exact error envelope for one closed code, with the status that code maps to. */
    public static ResponseEntity<PreviewEnvelope> failure(String requestId, String code) {
        return failure(requestId, code, Map.of());
    }

    /** The exact error envelope for one closed code and closed bounded details. */
    public static ResponseEntity<PreviewEnvelope> failure(
            String requestId, String code, Map<String, Object> details) {
        return ResponseEntity.status(statusFor(code))
                .contentType(MediaType.APPLICATION_JSON)
                .body(PreviewEnvelope.failure(PreviewErrorBody.of(code, details), requestId));
    }

    /** The error envelope and status for one bounded Preview failure. */
    public static ResponseEntity<PreviewEnvelope> failure(String requestId, PreviewException failure) {
        return failure(requestId, failure.code(), failure.details());
    }

    /** The closed status mapping; unknown codes are treated as an unexpected internal failure. */
    public static HttpStatus statusFor(String code) {
        return switch (code) {
            case PreviewErrorCodes.REQUEST_MISSING,
                 PreviewErrorCodes.REQUEST_ENCODING_INVALID,
                 PreviewErrorCodes.REQUEST_JSON_MALFORMED,
                 PreviewErrorCodes.REQUEST_DUPLICATE_KEY,
                 PreviewErrorCodes.REQUEST_UNKNOWN_FIELD,
                 PreviewErrorCodes.REQUEST_MISSING_FIELD,
                 PreviewErrorCodes.REQUEST_NULL_FIELD,
                 PreviewErrorCodes.REQUEST_SHAPE_INVALID,
                 PreviewErrorCodes.REQUEST_DSL_INVALID,
                 PreviewErrorCodes.REQUEST_UNREADABLE,
                 PreviewErrorCodes.METADATA_BINDING_MISMATCH -> HttpStatus.BAD_REQUEST;
            case PreviewErrorCodes.REQUEST_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case PreviewErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE -> HttpStatus.UNAUTHORIZED;
            case PreviewErrorCodes.METADATA_SNAPSHOT_MISSING,
                 PreviewErrorCodes.METADATA_OWNER_MISMATCH -> HttpStatus.NOT_FOUND;
            case PreviewErrorCodes.METADATA_UNAVAILABLE,
                 PreviewErrorCodes.BACKEND_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PreviewErrorCodes.VALIDATION_REJECTED,
                 PreviewErrorCodes.PLANNING_REJECTED,
                 PreviewErrorCodes.RESULT_LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PreviewErrorCodes.DATA_STALE -> HttpStatus.CONFLICT;
            case PreviewErrorCodes.BACKEND_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case PreviewErrorCodes.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case PreviewErrorCodes.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}