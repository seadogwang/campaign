package com.company.campaign.preview.api;

import com.company.campaign.preview.PreviewErrorCodes;
import com.company.campaign.preview.PreviewException;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.preview.SegmentPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * The read-only {@code POST /ai/segments/preview} HTTP adapter.
 *
 * <p>It accepts only the raw bounded body and passes it to the strict request codec inside
 * {@link SegmentPreviewService}. It takes no query parameter, no trusted header and no alternate DTO field,
 * so there is no path by which a client can bind tenant, program, user, DataScope, {@code asOf}, timezone,
 * watermark, snapshot, plan, SQL, fixture, FX or backend selection into execution context.
 *
 * <p>Registration is conditional on a host-supplied {@link SegmentPreviewService} bean. No Preview service,
 * trusted context provider, Metadata repository, authorization service, execution backend, sample hasher or
 * policy is created by default, so the normal application cannot start a usable Preview endpoint — it starts
 * with no Preview mapping at all until a host explicitly supplies every safe dependency. Focused MockMvc
 * slice tests construct the controller directly with explicit safe test dependencies.
 *
 * <p>Method and media-type rejection is performed explicitly, so an unsupported method or media type returns
 * the exact bounded error envelope instead of a framework default body.
 */
@Controller
@ConditionalOnBean(SegmentPreviewService.class)
@RequestMapping(path = "/ai/segments", produces = MediaType.APPLICATION_JSON_VALUE)
public final class SegmentPreviewController {

    private static final String POST = "POST";
    private static final String JSON = "application/json";

    private final SegmentPreviewService service;

    /** Creates the controller over the one mandatory orchestrator. */
    public SegmentPreviewController(SegmentPreviewService service) {
        if (service == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "previewService");
        }
        this.service = service;
    }

    /** Executes one Preview call over the raw request body. */
    @RequestMapping(path = "/preview")
    @ResponseBody
    public ResponseEntity<PreviewEnvelope> preview(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        String requestId = PreviewRequestId.from(body);
        if (request == null || request.getMethod() == null
                || !POST.equals(request.getMethod().toUpperCase(Locale.ROOT))) {
            return PreviewHttpResponses.failure(requestId, PreviewErrorCodes.METHOD_NOT_ALLOWED);
        }
        if (!isJson(request.getContentType())) {
            return PreviewHttpResponses.failure(requestId, PreviewErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            PreviewResponse response = service.preview(body);
            return PreviewHttpResponses.success(response, requestId);
        } catch (PreviewException failure) {
            return PreviewHttpResponses.failure(requestId, failure);
        } catch (RuntimeException failure) {
            // No unexpected exception text, type or cause is ever allowed to escape the boundary.
            return PreviewHttpResponses.failure(requestId, PreviewErrorCodes.INTERNAL_FAILURE);
        }
    }

    /** A body the transport could not hand to the controller still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<PreviewEnvelope> unreadableBody(HttpMessageNotReadableException failure) {
        return PreviewHttpResponses.failure(PreviewRequestId.unavailable(), PreviewErrorCodes.REQUEST_UNREADABLE);
    }

    /** A framework-level media-type rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<PreviewEnvelope> unsupportedMediaType(HttpMediaTypeNotSupportedException failure) {
        return PreviewHttpResponses.failure(
                PreviewRequestId.unavailable(), PreviewErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    /** A framework-level method rejection still produces the exact bounded envelope. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<PreviewEnvelope> methodNotAllowed(HttpRequestMethodNotSupportedException failure) {
        return PreviewHttpResponses.failure(
                PreviewRequestId.unavailable(), PreviewErrorCodes.METHOD_NOT_ALLOWED);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return JSON.equals(base);
    }
}