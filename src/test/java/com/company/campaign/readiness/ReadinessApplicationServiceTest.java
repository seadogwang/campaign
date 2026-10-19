package com.company.campaign.readiness;

import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T25 host-only application service tests.
 *
 * <p>The service is the only place evidence is recorded and assessments are produced. These tests prove it
 * takes tenant, program, environment, actor and correlation from the host context rather than from a
 * submission, commits each write with its audit event, honours the optimistic revision, and reports an
 * observability snapshot whose labels are closed codes only.
 */
class ReadinessApplicationServiceTest {

    private static final ReadinessEnvironment LOCAL = ReadinessEnvironment.LOCAL;

    private final ReadinessHostContext context = ReadinessTestSupport.context(LOCAL);
    private final InMemoryReadinessAuditSink auditSink = new InMemoryReadinessAuditSink();
    private final InMemoryReadinessStore store = new InMemoryReadinessStore(auditSink);
    private final ReadinessApplicationService service = new ReadinessApplicationService(
            store, new ReadinessEvaluator(), Clock.fixed(ReadinessTestSupport.AS_OF, ZoneOffset.UTC));

    private EvidenceSubmission submission(ReadinessRequirement requirement, String evidenceId) {
        return ReadinessTestSupport.satisfied(requirement, LOCAL, evidenceId);
    }

    @Test
    void recordedEvidenceCarriesTheHostEnvironmentAndTheHostScope() {
        EvidenceAppendResult result = service.recordEvidence(
                context, submission(ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api"));

        assertThat(result.disposition()).isEqualTo(ReadinessDisposition.RECORDED);
        assertThat(result.evidence().environment()).isEqualTo(LOCAL);
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY))
                .singleElement()
                .satisfies(record -> assertThat(record.environment()).isEqualTo(LOCAL));
        assertThat(auditSink.events()).singleElement().satisfies(event -> {
            assertThat(event.tenantId()).isEqualTo(ReadinessTestSupport.TENANT);
            assertThat(event.programId()).isEqualTo(ReadinessTestSupport.PROGRAM);
            assertThat(event.actorRef()).isEqualTo(ReadinessTestSupport.ACTOR);
            assertThat(event.requestCorrelation()).isEqualTo(ReadinessTestSupport.CORRELATION);
            assertThat(event.occurredAt()).isEqualTo(ReadinessTestSupport.AS_OF);
        });
    }

    @Test
    void theSubmissionTypeCarriesNoTenantActorOrEnvironmentField() {
        assertThat(EvidenceSubmission.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tenantId", "programId", "actorRef", "environment", "identityHash",
                        "materialHash", "scope");
    }

    @Test
    void anEvaluationIsPersistedWithItsAuditEventInOneUnit() {
        long revision = 0;
        for (RequirementEvidence evidence : ReadinessTestSupport.completeEvidence(
                LOCAL, ReadinessLevel.LOCAL_READY)) {
            revision = store.appendEvidence(
                    context.scope(), evidence,
                    ReadinessTestSupport.evidenceAudit(context, evidence), revision).revision();
        }

        AssessmentAppendResult result = service.evaluate(
                context, ReadinessLevel.LOCAL_READY, "assessment-1");

        assertThat(result.disposition()).isEqualTo(ReadinessDisposition.RECORDED);
        assertThat(result.assessment().status()).isEqualTo(ReadinessStatus.READY);
        assertThat(store.latestAssessment(context.scope(), ReadinessLevel.LOCAL_READY))
                .contains(result.assessment());
        assertThat(auditSink.events()).last().satisfies(event -> {
            assertThat(event.action()).isEqualTo(ReadinessAuditAction.EVALUATE_READINESS);
            assertThat(event.disposition()).isEqualTo(ReadinessAuditDisposition.RECORDED);
            assertThat(event.statusTransition()).isEqualTo("EVALUATED>READY");
            assertThat(event.assessmentHash()).isEqualTo(result.assessment().assessmentHash());
        });
    }

    @Test
    void aStaleExplicitRevisionIsReportedAndWritesNothing() {
        EvidenceAppendResult result = service.recordEvidence(
                context, submission(ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api"), 5);

        assertThat(result.disposition()).isEqualTo(ReadinessDisposition.STALE_REVISION);
        assertThat(store.revision(context.scope())).isZero();
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
    }

    @Test
    void anIdempotentReplayThroughTheServiceWritesNothing() {
        EvidenceSubmission value = submission(ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        assertThat(service.recordEvidence(context, value).disposition())
                .isEqualTo(ReadinessDisposition.RECORDED);
        assertThat(service.recordEvidence(context, value).disposition())
                .isEqualTo(ReadinessDisposition.DUPLICATE);
        assertThat(store.revision(context.scope())).isEqualTo(1);
    }

    @Test
    void theObservabilitySnapshotLabelsBlockerCodesOnlyAndNotTheScope() {
        service.recordEvidence(context, submission(ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api"));

        ReadinessObservabilitySnapshot snapshot = service.snapshot(context.scope(), ReadinessLevel.LOCAL_READY);

        assertThat(snapshot.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(snapshot.level()).isEqualTo(ReadinessLevel.LOCAL_READY);
        assertThat(snapshot.evidenceCount()).isEqualTo(1);
        assertThat(snapshot.satisfiedCount()).isEqualTo(1);
        assertThat(snapshot.blockerCount()).isEqualTo(9);
        assertThat(snapshot.blockerCount(ReadinessBlockerCodes.REQUIREMENT_MISSING)).isEqualTo(9);
        assertThat(snapshot.blockerCountsByCode().keySet())
                .allSatisfy(code -> assertThat(
                        ReadinessBlockerCodes.isKnown(code) || ReadinessReasonCodes.isKnown(code)).isTrue());
        assertThat(snapshot.toString())
                .doesNotContain(ReadinessTestSupport.TENANT)
                .doesNotContain(ReadinessTestSupport.PROGRAM);
    }

    @Test
    void theSnapshotReflectsTheLatestPersistedAssessment() {
        long revision = 0;
        for (RequirementEvidence evidence : ReadinessTestSupport.completeEvidence(
                LOCAL, ReadinessLevel.LOCAL_READY)) {
            revision = store.appendEvidence(
                    context.scope(), evidence,
                    ReadinessTestSupport.evidenceAudit(context, evidence), revision).revision();
        }
        service.evaluate(context, ReadinessLevel.LOCAL_READY, "assessment-1");

        ReadinessObservabilitySnapshot snapshot = service.snapshot(context.scope(), ReadinessLevel.LOCAL_READY);

        assertThat(snapshot.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(snapshot.blockerCount()).isZero();
        assertThat(snapshot.blockerCountsByCode()).isEmpty();
        assertThat(snapshot.revision()).isEqualTo(store.revision(context.scope()));
    }

    @Test
    void theReportedIdentityMatchesTheStoredEvidenceIdentity() {
        EvidenceSubmission value = submission(ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        service.recordEvidence(context, value);

        List<RequirementEvidence> stored = store.evidence(context.scope(), ReadinessLevel.LOCAL_READY);
        assertThat(stored).singleElement().satisfies(record ->
                assertThat(record.identityHash()).isEqualTo(ReadinessApplicationService.identityOf(value)));
    }
}