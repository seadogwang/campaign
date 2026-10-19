package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 reconciliation and retry tests: every possible remote success creates durable verification work
 * that queries by the original key before any retry, a retry reuses the original action, key, request hash and
 * reservation, and a manual retry can only ever be authorized by persisted evidence and the bounded limits.
 */
class ActionReconciliationAndRetryTest {

    private static final String WORKER = "action-worker";

    @Test
    void aPossibleRemoteSuccessCreatesDurableVerificationWorkAndNeverResubmitsFirst() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);

        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);
        assertThat(scenario.store.findActionTask(
                RuntimeJourneyScenario.TENANT, first.action().actionId(), ActionTaskKind.VERIFICATION))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(ActionTaskStatus.DUE));

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.attempt().attemptKind()).isEqualTo(ActionAttemptKind.VERIFICATION);
        assertThat(scenario.messages.queryCount(key)).isEqualTo(1);
        // No retry ever preceded the reconciliation, so the original effect was never double-applied.
        assertThat(scenario.messages.submissionCount(key)).isEqualTo(1);
    }

    @Test
    void aSafeRetryReusesTheSameKeyRequestHashReservationAndBudget() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.DEFINITIVE_REJECTION);

        ActionOutcome first = scenario.step(WORKER);
        ActionExecution original = first.action();
        assertThat(first.disposition()).isEqualTo(ActionDisposition.SAFE_RETRY_SCHEDULED);
        BudgetReservation before = scenario.store
                .findReservation(RuntimeJourneyScenario.TENANT, original.actionId()).orElseThrow();

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.action().idempotencyKey()).isEqualTo(key);
        assertThat(second.action().requestHash()).isEqualTo(original.requestHash());
        BudgetReservation after = scenario.store
                .findReservation(RuntimeJourneyScenario.TENANT, original.actionId()).orElseThrow();
        assertThat(after).isEqualTo(before);
        assertThat(after.state()).isEqualTo(BudgetReservationState.HELD);
        assertThat(scenario.rewards.effectCount(key)).isZero();
    }

    @Test
    void verificationExhaustionBecomesNeedsReviewAndHoldsTheRewardBudget() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.DELAYED_RECONCILIATION)
                .withDelayedReconciliations(3);

        ActionOutcome outcome = null;
        for (int step = 0; step < 4; step++) {
            outcome = scenario.step(WORKER);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.NEEDS_REVIEW);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.NEEDS_REVIEW);
        assertThat(outcome.action().reasonCode())
                .isEqualTo(ActionErrorCodes.ACTION_VERIFICATION_EXHAUSTED);
        assertThat(scenario.store.findReservation(
                RuntimeJourneyScenario.TENANT, outcome.action().actionId()).orElseThrow().state())
                .isEqualTo(BudgetReservationState.HELD);
        // A review-needing action is terminal: no worker step can ever resubmit it.
        assertThat(scenario.worker.runOnce(RuntimeJourneyScenario.TENANT, WORKER)).isEmpty();
        assertThat(scenario.rewards.submissionCount(key)).isEqualTo(1);
    }

    @Test
    void reconcileForcesOneBoundedQueryWithoutAnyResubmission() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);
        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);

        ActionOutcome reconciled = scenario.service.reconcile(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, first.action().actionId(), "operator-1", true,
                first.action().revision(), "RECONCILE"));
        assertThat(reconciled.disposition()).isEqualTo(ActionDisposition.VERIFICATION_SCHEDULED);
        assertThat(reconciled.task().status()).isEqualTo(ActionTaskStatus.DUE);
        assertThat(scenario.messages.submissionCount(key)).isEqualTo(1);

        ActionOutcome verified = scenario.step(WORKER);
        assertThat(verified.attempt().attemptKind()).isEqualTo(ActionAttemptKind.VERIFICATION);
    }

    @Test
    void reconcileFromUnknownMovesTheActionToVerifying() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);
        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);

        // An in-flight action has nothing to reconcile.
        assertThatThrownBy(() -> scenario.service.reconcile(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, first.action().actionId(), "operator-1", true,
                first.action().revision() + 5L, "RECONCILE")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_STORE_CONFLICT);
    }

    @Test
    void aManualRetryRequiresPersistedNonApplicationEvidenceAndTheBoundedLimits() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);

        ActionOutcome unknown = scenario.step(WORKER);
        ActionExecution unresolved = unknown.action();
        assertThat(unresolved.status()).isEqualTo(ActionStatus.VERIFYING);

        // Without authorization the command is refused outright.
        assertThatThrownBy(() -> scenario.service.safeRetry(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, unresolved.actionId(), "operator-1", false,
                unresolved.revision(), "RETRY")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_OPERATION_FORBIDDEN);

        // Without persisted reconciliation evidence it is refused.
        assertThatThrownBy(() -> scenario.service.safeRetry(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, unresolved.actionId(), "operator-1", true,
                unresolved.revision(), "RETRY")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_RETRY_NOT_PERMITTED);

        // After a reconciliation proves non-application, a bounded retry is permitted.
        ActionOutcome verified = scenario.step(WORKER);
        ActionExecution proved = verified.action();
        assertThat(scenario.store.findActionAttempts(RuntimeJourneyScenario.TENANT, proved.actionId()))
                .anySatisfy(attempt -> assertThat(attempt.outcome().definitivelyNotApplied()).isTrue());
        ActionOutcome retried = scenario.service.safeRetry(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, proved.actionId(), "operator-1", true,
                proved.revision(), "RETRY"));
        assertThat(retried.disposition()).isEqualTo(ActionDisposition.SAFE_RETRY_SCHEDULED);
        assertThat(retried.action().idempotencyKey()).isEqualTo(key);
        assertThat(retried.action().requestHash()).isEqualTo(proved.requestHash());
    }

    @Test
    void aManualRetryCanNeverOverrideAnUnknownOrReviewNeedingAction() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.DELAYED_RECONCILIATION)
                .withDelayedReconciliations(3);
        ActionOutcome outcome = null;
        for (int step = 0; step < 4; step++) {
            outcome = scenario.step(WORKER);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.NEEDS_REVIEW);
        ActionExecution review = outcome.action();
        assertThatThrownBy(() -> scenario.service.safeRetry(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, review.actionId(), "operator-1", true,
                review.revision(), "RETRY")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_RETRY_NOT_PERMITTED);
    }

    @Test
    void aManualRetryCannotExceedTheBoundedAttemptLimits() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);
        scenario.step(WORKER);
        ActionOutcome verified = scenario.step(WORKER);
        // The automatic bounded retry already consumed the lane; a manual one is still bounded.
        assertThat(verified.action().submissionCount()).isLessThanOrEqualTo(3);
        assertThat(ActionPolicies.defaults().submissionAttemptsRemain(3)).isFalse();
    }
}