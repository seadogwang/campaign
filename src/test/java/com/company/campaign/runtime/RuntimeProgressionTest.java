package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import com.company.campaign.runtime.testsupport.ScriptedFactPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T22 local progression tests: every local node kind advances by exact logical identity, an unresolved
 * Condition never becomes {@code FALSE}, and MESSAGE and REWARD produce only bounded {@code ACTION_READY}
 * handoff work for T23.
 */
class RuntimeProgressionTest {

    private static final String FACT = "member.tier";

    private static RuntimeJourneyScenario linear() {
        return RuntimeJourneyScenario.of(RuntimeJourneys.linear(), "member-1");
    }

    private static RuntimeNodeResult drain(RuntimeJourneyScenario scenario) {
        RuntimeWorker worker = scenario.worker();
        RuntimeNodeResult last = null;
        for (int step = 0; step < 8; step++) {
            RuntimeClaimResult claim = worker.claimNext("worker-1");
            if (!claim.claimed()) {
                return last;
            }
            last = worker.process(claim.task());
        }
        throw new IllegalStateException("the fixture Journey did not terminate within its bounded step count");
    }

    @Test
    void aStartNodeAdvancesTheParticipantToItsExactSuccessorAndAnEndNodeCompletesIt() {
        RuntimeJourneyScenario scenario = linear();
        RuntimeWorker worker = scenario.worker();

        RuntimeNodeResult advanced = worker.process(worker.claimNext("worker-1").task());
        assertThat(advanced.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        assertThat(advanced.nodeExecution().nodeKind()).isEqualTo(JourneyNodeKind.START);
        assertThat(advanced.nodeExecution().status()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThat(advanced.participant().status()).isEqualTo(ParticipantStatus.RUNNING);
        assertThat(advanced.task().status()).isEqualTo(RuntimeTaskStatus.COMPLETED);
        assertThat(scenario.tasks()).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(RuntimeTaskKind.NODE);
            assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE);
        });

        RuntimeNodeResult terminated = worker.process(worker.claimNext("worker-1").task());
        assertThat(terminated.disposition()).isEqualTo(RuntimeNodeDisposition.TERMINATED);
        assertThat(terminated.participant().status()).isEqualTo(ParticipantStatus.COMPLETED);
        assertThat(terminated.execution().status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(terminated.counters().completedCount()).isEqualTo(1);
        assertThat(terminated.counters().runningCount()).isZero();
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_EXECUTION_AGGREGATED));
    }

    @Test
    void anExitNodeTerminatesTheParticipantAsExited() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.exit(), "member-1");

        RuntimeNodeResult terminated = drain(scenario);

        assertThat(terminated.participant().status()).isEqualTo(ParticipantStatus.EXITED);
        assertThat(terminated.execution().status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(terminated.counters().exitedCount()).isEqualTo(1);
    }

    @Test
    void aConditionSelectsOnlyItsExactTypedArm() {
        RuntimeJourneyScenario matched = RuntimeJourneyScenario.of(conditionJourney(FlowConditionPack.ON_UNKNOWN), "m");
        matched.facts.declare(FACT, RuntimeFactResult.known(
                FACT, FlowConditionPack.TYPE_STRING, "GOLD", Instant.parse(RuntimeJourneyScenario.ENROLLED_AT)));
        assertThat(drain(matched).participant().status()).isEqualTo(ParticipantStatus.COMPLETED);

        RuntimeJourneyScenario unmatched =
                RuntimeJourneyScenario.of(conditionJourney(FlowConditionPack.ON_UNKNOWN), "m");
        unmatched.facts.declare(FACT, RuntimeFactResult.known(
                FACT, FlowConditionPack.TYPE_STRING, "SILVER", Instant.parse(RuntimeJourneyScenario.ENROLLED_AT)));
        RuntimeNodeResult falseArm = drain(unmatched);
        assertThat(falseArm.disposition()).isEqualTo(RuntimeNodeDisposition.TERMINATED);
        assertThat(falseArm.participant().status()).isEqualTo(ParticipantStatus.EXITED);
    }

    @Test
    void anUnresolvedConditionNeverSelectsFalseAndNeedsAReview() {
        RuntimeJourneyScenario scenario =
                RuntimeJourneyScenario.of(conditionJourney(FlowConditionPack.ON_UNKNOWN), "m");

        RuntimeNodeResult outcome = workerAtNode(scenario, "c");

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(outcome.participant().status()).isEqualTo(ParticipantStatus.NEEDS_REVIEW);
        assertThat(outcome.nodeExecution().status()).isEqualTo(NodeExecutionStatus.NEEDS_REVIEW);
        assertThat(outcome.execution().status()).isEqualTo(ExecutionStatus.NEEDS_REVIEW);
        assertThat(outcome.task().status()).isEqualTo(RuntimeTaskStatus.DEAD_LETTER);
        assertThat(outcome.task().reasonCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_CONDITION_UNKNOWN);
        assertThat(outcome.counters().needsReviewCount()).isEqualTo(1);
        assertThat(scenario.participant("m").currentNodeLogicalId()).isNotEqualTo("t");
        assertThat(scenario.participant("m").currentNodeLogicalId()).isNotEqualTo("f");
    }

    @Test
    void anUnknownFactUnderTheFailClosedPolicyStopsWithTheFrozenPolicyReason() {
        RuntimeJourneyScenario scenario =
                RuntimeJourneyScenario.of(conditionJourney(FlowConditionPack.ON_UNKNOWN_ERROR), "m");

        RuntimeNodeResult outcome = workerAtNode(scenario, "c");

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.NEEDS_REVIEW);
        assertThat(outcome.task().reasonCode())
                .isEqualTo(RuntimeErrorCodes.RUNTIME_CONDITION_FAILED_CLOSED);
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_BUSINESS_FAILURE));
    }

    @Test
    void aMessageNodeCreatesOnlyABoundedHandoffAndStopsLocalAdvancement() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.message(), "member-1");
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());

        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.ACTION_READY);
        assertThat(outcome.handedOff()).isTrue();
        assertThat(outcome.handoff().actionKind()).isEqualTo(JourneyNodeKind.MESSAGE);
        assertThat(outcome.handoff().reasonCode()).isEqualTo(ActionHandoff.REASON_ACTION_READY);
        assertThat(outcome.handoff().nodePayloadHash())
                .isEqualTo(scenario.journey.node("m").payloadHash());
        assertThat(scenario.store.findActionHandoffs(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(1);
        // The Participant stays unresolved at the action node, so the Execution can never complete over it.
        assertThat(outcome.participant().status()).isEqualTo(ParticipantStatus.RUNNING);
        assertThat(outcome.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
        // The external-action node is not successful merely because a request was handed off, so T23's atomic
        // completion — and only that — may finish it.
        assertThat(outcome.nodeExecution().status()).isEqualTo(NodeExecutionStatus.RUNNING);
        assertThat(scenario.tasks()).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(RuntimeTaskKind.ACTION_READY);
            assertThat(task.status()).isEqualTo(RuntimeTaskStatus.DUE);
        });
        // A T22 worker never claims the T23 handoff work, so nothing else advances here.
        assertThat(worker.claimNext("worker-1").claimed()).isFalse();
    }

    @Test
    void aRewardNodeCreatesOnlyABoundedHandoffAndClaimsNoRewardSuccess() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.reward(), "member-1");
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());

        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());

        assertThat(outcome.disposition()).isEqualTo(RuntimeNodeDisposition.ACTION_READY);
        assertThat(outcome.handoff().actionKind()).isEqualTo(JourneyNodeKind.REWARD);
        assertThat(outcome.counters().completedCount()).isZero();
        assertThat(outcome.counters().failedCount()).isZero();
        assertThat(scenario.store.findActionHandoffs(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(1);
        assertThat(scenario.store.findActionHandoffs(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .allSatisfy(handoff -> assertThat(handoff.toString()).doesNotContain("member-1"));
    }

    @Test
    void aFrozenJourneyNodeIsResolvedByExactIdentityAndPayloadHash() {
        RuntimeJourneyScenario scenario = RuntimeJourneyScenario.of(RuntimeJourneys.linear(), "member-1");
        RuntimeWorker worker = scenario.worker();

        RuntimeNodeResult outcome = worker.process(worker.claimNext("worker-1").task());

        NodeExecution node = outcome.nodeExecution();
        assertThat(node.nodeLogicalId()).isEqualTo(scenario.journey.startNodeLogicalId());
        assertThat(node.nodePayloadHash())
                .isEqualTo(scenario.journey.node(scenario.journey.startNodeLogicalId()).payloadHash());
        assertThat(node.entryAt()).isEqualTo(scenario.clock.instant());
    }

    @Test
    void aConditionFactRequestCarriesOnlyTheExactFrozenPredicateFields() {
        RuntimeJourneyScenario scenario =
                RuntimeJourneyScenario.of(conditionJourney(FlowConditionPack.ON_UNKNOWN), "m");
        ScriptedFactPort facts = scenario.facts;
        RuntimeWorker worker = scenario.worker();
        worker.process(worker.claimNext("worker-1").task());
        worker.process(worker.claimNext("worker-1").task());

        assertThat(facts.calls()).isEqualTo(1);
        assertThat(facts.lastRequest().factCode()).isEqualTo(FACT);
        assertThat(facts.lastRequest().valueType()).isEqualTo(FlowConditionPack.TYPE_STRING);
        assertThat(facts.lastRequest().nodeLogicalId()).isEqualTo("c");
        assertThat(facts.lastRequest().timeoutMillis())
                .isEqualTo(RuntimeJourneyScenario.FACT_TIMEOUT_MILLIS);
    }

    // ------------------------------------------------------------------ helpers

    private static RuntimeJourneys.Shape conditionJourney(String onUnknown) {
        return RuntimeJourneys.condition(
                FACT, FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD", onUnknown);
    }

    /** Runs the worker until one exact frozen node is processed, within a bounded step count. */
    private static RuntimeNodeResult workerAtNode(RuntimeJourneyScenario scenario, String nodeLogicalId) {
        RuntimeWorker worker = scenario.worker();
        for (int step = 0; step < 8; step++) {
            RuntimeClaimResult claim = worker.claimNext("worker-1");
            if (!claim.claimed()) {
                throw new IllegalStateException("the fixture Journey stopped before reaching the node");
            }
            RuntimeNodeResult outcome = worker.process(claim.task());
            if (outcome.nodeExecution().nodeLogicalId().equals(nodeLogicalId)) {
                return outcome;
            }
        }
        throw new IllegalStateException("the fixture Journey did not reach the node within its bound");
    }
}