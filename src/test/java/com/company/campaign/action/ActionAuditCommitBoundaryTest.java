package com.company.campaign.action;

import com.company.campaign.action.testsupport.ActionScenarios;
import com.company.campaign.runtime.RuntimeBoundaryException;
import com.company.campaign.runtime.RuntimeErrorCodes;
import com.company.campaign.runtime.RuntimeStore;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepted commit boundary of the {@link ActionAuditSink} port, proved through every {@link InMemoryActionStore}
 * commit path.
 *
 * <p>Each commit path must call {@code recordAll} once and {@code commitAll} exactly once at the final accepted
 * boundary, call {@code rollbackAll} instead of {@code commitAll} when a later fallible participant fails, and
 * leave no Action state behind when the accepted commit itself fails.
 */
class ActionAuditCommitBoundaryTest {

    private static final String WORKER = "action-worker";

    /** A sink that counts every accepted-protocol call and can fail on demand. */
    private static final class CountingAuditSink implements ActionAuditSink {

        private int recordAllCalls;
        private int commitAllCalls;
        private int rollbackAllCalls;
        private List<ActionAuditRecord> recorded;
        private boolean failCommit;

        @Override
        public void recordAll(List<ActionAuditRecord> records) {
            recordAllCalls++;
            recorded = List.copyOf(records);
        }

        @Override
        public void commitAll(List<ActionAuditRecord> records) {
            if (!Objects.equals(recorded, List.copyOf(records))) {
                throw new IllegalStateException("commitAll must name exactly the recorded batch");
            }
            commitAllCalls++;
            if (failCommit) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED, "audit",
                        "The counting audit sink was injected to fail at the accepted commit.");
            }
        }

        @Override
        public void rollbackAll(List<ActionAuditRecord> records) {
            rollbackAllCalls++;
        }

        private void reset() {
            recordAllCalls = 0;
            commitAllCalls = 0;
            rollbackAllCalls = 0;
        }
    }

    /** One local store whose injected commit seam fails on demand. */
    private static final class FailingSeamStore extends InMemoryActionStore {

        private boolean fail;

        private FailingSeamStore(RuntimeStore runtimeStore, ActionAuditSink auditSink) {
            super(runtimeStore, auditSink);
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

    @Test
    void aPreparationCommitInvokesExactlyOneAcceptedCommit() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);

        service.prepare(scenario.handoff());

        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isZero();
        assertThat(store.auditCount()).isPositive();
    }

    @Test
    void aClaimCommitInvokesExactlyOneAcceptedCommit() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);

        service.prepare(scenario.handoff());
        sink.reset();
        ActionClaimResult claim = service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);

        assertThat(claim.claimed()).isTrue();
        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isZero();
    }

    @Test
    void aScheduleCommitInvokesExactlyOneAcceptedCommit() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);
        ActionWorker worker = worker(scenario, service);
        scenario.messages.withBehaviour(
                scenario.idempotencyKey(), SimulatedProviderBehaviour.UNKNOWN_THEN_NOT_APPLIED);

        scenario.runtime.clock.advance(Duration.ofMinutes(1));
        ActionOutcome first = worker.runOnce(RuntimeJourneyScenario.TENANT, WORKER).orElseThrow();
        assertThat(first.action().status()).isEqualTo(ActionStatus.VERIFYING);

        sink.reset();
        ActionOutcome reconciled = service.reconcile(ActionOperatorCommand.of(
                RuntimeJourneyScenario.TENANT, first.action().actionId(), "operator-1", true,
                first.action().revision(), "RECONCILE"));

        assertThat(reconciled.disposition()).isEqualTo(ActionDisposition.VERIFICATION_SCHEDULED);
        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isZero();
    }

    @Test
    void aCompletionCommitInvokesExactlyOneAcceptedCommit() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);

        service.prepare(scenario.handoff());
        ActionClaimResult claim = service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        sink.reset();
        service.process(claim.task());

        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isZero();
        assertThat(store.attemptCount()).isEqualTo(1);
    }

    @Test
    void theCommitSeamRunsBeforeAnyAuditCallback() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        CountingAuditSink sink = new CountingAuditSink();
        FailingSeamStore store = new FailingSeamStore(scenario.runtime.store, sink);
        store.fail = true;
        ActionApplicationService service = service(scenario, store);

        assertThatThrownBy(() -> service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_STORE_UNAVAILABLE);

        assertThat(sink.recordAllCalls).isZero();
        assertThat(sink.commitAllCalls).isZero();
        assertThat(store.actionCount()).isZero();
    }

    @Test
    void anAcceptedCommitFailureLeavesNoActionState() {
        ActionScenarios.Scenario scenario = ActionScenarios.reward();
        CountingAuditSink sink = new CountingAuditSink();
        sink.failCommit = true;
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);

        assertThatThrownBy(() -> service.prepare(scenario.handoff()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED);

        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isZero();
        assertThat(store.actionCount()).isZero();
        assertThat(store.taskCount()).isZero();
        assertThat(store.auditCount()).isZero();
        assertThat(store.findIdempotencyRecord(
                RuntimeJourneyScenario.TENANT, scenario.idempotencyKey())).isEmpty();
    }

    @Test
    void aRuntimeFailureAfterRecordingRollsBackAndNeverCommits() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(failingRuntime(scenario.runtime.store), sink);
        ActionApplicationService service = service(scenario, store);
        service.prepare(scenario.handoff());
        ActionClaimResult claim = service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        sink.reset();

        assertThatThrownBy(() -> service.process(claim.task()))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_STORE_CONFLICT);

        assertThat(sink.recordAllCalls).isEqualTo(1);
        assertThat(sink.rollbackAllCalls).isEqualTo(1);
        assertThat(sink.commitAllCalls).isZero();
        assertThat(store.findActionById(
                RuntimeJourneyScenario.TENANT, claim.task().actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.IN_FLIGHT);
        assertThat(store.findActionAttempts(RuntimeJourneyScenario.TENANT, claim.task().actionId())).isEmpty();
    }

    @Test
    void aCompletionCommitFailureLeavesNoActionState() {
        ActionScenarios.Scenario scenario = ActionScenarios.message();
        CountingAuditSink sink = new CountingAuditSink();
        InMemoryActionStore store = new InMemoryActionStore(scenario.runtime.store, sink);
        ActionApplicationService service = service(scenario, store);
        service.prepare(scenario.handoff());
        ActionClaimResult claim = service.claimNext(RuntimeJourneyScenario.TENANT, WORKER);
        sink.failCommit = true;

        assertThatThrownBy(() -> service.process(claim.task()))
                .isInstanceOf(ActionBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", ActionErrorCodes.ACTION_AUDIT_BATCH_UNSUPPORTED);

        // A failed accepted commit is not compensated by a rollback; it simply leaves no Action state.
        assertThat(sink.rollbackAllCalls).isZero();
        assertThat(store.findActionById(
                RuntimeJourneyScenario.TENANT, claim.task().actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.IN_FLIGHT);
        assertThat(store.findActionAttempts(RuntimeJourneyScenario.TENANT, claim.task().actionId())).isEmpty();
    }

    private static RuntimeStore failingRuntime(RuntimeStore delegate) {
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

    private static ActionWorker worker(ActionScenarios.Scenario scenario, ActionApplicationService service) {
        return new ActionWorker(scenario.port, service, scenario.runtime.clock, 64);
    }
}