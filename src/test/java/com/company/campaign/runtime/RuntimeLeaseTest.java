package com.company.campaign.runtime;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.runtime.testsupport.RuntimeJourneyScenario;
import com.company.campaign.runtime.testsupport.RuntimeJourneys;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 lease tests: deterministic ordering, one active lease per task, safe reclaim of an expired
 * lease, exact lease-token and Participant compare-and-set completion, concurrent claims and restart recovery.
 */
class RuntimeLeaseTest {

    private static final Duration LEASE = RuntimeJourneyScenario.LEASE;

    private static RuntimeJourneyScenario scenario() {
        return RuntimeJourneyScenario.of(RuntimeJourneys.linear(), "member-1");
    }

    private static RuntimeTask startTask(RuntimeJourneyScenario scenario) {
        return scenario.startTask("member-1");
    }

    @Test
    void aClaimLeasesTheOneDueTaskWithAnExplicitWorkerLeaseAndAttempt() {
        RuntimeJourneyScenario scenario = scenario();
        RuntimeTask due = startTask(scenario);
        RuntimeClaimResult claim = scenario.worker().claimNext("worker-1");

        assertThat(claim.outcome()).isEqualTo(RuntimeClaimOutcome.CLAIMED);
        RuntimeTask leased = claim.task();
        assertThat(leased.taskId()).isEqualTo(startTask(scenario).taskId());
        assertThat(leased.status()).isEqualTo(RuntimeTaskStatus.LEASED);
        assertThat(leased.workerId()).isEqualTo("worker-1");
        assertThat(leased.leaseToken()).isNotBlank();
        assertThat(leased.leaseExpiry()).isEqualTo(Instant.parse(RuntimeJourneyScenario.ENROLLED_AT).plus(LEASE));
        assertThat(leased.attemptNumber()).isEqualTo(1);
        assertThat(leased.revision()).isEqualTo(due.revision() + 1L);
        assertThat(scenario.store.findAudits(RuntimeJourneyScenario.TENANT))
                .anySatisfy(audit -> assertThat(audit.reasonCode())
                        .isEqualTo(RuntimeAuditRecord.REASON_LEASE_CLAIMED));
    }

    @Test
    void anUnexpiredLeaseCannotBeStolen() {
        RuntimeJourneyScenario scenario = scenario();
        scenario.worker().claimNext("worker-1");

        RuntimeClaimResult second = scenario.worker().claimNext("worker-2");

        assertThat(second.outcome()).isNotEqualTo(RuntimeClaimOutcome.CLAIMED);
        assertThat(second.task()).isNull();
        assertThat(scenario.store.findTask(
                RuntimeJourneyScenario.TENANT, startTask(scenario).taskId()).orElseThrow().workerId())
                .isEqualTo("worker-1");
    }

    @Test
    void anExpiredLeaseIsReclaimedAsANewAttemptWithoutChangingTheLogicalResult() {
        RuntimeJourneyScenario scenario = scenario();
        RuntimeTask first = scenario.worker().claimNext("worker-1").task();
        scenario.clock.advance(LEASE.plusSeconds(1));

        RuntimeClaimResult reclaimed = scenario.worker().claimNext("worker-2");

        assertThat(reclaimed.outcome()).isEqualTo(RuntimeClaimOutcome.CLAIMED);
        assertThat(reclaimed.task().taskId()).isEqualTo(first.taskId());
        assertThat(reclaimed.task().workerId()).isEqualTo("worker-2");
        assertThat(reclaimed.task().attemptNumber()).isEqualTo(2);
        assertThat(reclaimed.task().leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(scenario.audits()).anySatisfy(audit -> assertThat(audit.reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_LEASE_RECLAIMED));
    }

    @Test
    void aCompletionMustNameTheExactLeaseTokenAndTheExactParticipantRevision() {
        RuntimeJourneyScenario scenario = scenario();
        RuntimeTask leased = scenario.worker().claimNext("worker-1").task();

        assertThatThrownBy(() -> scenario.store.commitNodeCompletion(forged(
                scenario, leased, "forged-lease-token", 0L)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_LEASE_TOKEN_MISMATCH);

        assertThatThrownBy(() -> scenario.store.commitNodeCompletion(forged(
                scenario, leased, leased.leaseToken(), 1L)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue(
                        "code", RuntimeErrorCodes.RUNTIME_PARTICIPANT_CAS_CONFLICT);

        // Neither refusal wrote anything at all.
        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId)).isEmpty();
        assertThat(scenario.store.findTask(RuntimeJourneyScenario.TENANT, leased.taskId()).orElseThrow())
                .isEqualTo(leased);
    }

    @Test
    void aStaleReclaimCreatesNothingOnceAnotherWorkerCommitted() {
        RuntimeJourneyScenario scenario = scenario();
        String startNode = scenario.journey.startNodeLogicalId();
        RuntimeTask stale = scenario.worker().claimNext("worker-1").task();
        scenario.clock.advance(LEASE.plusSeconds(1));
        RuntimeTask reclaimed = scenario.worker().claimNext("worker-2").task();
        RuntimeNodeResult committed = scenario.worker().process(reclaimed);
        assertThat(committed.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);
        int attemptsAfterCommit = scenario.store.findAttempts(
                RuntimeJourneyScenario.TENANT, scenario.executionId).size();
        int auditsAfterCommit = scenario.store.auditCount();

        // The stale claim's work is refused rather than applied a second time.
        assertThatThrownBy(() -> scenario.worker().process(stale))
                .isInstanceOf(RuntimeBoundaryException.class);

        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(attemptsAfterCommit);
        assertThat(scenario.store.auditCount()).isEqualTo(auditsAfterCommit);
        assertThat(scenario.participant("member-1").currentNodeLogicalId()).isNotEqualTo(startNode);
        assertThat(scenario.store.findNodeExecutions(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(1);
    }

    @Test
    void aDuplicateCompletionReplaysTheCommittedResultAndWritesNothing() {
        RuntimeJourneyScenario scenario = scenario();
        RuntimeTask leased = scenario.worker().claimNext("worker-1").task();
        RuntimeNodeResult first = scenario.worker().process(leased);
        int auditsAfterFirst = scenario.store.auditCount();
        int attemptsAfterFirst = scenario.store.findAttempts(
                RuntimeJourneyScenario.TENANT, scenario.executionId).size();

        RuntimeNodeResult replay = scenario.worker().process(leased);

        assertThat(first.duplicate()).isFalse();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.disposition()).isEqualTo(RuntimeNodeDisposition.DUPLICATE);
        assertThat(replay.participant()).isEqualTo(scenario.participant("member-1"));
        assertThat(replay.task().status()).isEqualTo(RuntimeTaskStatus.COMPLETED);
        assertThat(scenario.store.auditCount()).isEqualTo(auditsAfterFirst);
        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(attemptsAfterFirst);
    }

    @Test
    void aRestartedWorkerOverTheSameStoreSafelyReclaimsAndFinishesTheWork() throws Exception {
        RuntimeJourneyScenario scenario = scenario();
        scenario.worker().claimNext("worker-1");
        scenario.clock.advance(LEASE.plusSeconds(1));

        RuntimeWorker restarted = scenario.worker();
        RuntimeClaimResult reclaimed = restarted.claimNext("worker-2");
        assertThat(reclaimed.outcome()).isEqualTo(RuntimeClaimOutcome.CLAIMED);

        RuntimeNodeResult advanced = restarted.process(reclaimed.task());
        assertThat(advanced.disposition()).isEqualTo(RuntimeNodeDisposition.ADVANCED);

        RuntimeClaimResult next = restarted.claimNext("worker-2");
        assertThat(next.claimed()).isTrue();
        RuntimeNodeResult terminated = restarted.process(next.task());
        assertThat(terminated.disposition()).isEqualTo(RuntimeNodeDisposition.TERMINATED);
        assertThat(terminated.participant().status()).isEqualTo(ParticipantStatus.COMPLETED);
        assertThat(terminated.execution().status()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void onlyOneOfManyConcurrentClaimsLeasesTheTask() throws Exception {
        RuntimeJourneyScenario scenario = scenario();

        List<RuntimeClaimResult> results = race(() -> scenario.worker().claimNext("worker-" + Thread
                .currentThread().getId()));

        long claimed = results.stream().filter(RuntimeClaimResult::claimed).count();
        assertThat(claimed).isEqualTo(1);
        assertThat(scenario.store.findTask(
                RuntimeJourneyScenario.TENANT, startTask(scenario).taskId()).orElseThrow().status())
                .isEqualTo(RuntimeTaskStatus.LEASED);
    }

    @Test
    void onlyOneOfManyConcurrentCompletionsMovesTheParticipant() throws Exception {
        RuntimeJourneyScenario scenario = scenario();
        RuntimeTask leased = scenario.worker().claimNext("worker-1").task();

        List<RuntimeNodeResult> results = race(() -> {
            try {
                return scenario.worker().process(leased);
            } catch (RuntimeBoundaryException contended) {
                // A bounded conflict is a legitimate outcome for a loser that read a pre-commit revision.
                return null;
            }
        });

        List<RuntimeNodeResult> applied = results.stream()
                .filter(result -> result != null && !result.duplicate())
                .toList();
        assertThat(applied).hasSize(1);
        assertThat(scenario.participant("member-1").currentNodeLogicalId())
                .isNotEqualTo(scenario.journey.startNodeLogicalId());
        assertThat(scenario.store.findAttempts(RuntimeJourneyScenario.TENANT, scenario.executionId)).hasSize(1);
        assertThat(scenario.store.findNodeExecutions(RuntimeJourneyScenario.TENANT, scenario.executionId))
                .hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> race(Callable<T> call) throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(threads);
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A hand-built completion unit that restates the current Execution and Participant unchanged and names an
     * explicit lease token and Participant revision.
     */
    private static NodeCompletionCommit forged(
            RuntimeJourneyScenario scenario, RuntimeTask leased, String leaseToken,
            long participantRevisionDelta) {
        JourneyParticipant participant = scenario.participant("member-1");
        return scenario.completion(
                leased, scenario.execution(), participant, leaseToken,
                participant.revision() + participantRevisionDelta, scenario.clock.instant());
    }
}
