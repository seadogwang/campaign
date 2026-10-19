package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.createPlan;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 requirement 4: Apply is idempotent and concurrent Applies of the same exact Plan create exactly one
 * Draft revision and one receipt.
 */
class ApplyServiceIdempotencyTest {

    @Test
    void repeatingAnExactApplyReturnsTheOriginalReceiptAndMutatesNothing() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        ApplyResult first = service.apply(command(plan), LifecycleTestFixtures.context());
        int auditAfterFirst = audit.size();
        ApplyReceipt receiptAfterFirst = store.findApplyReceipt(plan.planId()).orElseThrow();

        ApplyResult second = service.apply(command(plan), LifecycleTestFixtures.context());

        // The exact repeat returns the same safe projection over the same original receipt.
        assertThat(second).isEqualTo(first);
        assertThat(second.clientRefBindings()).containsExactlyElementsOf(first.clientRefBindings());
        assertThat(store.findApplyReceipt(plan.planId()).orElseThrow())
                .isEqualTo(receiptAfterFirst);

        // One Draft revision, one receipt and no second audit mutation.
        assertThat(store.draftCount()).isEqualTo(1);
        assertThat(store.receiptCount()).isEqualTo(1);
        assertThat(audit.size()).isEqualTo(auditAfterFirst);
        SavedCampaignDraft draft = store.findDraft(first.campaignId()).orElseThrow();
        assertThat(draft.draftRevision()).isEqualTo(1L);
    }

    @Test
    void concurrentAppliesOfTheSamePlanCreateExactlyOneRevisionAndReceipt() throws Exception {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        List<ApplyResult> results = runConcurrently(
                () -> service.apply(command(plan), LifecycleTestFixtures.context()), 8);

        assertThat(store.draftCount()).isEqualTo(1);
        assertThat(store.receiptCount()).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.get(0)));

        // Every racer that lost the compare-and-set was handed the one committed receipt, not a second one.
        ApplyReceipt committed = store.findApplyReceipt(plan.planId()).orElseThrow();
        assertThat(committed.campaignId()).isEqualTo(results.get(0).campaignId());
        assertThat(committed.draftRevision()).isEqualTo(1L);
        assertThat(store.findDraft(results.get(0).campaignId()).orElseThrow().draftRevision())
                .isEqualTo(1L);
    }

    @Test
    void concurrentConflictingEditsAdvanceExactlyOneRevision() throws Exception {
        CampaignPlan create = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        var plans = LifecycleTestFixtures.planStore(create);
        ApplyService service = LifecycleTestFixtures.service(plans, store);

        ApplyResult created = service.apply(command(create), LifecycleTestFixtures.context());
        SavedCampaignDraft base = store.findDraft(created.campaignId()).orElseThrow();

        CampaignPlan first = LifecycleTestFixtures.editPlan(base, "first");
        CampaignPlan second = LifecycleTestFixtures.editPlan(base, "second");
        plans.store(first);
        plans.store(second);

        // Both writers read revision 1; only one write may win the compare-and-set.
        List<Integer> outcomes = runConcurrentlyCounting(
                List.of(command(first), command(second)), service, LifecycleTestFixtures.context());

        assertThat(outcomes).containsExactlyInAnyOrder(1, 0);
        assertThat(store.draftCount()).isEqualTo(1);
        assertThat(store.receiptCount()).isEqualTo(2);
        assertThat(store.findDraft(base.campaignId()).orElseThrow().draftRevision()).isEqualTo(2L);
    }

    private static List<ApplyResult> runConcurrently(java.util.concurrent.Callable<ApplyResult> work,
            int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApplyResult>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<ApplyResult> results = new ArrayList<>();
            for (Future<ApplyResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ApplyCommand command(CampaignPlan plan) {
        return new ApplyCommand(plan.planId(), plan.planHash());
    }

    /** Runs one Apply per command concurrently and reports 1 for an accepted Apply and 0 for a rejection. */
    private static List<Integer> runConcurrentlyCounting(
            List<ApplyCommand> commands, ApplyService service,
            com.company.campaign.security.context.TrustedExecutionContext context) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(commands.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (ApplyCommand command : commands) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.apply(command, context);
                        return 1;
                    } catch (RuntimeException rejection) {
                        return 0;
                    }
                }));
            }
            start.countDown();
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}