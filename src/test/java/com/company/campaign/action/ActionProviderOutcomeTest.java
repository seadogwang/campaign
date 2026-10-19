package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.runtime.NodeExecution;
import com.company.campaign.runtime.NodeExecutionStatus;
import com.company.campaign.runtime.ParticipantStatus;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T23 Provider-outcome tests: the deterministic simulated Providers reproduce every failure mode the
 * reliability requirements name, and the closed result semantics stay distinguishable.
 */
class ActionProviderOutcomeTest {

    private static final String WORKER = "action-worker";

    @Test
    void immediateSuccessAdvancesTheExactFrozenParticipantOnce() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(outcome.providerOutcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(outcome.attempt().attemptKind()).isEqualTo(ActionAttemptKind.SUBMISSION);
        assertThat(outcome.attempt().providerReference()).isNotBlank();
        assertThat(scenario.messages.effectCount(scenario.idempotencyKey())).isEqualTo(1);

        JourneyParticipant participant = scenario.runtime.participant("member-message-1");
        assertThat(participant.status()).isEqualTo(ParticipantStatus.RUNNING);
        assertThat(participant.currentNodeLogicalId()).isEqualTo("e");
        assertThat(participant.lastAcceptedActionInstant()).isNotNull();
        assertThat(scenario.runtime.execution().status())
                .isEqualTo(com.company.campaign.runtime.ExecutionStatus.RUNNING);
    }

    @Test
    void aDefinitiveRejectionSchedulesABoundedSafeRetryUnderTheSameKey() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.DEFINITIVE_REJECTION);
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SAFE_RETRY_SCHEDULED);
        assertThat(outcome.providerOutcome()).isEqualTo(ProviderOutcome.DEFINITELY_NOT_APPLIED);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.RESERVED);
        assertThat(outcome.action().idempotencyKey()).isEqualTo(scenario.idempotencyKey());
        assertThat(outcome.action().submissionCount()).isEqualTo(1);
        // No Provider submission was made by the retry itself, and the queue holds the bounded next try.
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.store.findActionTask(
                RuntimeJourneyScenario.TENANT, outcome.action().actionId(), ActionTaskKind.SUBMISSION))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(ActionTaskStatus.DUE));
    }

    @Test
    void exhaustedSubmissionsBecomeADefinitiveFailureRatherThanAnUnknown() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.DEFINITIVE_REJECTION);
        ActionOutcome outcome = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            outcome = scenario.step(WORKER);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.DEFINITELY_FAILED);
        assertThat(outcome.action().submissionCount()).isEqualTo(3);
        assertThat(scenario.runtime.participant("member-message-1").status())
                .isEqualTo(ParticipantStatus.FAILED);
    }

    @Test
    void aPendingOutcomeRequiresVerificationAndNeverASecondSubmission() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.PENDING_THEN_SUCCESS);
        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.disposition()).isEqualTo(ActionDisposition.UNKNOWN_VERIFYING);
        assertThat(first.providerOutcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(second.attempt().attemptKind()).isEqualTo(ActionAttemptKind.VERIFICATION);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.messages.queryCount(scenario.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void anUnknownOutcomeIsReconciledToDefinitiveNonApplication() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);
        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.providerOutcome()).isEqualTo(ProviderOutcome.UNKNOWN);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.providerOutcome()).isEqualTo(ProviderOutcome.DEFINITELY_NOT_APPLIED);
        assertThat(second.disposition()).isEqualTo(ActionDisposition.SAFE_RETRY_SCHEDULED);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void aRemoteSuccessWithATimedOutResponseBecomesUnknownAndReconcilesToOneEffect() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.REMOTE_SUCCESS_RESPONSE_TIMEOUT);

        ActionOutcome first = scenario.step(WORKER);
        // The effect exists remotely, the caller learned nothing, and this is never a failure.
        assertThat(first.providerOutcome()).isEqualTo(ProviderOutcome.UNKNOWN);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);
        assertThat(scenario.rewards.effectCount(key)).isEqualTo(1);

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(second.action().status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(scenario.rewards.effectCount(key)).isEqualTo(1);
        assertThat(scenario.rewards.submissionCount(key)).isEqualTo(1);
    }

    @Test
    void delayedReconciliationQueriesRepeatedlyByTheOriginalKey() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.DELAYED_RECONCILIATION)
                .withDelayedReconciliations(1);

        assertThat(scenario.step(WORKER).providerOutcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(scenario.step(WORKER).providerOutcome()).isEqualTo(ProviderOutcome.PENDING);
        ActionOutcome settled = scenario.step(WORKER);
        assertThat(settled.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(scenario.messages.submissionCount(key)).isEqualTo(1);
        assertThat(scenario.messages.queryCount(key)).isEqualTo(2);
    }

    @Test
    void aRewardGrantSucceedsAndMarksTheExternalActionNodeSucceeded() {
        ActionScenarios.Scenario scenario = ActionScenarios.rewardWithLimits(2, "20.00", "CNY");
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        NodeExecution node = scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId,
                scenario.handoff().participantId(), scenario.handoff().nodeLogicalId()).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThat(node.toString()).doesNotContain("member-reward-1");
    }
}
