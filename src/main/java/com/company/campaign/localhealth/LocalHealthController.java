package com.company.campaign.localhealth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bounded, unauthenticated local health and readiness endpoint at {@code GET /api/v1/health}.
 *
 * <p>It answers process health, the active storage mode, whether real PostgreSQL was actually exercised in
 * this configured environment, and readiness as blockers and status only. It takes no request body, no
 * parameter and no header, so there is no path by which a client can bind a tenant, a program, an
 * environment, an actor, an evidence record or any other trusted fact into the answer — the trusted context
 * stays entirely outside the request payload, and the endpoint reads no payload at all.
 *
 * <p>An unexpected failure is mapped to the one closed bounded code with an empty detail map, so a stack
 * trace, an exception type, a configuration value or a JDBC URL can never escape the boundary.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class LocalHealthController {

    private final ReadinessLocalHealthService service;

    public LocalHealthController(ReadinessLocalHealthService service) {
        if (service == null) {
            throw new IllegalArgumentException("The local health controller requires its service.");
        }
        this.service = service;
    }

    /** Reports the bounded local health payload. */
    @GetMapping(path = "/health")
    public ResponseEntity<LocalHealthEnvelope> health(HttpServletRequest request) {
        String requestId = LocalHealthRequestId.of(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(LocalHealthEnvelope.success(service.snapshot(), requestId));
    }

    /** Any unexpected failure still produces the exact bounded envelope and never a framework error body. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<LocalHealthEnvelope> unavailable(RuntimeException failure) {
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(LocalHealthEnvelope.failure(
                        LocalHealthErrorCodes.LOCAL_HEALTH_UNAVAILABLE, LocalHealthRequestId.unavailable()));
    }
}