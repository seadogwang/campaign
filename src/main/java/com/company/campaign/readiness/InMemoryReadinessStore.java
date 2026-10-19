package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deterministic transactional in-memory readiness store used by tests and local development.
 *
 * <p>It is the reference adapter for the {@link ReadinessStore} contract: one append attempt builds a working
 * copy of the scope, emits the audit event through the injected {@link ReadinessAuditSink} and only then
 * swaps the working copy in. If the sink throws, the swap never happens, so a failed unit leaves neither the
 * record nor an audit trail — exactly the atomicity the JDBC adapter gets from a real transaction.
 *
 * <p>It is the honest local adapter, not PostgreSQL evidence: a scope written here proves the contract, never
 * real database integration, and an assessment of it is bounded evidence of contract behaviour only.
 */
public final class InMemoryReadinessStore implements ReadinessStore {

    private final ReadinessAuditSink auditSink;
    private final Map<EvidenceScope, ScopeState> scopes = new LinkedHashMap<>();

    public InMemoryReadinessStore(ReadinessAuditSink auditSink) {
        this.auditSink = ReadinessSupport.requireNonNull(auditSink, "auditSink");
    }

    @Override
    public synchronized long revision(EvidenceScope scope) {
        return state(ReadinessSupport.requireNonNull(scope, "scope")).revision;
    }

    @Override
    public synchronized EvidenceAppendResult appendEvidence(
            EvidenceScope scope,
            RequirementEvidence evidence,
            ReadinessAuditEvent audit,
            long expectedRevision) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        RequirementEvidence record = ReadinessSupport.requireNonNull(evidence, "evidence");
        ScopeState current = state(key);
        if (record.environment() != key.environment()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_EVIDENCE_ORIGIN_REJECTED,
                    "Evidence recorded in one environment cannot be stored in another scope.");
        }
        if (expectedRevision != current.revision) {
            emit(audit, ReadinessAuditDisposition.STALE_REVISION);
            return new EvidenceAppendResult(ReadinessDisposition.STALE_REVISION, record, current.revision);
        }
        RequirementEvidence stored = current.byIdentity.get(record.identityHash());
        if (stored != null) {
            boolean same = stored.materialHash().equals(record.materialHash());
            emit(audit, same ? ReadinessAuditDisposition.DUPLICATE : ReadinessAuditDisposition.CONFLICT);
            return new EvidenceAppendResult(
                    same ? ReadinessDisposition.DUPLICATE : ReadinessDisposition.CONFLICT, stored, current.revision);
        }
        if (current.evidence.size() >= ReadinessLimits.MAX_EVIDENCE_PER_SCOPE) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "The readiness evidence log for this scope is full.");
        }

        List<RequirementEvidence> evidenceRecords = new ArrayList<>(current.evidence);
        evidenceRecords.add(record);
        Map<ContentHash, RequirementEvidence> byIdentity = new HashMap<>(current.byIdentity);
        byIdentity.put(record.identityHash(), record);

        // The audit event is emitted before the working copy is swapped in: a throwing sink rolls the unit back.
        emit(audit, ReadinessAuditDisposition.RECORDED);
        current.evidence = List.copyOf(evidenceRecords);
        current.byIdentity = Map.copyOf(byIdentity);
        current.revision = current.revision + 1;
        return new EvidenceAppendResult(ReadinessDisposition.RECORDED, record, current.revision);
    }

    @Override
    public synchronized AssessmentAppendResult appendAssessment(
            EvidenceScope scope,
            ReadinessAssessment assessment,
            ReadinessAuditEvent audit,
            long expectedRevision) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessAssessment value = ReadinessSupport.requireNonNull(assessment, "assessment");
        ScopeState current = state(key);
        if (value.environment() != key.environment()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID,
                    "An assessment of one environment cannot be stored in another scope.");
        }
        if (expectedRevision != current.revision) {
            emit(audit, ReadinessAuditDisposition.STALE_REVISION);
            return new AssessmentAppendResult(ReadinessDisposition.STALE_REVISION, value, current.revision);
        }
        for (ReadinessAssessment existing : current.assessments) {
            if (existing.assessmentId().equals(value.assessmentId())) {
                boolean same = existing.assessmentHash().equals(value.assessmentHash());
                emit(audit, same ? ReadinessAuditDisposition.DUPLICATE : ReadinessAuditDisposition.CONFLICT);
                return new AssessmentAppendResult(
                        same ? ReadinessDisposition.DUPLICATE : ReadinessDisposition.CONFLICT,
                        existing,
                        current.revision);
            }
        }
        if (current.assessments.size() >= ReadinessLimits.MAX_ASSESSMENTS_PER_SCOPE) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "The readiness assessment log for this scope is full.");
        }

        List<ReadinessAssessment> assessments = new ArrayList<>(current.assessments);
        assessments.add(value);

        emit(audit, ReadinessAuditDisposition.RECORDED);
        current.assessments = List.copyOf(assessments);
        current.revision = current.revision + 1;
        return new AssessmentAppendResult(ReadinessDisposition.RECORDED, value, current.revision);
    }

    @Override
    public synchronized List<RequirementEvidence> evidence(EvidenceScope scope, ReadinessLevel level) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        List<RequirementEvidence> selected = new ArrayList<>();
        for (RequirementEvidence record : state(key).evidence) {
            if (record.requirement().requiredAt(requested)) {
                selected.add(record);
            }
        }
        selected.sort(Comparator
                .comparingInt((RequirementEvidence record) -> record.requirement().ordinal())
                .thenComparing(RequirementEvidence::evidenceId));
        if (selected.size() > ReadinessLimits.MAX_EVIDENCE_PER_READ) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_STORE_LIMIT_EXCEEDED,
                    "The readiness evidence read for this level exceeds its configured bound.");
        }
        return List.copyOf(selected);
    }

    @Override
    public synchronized Optional<ReadinessAssessment> latestAssessment(EvidenceScope scope, ReadinessLevel level) {
        EvidenceScope key = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        ReadinessAssessment latest = null;
        for (ReadinessAssessment assessment : state(key).assessments) {
            if (assessment.level() == requested) {
                latest = assessment;
            }
        }
        return Optional.ofNullable(latest);
    }

    private void emit(ReadinessAuditEvent audit, ReadinessAuditDisposition disposition) {
        if (audit == null) {
            return;
        }
        // The store owns the final disposition: it is decided here, under the lock that commits the record.
        auditSink.record(audit.withDisposition(disposition));
    }

    private ScopeState state(EvidenceScope scope) {
        return scopes.computeIfAbsent(scope, key -> new ScopeState());
    }

    /** One append-only scope partition. Mutable fields are only ever swapped inside a synchronized commit. */
    private static final class ScopeState {
        private List<RequirementEvidence> evidence = List.of();
        private Map<ContentHash, RequirementEvidence> byIdentity = Map.of();
        private List<ReadinessAssessment> assessments = List.of();
        private long revision;
    }
}
