package com.company.campaign.production.runtime;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T35C2: the bounded, default-off, non-overlapping scheduler.
 *
 * <p>Every test injects or manually drives the executor, so no test sleeps: a long cadence is used and the cycle
 * is driven through {@link ProviderRuntimeScheduler#runScheduledCycle()}.
 */
class ProviderRuntimeSchedulerTest {

    private static final String WORKER_ID = "prw-00000000000000000000000000000000";
    private static final ContentHash CONFIGURATION_HASH =
            ContentHash.sha256("provider-runtime-config".getBytes(StandardCharsets.UTF_8));
    private static final Duration CADENCE = Duration.ofHours(1);

    @Test
    void startingAndStoppingIsGracefulAndTogglesTheRunningState() {
        ProviderRuntimeObservability observability = observability();
        ProviderRuntimeScheduler scheduler = scheduler(
                workerId -> completed(), observability, ProviderRuntimeScheduler.ownedExecutor(), true);

        assertThat(scheduler.running()).isFalse();
        scheduler.start();
        assertThat(scheduler.running()).isTrue();
        assertThat(observability.snapshot().running()).isTrue();

        scheduler.close();
        assertThat(scheduler.running()).isFalse();
        assertThat(observability.snapshot().running()).isFalse();
        // A second close is a no-op.
        scheduler.close();
    }

    @Test
    void cyclesNeverOverlap() {
        AtomicInteger cycles = new AtomicInteger();
        AtomicReference<ProviderRuntimeScheduler> reference = new AtomicReference<>();
        ProviderRuntimeCycle reentrant = workerId -> {
            cycles.incrementAndGet();
            // A nested tick must be skipped while this cycle is still running.
            reference.get().runScheduledCycle();
            return completed();
        };
        ProviderRuntimeScheduler scheduler =
                scheduler(reentrant, observability(), ProviderRuntimeScheduler.ownedExecutor(), true);
        reference.set(scheduler);

        scheduler.runScheduledCycle();

        assertThat(cycles).hasValue(1);
        assertThat(scheduler.cycleInProgress()).isFalse();
    }

    @Test
    void aManualCycleUpdatesTheBoundedObservabilityState() {
        ProviderRuntimeObservability observability = observability();
        ProviderRuntimeScheduler scheduler = scheduler(
                workerId -> new ProviderRuntimeCycleReport(
                        ProviderRuntimeCycleStatus.COMPLETED, 2, 3, 1, 0, 0, List.of()),
                observability, ProviderRuntimeScheduler.ownedExecutor(), true);

        scheduler.runScheduledCycle();

        ProviderRuntimeSnapshot snapshot = observability.snapshot();
        assertThat(snapshot.lastCycleStatus()).isEqualTo(ProviderRuntimeCycleStatus.COMPLETED);
        assertThat(snapshot.runtimeProcessed()).isEqualTo(3);
        assertThat(snapshot.actionProcessed()).isEqualTo(1);
        assertThat(snapshot.lastCycleAt()).isNotNull();
    }

    @Test
    void aFailingCycleIsIsolatedIntoABoundedStatus() {
        ProviderRuntimeObservability observability = observability();
        ProviderRuntimeScheduler scheduler = scheduler(
                workerId -> {
                    throw new IllegalStateException("raw failure detail that must never surface");
                },
                observability, ProviderRuntimeScheduler.ownedExecutor(), true);

        scheduler.runScheduledCycle();

        assertThat(observability.snapshot().lastCycleStatus()).isEqualTo(ProviderRuntimeCycleStatus.FAILED);
        assertThat(observability.snapshot().toString()).doesNotContain("raw failure detail");
    }

    @Test
    void theOwnedExecutorIsSingleThreadedAndBounded() {
        ScheduledExecutorService executor = ProviderRuntimeScheduler.ownedExecutor();
        try {
            assertThat(executor).isInstanceOf(ScheduledThreadPoolExecutor.class);
            ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) executor;
            assertThat(scheduled.getCorePoolSize()).isEqualTo(1);
            assertThat(scheduled.getRemoveOnCancelPolicy()).isTrue();

            ProviderRuntimeScheduler scheduler =
                    scheduler(workerId -> completed(), observability(), executor, true);
            scheduler.start();
            // Only the one bounded periodic task is ever queued.
            assertThat(scheduled.getQueue().size()).isLessThanOrEqualTo(1);
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anOutOfBoundsCadenceIsRefused() {
        assertThatThrownBy(() -> new ProviderRuntimeScheduler(
                workerId -> completed(), observability(), Clock.systemUTC(), Duration.ofMillis(1), WORKER_ID,
                ProviderRuntimeScheduler.ownedExecutor(), true))
                .isInstanceOf(ProductionProviderRuntimeConfigurationException.class)
                .satisfies(failure -> assertThat(
                        ((ProductionProviderRuntimeConfigurationException) failure).code())
                        .isEqualTo(ProductionProviderRuntimeErrorCodes.SCHEDULER_INVALID));
    }

    @Test
    void aCallerSuppliedWorkerIdentityThatIsNotBoundedIsRefused() {
        assertThatThrownBy(() -> new ProviderRuntimeScheduler(
                workerId -> completed(), observability(), Clock.systemUTC(), CADENCE, "not a valid identifier",
                ProviderRuntimeScheduler.ownedExecutor(), true))
                .isInstanceOf(RuntimeException.class);
    }

    private static ProviderRuntimeScheduler scheduler(
            ProviderRuntimeCycle cycle,
            ProviderRuntimeObservability observability,
            ScheduledExecutorService executor,
            boolean ownsExecutor) {
        return new ProviderRuntimeScheduler(
                cycle, observability, Clock.systemUTC(), CADENCE, WORKER_ID, executor, ownsExecutor);
    }

    private static ProviderRuntimeObservability observability() {
        return new ProviderRuntimeObservability(ProviderRuntimeBacklog::empty, CONFIGURATION_HASH);
    }

    private static ProviderRuntimeCycleReport completed() {
        return new ProviderRuntimeCycleReport(
                ProviderRuntimeCycleStatus.COMPLETED, 0, 0, 0, 0, 0, List.of());
    }
}