package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.time.Instant;

/**
 * What a host submits when it records one piece of readiness evidence.
 *
 * <p>It deliberately has no tenant, program, actor, environment, identity-hash or material-hash field: all of
 * those are supplied by the {@link ReadinessHostContext} and recomputed by this slice, so an untrusted payload
 * has nothing to bind to and cannot claim a different tenant, a production environment or a pre-computed
 * identity. Reading such a payload with {@link com.company.campaign.shared.canonical.CanonicalJson} rejects
 * any unknown field outright.
 *
 * <p>The submission carries no payload of the underlying evidence: {@code evidenceHash} is the exact hash of
 * the artifact the host observed, and {@code evidenceReference} is a bounded, non-sensitive pointer to it.
 * Member identifiers, recipient targets, Provider payloads, model prose and raw failures are unrepresentable.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvidenceSubmission(
        String evidenceId,
        ReadinessRequirement requirement,
        EvidenceStatus status,
        EvidenceOrigin origin,
        String subject,
        String version,
        ContentHash evidenceHash,
        String evidenceReference,
        boolean productionGateEvidence,
        Instant observedAt,
        Instant expiresAt,
        String reasonCode) {

    public EvidenceSubmission {
        ReadinessSupport.requireId(evidenceId, "evidenceId");
        ReadinessSupport.requireNonNull(requirement, "requirement");
        ReadinessSupport.requireNonNull(status, "status");
        ReadinessSupport.requireNonNull(origin, "origin");
        ReadinessSupport.requireId(subject, "subject");
        ReadinessSupport.requireVersion(version, "version");
        ReadinessSupport.requireNonNull(evidenceHash, "evidenceHash");
        ReadinessSupport.requireReference(evidenceReference, "evidenceReference");
        ReadinessSupport.requireInstant(observedAt, "observedAt");
        if (status.satisfied()) {
            if (reasonCode != null) {
                throw ReadinessException.invalidEvidence("reasonCode");
            }
        } else if (!ReadinessReasonCodes.isKnown(reasonCode)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_REASON_UNKNOWN,
                    "A non-satisfied readiness evidence record must carry a code from the closed reason catalog.");
        }
        if (expiresAt != null) {
            if (!expiresAt.isAfter(observedAt)) {
                throw ReadinessException.invalidEvidence("expiresAt");
            }
            if (Duration.between(observedAt, expiresAt).compareTo(ReadinessLimits.MAX_EVIDENCE_WINDOW) > 0) {
                throw ReadinessException.invalidEvidence("expiresAt");
            }
        }
    }

    /** True when this submission claims to be production Gate evidence. */
    public boolean claimsProduction() {
        return productionGateEvidence;
    }
}