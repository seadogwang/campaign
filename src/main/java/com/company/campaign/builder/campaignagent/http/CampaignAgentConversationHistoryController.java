package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The bounded read-only Campaign conversation-history HTTP adapter.
 *
 * <p>It exposes exactly two {@code GET} routes under {@code /api/v1/campaign-agent/history}: the caller's own
 * conversation list and one conversation projection by its bounded conversation/run identity. It takes no
 * request body, binds no trusted fact from the request and runs no model, Tool, Proposal or lifecycle action, so
 * the surface can never mutate Campaign state.
 *
 * <p>Registration belongs to the host: the conditional prevents incidental component-scan registration without
 * a host service, and both hosts publish the authoritative explicit bean. The routes live under
 * {@code /api/v1/campaign-agent/**}, so the production security boundary requires a verified bearer token for
 * them exactly as it does for the create and turn routes.
 */
@Controller
@ConditionalOnBean(CampaignAgentConversationHistoryService.class)
@RequestMapping(path = "/api/v1/campaign-agent/history", produces = MediaType.APPLICATION_JSON_VALUE)
public final class CampaignAgentConversationHistoryController {

    private static final String LIST_REQUEST_ID = "req-history-list";
    private static final String READ_REQUEST_ID = "req-history-read";

    private final CampaignAgentConversationHistoryService service;

    public CampaignAgentConversationHistoryController(CampaignAgentConversationHistoryService service) {
        if (service == null) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.INTERNAL_FAILURE, "A conversation history service is required.");
        }
        this.service = service;
    }

    /** Lists the caller's own live conversations, most recently updated first. */
    @RequestMapping(path = "/conversations", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<CampaignAgentHistoryEnvelope> list(
            @RequestParam(name = "limit", required = false) String limit) {
        try {
            return ResponseEntity.ok(CampaignAgentHistoryEnvelope.success(
                    service.list(parseLimit(limit)), LIST_REQUEST_ID));
        } catch (CampaignAgentException failure) {
            return failure(failure.code(), LIST_REQUEST_ID);
        } catch (RuntimeException failure) {
            return failure(CampaignAgentHttpErrorCodes.INTERNAL_FAILURE, LIST_REQUEST_ID);
        }
    }

    /** Reads one stored conversation projection of the caller's own boundary. */
    @RequestMapping(path = "/conversations/{conversationId}", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<CampaignAgentHistoryEnvelope> read(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(name = "runId", required = false) String runId) {
        try {
            return ResponseEntity.ok(CampaignAgentHistoryEnvelope.success(
                    service.read(conversationId, runId), READ_REQUEST_ID));
        } catch (CampaignAgentException failure) {
            return failure(failure.code(), READ_REQUEST_ID);
        } catch (RuntimeException failure) {
            return failure(CampaignAgentHttpErrorCodes.INTERNAL_FAILURE, READ_REQUEST_ID);
        }
    }

    private static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return CampaignAgentConversationHistoryService.DEFAULT_LIST_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(limit.trim());
        } catch (NumberFormatException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID,
                    "The history limit must be a bounded positive integer.");
        }
        if (parsed < 1) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID,
                    "The history limit must be a bounded positive integer.");
        }
        if (parsed > CampaignAgentConversationHistoryService.MAX_LIST_LIMIT) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED,
                    "The history limit exceeds its declared bound.");
        }
        return parsed;
    }

    private static ResponseEntity<CampaignAgentHistoryEnvelope> failure(String code, String requestId) {
        return ResponseEntity.status(CampaignAgentHttpResponses.statusOf(code))
                .body(CampaignAgentHistoryEnvelope.failure(
                        code, CampaignAgentHttpResponses.messageFor(code), requestId));
    }
}