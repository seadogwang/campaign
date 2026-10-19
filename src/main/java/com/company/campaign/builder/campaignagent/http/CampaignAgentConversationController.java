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
 * The bounded {@code POST /api/v1/campaign-agent/conversations} HTTP adapter.
 *
 * <p>It accepts only the raw body — which must be one strict, empty JSON object — and the explicit
 * {@code Idempotency-Key} header, and hands both to the strictly decoding
 * {@link CampaignAgentConversationCodec}. It takes no query parameter and no DTO field, so there is no path by
 * which a client could bind tenant, program, user, permission, conversation identity, run identity, campaign,
 * revision or trusted time into a create: the host resolves the trusted context and issues the identity itself.
 *
 * <p>Registration belongs to the host. The conditional prevents incidental component-scan registration without
 * a host service; the opt-in development configuration publishes the authoritative explicit bean, so normal
 * startup has no conversation-create mapping. Only a malformed, unreadable, over-limit, unauthenticated,
 * unauthorized or identity-exhausted request is a transport failure, and even then the body is the exact
 * bounded envelope rather than a framework default.
 */
@Controller
@ConditionalOnBean(CampaignAgentConversationService.class)
@RequestMapping(path = "/api/v1/campaign-agent", produces = MediaType.APPLICATION_JSON_VALUE)
public final class CampaignAgentConversationController {

    private static final String POST = "POST";
    private static final String JSON = "application/json";

    private final CampaignAgentConversationService service;
    private final CampaignAgentConversationCodec codec = new CampaignAgentConversationCodec();

    public CampaignAgentConversationController(CampaignAgentConversationService service) {
        if (service == null) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.INTERNAL_FAILURE, "A conversation service is required.");
        }
        this.service = service;
    }

    /** Issues or replays one conversation identity over the strict empty create body. */
    @RequestMapping(path = "/conversations")
    @ResponseBody
    public ResponseEntity<CampaignAgentConversationEnvelope> create(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        String requestId = codec.requestId(body);
        if (request == null || request.getMethod() == null
                || !POST.equals(request.getMethod().toUpperCase(Locale.ROOT))) {
            return failure(requestId, HttpStatus.METHOD_NOT_ALLOWED, CampaignAgentHttpErrorCodes.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            return failure(
                    requestId, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    CampaignAgentHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            codec.decode(body);
            CampaignAgentConversationHttpResponse response =
                    service.create(request.getHeader("Idempotency-Key"));
            return ResponseEntity.ok(CampaignAgentConversationEnvelope.success(response, requestId));
        } catch (CampaignAgentException failure) {
            return failure(
                    requestId, CampaignAgentHttpResponses.statusOf(failure.code()), failure.code());
        } catch (RuntimeException failure) {
            // No unexpected exception text, type or cause is ever allowed to escape this boundary.
            return failure(requestId, HttpStatus.INTERNAL_SERVER_ERROR, CampaignAgentHttpErrorCodes.INTERNAL_FAILURE);
        }
    }

    /** A body the transport could not hand to the controller still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentConversationEnvelope> unreadableBody(
            HttpMessageNotReadableException failure) {
        return failure(
                "req-unavailable", HttpStatus.BAD_REQUEST, CampaignAgentHttpErrorCodes.REQUEST_UNREADABLE);
    }

    /** A framework-level media-type rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentConversationEnvelope> unsupportedMediaType(
            HttpMediaTypeNotSupportedException failure) {
        return failure(
                "req-unavailable", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                CampaignAgentHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    /** A framework-level method rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<CampaignAgentConversationEnvelope> methodNotAllowed(
            HttpRequestMethodNotSupportedException failure) {
        return failure(
                "req-unavailable", HttpStatus.METHOD_NOT_ALLOWED, CampaignAgentHttpErrorCodes.METHOD_NOT_ALLOWED);
    }

    private static ResponseEntity<CampaignAgentConversationEnvelope> failure(
            String requestId, HttpStatus status, String code) {
        return ResponseEntity.status(status).body(CampaignAgentConversationEnvelope.failure(
                code, CampaignAgentHttpResponses.messageFor(code), requestId));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return JSON.equals(base);
    }
}