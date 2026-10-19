package com.company.campaign.readiness;

import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T25 audit and observability tests: an audit record is a bounded, redacted contract that carries no
 * payload, a non-atomic sink fails closed before appending a partial batch, and an observability snapshot's
 * labels are closed machine codes rather than scope identifiers.
 */
class ReadinessAuditAndObservabilityTest {

    private static final ReadinessEnvironment LOCAL = ReadinessEnvironment.LOCAL;

    private final ReadinessHostContext context = ReadinessTestSupport.context(LOCAL);

    private ReadinessAuditEvent event(String requirementCode, ReadinessAuditDisposition disposition) {
        return new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.RECORD_EVIDENCE,
                disposition,
                ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED,
                ReadinessTestSupport.AS_OF,
                ReadinessTestSupport.TENANT,
                ReadinessTestSupport.PROGRAM,
                LOCAL,
                ReadinessTestSupport.ACTOR,
                ReadinessTestSupport.CORRELATION,
                requirementCode,
                "NONE>BLOCKED",
                ReadinessTestSupport.hash("identity"),
                ReadinessTestSupport.hash("subject"),
                null);
    }

    @Test
    void anAuditRecordCarriesIdentifiersChecksumsAStatusTransitionAndACorrelationOnly() {
        ReadinessAuditEvent event = event("LOCAL_POSTGRESQL_INTEGRATION", ReadinessAuditDisposition.RECORDED);

        assertThat(event.requirementCode()).isEqualTo("LOCAL_POSTGRESQL_INTEGRATION");
        assertThat(event.statusTransition()).isEqualTo("NONE>BLOCKED");
        assertThat(event.evidenceIdentityHash()).isNotNull();
        assertThat(event.subjectFingerprint()).isNotNull();
        assertThat(event.assessmentHash()).isNull();
        assertThat(event.formatVersion()).isEqualTo("campaign-readiness-audit/1.0");
    }

    @Test
    void anAuditRecordHasNoComponentThatCouldCarryAPayloadOrASensitiveTarget() {
        List<String> components = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component : ReadinessAuditEvent.class.getRecordComponents()) {
            components.add(component.getName());
        }

        assertThat(components).doesNotContain(
                "payload", "message", "body", "details", "content", "values",
                "memberId", "recipient", "providerTarget", "evidenceReference", "subject",
                "failure", "exception", "stackTrace", "credential", "token", "secret");
    }

    @Test
    void anAuditRecordNamesNoRequirementOutsideTheClosedCatalog() {
        assertThatThrownBy(() -> event("NOT_A_REQUIREMENT", ReadinessAuditDisposition.RECORDED))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_REQUIREMENT_UNKNOWN));
    }

    @Test
    void anAuditRecordMustCarryTheBoundedFieldItsActionRequires() {
        // A record-evidence audit names the requirement it recorded evidence for.
        assertThatThrownBy(() -> new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.RECORD_EVIDENCE,
                ReadinessAuditDisposition.RECORDED,
                null,
                ReadinessTestSupport.AS_OF,
                ReadinessTestSupport.TENANT,
                ReadinessTestSupport.PROGRAM,
                LOCAL,
                ReadinessTestSupport.ACTOR,
                ReadinessTestSupport.CORRELATION,
                null,
                "NONE>SATISFIED",
                null,
                null,
                null))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_AUDIT_INVALID));

        // An evaluation audit carries its status transition and the assessment hash it committed.
        assertThatThrownBy(() -> new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.EVALUATE_READINESS,
                ReadinessAuditDisposition.RECORDED,
                null,
                ReadinessTestSupport.AS_OF,
                ReadinessTestSupport.TENANT,
                ReadinessTestSupport.PROGRAM,
                LOCAL,
                ReadinessTestSupport.ACTOR,
                ReadinessTestSupport.CORRELATION,
                null,
                "EVALUATED>READY",
                null,
                null,
                null))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_AUDIT_INVALID));
    }

    @Test
    void anAuditDispositionStatesOnlyWhatTheStoreDid() {
        assertThat(ReadinessAuditDisposition.values()).containsExactly(
                ReadinessAuditDisposition.RECORDED,
                ReadinessAuditDisposition.DUPLICATE,
                ReadinessAuditDisposition.CONFLICT,
                ReadinessAuditDisposition.STALE_REVISION,
                ReadinessAuditDisposition.REJECTED);
        assertThat(ReadinessAuditDisposition.RECORDED.committed()).isTrue();
        assertThat(ReadinessAuditDisposition.DUPLICATE.wroteNothing()).isTrue();
        assertThat(ReadinessAuditDisposition.STALE_REVISION.wroteNothing()).isTrue();
    }

    @Test
    void anAuditRecordRejectsAnUnboundedTransitionOrTrustedIdentifier() {
        assertThatThrownBy(() -> new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.RECORD_EVIDENCE,
                ReadinessAuditDisposition.RECORDED,
                null,
                ReadinessTestSupport.AS_OF,
                "tenant with spaces",
                ReadinessTestSupport.PROGRAM,
                LOCAL,
                ReadinessTestSupport.ACTOR,
                ReadinessTestSupport.CORRELATION,
                "LOCAL_POSTGRESQL_INTEGRATION",
                "lowercase transition",
                null,
                null,
                null))
                .isInstanceOf(ReadinessException.class);
    }

    @Test
    void anAuditRecordPrintsNoTrustedIdentifierValue() {
        ReadinessAuditEvent event = event("LOCAL_POSTGRESQL_INTEGRATION", ReadinessAuditDisposition.RECORDED);

        assertThat(event.toString())
                .doesNotContain(ReadinessTestSupport.TENANT)
                .doesNotContain(ReadinessTestSupport.PROGRAM)
                .doesNotContain(ReadinessTestSupport.ACTOR)
                .doesNotContain(ReadinessTestSupport.CORRELATION);
    }

    @Test
    void anAuditRecordCanBeRestatedWithTheDispositionTheStoreReached() {
        ReadinessAuditEvent recorded = event("LOCAL_POSTGRESQL_INTEGRATION", ReadinessAuditDisposition.RECORDED);
        ReadinessAuditEvent stale = recorded.withDisposition(ReadinessAuditDisposition.STALE_REVISION);

        assertThat(stale.disposition()).isEqualTo(ReadinessAuditDisposition.STALE_REVISION);
        assertThat(stale.evidenceIdentityHash()).isEqualTo(recorded.evidenceIdentityHash());
        assertThat(stale.requirementCode()).isEqualTo(recorded.requirementCode());
        assertThat(recorded.disposition()).isEqualTo(ReadinessAuditDisposition.RECORDED);
    }

    @Test
    void theInMemorySinkValidatesTheWholeBatchBeforeAppendingAnything() {
        InMemoryReadinessAuditSink sink = new InMemoryReadinessAuditSink();
        ReadinessAuditEvent first = event("LOCAL_POSTGRESQL_INTEGRATION", ReadinessAuditDisposition.RECORDED);

        sink.recordAll(List.of(first, first));
        assertThat(sink.size()).isEqualTo(2);

        List<ReadinessAuditEvent> withNull = new ArrayList<>();
        withNull.add(first);
        withNull.add(null);
        assertThatThrownBy(() -> sink.recordAll(withNull))
                .isInstanceOf(NullPointerException.class);
        assertThat(sink.size()).isEqualTo(2);

        assertThatThrownBy(() -> sink.recordAll(List.of()))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_AUDIT_INVALID));
        assertThat(sink.size()).isEqualTo(2);

        sink.clear();
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void theDefaultSinkRefusesABatchLargerThanOne() {
        List<ReadinessAuditEvent> events = new ArrayList<>();
        ReadinessAuditSink nonAtomic = events::add;
        ReadinessAuditEvent first = event("LOCAL_POSTGRESQL_INTEGRATION", ReadinessAuditDisposition.RECORDED);

        nonAtomic.recordAll(List.of(first));
        assertThat(events).hasSize(1);

        assertThatThrownBy(() -> nonAtomic.recordAll(List.of(first, first)))
                .isInstanceOf(ReadinessException.class);
        assertThat(events).hasSize(1);
    }

    @Test
    void anObservabilitySnapshotAcceptsOnlyClosedCodesAndConsistentCounts() {
        ReadinessObservabilitySnapshot snapshot = ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY,
                ReadinessStatus.BLOCKED,
                LOCAL,
                10, 1, 9, 0,
                9,
                Map.of(ReadinessBlockerCodes.REQUIREMENT_MISSING, 9),
                3L);

        assertThat(snapshot.blockerCount(ReadinessBlockerCodes.REQUIREMENT_MISSING)).isEqualTo(9);
        assertThat(snapshot.blockerCount(ReadinessBlockerCodes.EVIDENCE_EXPIRED)).isZero();
        assertThat(snapshot.snapshotHash()).isNotNull();
        assertThat(snapshot.formatVersion()).isEqualTo("campaign-readiness-observability/1.0");
    }

    @Test
    void anObservabilitySnapshotRejectsAnOpenLabelAnInconsistentCountOrAnAmbientIdentifier() {
        assertThatThrownBy(() -> ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL,
                10, 1, 9, 0, 9, Map.of("SOME_FREE_TEXT_LABEL", 9), 3L))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_REASON_UNKNOWN));

        assertThatThrownBy(() -> ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL,
                10, 1, 9, 0, 4, Map.of(ReadinessBlockerCodes.REQUIREMENT_MISSING, 3), 3L))
                .isInstanceOf(ReadinessException.class);

        assertThatThrownBy(() -> ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL,
                10, 1, 8, 0, 9, Map.of(ReadinessBlockerCodes.REQUIREMENT_MISSING, 9), 3L))
                .isInstanceOf(ReadinessException.class);
    }

    @Test
    void aSnapshotIsDeterministicForTheSameCounts() {
        Map<String, Integer> counts = Map.of(
                ReadinessBlockerCodes.REQUIREMENT_MISSING, 4,
                ReadinessReasonCodes.BROWSER_END_TO_END_ABSENT, 1);

        ReadinessObservabilitySnapshot first = ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL, 6, 1, 5, 0, 5, counts, 2L);
        ReadinessObservabilitySnapshot second = ReadinessObservabilitySnapshot.of(
                ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL, 6, 1, 5, 0, 5, counts, 2L);

        assertThat(first.snapshotHash()).isEqualTo(second.snapshotHash());
        assertThat(first).isEqualTo(second);
        assertThat(first.blockerCountsByCode().keySet())
                .containsExactly(ReadinessReasonCodes.BROWSER_END_TO_END_ABSENT,
                        ReadinessBlockerCodes.REQUIREMENT_MISSING);
    }

    @Test
    void anInstantIsAlwaysExplicitAndNeverReadFromAnAmbientClock() {
        Instant evaluatedAt = ReadinessTestSupport.AS_OF;
        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-1", ReadinessLevel.LOCAL_READY, LOCAL, evaluatedAt,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY)));

        assertThat(assessment.evaluatedAt()).isEqualTo(evaluatedAt);
    }
}
