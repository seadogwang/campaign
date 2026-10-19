package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import com.company.campaign.runtime.testsupport.ScriptedCampaignStatePort;
import com.company.campaign.runtime.testsupport.ScriptedFactPort;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 recovery, guard and aggregation tests: bounded retry and dead-letter, the pause guard and its
 * resume, an existing Execution's frozen version, terminal aggregation, and tenant isolation.
 */
class RuntimeRecoveryTest {

    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final String MEMBER = "member-1";
    private static final String FACT = "member.tier";
    private static final ContentHash HASH = RuntimeSupport.seedHash("recovery-evidence");

    private static RuntimeJourneyScenario conditionScenario() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(
                RuntimeJourneys.condition(
                        FACT, FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                MEMBER);
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());
        return scenario;
    }

    // ------------------------------------------------------------------ retry and dead-letter

    @Test
    void aTechnicalFailureConsumesTheThreeBoundedAttemptsWithTheFrozenBackoffThenDeadLetters() {
        RuntimeJourneyScenario scenario = conditionScenario();
        scenario.facts.behave(ScriptedFactPort.timeout());
        Instant start = Instant.parse(RuntimeJourneyScenario.ENROLLED_AT);
        RuntimeWorker worker = scenario.worker();

        RuntimeNodeResult first = worker.process(worker.claimNext("worker-1").task());
        assertThat(first.disposition()).isEqualTo(RuntimeNodeDisposition.RETRY_SCHEDULED);
        assertThat(first.task().status()).isEqualTo(RuntimeTaskStatus.DUE);
        assertThat(first.task().availableAt()).isEqualTo(start.plusSeconds(5));
        assertThat(first.nodeExecution().status()).isEqualTo(NodeExecutionStatus.RETRY_PENDING);
        assertThat(first.participant().status()).isEqualTo(ParticipantStatus.RUNNING);
        assertThat(conditionAttempts(scenario))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.attemptNumber()).isEqualTo(1);
                    assertThat(attempt.outcomeCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_FACT_TIMEOUT);
                });

        scenario.clock.set(first.task().availableAt());
        RuntimeNodeResult second = worker.process(worker.claimNext("worker-1").task());
        assertThat(second.disposition()).isEqualTo(RuntimeNodeDisposition.RETRY_SCHEDULED);
        assertThat(second.task().attemptNumber()).isEqualTo(2);
        assertThat(second.task().availableAt()).isEqualTo(start.plusSeconds(35));

        scenario.clock.set(second.task().availableAt());
        RuntimeNodeResult third = worker.process(worker.claimNext("worker-1").task());

        assertThat(third.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(third.task().status()).isEqualTo(RuntimeTaskStatus.DEAD_LETTER);
        assertThat(third.task().reasonCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_ATTEMPTS_EXHAUSTED);
        assertThat(third.participant().status()).isEqualTo(ParticipantStatus.NEEDS_REVIEW);
        assertThat(third.execution().status()).isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(third.counters().needsReviewCount()).isEqualTo(1);
        assertThat(conditionAttempts(scenario))
                .hasSize(3);
        assertThat(scenario.audits()).anySatisfy(audit -> {
            assertThat(audit.reasonCode()).isEqualTo(RuntimeAuditRecord.REASON_RETRY_SCHEDULED);
        });
        assertThat(scenario.audits()).anySatisfy(audit -> {
            assertThat(audit.reasonCode()).isEqualTo(RuntimeAuditRecord.REASON_DEAD_LETTERED);
        });
    }

    @Test
    void aFactPortThatCannotAnswerIsATechnicalFailureAndNotABusinessOne() {
        RuntimeJourneyScenario scenario = conditionScenario();
        scenario.facts.behave(ScriptedFactPort.failing());

        RuntimeNodeResult outcome = scenario.worker().process(
                scenario.worker().claimNext("worker-1").task());

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.RETRY_SCHEDULED);
        assertThat(outcome.task().reasonCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE);
    }

    @Test
    void aNonRetryableBusinessOutcomeSpendsNoAttemptAndNeverBecomesFalse() {
        RuntimeJourneyScenario scenario = conditionScenario();
        RuntimeWorker worker = scenario.worker();
        Instant before = scenario.clock.instant();

        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(outcome.task().status()).isEqualTo(RuntimeTaskStatus.DEAD_LETTER);
        assertThat(outcome.participant().currentNodeLogicalId()).isEqualTo("c");
        assertThat(conditionAttempts(scenario))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.outcomeCode())
                        .isEqualTo(RuntimeErrorCodes.RUNTIME_CONDITION_UNKNOWN));
        assertThat(scenario.clock.instant()).isEqualTo(before);
        // A non-retryable outcome leaves no work behind to try again.
        assertThat(scenario.tasks()).noneSatisfy(task ->
                assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE));
    }

    @Test
    void aFailedRetryPolicyIsUnrepresentable() {
        assertThatThrownBy(() -> RuntimeRetryPolicy.of(3, List.of(Duration.ofSeconds(5))))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue(
                        "code", RuntimeErrorCodes.RUNTIME_RETRY_POLICY_INVALID);
        assertThatThrownBy(() -> RuntimeRetryPolicy.of(0, List.of()))
                .isInstanceOf(RuntimeBoundaryException.class);
        assertThat(RuntimeRetryPolicy.defaults().maxAttempts()).isEqualTo(3);
        assertThat(RuntimeRetryPolicy.defaults().delayBeforeAttempt(2)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RuntimeRetryPolicy.defaults().delayBeforeAttempt(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RuntimeRetryPolicy.defaults().isLastAttempt(3)).isTrue();
    }

    private static List<NodeAttempt> conditionAttempts(RuntimeJourneyScenario scenario) {
        String nodeExecutionId = NodeExecution.nodeExecutionId(
                RuntimeJourneyScenario.TENANT, scenario.executionId,
                scenario.participant(MEMBER).participantId(), "c");
        return scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId).stream()
                .filter(attempt -> attempt.nodeExecutionId().equals(nodeExecutionId))
                .toList();
    }

    // ------------------------------------------------------------------ aggregation

    @Test
    void theExecutionAggregateIsDeterministicOverEveryTerminalMix() {
        assertThat(RuntimeAggregation.status(List.of())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(RuntimeAggregation.status(List.of(completed(), exited()))).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(RuntimeAggregation.status(List.of(completed(), failed())))
                .isEqualTo(ExecutionStatus.PARTIAL_FAILED);
        assertThat(RuntimeAggregation.status(List.of(exited(), failed())))
                .isEqualTo(ExecutionStatus.PARTIAL_FAILED);
        assertThat(RuntimeAggregation.status(List.of(failed(), failed()))).isEqualTo(ExecutionStatus.FAILED);
        assertThat(RuntimeAggregation.status(List.of(completed(), needsReview())))
                .isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(RuntimeAggregation.status(List.of(completed(), waiting()))).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(RuntimeAggregation.status(List.of(completed(), running()))).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(RuntimeAggregation.status(List.of(created()))).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(RuntimeAggregation.hasUnresolved(List.of(completed(), waiting()))).isTrue();
        assertThat(RuntimeAggregation.hasUnresolved(List.of(completed(), failed()))).isFalse();

        ExecutionCounters counters = RuntimeAggregation.counters(
                runningExecution(), List.of(completed(), failed()), 4L);
        assertThat(counters.completedCount()).isEqualTo(1);
        assertThat(counters.failedCount()).isEqualTo(1);
        assertThat(counters.runningCount()).isZero();
        assertThat(counters.revision()).isEqualTo(4L);
        assertThat(counters.enrolledCount()).isEqualTo(2);
    }

    @Test
    void anExecutionMustNotCompleteOverAnUnresolvedParticipant() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), MEMBER);
        RuntimeTask leased = scenario.worker().claimNext("worker-1").task();
        Instant at = scenario.clock.instant();
        CampaignExecution completed = scenario.execution()
                .transitioned(ExecutionStatus.COMPLETED, 1, 1, null, null, at);
        JourneyParticipant running = scenario.participant(MEMBER);

        assertThatThrownBy(() -> scenario.store.commitNodeCompletion(
                scenario.completion(
                        leased, completed, running, leased.leaseToken(), running.revision(), at)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID);

        assertThat(scenario.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId)).isEmpty();
    }

    // ------------------------------------------------------------------ pause, resume and version pins

    @Test
    void aPauseGuardsTheNextTaskAndAResumeReleasesOnlyWorkThatIsAlreadyDue() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), MEMBER);
        RuntimeWorker worker = scenario.worker();
        RuntimeTask start = worker.claimNext("worker-1").task();
        scenario.campaignStates.pause();

        RuntimeNodeResult outcome = worker.process(start);

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(outcome.nextTaskGuarded()).isTrue();
        assertThat(scenario.tasks()).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(RuntimeTaskKind.NODE);
            assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DEFERRED);
            assertThat(task.availableAt()).isEqualTo(Instant.parse(RuntimeJourneyScenario.ENROLLED_AT));
        });
        assertThat(worker.claimNext("worker-1").outcome()).isEqualTo(RuntimeClaimOutcome.PAUSED);
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_PAUSE_GUARDED));

        scenario.campaignStates.resume();
        assertThat(worker.releaseGuarded(RuntimeJourneyScenario.TENANT, RuntimeJourneyScenario.CAMPAIGN))
                .isEqualTo(1);
        assertThat(scenario.tasks()).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(RuntimeTaskKind.NODE);
            assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE);
            assertThat(task.availableAt()).isEqualTo(Instant.parse(RuntimeJourneyScenario.ENROLLED_AT));
        });

        RuntimeNodeResult terminated = worker.process(worker.claimNext("worker-1").task());
        assertThat(terminated.disposition()).isEqualTo(RuntimeNodeDisposition.TERMINATED);
        // The already successful Start work is never resent.
        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(2);
    }

    @Test
    void anExistingExecutionKeepsItsFrozenVersionAfterANewerPublication() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), MEMBER);
        ContentHash frozen = scenario.execution().fullDefinitionHash();
        ContentHash olderNodePayload =
                scenario.journey.node(scenario.journey.startNodeLogicalId()).payloadHash();

        scenario.campaignStates.published(RuntimeSupport.seedHash("newer-publication"));

        RuntimeNodeResult outcome =
                scenario.worker().process(scenario.worker().claimNext("worker-1").task());

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(outcome.nodeExecution().nodePayloadHash()).isEqualTo(olderNodePayload);
        assertThat(scenario.execution().fullDefinitionHash()).isEqualTo(frozen);
        assertThat(scenario.execution().journeyHash()).isEqualTo(scenario.journey.contentHash());
    }

    // ------------------------------------------------------------------ host state and tenancy

    @Test
    void anUnverifiableCampaignStateStartsNothingAndFailsClosed() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), MEMBER);
        scenario.campaignStates.absent();

        RuntimeClaimResult refused = scenario.worker().claimNext(RuntimeJourneyScenario.TENANT, "worker-1");
        assertThat(refused.outcome()).isEqualTo(RuntimeClaimOutcome.UNVERIFIED);
        assertThat(refused.task()).isNull();
        assertThat(scenario.startTask(MEMBER).status()).isEqualTo(RuntimeTaskStatus.DUE);

        scenario.campaignStates.present();
        RuntimeWorker worker = scenario.worker();
        RuntimeTask leased = worker.claimNext("worker-1").task();
        scenario.campaignStates.absent();

        RuntimeNodeResult outcome = worker.process(leased);

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(outcome.task().reasonCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_CAMPAIGN_STATE_UNVERIFIABLE);
        assertThat(outcome.execution().status()).isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_CAMPAIGN_STATE_UNVERIFIED));
    }

    @Test
    void oneTenantCannotSeeClaimAdvanceOrEnrollAnotherTenantsWork() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), MEMBER);
        String other = "other-tenant";

        assertThat(scenario.worker().claimNext(other, "worker-1").outcome())
                .isEqualTo(RuntimeClaimOutcome.NONE_DUE);
        assertThat(scenario.store.findTask(other, scenario.startTask(MEMBER).taskId())).isEmpty();
        assertThat(scenario.store.findExecution(other, RuntimeJourneyScenario.EXECUTION_KEY)).isEmpty();
        assertThat(scenario.store.findExecutionById(other, scenario.executionId)).isEmpty();
        assertThat(scenario.store.findParticipants(other, scenario.executionId)).isEmpty();
        assertThat(scenario.store.findTasks(other, scenario.executionId)).isEmpty();
        assertThat(scenario.store.findCounters(other, scenario.executionId)).isEmpty();
        assertThat(scenario.store.findAudits(other)).isEmpty();
        assertThatThrownBy(() -> scenario.worker().process(scenario.startTask(MEMBER)))
                .isInstanceOf(RuntimeBoundaryException.class);

        // A projection for another Campaign of the same tenant proves nothing about this Execution's Campaign.
        scenario.withCampaignState(new ScriptedCampaignStatePort(
                RuntimeJourneyScenario.TENANT, RuntimeJourneyScenario.PROGRAM, "another-campaign",
                scenario.definition.contentHash(), AT));
        assertThat(scenario.worker().claimNext(RuntimeJourneyScenario.TENANT, "worker-1").outcome())
                .isEqualTo(RuntimeClaimOutcome.UNVERIFIED);
    }

    // ------------------------------------------------------------------ aggregation fixtures

    private static CampaignExecution runningExecution() {
        CampaignExecution created = CampaignExecution.created(
                "exec-key-1", "tenant-1", "program-1", "campaign-1", "definition-1", HASH, 1L, HASH, HASH,
                AT, AT, AT);
        return created.transitioned(ExecutionStatus.RUNNING, 2, 2, null, null, AT);
    }

    private static JourneyParticipant created() {
        return JourneyParticipant.created(
                "tenant-1", "exec-1", "campaign-1", "definition-1", HASH, HASH, "member-1", "node-1", AT);
    }

    private static JourneyParticipant running() {
        return created().transitioned(ParticipantStatus.RUNNING, "node-1", null, null, null, AT);
    }

    private static JourneyParticipant waiting() {
        return running().transitioned(
                ParticipantStatus.WAITING, "node-1", RuntimeVersions.WAIT_REASON_DURATION,
                AT.plusSeconds(3600), null, AT);
    }

    private static JourneyParticipant completed() {
        return running().transitioned(ParticipantStatus.COMPLETED, "node-1", null, null, null, AT);
    }

    private static JourneyParticipant exited() {
        return running().transitioned(ParticipantStatus.EXITED, "node-1", null, null, null, AT);
    }

    private static JourneyParticipant failed() {
        return running().transitioned(ParticipantStatus.FAILED, "node-1", null, null, null, AT);
    }

    private static JourneyParticipant needsReview() {
        return created().transitioned(ParticipantStatus.NEEDS_REVIEW, "node-1", null, null, null, AT);
    }
}
