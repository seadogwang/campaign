package com.company.campaign.security.audit;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Safe, structured audit record for one security decision.
 *
 * <p>The record deliberately has no request, response, payload or member-sample component. It may carry the
 * caller's trusted identifiers, a {@link AuditResourceFingerprint} and a canonical
 * {@link ContentHash} — never the resource itself and never the payload.
 *
 * <p>{@code redactedProjectionHash}, when present, is the canonical hash of an <em>already-redacted</em>
 * projection. It is a deduplication and integrity handle for data that has already passed the projection
 * policy; it is not a claim that raw data was retained. Raw payloads must be projected and redacted before
 * this hash is computed, and no audit field can authorize skipping that step.
 *
 * <p>A cross-boundary denial records the caller's trusted context and the internal reason code, but the
 * foreign tenant/program identifier is never copied in: for a not-found decision the fingerprint is dropped,
 * and the constructor refuses to build such an event if a caller tries to keep one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record SecurityAuditEvent(
        SecurityAction action,
        SecurityDisposition disposition,
        String reasonCode,
        Instant occurredAt,
        Long durationNanos,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        AuditResourceFingerprint resourceFingerprint,
        ContentHash redactedProjectionHash
) {

    private static final Pattern REASON_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public SecurityAuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(disposition, "disposition");
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw AuditFieldException.invalidReasonCode();
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (durationNanos != null && durationNanos < 0L) {
            throw AuditFieldException.invalidDuration();
        }
        tenantId = requireTrustedIdentifier(tenantId, "tenantId");
        programId = requireTrustedIdentifier(programId, "programId");
        userId = requireTrustedIdentifier(userId, "userId");
        dataScopeId = requireTrustedIdentifier(dataScopeId, "dataScopeId");
        if (disposition == SecurityDisposition.NOT_FOUND && resourceFingerprint != null) {
            // Enforced in the canonical constructor so neither direct construction nor deserialization can
            // produce a not-found event that discloses a resource.
            throw AuditReferenceException.resourceForbiddenForNotFound();
        }
    }

    /**
     * Builds an audit event from an authorization decision and the caller's trusted context.
     *
     * <p>For a {@link SecurityDisposition#NOT_FOUND} decision the fingerprint is always omitted, so a
     * cross-boundary denial cannot record the foreign resource. For any other disposition a supplied
     * fingerprint must describe the same resource kind as the decision.
     */
    public static SecurityAuditEvent authorization(
            TrustedExecutionContext context,
            AuthorizationDecision decision,
            Instant occurredAt,
            Long durationNanos,
            AuditResourceFingerprint resourceFingerprint,
            ContentHash redactedProjectionHash) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");

        boolean notFound = decision.disposition() == AuthorizationDisposition.NOT_FOUND;
        AuditResourceFingerprint fingerprint = notFound ? null : resourceFingerprint;
        if (fingerprint != null && fingerprint.kind() != decision.resourceKind()) {
            throw AuditReferenceException.fingerprintKindMismatch();
        }
        return new SecurityAuditEvent(
                SecurityAction.RESOURCE_AUTHORIZATION,
                auditedDisposition(decision.disposition()),
                decision.reason().wireName(),
                occurredAt,
                durationNanos,
                context.tenantId(),
                context.programId(),
                context.userId(),
                context.dataScopeId(),
                fingerprint,
                redactedProjectionHash);
    }

    private static String requireTrustedIdentifier(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw AuditFieldException.invalidTrustedIdentifier(field);
        }
        return value;
    }

    private static SecurityDisposition auditedDisposition(AuthorizationDisposition disposition) {
        return switch (disposition) {
            case ALLOWED -> SecurityDisposition.ALLOWED;
            case NOT_FOUND -> SecurityDisposition.NOT_FOUND;
            case PERMISSION_DENIED, DATASCOPE_DENIED -> SecurityDisposition.DENIED;
            case DATASCOPE_UNAVAILABLE -> SecurityDisposition.UNAVAILABLE;
            case INVALID_REQUEST -> SecurityDisposition.INVALID;
        };
    }
}