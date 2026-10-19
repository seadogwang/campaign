package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Safe, structured audit record for one host-only lifecycle command.
 *
 * <p>The record deliberately has no request, response, payload, Manifest, ChangeSet or Preview component.
 * It may carry the caller's trusted identifiers, a non-reversible {@link AuditResourceFingerprint}, and the
 * canonical content hashes of the exact material the command addressed — never the material itself.
 *
 * <p>{@code reasonCode} is a bounded uppercase machine code, so a rejected value, a foreign identifier or
 * an internal exception message cannot be smuggled into the audit log through it. A cross-boundary or
 * absent resource records {@link LifecycleDisposition#NOT_FOUND} and, like the accepted T02 audit contract,
 * must not carry a resource fingerprint at all — the constructor refuses to build such an event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record LifecycleAuditEvent(
        LifecycleAction action,
        LifecycleDisposition disposition,
        String reasonCode,
        Instant occurredAt,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        AuditResourceFingerprint resourceFingerprint,
        ContentHash planHash,
        ContentHash contentHash
) {

    private static final Pattern REASON_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public LifecycleAuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(disposition, "disposition");
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw LifecycleAuditException.invalidReasonCode();
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        tenantId = requireTrustedIdentifier(tenantId, "tenantId");
        programId = requireTrustedIdentifier(programId, "programId");
        userId = requireTrustedIdentifier(userId, "userId");
        dataScopeId = requireTrustedIdentifier(dataScopeId, "dataScopeId");
        if (disposition == LifecycleDisposition.NOT_FOUND && resourceFingerprint != null) {
            // Enforced in the canonical constructor so neither direct construction nor deserialization can
            // produce a not-found event that discloses a foreign resource.
            throw LifecycleAuditException.fingerprintForbiddenForNotFound();
        }
    }

    /** Builds a rejection or availability audit event from the caller's trusted context. */
    public static LifecycleAuditEvent of(
            LifecycleAction action,
            LifecycleDisposition disposition,
            String reasonCode,
            Instant occurredAt,
            TrustedExecutionContext context,
            AuditResourceFingerprint resourceFingerprint,
            ContentHash planHash,
            ContentHash contentHash) {
        Objects.requireNonNull(context, "context");
        return new LifecycleAuditEvent(
                action,
                disposition,
                reasonCode,
                occurredAt,
                context.tenantId(),
                context.programId(),
                context.userId(),
                context.dataScopeId(),
                resourceFingerprint,
                planHash,
                contentHash);
    }

    private static String requireTrustedIdentifier(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw LifecycleAuditException.invalidTrustedIdentifier(field);
        }
        return value;
    }
}