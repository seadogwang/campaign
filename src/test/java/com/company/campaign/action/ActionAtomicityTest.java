package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.ExecutionStatus;
import com.company.campaign.runtime.InMemoryRuntimeStore;
import com.company.campaign.runtime.NodeExecutionStatus;
import com.company.campaign.runtime.ParticipantStatus;
import com.company.campaign.runtime.RuntimeBoundaryException;
import com.company.campaign.runtime.RuntimeErrorCodes;
import com.company.campaign.runtime.RuntimeStore;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 atomicity tests: a failing store collaborator, a failing audit sink, a stale revision and a stale
 * lease token all leave no partial local result at all, and a retry observes exactly the original durable state.
 */
class ActionAtomicityTest {

    private static final String WORKER = "action-worker";

    /** One local store whose injected commit seam fails on demand. */
    private static final class FailingStore extends InMemoryActionStore {

        private boolean fail;

        private FailingStore(RuntimeStore runtimeStore) {
            super(runtimeStore);
        }

        @Override
        protected void beforeCommit() {
            if (fail) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_STORE_UNAVAILABLE, "commit",
                        "The local action store was injected to fail.");
            }
        }
    }

    /** One local audit sink that refuses every batch on demand. */
    private static final class FailingAuditSink implements ActionAuditSink {

        private boolean fail;

        @Override
        public void recordAll(List<ActionAuditRecord> records) {
            if (fail) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED, "audit",
                        "The local action audit sink was injected to fail.");
            }
        }

        @Override
        public void commitAll(List<ActionAuditRecord> records) {
            if (fail) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED, "audit",
                        "The local action audit sink was injected to fail.");
            }
            ActionAuditSink.super.commitAll(records);
        }

        @Override
        public void rollbackAll(List<ActionAuditRecord> records) {
        }
    }

    @Test
    void aFailingStoreLeavesNoActionReservationTaskOrLedgerBehind() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        FailingStore store = new FailingStore(scenario.runtime.store);
        store.fail = true;
        ActionApplicationService service = service(scenario, store);

        assertThatThrownBy(() -> service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_STORE_UNAVAILABLE);

        assertThat(store.actionCount()).isZero();
        assertThat(store.taskCount()).isZero();
        assertThat(store.findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId))
                .isEmpty();
        assertThat(store.findIdempotencyRecord(RuntimeJourneyScenario.TENANT, scenario.idempotencyKey()))
                .isEmpty();
    }

    @Test
    void aFailingAuditSinkRollsTheWholePreparationBack() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        FailingAuditSink sink = new FailingAuditSink();
        sink.fail = true;
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);

        assertThatThrownBy(() -> service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED);

        assertThat(store.actionCount()).isZero();
        assertThat(store.taskCount()).isZero();
        assertThat(store.findBudgetLedger(RuntimeJourneyScenario.TENANT, scenario.runtime.executionId))
                .isEmpty();
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void aRetryAfterAFailedCommitObservesExactlyTheOriginalDurableState() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        FailingStore store = new FailingStore(scenario.runtime.store);
        store.fail = true;
        ActionApplicationService service = service(scenario, store);
        assertThatThrownBy(() -> service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class);

        // The same handoff, retried against a healthy store, produces exactly one action.
        store.fail = false;
        ActionPreparation preparation = service.prepare(scenario.handoff());
        assertThat(preparation.duplicate()).isFalse();
        assertThat(store.actionCount()).isEqualTo(1);
        assertThat(scenario.runtime.participant("member-message-1").revision()).isEqualTo(1L);
        assertThat(scenario.runtime.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void aStaleLeaseTokenWritesNothingAndLeavesTheNodeUntouched() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        scenario.worker.ingest(RuntimeJourneyScenario.TENANT, WORKER);
        ActionClaimResult claim = scenario.service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        assertThat(claim.claimed()).isTrue();

        ActionTask stolen = claim.task().completed(scenario.runtime.clock.instant());
        ActionBoundaryException refused = (ActionBoundaryException) org.assertj.core.api.Assertions
                .catchThrowable(() -> scenario.store.commitActionCompletion(
                        new ActionStore.ActionCompletionCommit(
                                RuntimeJourneyScenario.TENANT, scenario.action().actionId(),
                                scenario.action().revision(), scenario.action(),
                                claim.task().taskId(), claim.task().revision(), "actlease-forged-token",
                                stolen,
                                ActionAttempt.of(
                                        RuntimeJourneyScenario.TENANT, scenario.action().actionId(),
                                        scenario.idempotencyKey(), ActionAttemptKind.SUBMISSION,
                                        scenario.action().submissionCount(),
                                        ProviderResult.definitivelyNotApplied(
                                                scenario.idempotencyKey(), "PROVIDER_REJECTED"),
                                        "PROVIDER_REJECTED", claim.task().updatedAt(),
                                        scenario.runtime.clock.instant()),
                                null, null, null, null, List.of())));

        assertThat(refused.code()).isEqualTo(ActionErrorCodes.ACTION_LEASE_TOKEN_MISMATCH);
        assertThat(scenario.store.findActionById(
                RuntimeJourneyScenario.TENANT, scenario.action().actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.IN_FLIGHT);
        assertThat(scenario.runtime.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.runtime.executionId, scenario.handoff().participantId(),
                scenario.handoff().nodeLogicalId()).orElseThrow().status())
                .isEqualTo(NodeExecutionStatus.RUNNING);
        assertThat(scenario.runtime.participant("member-message-1").status())
                .isEqualTo(ParticipantStatus.RUNNING);
    }

    @Test
    void aDuplicateCompletionReusesTheCommittedResultAndWritesNothing() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        ActionOutcome first = scenario.step(WORKER);
        ActionExecution committed = scenario.action();
        ActionAttempt attempt = scenario.store
                .findActionAttempts(RuntimeJourneyScenario.TENANT, committed.actionId())
                .stream()
                .findFirst()
                .orElseThrow();

        int actionCount = scenario.store.actionCount();
        int taskCount = scenario.store.taskCount();
        int attemptCount = scenario.store.attemptCount();
        ActionStore.ActionCompletionResult result = scenario.store.commitActionCompletion(
                new ActionStore.ActionCompletionCommit(
                        RuntimeJourneyScenario.TENANT, committed.actionId(), committed.revision(), committed,
                        first.task().taskId(), first.task().revision(), first.task().leaseToken(), first.task(),
                        attempt, null, null, null, null, List.of()));

        assertThat(result.duplicate()).isTrue();
        assertThat(scenario.store.actionCount()).isEqualTo(actionCount);
        assertThat(scenario.store.taskCount()).isEqualTo(taskCount);
        assertThat(scenario.store.attemptCount()).isEqualTo(attemptCount);
    }

    @Test
    void aFailingRuntimeCommitRollsBackTheActionAuditAndLocalCompletion() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        InMemoryActionAuditSink sink = new InMemoryActionAuditSink();
        RuntimeStore failingRuntime = failingRuntime(scenario.runtime.store);
        InMemoryActionStore store = new InMemoryActionStore(failingRuntime, sink);
        ActionApplicationService service = service(scenario, store);
        service.prepare(scenario.handoff());
        ActionClaimResult claim = service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        int auditCount = sink.count();

        assertThatThrownBy(() -> service.process(claim.task()))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_STORE_CONFLICT);

        assertThat(sink.count()).isEqualTo(auditCount);
        assertThat(store.findActionById(
                RuntimeJourneyScenario.TENANT, claim.task().actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.IN_FLIGHT);
        assertThat(store.findActionAttempts(RuntimeJourneyScenario.TENANT, claim.task().actionId())).isEmpty();
        assertThat(scenario.runtime.participant("member-message-1").status())
                .isEqualTo(ParticipantStatus.RUNNING);
    }

    private static RuntimeStore failingRuntime(InMemoryRuntimeStore delegate) {
        return (RuntimeStore) Proxy.newProxyInstance(
                RuntimeStore.class.getClassLoader(), new Class<?>[]{RuntimeStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("commitExternalActionCompletion")) {
                        throw new RuntimeBoundaryException(
                                RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "completion",
                                "The runtime commit was injected to fail.");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static ActionApplicationService service(
            ActionScenarios.Scenario scenario, InMemoryActionStore store) {
        return new ActionApplicationService(
                scenario.port, store,
                new ActionRequestResolver(scenario.port, scenario.recipients),
                ActionPolicies.defaults(), scenario.messages, scenario.rewards,
                scenario.runtime.clock, ActionScenarios.LEASE, ActionScenarios.CLAIM_SCAN);
    }
}
