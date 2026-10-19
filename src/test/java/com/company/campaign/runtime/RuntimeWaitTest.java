package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T22 Wait tests: one deadline computed exactly once from the frozen duration and anchor, retained
 * across a duplicate wake, a restart, a pause and a delayed recovery and never shifted.
 */
class RuntimeWaitTest {

    private static final String MEMBER = "member-1";
    private static final Instant ENROLLED_AT = Instant.parse(RuntimeJourneyScenario.ENROLLED_AT);
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    /** Runs the Start node only, so the Wait node's own task is the next claimable work. */
    private static RuntimeJourneyScenario atWait(String duration, String anchor) {
        RuntimeJourneyScenario scenario =
                RuntimeJourneyScenario.of(RuntimeJourneys.wait(duration, anchor), MEMBER);
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());
        return scenario;
    }

    private static RuntimeJourneyScenario waiting(String duration, String anchor) {
        RuntimeJourneyScenario scenario = atWait(duration, anchor);
        RuntimeWorker worker = scenario.worker();
        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());
        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.WAITING);
        return scenario;
    }

    private static RuntimeTask wakeTask(RuntimeJourneyScenario scenario) {
        return scenario.tasks().stream()
                .filter(task -> task.kind() == RuntimeTaskKind.WAIT_WAKE)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aWaitStoresExactlyOneDeadlineAndOneWakeTaskFromTheFrozenDurationAndEntryAnchor() {
        RuntimeJourneyScenario scenario = waiting("PT1H", FlowWaitPack.ANCHOR_ENTRY);

        assertThat(scenario.participant(MEMBER).status()).isEqualTo(ParticipantStatus.WAITING);
        assertThat(scenario.participant(MEMBER).waitingReason())
                .isEqualTo(RuntimeVersions.WAIT_REASON_DURATION);
        assertThat(scenario.participant(MEMBER).waitingDeadline()).isEqualTo(ENROLLED_AT.plus(ONE_HOUR));
        assertThat(scenario.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.executionId,
                scenario.participant(MEMBER).participantId(), "w").orElseThrow().status())
                .isEqualTo(NodeExecutionStatus.WAITING);
        assertThat(scenario.tasks().stream()
                .filter(task -> task.kind() == RuntimeTaskKind.WAIT_WAKE).toList())
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.availableAt()).isEqualTo(ENROLLED_AT.plus(ONE_HOUR));
                    assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE);
                });
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_NODE_WAITING));
    }

    @Test
    void aWakeIsNotClaimableBeforeItsDeadlineAndSucceedsTheNodeExactlyOnceAtIt() {
        RuntimeJourneyScenario scenario = waiting("PT1H", FlowWaitPack.ANCHOR_ENTRY);
        RuntimeWorker worker = scenario.worker();

        assertThat(worker.claimNext("worker-1").claimed()).isFalse();
        scenario.clock.set(ENROLLED_AT.plus(ONE_HOUR).minusSeconds(1));
        assertThat(worker.claimNext("worker-1").claimed()).isFalse();

        scenario.clock.set(ENROLLED_AT.plus(ONE_HOUR));
        RuntimeTask wake = worker.claimNext("worker-1").task();
        assertThat(wake.kind()).isEqualTo(RuntimeTaskKind.WAIT_WAKE);
        RuntimeNodeResult woken = worker.process(wake);

        assertThat(woken.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(woken.nodeExecution().status()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThat(woken.participant().status()).isEqualTo(ParticipantStatus.RUNNING);
        assertThat(woken.participant().currentNodeLogicalId()).isEqualTo("e");
        assertThat(woken.participant().waitingDeadline()).isNull();
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_WAIT_WOKEN));

        RuntimeNodeResult duplicate = worker.process(wake);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.participant().currentNodeLogicalId()).isEqualTo("e");
    }

    @Test
    void theDeadlineIsNotRecomputedByARestartOrALateRecovery() {
        RuntimeJourneyScenario scenario = waiting("PT1H", FlowWaitPack.ANCHOR_ENTRY);
        Instant deadline = ENROLLED_AT.plus(ONE_HOUR);
        // One worker takes the wake lease exactly at the deadline and never finishes.
        scenario.clock.set(deadline);
        assertThat(scenario.worker().claimNext("worker-1").claimed()).isTrue();

        // The lease expires and the work is recovered long after the deadline, by a brand-new worker.
        scenario.clock.set(deadline.plus(Duration.ofHours(6)));
        RuntimeWorker restarted = scenario.worker();
        RuntimeClaimResult recovered = restarted.claimNext("worker-2");
        assertThat(recovered.claimed()).isTrue();
        assertThat(recovered.task().kind()).isEqualTo(RuntimeTaskKind.WAIT_WAKE);
        assertThat(recovered.task().availableAt()).isEqualTo(deadline);
        assertThat(recovered.task().attemptNumber()).isEqualTo(2);

        RuntimeNodeResult woken = restarted.process(recovered.task());
        assertThat(woken.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(woken.participant().currentNodeLogicalId()).isEqualTo("e");
    }

    @Test
    void aPauseGuardsTheWakeTaskWithoutShiftingItsDeadlineAndAResumeRetainsIt() {
        RuntimeJourneyScenario scenario = atWait("PT1H", FlowWaitPack.ANCHOR_ENTRY);
        RuntimeWorker worker = scenario.worker();
        RuntimeTask waitNode = worker.claimNext("worker-1").task();
        scenario.campaignStates.pause();

        RuntimeNodeResult outcome = worker.process(waitNode);

        assertThat(outcome.nextTaskGuarded()).isTrue();
        RuntimeTask guarded = wakeTask(scenario);
        assertThat(guarded.status()).isEqualTo(RuntimeTaskStatus.DEFERRED);
        assertThat(guarded.availableAt()).isEqualTo(ENROLLED_AT.plus(ONE_HOUR));
        // Once due, a paused Campaign still starts no new node.
        scenario.clock.set(ENROLLED_AT.plus(ONE_HOUR));
        assertThat(worker.claimNext("worker-1").outcome()).isEqualTo(RuntimeClaimOutcome.PAUSED);

        scenario.campaignStates.resume();
        RuntimeClaimResult released = worker.claimNext("worker-1");
        assertThat(released.claimed()).isTrue();
        assertThat(released.task().availableAt()).isEqualTo(ENROLLED_AT.plus(ONE_HOUR));
        assertThat(worker.process(released.task()).participant().currentNodeLogicalId()).isEqualTo("e");
    }

    @Test
    void aWaitAnchoredAtThePreviousAcceptedActionFailsClosedWhenNoActionWasAccepted() {
        RuntimeJourneyScenario scenario = atWait("PT15M", FlowWaitPack.ANCHOR_PREVIOUS_ACCEPTED_ACTION);

        // The fixture Journey accepts no action before the Wait, so the anchor has nothing to measure from.
        assertThat(scenario.participant(MEMBER).lastAcceptedActionInstant()).isNull();
        RuntimeWorker worker = scenario.worker();
        scenario.clock.advance(Duration.ofMinutes(1));
        RuntimeNodeResult deadLettered = worker.process(worker.claimNext("worker-1").task());

        assertThat(deadLettered.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(deadLettered.task().reasonCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_WAIT_ANCHOR_MISSING);
        assertThat(deadLettered.participant().status()).isEqualTo(ParticipantStatus.NEEDS_REVIEW);
        assertThat(deadLettered.execution().status()).isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(scenario.store.findNodeExecution(
                RuntimeJourneyScenario.TENANT, scenario.executionId,
                scenario.participant(MEMBER).participantId(), "w").orElseThrow().status())
                .isEqualTo(NodeExecutionStatus.NEEDS_REVIEW);
    }
}
