package com.company.campaign.readiness;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The host-only readiness application service.
 *
 * <p>It is the only place evidence is recorded and assessments are produced, and it is deliberately not a
 * web endpoint, not a model-callable Tool and not a public Campaign command: there is no controller in this
 * package, no annotation, and no method that accepts a trust decision from a caller. Everything it trusts
 * arrives as a {@link ReadinessHostContext} the host built from an accepted {@code TrustedExecutionContext}.
 *
 * <p>Each write is one unit: the store commits the record, its audit event and the revision bump together, or
 * commits nothing.
 */
public final class ReadinessApplicationService implements ReadinessObservabilityPort {

    private static final String SNAPSHOT_ASSESSMENT_ID = "observability-snapshot";

    private final ReadinessStore store;
    private final ReadinessEvaluator evaluator;
    private final Clock clock;

    public ReadinessApplicationService(ReadinessStore store, ReadinessEvaluator evaluator, Clock clock) {
        this.store = ReadinessSupport.requireNonNull(store, "store");
        this.evaluator = ReadinessSupport.requireNonNull(evaluator, "evaluator");
        this.clock = ReadinessSupport.requireNonNull(clock, "clock");
    }

    /** Records one evidence submission against the scope's current revision. */
    public EvidenceAppendResult recordEvidence(
            ReadinessHostContext context, EvidenceSubmission submission) {
        ReadinessHostContext hostContext = ReadinessSupport.requireNonNull(context, "context");
        return recordEvidence(hostContext, submission, store.revision(hostContext.scope()));
    }

    /** Records one evidence submission against an explicit optimistic revision. */
    public EvidenceAppendResult recordEvidence(
            ReadinessHostContext context, EvidenceSubmission submission, long expectedRevision) {
        ReadinessHostContext hostContext = ReadinessSupport.requireNonNull(context, "context");
        EvidenceSubmission value = ReadinessSupport.requireNonNull(submission, "submission");
        RequirementEvidence evidence = RequirementEvidence.of(hostContext, value);
        ReadinessAuditEvent audit = new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.RECORD_EVIDENCE,
                ReadinessAuditDisposition.RECORDED,
                evidence.reasonCode(),
                now(),
                hostContext.tenantId(),
                hostContext.programId(),
                hostContext.environment(),
                hostContext.actorRef(),
                hostContext.requestCorrelation(),
                evidence.requirement().code(),
                "NONE>" + evidence.status().name(),
                evidence.identityHash(),
                evidence.subjectFingerprint(),
                null);
        return store.appendEvidence(hostContext.scope(), evidence, audit, expectedRevision);
    }

    /** Evaluates one level against the scope's current revision. */
    public AssessmentAppendResult evaluate(
            ReadinessHostContext context, ReadinessLevel level, String assessmentId) {
        ReadinessHostContext hostContext = ReadinessSupport.requireNonNull(context, "context");
        return evaluate(hostContext, level, assessmentId, store.revision(hostContext.scope()));
    }

    /** Evaluates one level against an explicit optimistic revision. */
    public AssessmentAppendResult evaluate(
            ReadinessHostContext context, ReadinessLevel level, String assessmentId, long expectedRevision) {
        ReadinessHostContext hostContext = ReadinessSupport.requireNonNull(context, "context");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        EvidenceScope scope = hostContext.scope();
        ReadinessAssessment assessment = evaluator.evaluate(new ReadinessEvaluationRequest(
                assessmentId,
                requested,
                scope.environment(),
                now(),
                store.evidence(scope, requested)));
        ReadinessAuditEvent audit = new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.EVALUATE_READINESS,
                ReadinessAuditDisposition.RECORDED,
                assessment.blockers().isEmpty() ? null : assessment.blockers().get(0).code(),
                assessment.evaluatedAt(),
                hostContext.tenantId(),
                hostContext.programId(),
                hostContext.environment(),
                hostContext.actorRef(),
                hostContext.requestCorrelation(),
                null,
                "EVALUATED>" + assessment.status().name(),
                null,
                null,
                assessment.assessmentHash());
        return store.appendAssessment(scope, assessment, audit, expectedRevision);
    }

    @Override
    public ReadinessObservabilitySnapshot snapshot(EvidenceScope scope, ReadinessLevel level) {
        EvidenceScope requestedScope = ReadinessSupport.requireNonNull(scope, "scope");
        ReadinessLevel requested = ReadinessSupport.requireNonNull(level, "level");
        List<RequirementEvidence> evidence = store.evidence(requestedScope, requested);
        Optional<ReadinessAssessment> latest = store.latestAssessment(requestedScope, requested);
        ReadinessAssessment assessment = latest.orElseGet(() -> evaluator.evaluate(
                new ReadinessEvaluationRequest(
                        SNAPSHOT_ASSESSMENT_ID,
                        requested,
                        requestedScope.environment(),
                        now(),
                        evidence)));

        int satisfied = 0;
        int blocked = 0;
        int invalid = 0;
        for (RequirementEvidence record : evidence) {
            if (record.status() == EvidenceStatus.SATISFIED) {
                satisfied++;
            } else if (record.status() == EvidenceStatus.BLOCKED) {
                blocked++;
            } else {
                invalid++;
            }
        }
        Map<String, Integer> blockerCounts = new LinkedHashMap<>();
        for (ReadinessBlocker blocker : assessment.blockers()) {
            blockerCounts.merge(blocker.code(), 1, Integer::sum);
        }
        return ReadinessObservabilitySnapshot.of(
                requested,
                assessment.status(),
                requestedScope.environment(),
                evidence.size(),
                satisfied,
                blocked,
                invalid,
                assessment.blockers().size(),
                blockerCounts,
                store.revision(requestedScope));
    }

    /** The trusted evaluation instant; never read from an ambient clock inside the model or the evaluator. */
    private Instant now() {
        return Instant.now(clock);
    }

    /** The exact evidence identity an append attempt would use, exposed for deterministic idempotency checks. */
    public static ContentHash identityOf(EvidenceSubmission submission) {
        EvidenceSubmission value = ReadinessSupport.requireNonNull(submission, "submission");
        return RequirementEvidence.identityOf(
                value.requirement(), value.origin(), value.subject(), value.version(), value.evidenceHash());
    }
}