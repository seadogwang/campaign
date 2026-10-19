package com.company.campaign.readiness;

import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T25 in-memory store tests: append-only, idempotent by identity, conflict-detecting on reused
 * identity, stale-write safe, audit-atomic, and bounded and deterministic under concurrent duplicate writes.
 *
 * <p>This adapter proves the persistence <em>contract</em>. It is not PostgreSQL evidence, and no test here
 * presents it as such.
 */
class InMemoryReadinessStoreTest {

    private static final ReadinessEnvironment LOCAL = ReadinessEnvironment.LOCAL;

    private final ReadinessHostContext context = ReadinessTestSupport.context(LOCAL);
    private final InMemoryReadinessAuditSink auditSink = new InMemoryReadinessAuditSink();
    private final InMemoryReadinessStore store = new InMemoryReadinessStore(auditSink);

    private EvidenceAppendResult append(RequirementEvidence evidence, long expectedRevision) {
        return store.appendEvidence(
                context.scope(), evidence, ReadinessTestSupport.evidenceAudit(context, evidence),
                expectedRevision);
    }

    @Test
    void anUnwrittenScopeHasRevisionZeroAndNoEvidence() {
        assertThat(store.revision(context.scope())).isZero();
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
        assertThat(store.latestAssessment(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
    }

    @Test
    void anExactReplayIsIdempotentAndReturnsTheOriginalRecord() {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");

        EvidenceAppendResult first = append(evidence, 0);
        EvidenceAppendResult replay = append(evidence, store.revision(context.scope()));

        assertThat(first.disposition()).isEqualTo(ReadinessDisposition.RECORDED);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(replay.disposition()).isEqualTo(ReadinessDisposition.DUPLICATE);
        assertThat(replay.revision()).isEqualTo(1);
        assertThat(replay.evidence().materialHash()).isEqualTo(evidence.materialHash());
        assertThat(store.revision(context.scope())).isEqualTo(1);
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).hasSize(1);
    }

    @Test
    void reusingAnIdentityWithDifferentMaterialIsAStableConflict() {
        RequirementEvidence original = RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-api",
                ReadinessRequirement.LOCAL_API_END_TO_END,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("api"),
                "readiness/test/api",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null));
        RequirementEvidence conflicting = RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-api",
                ReadinessRequirement.LOCAL_API_END_TO_END,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("api"),
                "readiness/test/api-other",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null));

        assertThat(original.identityHash()).isEqualTo(conflicting.identityHash());
        assertThat(original.materialHash()).isNotEqualTo(conflicting.materialHash());

        assertThat(append(original, 0).disposition()).isEqualTo(ReadinessDisposition.RECORDED);
        EvidenceAppendResult conflict = append(conflicting, store.revision(context.scope()));

        assertThat(conflict.disposition()).isEqualTo(ReadinessDisposition.CONFLICT);
        assertThat(conflict.evidence().materialHash()).isEqualTo(original.materialHash());
        assertThat(store.revision(context.scope())).isEqualTo(1);
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).hasSize(1);
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY).get(0).evidenceReference())
                .isEqualTo("readiness/test/api");
    }

    @Test
    void aStaleOptimisticRevisionWritesNothing() {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");

        EvidenceAppendResult result = append(evidence, 7);

        assertThat(result.disposition()).isEqualTo(ReadinessDisposition.STALE_REVISION);
        assertThat(result.committed()).isFalse();
        assertThat(result.revision()).isZero();
        assertThat(store.revision(context.scope())).isZero();
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
    }

    @Test
    void aStaleAssessmentRevisionWritesNothing() {
        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-1", ReadinessLevel.LOCAL_READY, LOCAL, ReadinessTestSupport.AS_OF,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY)));

        AssessmentAppendResult result = store.appendAssessment(
                context.scope(), assessment,
                ReadinessTestSupport.assessmentAudit(context, assessment), 4);

        assertThat(result.disposition()).isEqualTo(ReadinessDisposition.STALE_REVISION);
        assertThat(store.revision(context.scope())).isZero();
        assertThat(store.latestAssessment(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
    }

    @Test
    void concurrentDuplicateAppendsCommitExactlyOneRecord() throws Exception {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<EvidenceAppendResult>> tasks = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                tasks.add(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return append(evidence, store.revision(context.scope()));
                });
            }
            List<Future<EvidenceAppendResult>> futures = new ArrayList<>();
            for (Callable<EvidenceAppendResult> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<ReadinessDisposition> dispositions = new ArrayList<>();
            for (Future<EvidenceAppendResult> future : futures) {
                dispositions.add(future.get(5, TimeUnit.SECONDS).disposition());
            }

            assertThat(dispositions).allSatisfy(disposition -> assertThat(disposition).isIn(
                    ReadinessDisposition.RECORDED,
                    ReadinessDisposition.DUPLICATE,
                    ReadinessDisposition.STALE_REVISION));
            assertThat(dispositions.stream().filter(ReadinessDisposition.RECORDED::equals)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.revision(context.scope())).isEqualTo(1);
        assertThat(store.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).hasSize(1);
    }

    @Test
    void evidenceIsReadInCatalogOrderAndBoundedToTheLevel() {
        append(ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_ORDERED_MIGRATIONS, "ev-migrations"), 0);
        append(ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION, "ev-postgresql"),
                store.revision(context.scope()));
        append(ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.AI_GATED_PINS_CURRENT, "ev-pins"),
                store.revision(context.scope()));

        List<RequirementEvidence> local = store.evidence(context.scope(), ReadinessLevel.LOCAL_READY);
        assertThat(local).hasSize(2);
        assertThat(local.get(0).requirement()).isEqualTo(ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION);
        assertThat(local.get(1).requirement()).isEqualTo(ReadinessRequirement.LOCAL_ORDERED_MIGRATIONS);
        assertThat(store.evidence(context.scope(), ReadinessLevel.AI_READY)).hasSize(3);
    }

    @Test
    void assessmentsAreAppendOnlyAndAnExactReplayIsIdempotent() {
        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-1", ReadinessLevel.LOCAL_READY, LOCAL, ReadinessTestSupport.AS_OF,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY)));

        AssessmentAppendResult first = store.appendAssessment(
                context.scope(), assessment,
                ReadinessTestSupport.assessmentAudit(context, assessment), 0);
        AssessmentAppendResult replay = store.appendAssessment(
                context.scope(), assessment,
                ReadinessTestSupport.assessmentAudit(context, assessment),
                store.revision(context.scope()));

        assertThat(first.disposition()).isEqualTo(ReadinessDisposition.RECORDED);
        assertThat(replay.disposition()).isEqualTo(ReadinessDisposition.DUPLICATE);
        assertThat(store.latestAssessment(context.scope(), ReadinessLevel.LOCAL_READY))
                .contains(assessment);
    }

    @Test
    void evidenceRecordedInAnotherEnvironmentCannotBeStoredInThisScope() {
        RequirementEvidence otherEnvironment = ReadinessTestSupport.evidence(
                ReadinessEnvironment.CI, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");

        assertThatThrownBy(() -> append(otherEnvironment, 0))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_EVIDENCE_ORIGIN_REJECTED));
        assertThat(store.revision(context.scope())).isZero();
    }

    @Test
    void everyAttemptEmitsAnAuditEventCarryingTheReachedDisposition() {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        append(evidence, 0);
        append(evidence, store.revision(context.scope()));
        append(evidence, 99);

        List<ReadinessAuditDisposition> dispositions = auditSink.events().stream()
                .map(ReadinessAuditEvent::disposition)
                .toList();
        assertThat(dispositions).containsExactly(
                ReadinessAuditDisposition.RECORDED,
                ReadinessAuditDisposition.DUPLICATE,
                ReadinessAuditDisposition.STALE_REVISION);
        assertThat(auditSink.events()).allSatisfy(event -> {
            assertThat(event.action()).isEqualTo(ReadinessAuditAction.RECORD_EVIDENCE);
            assertThat(event.requirementCode()).isEqualTo("LOCAL_API_END_TO_END");
        });
    }

    @Test
    void aFailingAuditSinkRollsTheWholeUnitBack() {
        ReadinessAuditSink failing = event -> {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_AUDIT_INVALID, "audit sink unavailable");
        };
        InMemoryReadinessStore failingStore = new InMemoryReadinessStore(failing);
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");

        assertThatThrownBy(() -> failingStore.appendEvidence(
                context.scope(), evidence, ReadinessTestSupport.evidenceAudit(context, evidence), 0))
                .isInstanceOf(ReadinessException.class);

        assertThat(failingStore.revision(context.scope())).isZero();
        assertThat(failingStore.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).isEmpty();
    }

    @Test
    void aScopeAcceptsACompleteEvidenceSetInOrder() {
        InMemoryReadinessStore bounded = new InMemoryReadinessStore(new InMemoryReadinessAuditSink());
        List<RequirementEvidence> complete = ReadinessTestSupport.completeEvidence(
                LOCAL, ReadinessLevel.LOCAL_READY);
        long revision = 0;
        for (RequirementEvidence evidence : complete) {
            revision = bounded.appendEvidence(
                    context.scope(), evidence,
                    ReadinessTestSupport.evidenceAudit(context, evidence), revision).revision();
        }
        assertThat(revision).isEqualTo(complete.size());
        assertThat(bounded.evidence(context.scope(), ReadinessLevel.LOCAL_READY)).hasSize(complete.size());
    }
}
