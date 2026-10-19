package com.company.campaign.runtime;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import com.company.campaign.runtime.testsupport.ScriptedAudiencePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Focused T22 materialization tests: atomic snapshot/enrollment/Participant/START creation, the fixed
 * {@code asOf}, the frozen recipient limit, closed stale/timeout/query/scope/over-limit outcomes, malformed
 * evidence, enrollment dedupe and the zero-audience and all-already-enrolled successes.
 */
class RuntimeMaterializationTest {

    private static final Instant AS_OF = RuntimeScenario.PUBLISHED_AT;

    private static RuntimeScenario deliveredOnce() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00"));
        scenario.publish();
        String key = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        return scenario;
    }

    private static RuntimeScenario deliveredDaily() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.dailyPlan("2026-09-28", "2026-09-30", "06:00"));
        scenario.publish();
        scenario.planDue(Instant.parse("2026-09-29T00:05:00Z"));
        scenario.planDue(Instant.parse("2026-09-30T00:05:00Z"));
        for (ScheduledOccurrenceRecord record : scenario.committedOccurrences()) {
            if (record.decision().due()) {
                scenario.ingress().deliverOccurrence(scenario.campaignId(), record.executionKey());
            }
        }
        return scenario;
    }

    private static String onlyKey(RuntimeScenario scenario) {
        return scenario.committedOccurrences().get(0).executionKey();
    }

    private static RuntimeBoundaryException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, RuntimeBoundaryException.class);
    }

    // ------------------------------------------------------------------ success

    @Test
    void materializationWritesSnapshotEnrollmentParticipantsAndStartTasksAtomically() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant observedAt = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1", "member-2", "member-3"), observedAt,
                observedAt.minusSeconds(600)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.failed()).isFalse();
        assertThat(result.snapshotMemberCount()).isEqualTo(3);
        assertThat(result.enrollmentCount()).isEqualTo(3);
        assertThat(result.participantCount()).isEqualTo(3);
        assertThat(result.taskCount()).isEqualTo(3);
        assertThat(result.execution().candidateCount()).isEqualTo(3);
        assertThat(result.execution().enrolledCount()).isEqualTo(3);
        assertThat(result.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(result.execution().revision()).isEqualTo(1);

        FullPublishedDefinition definition = scenario.definition();
        assertThat(scenario.store.snapshotMemberCount()).isEqualTo(3);
        assertThat(scenario.store.enrollmentCount()).isEqualTo(3);
        assertThat(scenario.store.participantCount()).isEqualTo(3);
        assertThat(scenario.store.taskCount()).isEqualTo(3);

        List<JourneyParticipant> participants = scenario.store.findParticipants(
                scenario.tenantId(), result.execution().executionId());
        assertThat(participants).hasSize(3);
        assertThat(participants).allSatisfy(participant -> {
            assertThat(participant.status()).isEqualTo(ParticipantStatus.CREATED);
            assertThat(participant.revision()).isZero();
            assertThat(participant.currentNodeLogicalId()).isEqualTo(definition.journey().startNodeLogicalId());
            assertThat(participant.journeyHash()).isEqualTo(definition.journeyHash());
            assertThat(participant.fullDefinitionHash()).isEqualTo(definition.contentHash());
        });

        List<RuntimeTask> tasks = scenario.store.findTasks(
                scenario.tenantId(), result.execution().executionId());
        assertThat(tasks).hasSize(3);
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.kind()).isEqualTo(RuntimeTaskKind.START);
            assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE);
            assertThat(task.reasonCode()).isEqualTo(RuntimeTaskKind.START.reasonCode());
            assertThat(task.isEligible()).isTrue();
            assertThat(participants).extracting(JourneyParticipant::participantId)
                    .contains(task.participantId());
        });

        ExecutionCounters counters = result.counters();
        assertThat(counters.candidateCount()).isEqualTo(3);
        assertThat(counters.enrolledCount()).isEqualTo(3);
        assertThat(counters.runningCount()).isEqualTo(3);
        assertThat(counters.completedCount()).isZero();
    }

    @Test
    void audienceIsEvaluatedAtTheFrozenNominalInstantNotTheDeliveryInstant() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), nominal, nominal.minusSeconds(60)));

        scenario.materialization().materialize(scenario.tenantId(), key);

        AudienceMaterializationRequest request = scenario.scriptedAudience().lastRequest();
        assertThat(request.asOf()).isEqualTo(nominal);
        assertThat(request.asOf()).isNotEqualTo(RuntimeScenario.DELIVERED_AT);
        assertThat(request.tenantId()).isEqualTo(scenario.tenantId());
        assertThat(request.dataScopeId()).isEqualTo(scenario.definition().dataScopeId());
        assertThat(request.maxRecipients()).isEqualTo(RuntimeTestPlans.DEFAULT_MAX_RECIPIENTS);
        assertThat(request.freshnessMaxAgeSeconds()).isEqualTo(RuntimeScenario.FRESHNESS.toSeconds());
        assertThat(request.manifestHash()).isEqualTo(scenario.definition().manifestHash());
        assertThat(request.requestHash()).isNotNull();
    }

    @Test
    void zeroAudienceCompletesSuccessfullyWithNoParticipantAndNoTask() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of(), nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.failed()).isFalse();
        assertThat(result.emptyAudience()).isTrue();
        assertThat(result.execution().status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.execution().candidateCount()).isZero();
        assertThat(result.execution().enrolledCount()).isZero();
        assertThat(scenario.store.participantCount()).isZero();
        assertThat(scenario.store.taskCount()).isZero();
        assertThat(scenario.store.enrollmentCount()).isZero();
        assertThat(scenario.audits()).extracting(RuntimeAuditRecord::reasonCode)
                .contains(RuntimeAuditRecord.REASON_MATERIALIZATION_EMPTY);
    }

    @Test
    void aSecondExecutionExcludesMembersEnrolledByAnEarlierExecution() {
        RuntimeScenario scenario = deliveredDaily();
        List<ScheduledOccurrenceRecord> due = scenario.committedOccurrences().stream()
                .filter(record -> record.decision().due())
                .toList();
        assertThat(due).hasSize(2);

        String firstKey = due.get(0).executionKey();
        Instant firstNominal = due.get(0).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), firstNominal, firstNominal.minusSeconds(60)));
        CampaignExecution first = scenario.materialization()
                .materialize(scenario.tenantId(), firstKey).execution();
        assertThat(scenario.store.enrollmentCount()).isEqualTo(1);

        String secondKey = due.get(1).executionKey();
        Instant secondNominal = due.get(1).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1", "member-2"), secondNominal,
                secondNominal.minusSeconds(60)));
        MaterializationResult second =
                scenario.materialization().materialize(scenario.tenantId(), secondKey);

        assertThat(second.allCandidatesAlreadyEnrolled()).isFalse();
        assertThat(second.execution().candidateCount()).isEqualTo(2);
        assertThat(second.execution().enrolledCount()).isEqualTo(1);
        assertThat(second.snapshotMemberCount()).isEqualTo(2);
        assertThat(second.enrollmentCount()).isEqualTo(1);
        assertThat(scenario.store.enrollmentCount()).isEqualTo(2);
        assertThat(scenario.store
                        .findEnrollment(scenario.tenantId(), scenario.campaignId(), "member-1")
                        .orElseThrow()
                        .executionId())
                .isEqualTo(first.executionId());
        assertThat(scenario.audits()).extracting(RuntimeAuditRecord::reasonCode)
                .contains(RuntimeAuditRecord.REASON_ENROLLMENT_EXCLUDED);
    }

    @Test
    void enrollmentIsUniquePerMemberWithinOneExecution() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1", "member-2"), nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.enrollmentCount()).isEqualTo(2);
        assertThat(scenario.store.enrollmentCount()).isEqualTo(2);
        assertThat(result.participantCount()).isEqualTo(2);
        assertThat(result.taskCount()).isEqualTo(2);
        assertThat(scenario.store.findParticipants(scenario.tenantId(), result.execution().executionId()))
                .extracting(JourneyParticipant::memberId)
                .containsExactlyInAnyOrder("member-1", "member-2");
    }

    @Test
    void recipientLimitIsResolvedFromTheFrozenGovernanceComponentOnly() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00", 7L));
        scenario.publish();
        String key = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), nominal, nominal.minusSeconds(60)));

        scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(scenario.scriptedAudience().lastRequest().maxRecipients()).isEqualTo(7L);
        assertThat(scenario.scriptedAudience().lastRequest().effectiveLimit())
                .isEqualTo(Math.min(7L, RuntimeVersions.MAX_AUDIENCE_MEMBERS));
    }

    // ------------------------------------------------------------------ bounded failures

    @Test
    void staleAudienceFailsClosedWithZeroWrites() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), nominal.minusSeconds(7200),
                nominal.minusSeconds(10800)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.failed()).isTrue();
        assertThat(result.execution().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.execution().failureCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_STALE);
        assertThat(result.execution().failureField())
                .isEqualTo(RuntimeMaterializationService.FIELD_AUDIENCE_EVIDENCE);
        assertZeroEffect(scenario, result);
    }

    @Test
    void timedOutAudienceFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.outcome(
                AudienceMaterializationOutcome.TIMEOUT, scenario.tenantId(), 0, nominal,
                nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.failed()).isTrue();
        assertThat(result.execution().failureCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_TIMEOUT);
        assertZeroEffect(scenario, result);
    }

    @Test
    void refusedScopeFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.outcome(
                AudienceMaterializationOutcome.SCOPE_REJECTED, scenario.tenantId(), 0, nominal,
                nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_SCOPE_REJECTED);
        assertZeroEffect(scenario, result);
    }

    @Test
    void hostThatCannotAnswerAtAllFailsClosedWithAQueryFailure() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        scenario.withScriptedAudience(ScriptedAudiencePort.failing());

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_QUERY_FAILED);
        assertZeroEffect(scenario, result);
    }

    @Test
    void overLimitAudienceFailsClosedWithoutTruncatingTheSet() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00", 5L));
        scenario.publish();
        String key = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(),
                List.of("member-1", "member-2", "member-3", "member-4", "member-5", "member-6"),
                nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.failed()).isTrue();
        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_OVER_LIMIT);
        assertThat(result.execution().failureField())
                .isEqualTo(RuntimeMaterializationService.FIELD_RECIPIENT_LIMIT);
        assertThat(result.execution().candidateCount()).isEqualTo(6);
        assertThat(result.snapshotMemberCount()).isZero();
        assertThat(scenario.store.participantCount()).isZero();
        assertThat(scenario.store.enrollmentCount()).isZero();
        assertThat(scenario.store.taskCount()).isZero();
    }

    @Test
    void hostDeclaredOverLimitFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.outcome(
                AudienceMaterializationOutcome.OVER_LIMIT, scenario.tenantId(), 5, nominal,
                nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_OVER_LIMIT);
        assertZeroEffect(scenario, result);
    }

    @Test
    void duplicateMemberEvidenceFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.completeWithClaimedCount(
                scenario.tenantId(), List.of("member-1", "member-1"), 2, nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID);
        assertZeroEffect(scenario, result);
    }

    @Test
    void countDisagreeingWithTheReturnedSetFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.completeWithClaimedCount(
                scenario.tenantId(), List.of("member-1"), 9, nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureField())
                .isEqualTo(RuntimeMaterializationService.FIELD_AUDIENCE_EVIDENCE);
        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID);
        assertZeroEffect(scenario, result);
    }

    @Test
    void crossTenantEvidenceFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                "another-tenant", List.of("member-1"), nominal, nominal.minusSeconds(60)));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID);
        assertZeroEffect(scenario, result);
    }

    @Test
    void evidenceAnsweredUnderAnotherFreshnessPolicyFailsClosed() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.completeUnderPolicy(
                scenario.tenantId(), List.of("member-1"), nominal, nominal.minusSeconds(60), 7200L));

        MaterializationResult result = scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(result.execution().failureCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID);
        assertZeroEffect(scenario, result);
    }

    // ------------------------------------------------------------------ state guards

    @Test
    void materializingTheSameExecutionTwiceIsRefused() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), nominal, nominal.minusSeconds(60)));
        scenario.materialization().materialize(scenario.tenantId(), key);

        RuntimeBoundaryException failure = failureOf(
                () -> scenario.materialization().materialize(scenario.tenantId(), key));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_EXECUTION_NOT_MATERIALIZABLE);
        assertThat(scenario.store.snapshotMemberCount()).isEqualTo(1);
    }

    @Test
    void materializingAnUnknownExecutionIsABoundedFailure() {
        RuntimeScenario scenario = deliveredOnce();

        RuntimeBoundaryException failure = failureOf(
                () -> scenario.materialization().materialize(scenario.tenantId(), "unknown-key"));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND);
    }

    @Test
    void materializingAnotherTenantsExecutionIsNotFound() {
        RuntimeScenario scenario = deliveredOnce();
        String key = onlyKey(scenario);

        RuntimeBoundaryException failure = failureOf(
                () -> scenario.materialization().materialize("another-tenant", key));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND);
        assertThat(scenario.store.snapshotMemberCount()).isZero();
    }

    private static void assertZeroEffect(RuntimeScenario scenario, MaterializationResult result) {
        assertThat(result.snapshotMemberCount()).isZero();
        assertThat(result.enrollmentCount()).isZero();
        assertThat(result.participantCount()).isZero();
        assertThat(result.taskCount()).isZero();
        assertThat(result.counters().enrolledCount()).isZero();
        assertThat(scenario.store.snapshotMemberCount()).isZero();
        assertThat(scenario.store.enrollmentCount()).isZero();
        assertThat(scenario.store.participantCount()).isZero();
        assertThat(scenario.store.taskCount()).isZero();
        assertThat(scenario.audits()).extracting(RuntimeAuditRecord::reasonCode)
                .contains(RuntimeAuditRecord.REASON_MATERIALIZATION_FAILED);
    }
}