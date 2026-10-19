package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.ExecutionStatus;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.runtime.NodeExecution;
import com.company.campaign.runtime.NodeExecutionStatus;
import com.company.campaign.runtime.ParticipantStatus;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeTask;
import com.company.campaign.runtime.RuntimeTaskKind;
import com.company.campaign.runtime.RuntimeTaskStatus;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T23 Runtime-integration tests: an action outcome advances the frozen Journey exactly once, marks the
 * external-action node honestly, enqueues the exact frozen successor, and keeps the Execution unresolved while
 * a remote effect is unknown or needs review.
 */
class ActionRuntimeIntegrationTest {

    private static final String WORKER = "action-worker";

    @Test
    void aConfirmedEffectAdvancesTheParticipantOnceAndEnqueuesTheExactSuccessor() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionOutcome outcome = scenario.step(WORKER);

        String participantId = scenario.handoff().participantId();
        String nodeLogicalId = scenario.handoff().nodeLogicalId();
        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, participantId, nodeLogicalId)
                .orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.SUCCEEDED);

        JourneyParticipant participant = scenario.runtime.participant("member-message-1");
        assertThat(participant.currentNodeLogicalId()).isEqualTo("e");
        assertThat(participant.lastAcceptedActionInstant()).isNotNull();
        assertThat(participant.revision()).isEqualTo(2L);

        assertThat(scenario.runtime.store.findTasks(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId))
                .anySatisfy(task -> {
                    assertThat(task.nodeLogicalId()).isEqualTo("e");
                    assertThat(task.kind()).isEqualTo(RuntimeTaskKind.NODE);
                });
        // The one handed-off task is retired, so a redelivered handoff can never be prepared again.
        RuntimeTask handoffTask = scenario.runtime.store.findTask(
                RuntimeJourneyScenario.TENANT,
                RuntimeTask.taskId(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, participantId,
                        nodeLogicalId, RuntimeTaskKind.ACTION_READY))
                .orElseThrow();
        assertThat(handoffTask.status()).isEqualTo(RuntimeTaskStatus.COMPLETED);
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(outcome.toString()).doesNotContain("member-message-1");
    }

    @Test
    void aDefinitiveFailureFailsTheNodeAndTheParticipantAndClaimsNoAcceptance() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.DEFINITIVE_REJECTION);
        ActionOutcome outcome = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            outcome = scenario.step(WORKER);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);

        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, scenario.handoff().participantId(),
                scenario.handoff().nodeLogicalId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.FAILED);
        JourneyParticipant participant = scenario.runtime.participant("member-reward-1");
        assertThat(participant.status()).isEqualTo(ParticipantStatus.FAILED);
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(scenario.runtime.counters().failedCount()).isEqualTo(1);
        assertThat(scenario.runtime.counters().completedCount()).isZero();

        ActionResultCounters counters = scenario.store
                .findActionResultCounters(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)
                .orElseThrow();
        assertThat(counters.rewardsSucceeded()).isZero();
        assertThat(counters.messagesAccepted()).isZero();
        assertThat(counters.definiteFailures()).isEqualTo(1);
    }

    @Test
    void anUnknownOutcomeLeavesTheNodeVerifyingAndTheParticipantWaitingAndTheExecutionUnresolved() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.PENDING_THEN_SUCCESS);
        ActionOutcome outcome = scenario.step(WORKER);

        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, scenario.handoff().participantId(),
                scenario.handoff().nodeLogicalId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.VERIFYING);
        JourneyParticipant participant = scenario.runtime.participant("member-message-1");
        assertThat(participant.status()).isEqualTo(ParticipantStatus.WAITING);
        assertThat(participant.waitingReason()).isEqualTo(ActionVersions.WAITING_REASON_ACTION_VERIFY);
        assertThat(participant.currentNodeLogicalId()).isEqualTo(scenario.handoff().nodeLogicalId());
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(scenario.runtime.counters().completedCount()).isZero();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.UNKNOWN_VERIFYING);
        assertThat(scenario.runtime.audits())
                .anySatisfy(audit -> assertThat(audit.reasonCode())
                        .isEqualTo(RuntimeAuditRecord.REASON_ACTION_NODE_VERIFYING));
    }

    @Test
    void aReviewNeedingActionLeavesTheExecutionUnresolvedAndCountedDistinctly() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.DELAYED_RECONCILIATION)
                .withDelayedReconciliations(3);
        ActionOutcome outcome = null;
        for (int step = 0; step < 4; step++) {
            outcome = scenario.step(WORKER);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.NEEDS_REVIEW);

        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, scenario.handoff().participantId(),
                scenario.handoff().nodeLogicalId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.NEEDS_REVIEW);
        assertThat(scenario.runtime.participant("member-message-1").status())
                .isEqualTo(ParticipantStatus.NEEDS_REVIEW);
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(scenario.runtime.counters().completedCount()).isZero();

        ActionResultCounters counters = scenario.store
                .findActionResultCounters(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)
                .orElseThrow();
        assertThat(counters.needsReviewActions()).isEqualTo(1);
        assertThat(counters.unknownActions()).isEqualTo(1);
        assertThat(counters.messagesAccepted()).isZero();
    }

    @Test
    void aConfirmedMessageAcceptanceAndAConfirmedRewardAreCountedSeparately() {
        ActionScenarios.Scenario message = ActionScenarios.message();
        message.step(WORKER);
        ActionResultCounters messageCounters = message.store
                .findActionResultCounters(RuntimeJourneyScenario.TENANT, message.runtime.executionId)
                .orElseThrow();
        assertThat(messageCounters.messagesAccepted()).isEqualTo(1);
        assertThat(messageCounters.rewardsSucceeded()).isZero();

        ActionScenarios.Scenario reward = ActionScenarios.reward();
        reward.step(WORKER);
        ActionResultCounters rewardCounters = reward.store
                .findActionResultCounters(RuntimeJourneyScenario.TENANT, reward.runtime.executionId)
                .orElseThrow();
        assertThat(rewardCounters.rewardsSucceeded()).isEqualTo(1);
        assertThat(rewardCounters.messagesAccepted()).isZero();
    }

    @Test
    void aSubmittedRequestAloneNeverMakesTheNodeSuccessful() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        assertThat(preparation.action().status()).isEqualTo(ActionStatus.RESERVED);
        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, scenario.handoff().participantId(),
                scenario.handoff().nodeLogicalId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.RUNNING);
        assertThat(scenario.runtime.participant("member-message-1").status())
                .isEqualTo(ParticipantStatus.RUNNING);
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
    }
}
