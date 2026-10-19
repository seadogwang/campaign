package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRecoveryTest {

    private static final String WORKER = "action-worker";

    @Test
    void anExpiredSubmissionLeaseQueriesBeforeSchedulingAnyRetry() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult initial = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);

        assertThat(initial.claimed()).isTrue();
        assertThat(scenario.action().status()).isEqualTo(ActionStatus.IN_FLIGHT);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();

        scenario.runtime.clock.advance(Duration.ofMinutes(1));
        ActionClaimResult recovered = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, "recovery-worker");
        ActionOutcome outcome = scenario.service.process(recovered.task());

        assertThat(recovered.claimed()).isTrue();
        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SAFE_RETRY_SCHEDULED);
        assertThat(outcome.attempt().attemptKind()).isEqualTo(ActionAttemptKind.VERIFICATION);
        assertThat(scenario.messages.queryCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void remoteSuccessBeforeLocalCommitIsReconciledWithoutResubmission() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult initial = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        ActionRequest request = scenario.store.findRequest(
                RuntimeJourneyScenario.TENANT, initial.task().actionId()).orElseThrow();

        scenario.messages.send(request.toProviderRequest(), scenario.idempotencyKey());
        scenario.runtime.clock.advance(Duration.ofMinutes(1));
        ActionClaimResult recovered = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, "recovery-worker");
        ActionOutcome outcome = scenario.service.process(recovered.task());

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(outcome.attempt().attemptKind()).isEqualTo(ActionAttemptKind.VERIFICATION);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.messages.queryCount(scenario.idempotencyKey())).isEqualTo(1);
        assertThat(scenario.messages.effectCount(scenario.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void restartRecoveryReusesThePersistedRequestWithoutResolvingAgain() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult initial = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        ActionRequest request = scenario.store.findRequest(
                RuntimeJourneyScenario.TENANT, initial.task().actionId()).orElseThrow();
        int resolutionCount = scenario.recipients.resolveCount();

        scenario.messages.send(request.toProviderRequest(), scenario.idempotencyKey());
        scenario.recipients.throwing();
        ActionApplicationService restarted = new ActionApplicationService(
                scenario.port, scenario.store,
                new ActionRequestResolver(scenario.port, scenario.recipients),
                ActionPolicies.defaults(), scenario.messages, scenario.rewards,
                scenario.runtime.clock, ActionScenarios.LEASE, ActionScenarios.CLAIM_SCAN);
        scenario.runtime.clock.advance(Duration.ofMinutes(1));

        ActionClaimResult recovered = restarted.claimNext(RuntimeJourneyScenario.TENANT, "restarted-worker");
        ActionOutcome outcome = restarted.process(recovered.task());

        assertThat(outcome.disposition()).isEqualTo(ActionDisposition.SUCCEEDED);
        assertThat(scenario.recipients.resolveCount()).isEqualTo(resolutionCount);
        assertThat(scenario.store.findRequest(
                RuntimeJourneyScenario.TENANT, initial.task().actionId())).contains(request);
    }

    @Test
    void concurrentClaimsProduceExactlyOneActiveLease() throws Exception {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        List<Callable<ActionClaimResult>> claims = List.of(
                () -> scenario.service.claimNext(RuntimeJourneyScenario.TENANT, "worker-a"),
                () -> scenario.service.claimNext(RuntimeJourneyScenario.TENANT, "worker-b"));

        List<ActionClaimResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            results = executor.invokeAll(claims).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
        }

        assertThat(results).filteredOn(ActionClaimResult::claimed).hasSize(1);
        assertThat(scenario.store.findActionTask(
                RuntimeJourneyScenario.TENANT, scenario.action().actionId(), ActionTaskKind.SUBMISSION))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(ActionTaskStatus.LEASED));
    }
}
