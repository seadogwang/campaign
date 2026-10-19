package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 idempotency tests: one frozen action node produces exactly one action, one idempotency record,
 * one reservation and one submission task however often it is presented, and changed material is a bounded
 * conflict that never reaches a Provider.
 */
class ActionIdempotencyTest {

    @Test
    void preparingTheSameHandoffTwiceReturnsTheOriginalActionAndWritesNothing() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation first = scenario.service.prepare(scenario.handoff());
        ActionPreparation second = scenario.service.prepare(scenario.handoff());

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.action()).isEqualTo(first.action());
        assertThat(second.submissionTask()).isEqualTo(first.submissionTask());
        assertThat(scenario.store.actionCount()).isEqualTo(1);
        assertThat(scenario.store.taskCount()).isEqualTo(1);
        // A duplicate never asks the host for a recipient and never reaches a Provider.
        assertThat(scenario.recipients.resolveCount()).isEqualTo(1);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void concurrentDuplicatePreparationCreatesExactlyOneActionAndOneSubmissionTask() throws Exception {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        int callers = 8;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<ActionPreparation>> tasks = new java.util.ArrayList<>();
            for (int index = 0; index < callers; index++) {
                tasks.add(() -> {
                    barrier.await();
                    return scenario.service.prepare(scenario.handoff());
                });
            }
            List<Future<ActionPreparation>> results = pool.invokeAll(tasks);
            int created = 0;
            for (Future<ActionPreparation> result : results) {
                if (!result.get().duplicate()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scenario.store.actionCount()).isEqualTo(1);
        assertThat(scenario.store.taskCount()).isEqualTo(1);
        assertThat(scenario.store.findActionTaskCandidates(
                RuntimeJourneyScenario.TENANT, scenario.runtime.clock.instant(), 64)).hasSize(1);
        assertThat(scenario.messages.submissionCount(scenario.idempotencyKey())).isZero();
    }

    @Test
    void theBusinessKeyExcludesEveryAttemptNumberAndIsStableAcrossRetries() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        String key = scenario.idempotencyKey();
        assertThat(preparation.action().idempotencyKey()).isEqualTo(key);
        assertThat(key).endsWith(":" + ActionKind.REWARD.code());
        assertThat(key).contains(scenario.handoff().participantId());
        assertThat(scenario.store.findIdempotencyRecord(RuntimeJourneyScenario.TENANT, key))
                .hasValueSatisfying(record -> assertThat(record.actionId())
                        .isEqualTo(preparation.action().actionId()));
    }

    @Test
    void theSameKeyUnderChangedFrozenMaterialIsABoundedConflictThatWritesNothing() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionPreparation preparation = scenario.service.prepare(scenario.handoff());
        ActionRequest request = scenario.store
                .findRequest(RuntimeJourneyScenario.TENANT, preparation.action().actionId())
                .orElseThrow();
        String key = scenario.idempotencyKey();
        ActionExecution tampered = ActionExecution.reserved(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId,
                scenario.handoff().participantId(), scenario.handoff().nodeLogicalId(), ActionKind.MESSAGE,
                key, ActionSupport.seedHash("changed-material"), scenario.runtime.clock.instant());
        ActionIdempotencyRecord changed = ActionIdempotencyRecord.of(
                RuntimeJourneyScenario.TENANT, key, tampered.actionId(),
                ActionSupport.seedHash("changed-frozen-material"),
                ActionSupport.seedHash("changed-material"), scenario.runtime.clock.instant());
        ActionTask task = ActionTask.due(
                RuntimeJourneyScenario.TENANT, tampered.actionId(), key, ActionTaskKind.SUBMISSION, 0,
                scenario.runtime.clock.instant());

        assertThatThrownBy(() -> scenario.store.commitPreparation(
                new ActionStore.ActionPreparationCommit(
                        changed, request, tampered, task, null, null, List.of())))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT);

        // Nothing was written for the conflicted key and the original action is untouched.
        assertThat(scenario.store.actionCount()).isEqualTo(1);
        assertThat(scenario.store.findActionByKey(RuntimeJourneyScenario.TENANT, key))
                .hasValueSatisfying(action -> assertThat(action).isEqualTo(preparation.action()));
    }

    @Test
    void aChangedRequestHashIsRefusedByTheProviderItself() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ProviderRequest first = request(scenario, ActionSupport.seedHash("one"));
        ProviderRequest second = request(scenario, ActionSupport.seedHash("two"));
        String key = scenario.idempotencyKey();
        scenario.messages.send(first, key);
        assertThatThrownBy(() -> scenario.messages.send(second, key))
                .isInstanceOf(ActionProviderException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT);
        assertThat(scenario.messages.effectCount(key)).isEqualTo(1);
    }

    @Test
    void duplicateSubmissionsOfOneKeyProduceExactlyOneRemoteEffect() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ProviderRequest request = request(scenario, ActionSupport.seedHash("one"));
        String key = scenario.idempotencyKey();
        ProviderResult first = scenario.messages.send(request, key);
        ProviderResult second = scenario.messages.send(request, key);
        assertThat(first.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(second.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(second.providerReference()).isEqualTo(first.providerReference());
        assertThat(scenario.messages.effectCount(key)).isEqualTo(1);
        assertThat(scenario.messages.submissionCount(key)).isEqualTo(2);
    }

    private static ProviderRequest request(ActionScenarios.Scenario scenario,
            com.company.campaign.shared.canonical.ContentHash requestHash) {
        return ProviderRequest.of(
                ActionKind.MESSAGE, scenario.idempotencyKey(), "EMAIL", "tpl", "tpl-v1",
                ActionSupport.seedHash("template"), null, null, null, null, null, null,
                "recipient-1", "route-m", requestHash);
    }
}