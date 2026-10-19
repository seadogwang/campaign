package com.company.campaign.builder.campaignagent.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The closed code-to-status mapping and the two envelope factories of the Campaign Plan route.
 *
 * <p>Every message is fixed for its code, so a response can never echo a rejected value,
 * a model output, a Provider fact or an exception message.
 */
public final class CampaignPlanHttpResponses {

    private CampaignPlanHttpResponses() {
    }

    /** Maps one stable code to its HTTP status; an unknown code is a bounded internal failure. */
    public static HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case CampaignPlanHttpErrorCodes.REQUEST_MALFORMED,
                    CampaignPlanHttpErrorCodes.REQUEST_INVALID,
                    CampaignPlanHttpErrorCodes.REQUEST_UNREADABLE,
                    CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED,
                    CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_INVALID,
                    CampaignPlanHttpErrorCodes.COMPILER_BASE_REVISIONS_STALE,
                    CampaignPlanHttpErrorCodes.COMPILER_CONTRACT_INVALID -> HttpStatus.BAD_REQUEST;
            case CampaignPlanHttpErrorCodes.REQUEST_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CampaignPlanHttpErrorCodes.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case CampaignPlanHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case CampaignPlanHttpErrorCodes.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case CampaignPlanHttpErrorCodes.FORBIDDEN,
                    CampaignPlanHttpErrorCodes.COMPILER_AUTHORIZATION_DENIED -> HttpStatus.FORBIDDEN;
            case CampaignPlanHttpErrorCodes.NOT_FOUND,
                    CampaignPlanHttpErrorCodes.COMPILER_TARGET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CampaignPlanHttpErrorCodes.CONFLICT,
                    CampaignPlanHttpErrorCodes.PLAN_STORE_REJECTED -> HttpStatus.CONFLICT;
            case CampaignPlanHttpErrorCodes.COMPILER_TARGET_UNAVAILABLE,
                    CampaignPlanHttpErrorCodes.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** One bounded success response. */
    public static ResponseEntity<CampaignPlanHttpEnvelope> success(
            CampaignPlanSummaryResponse data, String requestId) {
        return ResponseEntity.ok(CampaignPlanHttpEnvelope.success(data, requestId));
    }

    /** One bounded failure response. */
    public static ResponseEntity<CampaignPlanHttpEnvelope> failure(
            String requestId, HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .body(CampaignPlanHttpEnvelope.failure(code, messageFor(code), requestId));
    }

    /** The fixed, value-free message of one code. */
    public static String messageFor(String code) {
        return "The Campaign Plan request was refused with the bounded code "
                + (code == null ? CampaignPlanHttpErrorCodes.INTERNAL_FAILURE : code);
    }
}