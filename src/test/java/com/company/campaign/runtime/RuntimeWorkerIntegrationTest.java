package com.company.campaign.runtime;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.fullpublish.FullPublishTestFixtures;
import com.company.campaign.fullpublish.ScheduleStateCommand;
import com.company.campaign.runtime.testsupport.MutableClock;
import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 worker integration tests: the leased worker advances a Participant over a genuine accepted T21
 * full publication, the host-owned T21 pause and resume projection is exactly what it rechecks, and an
 * injected store failure rolls the whole node completion back.
 */
class RuntimeWorkerIntegrationTest {

    private static final String ONCE_AT = "2026-10-03T07:00:00+08:00";

    private static RuntimeScenario scenario() {
        CampaignPlan plan = RuntimeTestPlans.oncePlan(ONCE_AT);
        return RuntimeScenario.withJourney(
                plan, FullPublishTestFixtures.source(plan),
                new MutableClock(RuntimeScenario.DELIVERED_AT));
    }

    @Test
    void theWorkerAdvancesARealPublishedJourneyToItsTerminalState() {
        RuntimeScenario scenario = scenario();
        scenario.publish();
        scenario.materialize(scenario.bootstrap(), List.of("member-1", "member-2"));
        RuntimeWorker worker = scenario.worker();

        RuntimeClaimResult first = worker.claimNext(scenario.tenantId(), "worker-1");
        assertThat(first.outcome()).isEqualTo(RuntimeClaimOutcome.CLAIMED);
        assertThat(worker.process(first.task()).disposition())
                .isEqualTo(RuntimeNodeDisposition.ADVANCED);

        RuntimeNodeResult terminated = null;
        for (int step = 0; step < 8 && terminated == null; step++) {
            RuntimeClaimResult claim = worker.claimNext(scenario.tenantId(), "worker-1");
            if (!claim.claimed()) {
                break;
            }
            RuntimeNodeResult outcome = worker.process(claim.task());
            if (outcome.execution().status() == ExecutionStatus.COMPLETED) {
                terminated = outcome;
            }
        }

        assertThat(terminated).isNotNull();
        assertThat(terminated.counters().completedCount()).isEqualTo(2);
        assertThat(terminated.counters().runningCount()).isZero();
        assertThat(terminated.execution().enrolledCount()).isEqualTo(2);
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_LEASE_CLAIMED));
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_NODE_SUCCEEDED));
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_EXECUTION_AGGREGATED));
    }

    @Test
    void theAcceptedT21PauseAndResumeProjectionIsExactlyWhatTheWorkerRechecks() {
        RuntimeScenario scenario = scenario();
        scenario.publish();
        scenario.materialize(scenario.bootstrap(), List.of("member-1"));
        RuntimeWorker worker = scenario.worker();
        scenario.publication.operationService().pause(
                new ScheduleStateCommand(
                        scenario.campaignId(), scenario.publication.activeSchedule().stateRevision()),
                scenario.context());

        RuntimeClaimResult paused = worker.claimNext(scenario.tenantId(), "worker-1");
        assertThat(paused.outcome()).isEqualTo(RuntimeClaimOutcome.PAUSED);
        assertThat(paused.task()).isNull();

        scenario.publication.operationService().resume(
                new ScheduleStateCommand(
                        scenario.campaignId(), scenario.publication.activeSchedule().stateRevision()),
                scenario.context());

        RuntimeClaimResult resumed = worker.claimNext(scenario.tenantId(), "worker-1");
        assertThat(resumed.claimed()).isTrue();
        assertThat(worker.process(resumed.task()).disposition())
                .isEqualTo(RuntimeNodeDisposition.ADVANCED);
    }

    @Test
    void anInjectedStoreFailureRollsTheWholeNodeCompletionBack() {
        RuntimeScenario scenario = RuntimeScenario.withStore(
                RuntimeTestPlans.oncePlan(ONCE_AT), ScriptedStore::new);
        scenario.publish();
        String executionKey = scenario.bootstrap();
        scenario.materialize(executionKey, List.of("member-1"));
        String executionId = scenario.store.findExecution(scenario.tenantId(), executionKey)
                .orElseThrow().executionId();
        ScriptedStore store = (ScriptedStore) scenario.store;
        RuntimeWorker worker = scenario.worker();
        RuntimeTask leased = worker.claimNext(scenario.tenantId(), "worker-1").task();
        int attemptsBefore = store.findAttempts(scenario.tenantId(), executionId).size();

        store.failCommits(true);
        assertThatThrownBy(() -> worker.process(leased)).isInstanceOf(IllegalStateException.class);
        store.failCommits(false);

        assertThat(store.findAttempts(scenario.tenantId(), executionId)).hasSize(attemptsBefore);
        assertThat(store.findNodeExecutions(scenario.tenantId(), executionId)).isEmpty();
        assertThat(store.findTask(scenario.tenantId(), leased.taskId()).orElseThrow()).isEqualTo(leased);
        assertThat(store.findCounters(scenario.tenantId(), executionId).orElseThrow().revision()).isZero();

        assertThat(worker.process(leased).disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(store.findNodeExecutions(scenario.tenantId(), executionId)).hasSize(1);
    }

    /** A store whose commit point can be made to fail on demand. */
    static final class ScriptedStore extends InMemoryRuntimeStore {

        private boolean fail;

        ScriptedStore(RuntimeAuditSink sink) {
            super(sink);
        }

        void failCommits(boolean value) {
            this.fail = value;
        }

        @Override
        protected void beforeCommit() {
            if (fail) {
                throw new IllegalStateException("scripted runtime store failure");
            }
        }
    }
}