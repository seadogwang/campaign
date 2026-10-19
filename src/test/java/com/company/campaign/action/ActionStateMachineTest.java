package com.company.campaign.action;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 boundary tests: the closed action, queue, node and budget state machines, the exact business
 * idempotency key and the bounded records that refuse to represent an illegal or overspent state.
 */
class ActionStateMachineTest {

    private static final Instant AT = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void theActionMachineKeepsEveryBusinessOutcomeDistinct() {
        assertThat(ActionStatus.RESERVED.reaches(ActionStatus.IN_FLIGHT)).isTrue();
        assertThat(ActionStatus.IN_FLIGHT.reaches(ActionStatus.SUCCEEDED)).isTrue();
        assertThat(ActionStatus.IN_FLIGHT.reaches(ActionStatus.DEFINITELY_FAILED)).isTrue();
        assertThat(ActionStatus.IN_FLIGHT.reaches(ActionStatus.UNKNOWN)).isTrue();
        assertThat(ActionStatus.UNKNOWN.reaches(ActionStatus.VERIFYING)).isTrue();
        assertThat(ActionStatus.VERIFYING.reaches(ActionStatus.SUCCEEDED)).isTrue();
        assertThat(ActionStatus.VERIFYING.reaches(ActionStatus.DEFINITELY_FAILED)).isTrue();
        assertThat(ActionStatus.VERIFYING.reaches(ActionStatus.UNKNOWN)).isTrue();
        assertThat(ActionStatus.VERIFYING.reaches(ActionStatus.NEEDS_REVIEW)).isTrue();
        // A verified non-application permits one bounded safe retry back through queued work.
        assertThat(ActionStatus.VERIFYING.reaches(ActionStatus.RESERVED)).isTrue();
        assertThat(ActionStatus.IN_FLIGHT.reaches(ActionStatus.RESERVED)).isTrue();
        // A terminal outcome is immutable, and a queued action is never successful directly.
        assertThat(ActionStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ActionStatus.DEFINITELY_FAILED.isTerminal()).isTrue();
        assertThat(ActionStatus.NEEDS_REVIEW.isTerminal()).isTrue();
        assertThat(ActionStatus.RESERVED.reaches(ActionStatus.SUCCEEDED)).isFalse();
        assertThat(ActionStatus.SUCCEEDED.reaches(ActionStatus.IN_FLIGHT)).isFalse();
    }

    @Test
    void onlyAnUnresolvedActionHoldsItsRewardBudgetAndBlocksCompletion() {
        assertThat(ActionStatus.RESERVED.holdsRewardBudget()).isTrue();
        assertThat(ActionStatus.IN_FLIGHT.holdsRewardBudget()).isTrue();
        assertThat(ActionStatus.UNKNOWN.holdsRewardBudget()).isTrue();
        assertThat(ActionStatus.VERIFYING.holdsRewardBudget()).isTrue();
        assertThat(ActionStatus.SUCCEEDED.holdsRewardBudget()).isFalse();
        assertThat(ActionStatus.DEFINITELY_FAILED.holdsRewardBudget()).isFalse();
        assertThat(ActionStatus.NEEDS_REVIEW.holdsRewardBudget()).isFalse();
        assertThat(ActionStatus.UNKNOWN.isUnresolved()).isTrue();
        assertThat(ActionStatus.NEEDS_REVIEW.isUnresolved()).isFalse();
    }

    @Test
    void theQueueMachineIsSeparateFromBusinessStateAndHasNoEdgeOutOfTerminal() {
        assertThat(ActionTaskStatus.DUE.reaches(ActionTaskStatus.LEASED)).isTrue();
        assertThat(ActionTaskStatus.LEASED.reaches(ActionTaskStatus.DUE)).isTrue();
        assertThat(ActionTaskStatus.LEASED.reaches(ActionTaskStatus.COMPLETED)).isTrue();
        assertThat(ActionTaskStatus.DEFERRED.reaches(ActionTaskStatus.DUE)).isTrue();
        assertThat(ActionTaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(ActionTaskStatus.DEAD_LETTER.isTerminal()).isTrue();
        assertThat(ActionTaskStatus.COMPLETED.reaches(ActionTaskStatus.DUE)).isFalse();
    }

    @Test
    void theBudgetLifecycleFinalizesOrReleasesExactlyOnce() {
        assertThat(BudgetReservationState.HELD.reaches(BudgetReservationState.FINALIZED)).isTrue();
        assertThat(BudgetReservationState.HELD.reaches(BudgetReservationState.RELEASED)).isTrue();
        assertThat(BudgetReservationState.FINALIZED.reaches(BudgetReservationState.RELEASED)).isFalse();
        assertThat(BudgetReservationState.RELEASED.reaches(BudgetReservationState.HELD)).isFalse();
        assertThat(BudgetReservationState.HELD.isHeld()).isTrue();
    }

    @Test
    void theExactBusinessKeyIsTheFrozenFourComponentFormAndCarriesNoAttemptNumber() {
        String key = ActionExecution.idempotencyKey(
                "tenant-a", "ptp-1", "node-m", ActionKind.MESSAGE);
        assertThat(key).isEqualTo("tenant-a:ptp-1:node-m:MESSAGE");
        assertThat(key).doesNotContain("attempt");
        assertThat(ActionExecution.idempotencyKey(
                "tenant-a", "ptp-1", "node-m", ActionKind.REWARD))
                .isEqualTo("tenant-a:ptp-1:node-m:REWARD");
    }

    @Test
    void aRewardReservesBudgetAndAMessageDoesNot() {
        assertThat(ActionKind.REWARD.reservesRewardBudget()).isTrue();
        assertThat(ActionKind.MESSAGE.reservesRewardBudget()).isFalse();
        assertThat(ActionKind.MESSAGE.capabilityCode()).isEqualTo("flow.message");
        assertThat(ActionKind.REWARD.capabilityCode()).isEqualTo("flow.reward");
    }

    @Test
    void theRequestHashIsDeterministicAndTheFrozenMaterialExcludesTheRecipient() {
        ActionRequest first = messageRequest("recipient-1");
        ActionRequest second = messageRequest("recipient-2");
        assertThat(first.requestHash()).isEqualTo(messageRequest("recipient-1").requestHash());
        assertThat(first.frozenMaterialHash()).isEqualTo(second.frozenMaterialHash());
        assertThat(first.hasSameFrozenMaterial(second)).isTrue();
        // The request hash covers the exact recipient evidence, so two different targets are two requests.
        assertThat(first.requestHash()).isNotEqualTo(second.requestHash());
    }

    @Test
    void aBudgetLedgerRefusesToRepresentAnOverspendOrATruncation() {
        BudgetLedger ledger = BudgetLedger.empty("tenant-a", "exec-1", "CNY", 3, new BigDecimal("30.00"));
        assertThat(ledger.reserve(3, new BigDecimal("30.00"))).isPresent();
        // One more grant unit is refused rather than truncated.
        BudgetLedger full = ledger.reserve(3, new BigDecimal("30.00")).orElseThrow();
        assertThat(full.reserve(1, new BigDecimal("0.01"))).isEmpty();
        assertThat(full.reserve(1, new BigDecimal("9.99"))).isEmpty();
        // The exact total is unchanged by a finalize or a release.
        assertThat(full.finalize(3, new BigDecimal("30.00")).availableGrants()).isEqualTo(0);
        assertThat(full.release(3, new BigDecimal("30.00")).availableGrants()).isEqualTo(0);
        assertThatThrownBy(() -> full.finalize(4, new BigDecimal("40.00")))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_BUDGET_CONFLICT);
    }

    @Test
    void aProviderCapabilityMustSupportBothGuaranteesAndIsNeverProductionEvidence() {
        ProviderCapability complete = ProviderCapability.of(
                "provider-1", "tenant-a", ActionKind.MESSAGE, true, true, false);
        assertThat(complete.satisfiesSubmission()).isTrue();
        assertThat(complete.satisfiesLiveReady()).isFalse();
        assertThat(complete.fixtureEvidence()).isTrue();
        assertThat(ProviderCapability.of("p", "t", ActionKind.MESSAGE, false, true, false)
                .satisfiesSubmission()).isFalse();
        assertThat(ProviderCapability.of("p", "t", ActionKind.MESSAGE, true, false, false)
                .satisfiesSubmission()).isFalse();
        // Even a complete production declaration is the only thing that could satisfy LIVE_READY.
        assertThat(ProviderCapability.of("p", "t", ActionKind.MESSAGE, true, true, true)
                .satisfiesLiveReady()).isTrue();
    }

    @Test
    void aProviderResultNeverClaimsAnAppliedEffectWithoutABoundedReference() {
        assertThatThrownBy(() -> ProviderResult.of(
                "tenant-a:ptp:node:MESSAGE", ProviderOutcome.SUCCEEDED, null, AT, "PROVIDER_APPLIED"))
                .isInstanceOf(ActionBoundaryException.class);
        assertThatThrownBy(() -> ProviderResult.of(
                "tenant-a:ptp:node:MESSAGE", ProviderOutcome.UNKNOWN, "prvref-1", null, "PROVIDER_UNKNOWN"))
                .isInstanceOf(ActionBoundaryException.class);
        ProviderResult applied = ProviderResult.succeeded(
                "tenant-a:ptp:node:MESSAGE", "prvref-1", AT, "PROVIDER_APPLIED");
        assertThat(applied.outcome().applied()).isTrue();
        assertThat(ProviderResult.unknown("tenant-a:ptp:node:MESSAGE", "PROVIDER_UNKNOWN")
                .outcome().requiresReconciliation()).isTrue();
    }

    @Test
    void aPoliciesScheduleIsBoundedAndComplete() {
        ActionPolicies policies = ActionPolicies.defaults();
        assertThat(policies.maxSubmissionAttempts()).isEqualTo(3);
        assertThat(policies.delayBeforeSubmission(1)).isZero();
        assertThat(policies.delayBeforeSubmission(2)).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(policies.delayBeforeSubmission(3)).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(policies.submissionAttemptsRemain(3)).isFalse();
        assertThat(policies.verificationAttemptsRemain(0)).isTrue();
        assertThatThrownBy(() -> ActionPolicies.of(2, List.of(), 2, List.of()))
                .isInstanceOf(ActionBoundaryException.class);
    }

    private static ActionRequest messageRequest(String recipientRef) {
        return ActionRequest.of(
                ActionKind.MESSAGE, "tenant-a:ptp:node-m:MESSAGE", "tenant-a", "exec-1", "ptp", "node-m",
                "m", "flow.message", "1.0", ActionSupport.seedHash("payload"),
                ActionSupport.seedHash("references"), "EMAIL", "tpl", "tpl-v1",
                ActionSupport.seedHash("template"), null, null, null, null, null, null,
                recipientRef, ActionSupport.seedHash(recipientRef), "route-m");
    }
}