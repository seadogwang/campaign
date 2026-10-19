package com.company.campaign.runtime;

import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import com.company.campaign.runtime.testsupport.ScriptedAudiencePort;
import org.junit.jupiter.api.Test;

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

/**
 * Focused T22 concurrency tests: one receipt and one Execution per accepted work item, and one winning
 * materialization per Execution revision, under genuinely concurrent callers.
 */
class RuntimeConcurrencyTest {

    private static final int THREADS = 8;

    private static final Instant AS_OF = RuntimeScenario.PUBLISHED_AT;

    private static RuntimeScenario published() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00"));
        scenario.publish();
        return scenario;
    }

    private static <T> List<T> race(Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(THREADS);
            for (int index = 0; index < THREADS; index++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>(THREADS);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRegistrationDeliveriesWriteExactlyOneReceipt() throws Exception {
        RuntimeScenario scenario = published();
        String eventId = scenario.registrationEvent().eventId();

        List<RuntimeDeliveryResult> results =
                race(() -> scenario.ingress().deliverRegistration(eventId));

        long created = results.stream().filter(result -> !result.duplicate()).count();
        assertThat(created).isEqualTo(1);
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
        assertThat(scenario.store.auditCount()).isEqualTo(1);
        assertThat(results).allSatisfy(result ->
                assertThat(result.inbox().sourceId()).isEqualTo(eventId));
    }

    @Test
    void concurrentOccurrenceDeliveriesCreateExactlyOneExecution() throws Exception {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);

        List<RuntimeDeliveryResult> results = race(
                () -> scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey));

        long created = results.stream().filter(result -> !result.duplicate()).count();
        assertThat(created).isEqualTo(1);
        assertThat(scenario.store.executionCount()).isEqualTo(1);
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
        assertThat(results).allSatisfy(result ->
                assertThat(result.execution().executionKey()).isEqualTo(executionKey));
    }

    @Test
    void concurrentMaterializationsHaveExactlyOneWinner() throws Exception {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey);
        Instant nominal = scenario.occurrence(executionKey).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1", "member-2"), nominal, nominal.minusSeconds(60)));

        List<MaterializationResult> winners = new ArrayList<>();
        int failures = 0;
        List<Object> outcomes = race(() -> {
            try {
                return (Object) scenario.materialization().materialize(scenario.tenantId(), executionKey);
            } catch (RuntimeBoundaryException rejected) {
                return (Object) rejected;
            }
        });
        for (Object outcome : outcomes) {
            if (outcome instanceof MaterializationResult result) {
                winners.add(result);
            } else {
                failures++;
            }
        }

        assertThat(winners).hasSize(1);
        assertThat(failures).isEqualTo(THREADS - 1);
        assertThat(scenario.store.snapshotMemberCount()).isEqualTo(2);
        assertThat(scenario.store.participantCount()).isEqualTo(2);
        assertThat(scenario.store.taskCount()).isEqualTo(2);
        assertThat(scenario.store.enrollmentCount()).isEqualTo(2);
        // One delivery audit and one materialization audit: the losing attempts wrote nothing at all.
        assertThat(scenario.store.auditCount()).isEqualTo(2);
        assertThat(winners.get(0).execution().status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void concurrentDeliveriesOfDistinctWorkItemsDoNotInterfere() throws Exception {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        String eventId = scenario.registrationEvent().eventId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeDeliveryResult> occurrence =
                    pool.submit(() -> scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey));
            Future<RuntimeDeliveryResult> registration =
                    pool.submit(() -> scenario.ingress().deliverRegistration(eventId));
            assertThat(occurrence.get(30, TimeUnit.SECONDS).disposition())
                    .isEqualTo(RuntimeDeliveryDisposition.EXECUTION_CREATED);
            assertThat(registration.get(30, TimeUnit.SECONDS).disposition())
                    .isEqualTo(RuntimeDeliveryDisposition.REGISTERED);
        } finally {
            pool.shutdownNow();
        }

        assertThat(scenario.store.inboxCount()).isEqualTo(2);
        assertThat(scenario.store.executionCount()).isEqualTo(1);
    }
}