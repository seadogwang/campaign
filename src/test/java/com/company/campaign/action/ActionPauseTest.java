package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.RuntimeTask;
import com.company.campaign.runtime.RuntimeTaskStatus;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T23 pause tests: a paused Campaign starts no new Provider submission and no new reconciliation, a
 * call already in flight still finishes and persists its result, the next task stays guarded, and a resume
 * releases guarded work without ever resending a successful action.
 */
class ActionPauseTest {

    private static final String WORKER = "action-worker";

    @Test
    void aPausedCampaignStartsNoProviderCallAtAll() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.service.prepare(scenario.handoff());
        scenario.runtime.campaignStates.pause();

        ActionClaimResult claim = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        assertThat(claim.outcome()).isEqualTo(ActionClaimOutcome.PAUSED);
        assertThat(claim.task()).isNull();
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
        assertThat(scenario.store.findActionByKey(
                RuntimeJourneyScenario.TENANT, scenario.idempotencyKey()).orElseThrow().status())
                .isEqualTo(ActionStatus.RESERVED);
        // The durable submission work is retained exactly where it was.
        assertThat(scenario.store.findActionTask(
                RuntimeJourneyScenario.TENANT, scenario.action().actionId(), ActionTaskKind.SUBMISSION))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(ActionTaskStatus.DUE));
    }

    @Test
    void aCallAlreadyInFlightFinishesAndPersistsWhilePausedAndGuardsTheNextTask() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult claim = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        assertThat(claim.claimed()).isTrue();
        assertThat(scenario.store.findActionById(
                RuntimeJourneyScenario.TENANT, claim.task().actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.IN_FLIGHT);

        // The pause happens while the call is already in flight.
        scenario.runtime.campaignStates.pause();
        ActionOutcome outcome = scenario.service.process(claim.task());
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(outcome.action().status()).isEqualTo(ActionStatus.SUCCEEDED);

        List<RuntimeTask> tasks =
                scenario.runtime.store.findTasks(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId);
        RuntimeTask successor = tasks.stream()
                .filter(task -> task.nodeLogicalId().equals("e"))
                .findFirst()
                .orElseThrow();
        assertThat(successor.status()).isEqualTo(RuntimeTaskStatus.DEFERRED);
        assertThat(outcome.toString()).doesNotContain("cancelled");
        assertThat(scenario.messages.effectCount(scenario.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void aResumeReleasesGuardedWorkWithoutResendingASuccessfulAction() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult claim = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        scenario.runtime.campaignStates.pause();
        scenario.service.process(claim.task());
        scenario.runtime.campaignStates.resume();

        int released = scenario.runtime.store.releaseGuardedTasks(
                RuntimeJourneyScenario.TENANT, RuntimeJourneyScenario.CAMPAIGN,
                scenario.runtime.clock.instant(), null);
        assertThat(released).isEqualTo(1);

        // The successful action is never resent: the submission slot stays completed forever.
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.store.findActionTask(
                RuntimeJourneyScenario.TENANT, scenario.action().actionId(), ActionTaskKind.SUBMISSION))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(ActionTaskStatus.COMPLETED));
        // The frozen definition an existing Execution runs against is untouched by a replacement.
        assertThat(scenario.runtime.execution().fullDefinitionHash())
                .isEqualTo(scenario.runtime.definition.contentHash());
    }

    @Test
    void aPausedCampaignNeverStartsANewReconciliationEither() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        String key = scenario.idempotencyKey();
        scenario.messages.withBehaviour(key, SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);
        ActionOutcome first = scenario.step(WORKER);
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);

        scenario.runtime.campaignStates.pause();
        ActionClaimResult claim = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        assertThat(claim.outcome()).isEqualTo(ActionClaimOutcome.PAUSED);
        assertThat(scenario.messages.queryCount(key)).isZero();
    }
}