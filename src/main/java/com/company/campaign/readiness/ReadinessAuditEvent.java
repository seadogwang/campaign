package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One immutable readiness audit record.
 *
 * <p>It carries bounded identifiers, canonical checksums, a status transition and a request correlation —
 * and nothing else. There is deliberately no field for an evidence payload, a credential, a member
 * identifier, a recipient or Provider target, model prose or a raw failure, so redaction is a property of the
 * type rather than a rule an author has to remember. The evidence subject is reduced to a non-reversible
 * {@link #subjectFingerprint()}.
 *
 * <p>The record is one half of an atomic unit: a store commits it together with the evidence or assessment it
 * describes, so a rolled-back transaction leaves no audit trail claiming a write that never happened.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReadinessAuditEvent(
        String formatVersion,
        ReadinessAuditAction action,
        ReadinessAuditDisposition disposition,
        String reasonCode,
        Instant occurredAt,
        String tenantId,
        String programId,
        ReadinessEnvironment environment,
        String actorRef,
        String requestCorrelation,
        String requirementCode,
        String statusTransition,
        ContentHash evidenceIdentityHash,
        ContentHash subjectFingerprint,
        ContentHash assessmentHash) {

    private static final Pattern TRANSITION =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,31}(>[A-Z][A-Z0-9_]{0,31})?$");

    public ReadinessAuditEvent {
        if (!ReadinessVersions.AUDIT_FORMAT_VERSION.equals(formatVersion)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_AUDIT_INVALID,
                    "The readiness audit format version is not the accepted one.");
        }
        ReadinessSupport.requireNonNull(action, "action");
        ReadinessSupport.requireNonNull(disposition, "disposition");
        ReadinessSupport.requireInstant(occurredAt, "occurredAt");
        ReadinessSupport.requireId(tenantId, "tenantId");
        ReadinessSupport.requireId(programId, "programId");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireId(actorRef, "actorRef");
        ReadinessSupport.requireId(requestCorrelation, "requestCorrelation");
        if (reasonCode != null) {
            ReadinessSupport.requireCode(reasonCode, "reasonCode");
        }
        if (requirementCode != null && !ReadinessCatalog.isKnownCode(requirementCode)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_REQUIREMENT_UNKNOWN,
                    "A readiness audit record names a requirement outside the closed catalog.");
        }
        if (statusTransition != null && !TRANSITION.matcher(statusTransition).matches()) {
            throw ReadinessException.invalidField("statusTransition");
        }
        if (action == ReadinessAuditAction.RECORD_EVIDENCE) {
            if (requirementCode == null) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_AUDIT_INVALID,
                        "A record-evidence audit record must name the requirement it recorded evidence for.");
            }
        } else if (statusTransition == null || assessmentHash == null) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_AUDIT_INVALID,
                    "An evaluation audit record must carry its status transition and its assessment hash.");
        }
    }

    /** True when this record describes a committed evidence append. */
    public boolean committed() {
        return disposition == ReadinessAuditDisposition.RECORDED;
    }

    /**
     * The same audit material with the disposition the store actually reached.
     *
     * <p>A caller supplies the operation's audit material before the outcome is known — a stale revision, an
     * exact replay and a conflicting identity are all decided inside the store, under the same lock that
     * commits the record — so the store is the only place that can state the final disposition truthfully.
     */
    public ReadinessAuditEvent withDisposition(ReadinessAuditDisposition reached) {
        return new ReadinessAuditEvent(
                formatVersion,
                action,
                reached,
                reasonCode,
                occurredAt,
                tenantId,
                programId,
                environment,
                actorRef,
                requestCorrelation,
                requirementCode,
                statusTransition,
                evidenceIdentityHash,
                subjectFingerprint,
                assessmentHash);
    }

    /** Redacted diagnostics: no identifier, fingerprint or assessment hash is printed in full. */
    @Override
    public String toString() {
        return "ReadinessAuditEvent[action=" + action + ", disposition=" + disposition
                + ", requirementCode=" + requirementCode + ", occurredAt=" + occurredAt + ']';
    }
}