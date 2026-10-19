package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 budget tests: one REWARD action reserves its exact conservative exposure before the Provider is
 * called, holds it while the remote outcome is uncertain, finalizes it exactly once on success, releases it
 * exactly once on definitive failure, and never overspends; a MESSAGE action touches no reward budget at all.
 */
class ActionBudgetTest {

    private static final String WORKER = "action-worker";

    @Test
    void aRewardReservesItsExactConservativeExposureBeforeAnyProviderCall() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());

        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
        assertThat(scenario.rewards.submissionCount(scenario.idempotencyKey())).isZero();
        BudgetReservation reservation = scenario.store
                .findReservation(RuntimeJourneyScenario.TENANT, preparation.action().actionId())
                .orElseThrow();
        assertThat(reservation.state()).isEqualTo(BudgetReservationState.HELD);
        assertThat(reservation.grantCount()).isEqualTo(2);
        assertThat(reservation.currency()).isEqualTo("CNY");
        assertThat(reservation.faceValue()).isEqualByComparingTo(new BigDecimal("20.0000"));

        BudgetLedger ledger = scenario.store
                .findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)
                .orElseThrow();
        assertThat(ledger.heldGrants()).isEqualTo(2);
        assertThat(ledger.finalizedGrants()).isZero();
        assertThat(ledger.releasedGrants()).isZero();
        assertThat(ledger.maxRewardGrants()).isEqualTo(100);
    }

    @Test
    void aMessageActionCreatesNoRewardReservationAndNoLedger() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());

        assertThat(scenario.store.findReservation(
                RuntimeJourneyScenario.TENANT, preparation.action().actionId())).isEmpty();
        assertThat(scenario.store.findBudgetLedger(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)).isEmpty();

        scenario.step(WORKER);
        assertThat(scenario.store.findBudgetLedger(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)).isEmpty();
    }

    @Test
    void aRewardFinalizesItsReservationExactlyOnceOnSuccess() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        ActionOutcome outcome = scenario.step(WORKER);

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        BudgetReservation reservation = scenario.store
                .findReservation(RuntimeJourneyScenario.TENANT, preparation.action().actionId())
                .orElseThrow();
        assertThat(reservation.state()).isEqualTo(BudgetReservationState.FINALIZED);
        assertThat(reservation.grantCount()).isEqualTo(2);

        BudgetLedger ledger = scenario.store
                .findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)
                .orElseThrow();
        assertThat(ledger.heldGrants()).isZero();
        assertThat(ledger.finalizedGrants()).isEqualTo(2);
        assertThat(ledger.finalizedFaceValue()).isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    @Test
    void aRewardReleasesItsReservationExactlyOnceOnADefinitiveFailure() {
        String worker = "action-worker";
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        scenario.rewards.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.DEFINITIVE_REJECTION);
        ActionOutcome outcome = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            outcome = scenario.step(worker);
        }
        assertThat(outcome).isNotNull();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.DEFINITELY_FAILED);

        BudgetReservation reservation = scenario.store
                .findReservation(RuntimeJourneyScenario.TENANT, outcome.action().actionId())
                .orElseThrow();
        assertThat(reservation.state()).isEqualTo(BudgetReservationState.RELEASED);
        BudgetLedger ledger = scenario.store
                .findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)
                .orElseThrow();
        assertThat(ledger.heldGrants()).isZero();
        assertThat(ledger.finalizedGrants()).isZero();
        assertThat(ledger.releasedGrants()).isEqualTo(2);
        assertThat(ledger.releasedFaceValue()).isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    @Test
    void aRewardKeepsItsReservationHeldWhileVerifyingAndNeedingReview() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        String key = scenario.idempotencyKey();
        scenario.rewards.withBehaviour(key, SimulatedProviderBehaviour.DELAYED_RECONCILIATION)
                .withDelayedReconciliations(3);

        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);
        assertThat(scenario.store.findReservation(RuntimeJourneyScenario.TENANT, first.action().actionId())
                .orElseThrow().state()).isEqualTo(BudgetReservationState.HELD);
        assertThat(scenario.store.findBudgetLedger(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId).orElseThrow().heldGrants())
                .isEqualTo(2);

        ActionOutcome second = scenario.step(WORKER);
        assertThat(second.disposition()).isEqualTo(ActionDisposition.UNKNOWN_VERIFYING);
        assertThat(scenario.step(WORKER).disposition()).isEqualTo(ActionDisposition.UNKNOWN_VERIFYING);
        ActionOutcome third = scenario.step(WORKER);
        assertThat(third.disposition()).isEqualTo(ActionDisposition.NEEDS_REVIEW);
        // A remote effect may exist, so the exact exposure stays held and the ledger never moves.
        assertThat(scenario.store.findReservation(RuntimeJourneyScenario.TENANT, third.action().actionId())
                .orElseThrow().state()).isEqualTo(BudgetReservationState.HELD);
        BudgetLedger ledger = scenario.store
                .findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId).orElseThrow();
        assertThat(ledger.heldGrants()).isEqualTo(2);
        assertThat(ledger.finalizedGrants()).isZero();
        assertThat(ledger.releasedGrants()).isZero();
    }

    @Test
    void aRewardBeyondTheFrozenGrantLimitIsRefusedRatherThanTruncated() {
        ActionScenarios.Scenario scenario = ActionScenarios.rewardWithLimits(1, "1000.00", "CNY");
        assertThatThrownBy(() -> scenario.service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_BUDGET_LIMITED);
        assertThat(scenario.store.actionCount()).isZero();
        assertThat(scenario.store.findBudgetLedger(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId)).isEmpty();
    }

    @Test
    void aRewardBeyondTheFrozenFaceValueLimitIsRefusedRatherThanTruncated() {
        ActionScenarios.Scenario scenario = ActionScenarios.rewardWithLimits(100, "10.00", "CNY");
        assertThatThrownBy(() -> scenario.service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_BUDGET_LIMITED);
        assertThat(scenario.store.actionCount()).isZero();
    }

    @Test
    void concurrentRewardsAtTheFrozenLimitAcceptExactlyOneAndRefuseTheOther() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward(
                2, "20.00", "CNY", List.of("member-reward-1", "member-reward-2"));
        List<com.company.campaign.runtime.ActionHandoff> handoffs = scenario.handoffs();
        assertThat(handoffs).hasSize(2);
        int accepted = 0;
        int refused = 0;
        for (com.company.campaign.runtime.ActionHandoff handoff : handoffs) {
            try {
                scenario.service.prepare(handoff);
                accepted++;
            } catch (ActionBoundaryException limited) {
                assertThat(limited.code()).isEqualTo(ActionErrorCodes.ACTION_BUDGET_LIMITED);
                refused++;
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        BudgetLedger ledger = scenario.store
                .findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId).orElseThrow();
        // The deterministic outcome is one acceptance and one refusal: never truncation, never overspend.
        assertThat(ledger.heldGrants()).isEqualTo(2);
        assertThat(ledger.availableGrants()).isZero();
    }

    @Test
    void aRewardWithoutTheFrozenGovernanceDeclarationCanNeverBeReserved() {
        ActionScenarios.Scenario scenario = ActionScenarios.rewardWithoutGovernance();
        assertThatThrownBy(() -> scenario.service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_MISSING);
        assertThat(scenario.store.actionCount()).isZero();
    }
}