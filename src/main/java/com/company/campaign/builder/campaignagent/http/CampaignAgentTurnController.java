package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
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
 * The bounded {@code POST /api/v1/campaign-agent/turns} HTTP adapter.
 *
 * <p>It accepts only the raw bounded body and the explicit {@code Idempotency-Key} header, and hands both to
 * the strictly decoding {@link CampaignAgentTurnCodec}. It takes no query parameter and no alternate DTO
 * field, so there is no path by which a client could bind tenant, program, user, permission, campaign, base
 * revision, Proposal or lifecycle facts into a turn.
 *
 * <p>Registration belongs to the host. The controller stereotype is the Spring MVC handler marker, while the
 * conditional prevents incidental component-scan registration without a host service. The opt-in development
 * configuration publishes the authoritative explicit bean, so normal startup has no Campaign conversation
 * mapping. Focused MockMvc slice tests may also construct it directly with an explicit test-wired service.
 *
 * <p>A refused turn is a {@code 200} response whose bounded projection reports {@code accepted=false}: the
 * contract answered the request and the client keeps its transcript. Only a malformed, unreadable,
 * over-limit, unauthenticated, unauthorized, absent, conflicting or unsupported request is a transport
 * failure, and even then the body is the exact bounded envelope rather than a framework default.
 */
@Controller
@ConditionalOnBean(CampaignAgentTurnService.class)
@RequestMapping(path = "/api/v1/campaign-agent", produces = MediaType.APPLICATION_JSON_VALUE)
public final class CampaignAgentTurnController {

    private static final String POST = "POST";
    private static final String JSON = "application/json";

    private final CampaignAgentTurnService service;
    private final CampaignAgentTurnCodec codec = new CampaignAgentTurnCodec();

    public CampaignAgentTurnController(CampaignAgentTurnService service) {
        if (service == null) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.INTERNAL_FAILURE, "A turn service is required.");
        }
        this.service = service;
    }

    /** Serves one conversation turn over the raw request body. */
    @RequestMapping(path = "/turns")
    @ResponseBody
    public ResponseEntity<CampaignAgentTurnEnvelope> turn(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        String requestId = codec.requestId(body);
        if (request == null || request.getMethod() == null
                || !POST.equals(request.getMethod().toUpperCase(Locale.ROOT))) {
            return CampaignAgentHttpResponses.failure(
                    requestId, HttpStatus.METHOD_NOT_ALLOWED, CampaignAgentHttpErrorCodes.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            return CampaignAgentHttpResponses.failure(
                    requestId, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    CampaignAgentHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            CampaignAgentTurnHttpRequest decoded = codec.decode(body);
            CampaignAgentTurnHttpResponse response =
                    service.turn(decoded, request.getHeader("Idempotency-Key"));
            return CampaignAgentHttpResponses.success(response, requestId);
        } catch (CampaignAgentException failure) {
            return CampaignAgentHttpResponses.failure(
                    requestId, CampaignAgentHttpResponses.statusOf(failure.code()), failure.code());
        } catch (RuntimeException failure) {
            // No unexpected exception text, type or cause is ever allowed to escape this boundary.
            return CampaignAgentHttpResponses.failure(
                    requestId, HttpStatus.INTERNAL_SERVER_ERROR, CampaignAgentHttpErrorCodes.INTERNAL_FAILURE);
        }
    }

    /** A body the transport could not hand to the controller still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentTurnEnvelope> unreadableBody(HttpMessageNotReadableException failure) {
        return CampaignAgentHttpResponses.failure(
                "req-unavailable", HttpStatus.BAD_REQUEST, CampaignAgentHttpErrorCodes.REQUEST_UNREADABLE);
    }

    /** A framework-level media-type rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentTurnEnvelope> unsupportedMediaType(
            HttpMediaTypeNotSupportedException failure) {
        return CampaignAgentHttpResponses.failure(
                "req-unavailable", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                CampaignAgentHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    /** A framework-level method rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentTurnEnvelope> methodNotAllowed(
            HttpRequestMethodNotSupportedException failure) {
        return CampaignAgentHttpResponses.failure(
                "req-unavailable", HttpStatus.METHOD_NOT_ALLOWED, CampaignAgentHttpErrorCodes.METHOD_NOT_ALLOWED);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return JSON.equals(base);
    }
}
