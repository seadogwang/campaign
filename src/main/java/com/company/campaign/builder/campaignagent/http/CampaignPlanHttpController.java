package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Locale;

/**
 * The bounded {@code POST /api/v1/campaign-plans} HTTP adapter.
 *
 * <p>It accepts the raw body — a strict {@code CampaignProposalPatch} — and the explicit
 * {@code Idempotency-Key} header, and hands both to the {@link CampaignPlanCompileService}.
 * It takes no query parameter and no DTO field, so there is no path by which a client could
 * bind tenant, program, user, permission, campaign identity or lifecycle facts into the compile:
 * the host resolves the trusted context and the frozen catalog/policy itself.
 *
 * <p>Registration belongs to the host. {@code ConditionalOnBean} prevents incidental
 * component-scan registration without a host service; the opt-in development configuration
 * publishes the authoritative explicit bean, so normal startup has no plan-compile mapping.
 *
 * <p>Every response body is the exact bounded {@link CampaignPlanHttpEnvelope}: there is no
 * Spring default timestamp/path body, and no raw exception value is ever echoed.
 */
@Controller
@ConditionalOnBean(CampaignPlanCompileService.class)
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class CampaignPlanHttpController {

    private static final String POST = "POST";
    private static final String JSON = "application/json";

    private final CampaignPlanCompileService service;

    public CampaignPlanHttpController(CampaignPlanCompileService service) {
        if (service == null) {
            throw new CampaignPlanHttpException(
                    CampaignPlanHttpErrorCodes.INTERNAL_FAILURE, "A plan compile service is required.");
        }
        this.service = service;
    }

    /** Compiles one Campaign proposal into a Plan. */
    @RequestMapping(path = "/campaign-plans")
    @ResponseBody
    public ResponseEntity<CampaignPlanHttpEnvelope> compile(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        String requestId = requestId(body);
        if (request == null || request.getMethod() == null
                || !POST.equals(request.getMethod().toUpperCase(Locale.ROOT))) {
            return CampaignPlanHttpResponses.failure(
                    requestId, HttpStatus.METHOD_NOT_ALLOWED,
                    CampaignPlanHttpErrorCodes.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            return CampaignPlanHttpResponses.failure(
                    requestId, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    CampaignPlanHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            CampaignPlanSummaryResponse response =
                    service.compile(body, request.getHeader("Idempotency-Key"));
            return CampaignPlanHttpResponses.success(response, requestId);
        } catch (CampaignPlanHttpException failure) {
            return CampaignPlanHttpResponses.failure(
                    requestId, CampaignPlanHttpResponses.statusOf(failure.code()), failure.code());
        } catch (RuntimeException failure) {
            // No unexpected exception text, type or cause is ever allowed to escape this boundary.
            return CampaignPlanHttpResponses.failure(
                    requestId, HttpStatus.INTERNAL_SERVER_ERROR,
                    CampaignPlanHttpErrorCodes.INTERNAL_FAILURE);
        }
    }

    /** A body the transport could not hand to the controller still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<CampaignPlanHttpEnvelope> unreadableBody(
            HttpMessageNotReadableException failure) {
        return CampaignPlanHttpResponses.failure(
                "req-unavailable", HttpStatus.BAD_REQUEST,
                CampaignPlanHttpErrorCodes.REQUEST_UNREADABLE);
    }

    /** A framework-level media-type rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignPlanHttpEnvelope> unsupportedMediaType(
            HttpMediaTypeNotSupportedException failure) {
        return CampaignPlanHttpResponses.failure(
                "req-unavailable", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                CampaignPlanHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    /** A framework-level method rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignPlanHttpEnvelope> methodNotAllowed(
            HttpRequestMethodNotSupportedException failure) {
        return CampaignPlanHttpResponses.failure(
                "req-unavailable", HttpStatus.METHOD_NOT_ALLOWED,
                CampaignPlanHttpErrorCodes.METHOD_NOT_ALLOWED);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return JSON.equals(base);
    }

    /** The deterministic, non-sensitive request identifier derived from the exact request bytes. */
    static String requestId(byte[] body) {
        if (body == null || body.length == 0) {
            return "req-unavailable";
        }
        String hex = ContentHash.sha256(body).value();
        return "req-" + hex.substring(
                CanonicalJson.HASH_PREFIX.length(), CanonicalJson.HASH_PREFIX.length() + 32);
    }
}