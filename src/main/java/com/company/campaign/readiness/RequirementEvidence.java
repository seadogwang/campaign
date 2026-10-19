package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;

/**
 * One immutable, self-proving readiness evidence record.
 *
 * <p>It adds exactly three things to a {@link EvidenceSubmission}: the environment the host recorded it in,
 * and two canonical hashes recomputed at construction. {@link #identityHash()} covers the fields that make
 * two records "the same evidence" — requirement, origin, subject, exact version and artifact hash — while
 * {@link #materialHash()} covers the whole record. A record whose bytes do not match its own hashes is not
 * representable, so a stored record cannot be edited in place into a different claim.
 *
 * <p>A structurally contradictory claim is refused here rather than later: production Gate evidence that
 * declares a simulated, fixture, synthetic, development-only or evaluation-asset origin, or an environment
 * that is not production, cannot be constructed. Everything that depends on the scope being assessed — the
 * requirement's admissible origins, expiry and observation time — is decided by {@link ReadinessEvaluator}
 * and reported as a deterministic blocker.
 */
public record RequirementEvidence(
        String formatVersion,
        String evidenceId,
        ReadinessRequirement requirement,
        EvidenceStatus status,
        EvidenceOrigin origin,
        ReadinessEnvironment environment,
        String subject,
        String version,
        ContentHash evidenceHash,
        String evidenceReference,
        boolean productionGateEvidence,
        Instant observedAt,
        Instant expiresAt,
        String reasonCode,
        ContentHash identityHash,
        ContentHash materialHash) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RequirementEvidence {
        if (!ReadinessVersions.EVIDENCE_FORMAT_VERSION.equals(formatVersion)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_INVALID,
                    "The readiness evidence format version is not the accepted one.");
        }
        ReadinessSupport.requireId(evidenceId, "evidenceId");
        ReadinessSupport.requireNonNull(requirement, "requirement");
        ReadinessSupport.requireNonNull(status, "status");
        ReadinessSupport.requireNonNull(origin, "origin");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireId(subject, "subject");
        ReadinessSupport.requireVersion(version, "version");
        ReadinessSupport.requireNonNull(evidenceHash, "evidenceHash");
        ReadinessSupport.requireReference(evidenceReference, "evidenceReference");
        ReadinessSupport.requireInstant(observedAt, "observedAt");
        ReadinessSupport.requireNonNull(identityHash, "identityHash");
        ReadinessSupport.requireNonNull(materialHash, "materialHash");
        if (status.satisfied() ? reasonCode != null : !ReadinessReasonCodes.isKnown(reasonCode)) {
            throw ReadinessException.invalidEvidence("reasonCode");
        }
        if (expiresAt != null && !expiresAt.isAfter(observedAt)) {
            throw ReadinessException.invalidEvidence("expiresAt");
        }
        if (productionGateEvidence && (!origin.productionCapable() || !environment.production())) {
            // A production Gate claim resting on a non-production origin or environment is not representable.
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_ORIGIN_REJECTED,
                    "Production Gate evidence requires a production origin and a production environment.");
        }
        EvidenceMaterial canonicalMaterial = new EvidenceMaterial(
                formatVersion,
                evidenceId,
                requirement.code(),
                status.name(),
                origin.name(),
                environment.name(),
                subject,
                version,
                evidenceHash,
                evidenceReference,
                productionGateEvidence,
                observedAt,
                expiresAt,
                reasonCode);
        if (!materialHash.equals(CANONICAL_JSON.hash(canonicalMaterial))) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_HASH_MISMATCH,
                    "The readiness evidence material hash does not match its own canonical bytes.");
        }
        if (!identityHash.equals(identityOf(requirement, origin, subject, version, evidenceHash))) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_HASH_MISMATCH,
                    "The readiness evidence identity hash does not match its own canonical bytes.");
        }
    }

    /** Stamps one host submission with its environment and its two canonical hashes. */
    public static RequirementEvidence of(ReadinessHostContext context, EvidenceSubmission submission) {
        ReadinessHostContext hostContext = ReadinessSupport.requireNonNull(context, "context");
        EvidenceSubmission value = ReadinessSupport.requireNonNull(submission, "submission");
        return of(hostContext.environment(), value);
    }

    /** Stamps one submission with an explicit environment and its two canonical hashes. */
    public static RequirementEvidence of(ReadinessEnvironment environment, EvidenceSubmission submission) {
        ReadinessEnvironment scopeEnvironment = ReadinessSupport.requireNonNull(environment, "environment");
        EvidenceSubmission value = ReadinessSupport.requireNonNull(submission, "submission");
        ContentHash identity = identityOf(value.requirement(), value.origin(), value.subject(), value.version(),
                value.evidenceHash());
        ContentHash material = CANONICAL_JSON.hash(new EvidenceMaterial(
                ReadinessVersions.EVIDENCE_FORMAT_VERSION,
                value.evidenceId(),
                value.requirement().code(),
                value.status().name(),
                value.origin().name(),
                scopeEnvironment.name(),
                value.subject(),
                value.version(),
                value.evidenceHash(),
                value.evidenceReference(),
                value.productionGateEvidence(),
                value.observedAt(),
                value.expiresAt(),
                value.reasonCode()));
        return new RequirementEvidence(
                ReadinessVersions.EVIDENCE_FORMAT_VERSION,
                value.evidenceId(),
                value.requirement(),
                value.status(),
                value.origin(),
                scopeEnvironment,
                value.subject(),
                value.version(),
                value.evidenceHash(),
                value.evidenceReference(),
                value.productionGateEvidence(),
                value.observedAt(),
                value.expiresAt(),
                value.reasonCode(),
                identity,
                material);
    }

    /** The canonical identity hash of one piece of evidence, independent of when it was recorded. */
    public static ContentHash identityOf(
            ReadinessRequirement requirement,
            EvidenceOrigin origin,
            String subject,
            String version,
            ContentHash evidenceHash) {
        return CANONICAL_JSON.hash(new IdentityMaterial(
                requirement.code(), origin.name(), subject, version, evidenceHash));
    }

    /** True when this record claims a satisfied requirement. */
    public boolean satisfied() {
        return status.satisfied();
    }

    /** True when this record is past its declared expiry at the given instant. */
    public boolean expiredAt(Instant instant) {
        return expiresAt != null && !expiresAt.isAfter(instant);
    }

    /** True when this record was observed after the given instant, beyond the bounded clock skew. */
    public boolean observedAfter(Instant instant) {
        return observedAt.isAfter(instant.plus(ReadinessLimits.MAX_OBSERVATION_SKEW));
    }

    /**
     * A bounded, non-reversible fingerprint of the subject, safe to carry into an audit record or a metric
     * label.
     */
    public ContentHash subjectFingerprint() {
        return CANONICAL_JSON.hash(new SubjectMaterial(requirement.code(), subject));
    }

    private EvidenceMaterial material() {
        return new EvidenceMaterial(
                formatVersion,
                evidenceId,
                requirement.code(),
                status.name(),
                origin.name(),
                environment.name(),
                subject,
                version,
                evidenceHash,
                evidenceReference,
                productionGateEvidence,
                observedAt,
                expiresAt,
                reasonCode);
    }

    /** Redacted diagnostics: no subject, reference or actor value is printed. */
    @Override
    public String toString() {
        return "RequirementEvidence[requirement=" + requirement + ", status=" + status
                + ", origin=" + origin + ", environment=" + environment
                + ", identityHash=" + identityHash + ']';
    }

    /** Package-private identity hash material. */
    record IdentityMaterial(
            String requirementCode,
            String origin,
            String subject,
            String version,
            ContentHash evidenceHash) {
    }

    /** Package-private full-material hash material. */
    record EvidenceMaterial(
            String formatVersion,
            String evidenceId,
            String requirementCode,
            String status,
            String origin,
            String environment,
            String subject,
            String version,
            ContentHash evidenceHash,
            String evidenceReference,
            boolean productionGateEvidence,
            Instant observedAt,
            Instant expiresAt,
            String reasonCode) {
    }

    /** Package-private subject fingerprint material. */
    record SubjectMaterial(String requirementCode, String subject) {
    }
}
