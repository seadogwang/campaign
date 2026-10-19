package com.company.campaign.builder.campaignagent.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The closed code-to-status mapping and the two envelope factories of the Campaign conversation route.
 *
 * <p>Every message is fixed for its code, so a response can never echo a rejected value, a model output, a
 * Provider fact or an exception message.
 */
public final class CampaignAgentHttpResponses {

    private CampaignAgentHttpResponses() {
    }

    /** Maps one stable code to its HTTP status; an unknown code is a bounded internal failure. */
    public static HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case CampaignAgentHttpErrorCodes.REQUEST_MALFORMED,
                    CampaignAgentHttpErrorCodes.REQUEST_DUPLICATE_KEY,
                    CampaignAgentHttpErrorCodes.REQUEST_UNKNOWN_FIELD,
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID,
                    CampaignAgentHttpErrorCodes.REQUEST_UNREADABLE,
                    CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED,
                    CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_INVALID -> HttpStatus.BAD_REQUEST;
            case CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CampaignAgentHttpErrorCodes.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case CampaignAgentHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case CampaignAgentHttpErrorCodes.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case CampaignAgentHttpErrorCodes.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CampaignAgentHttpErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CampaignAgentHttpErrorCodes.CONFLICT -> HttpStatus.CONFLICT;
            case CampaignAgentHttpErrorCodes.CREATE_IDENTITY_EXHAUSTED,
                    CampaignAgentHttpErrorCodes.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** One bounded success response. */
    public static ResponseEntity<CampaignAgentTurnEnvelope> success(
            CampaignAgentTurnHttpResponse data, String requestId) {
        return ResponseEntity.ok(CampaignAgentTurnEnvelope.success(data, requestId));
    }

    /** One bounded failure response. */
    public static ResponseEntity<CampaignAgentTurnEnvelope> failure(
            String requestId, HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .body(CampaignAgentTurnEnvelope.failure(code, messageFor(code), requestId));
    }

    /** The fixed, value-free message of one code. */
    public static String messageFor(String code) {
        return "The Campaign conversation request was refused with the bounded code "
                + (code == null ? CampaignAgentHttpErrorCodes.INTERNAL_FAILURE : code);
    }
}