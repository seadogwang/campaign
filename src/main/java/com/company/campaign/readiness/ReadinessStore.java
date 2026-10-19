package com.company.campaign.readiness;

import java.util.List;
import java.util.Optional;

/**
 * The readiness persistence port.
 *
 * <p>It is deliberately small and honest about the three properties the slice depends on:
 * <ul>
 *   <li><strong>append-only</strong> — nothing here updates or deletes a record;</li>
 *   <li><strong>idempotent and conflict-detecting</strong> — appending the exact same evidence identity and
 *       material is a {@link ReadinessDisposition#DUPLICATE}, and reusing an identity with different material
 *       is a stable {@link ReadinessDisposition#CONFLICT};</li>
 *   <li><strong>transactional in one unit</strong> — the audit event passed alongside a record is committed
 *       with it, so a failed transaction leaves neither the record nor a trail claiming it.</li>
 * </ul>
 *
 * <p>Every write is guarded by an optimistic revision: a write whose {@code expectedRevision} is behind the
 * committed revision writes nothing and reports {@link ReadinessDisposition#STALE_REVISION}.
 */
public interface ReadinessStore {

    /** The committed revision of one scope, or zero when the scope has never been written. */
    long revision(EvidenceScope scope);

    /**
     * Appends one evidence record and its audit event as one unit.
     *
     * @param expectedRevision the revision the caller read; a stale value writes nothing
     */
    EvidenceAppendResult appendEvidence(
            EvidenceScope scope,
            RequirementEvidence evidence,
            ReadinessAuditEvent audit,
            long expectedRevision);

    /**
     * Appends one assessment and its audit event as one unit.
     *
     * @param expectedRevision the revision the caller read; a stale value writes nothing
     */
    AssessmentAppendResult appendAssessment(
            EvidenceScope scope,
            ReadinessAssessment assessment,
            ReadinessAuditEvent audit,
            long expectedRevision);

    /**
     * The evidence a level needs, in deterministic order: by requirement catalog position, then by evidence
     * identifier. The read is bounded and refuses rather than truncates.
     */
    List<RequirementEvidence> evidence(EvidenceScope scope, ReadinessLevel level);

    /** The most recently committed assessment for a level, if any. */
    Optional<ReadinessAssessment> latestAssessment(EvidenceScope scope, ReadinessLevel level);
}