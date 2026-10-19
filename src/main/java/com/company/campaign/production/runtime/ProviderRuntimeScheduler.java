package com.company.campaign.production.runtime;

import com.company.campaign.runtime.RuntimeSupport;
import com.company.campaign.shared.error.ContractException;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one bounded, default-off background scheduler of the production provider runtime host.
 *
 * <p>It owns exactly one single-thread scheduled executor with one periodic task and no unbounded queue: only a
 * single fixed-delay task is ever submitted, so the queue can hold at most that one task. It never uses an ambient
 * common pool. The worker identity is the stable, server-generated value the host derived from its configuration,
 * never a random or caller-supplied value.
 *
 * <p>Cycles never overlap: a guarded flag makes a scheduled tick a no-op while the previous cycle is still
 * running, and the task is scheduled with a fixed delay so a slow cycle defers the next one rather than queuing
 * behind it. {@link #runScheduledCycle()} runs the same guarded cycle and exists so a test or an operator can
 * drive a cycle deterministically without sleeping.
 *
 * <p>{@link #close()} stops gracefully: the periodic task is cancelled, no further cycle starts, an in-flight
 * cycle is allowed to finish, and only an owned executor is shut down.
 *
 * <p>It is a {@link SmartLifecycle}: once the host has separately opted into scheduling, the bean starts its one
 * periodic task as the application context refreshes and stops it as the context closes. Nothing schedules
 * unless the scheduler property is set, so the default application creates no thread and no executor at all.
 */
public final class ProviderRuntimeScheduler implements SmartLifecycle, AutoCloseable {

    /** The thread name prefix of the one owned executor; it names no tenant or owner value. */
    public static final String THREAD_NAME = "campaign-provider-runtime-scheduler";

    private final ProviderRuntimeCycle cycle;
    private final ProviderRuntimeObservability observability;
    private final Clock clock;
    private final String workerId;
    private final Duration cadence;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cycleInProgress = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

    public ProviderRuntimeScheduler(
            ProviderRuntimeCycle cycle,
            ProviderRuntimeObservability observability,
            Clock clock,
            Duration cadence,
            String workerId,
            ScheduledExecutorService executor,
            boolean ownsExecutor) {
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = RuntimeSupport.requireIdentifier(workerId, "workerId");
        Objects.requireNonNull(cadence, "cadence");
        if (cadence.compareTo(ProductionProviderRuntimeProperties.MIN_SCHEDULER_CADENCE) < 0
                || cadence.compareTo(ProductionProviderRuntimeProperties.MAX_SCHEDULER_CADENCE) > 0) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.SCHEDULER_INVALID,
                    "A scheduler cadence must lie inside its frozen bounded interval.");
        }
        this.cadence = cadence;
        this.ownsExecutor = ownsExecutor;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * The one bounded single-thread scheduled executor this host owns.
     *
     * <p>Only a single periodic task is ever submitted to it. An uncaught failure on the periodic task is
     * swallowed by the task itself, so the executor never terminates and never prints a raw exception.
     */
    public static ScheduledExecutorService ownedExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, failure) -> {
                // Bounded: an uncaught task failure is never printed or propagated.
            });
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /** Starts the one bounded periodic cycle; a second call is a no-op. */
    public synchronized void startScheduler() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        observability.start();
        try {
            // The first cycle runs after one full cadence, so starting the host never performs work synchronously
            // and a test can start the scheduler without any cycle firing behind its back.
            scheduled.set(executor.scheduleWithFixedDelay(
                    this::runScheduledCycle, cadence.toMillis(), cadence.toMillis(), TimeUnit.MILLISECONDS));
        } catch (RuntimeException rejected) {
            started.set(false);
            observability.stop();
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.SCHEDULER_INVALID,
                    "The bounded scheduler could not start its single periodic task.", rejected);
        }
    }

    /**
     * Runs one guarded cycle at most, returning immediately when a previous cycle is still running.
     *
     * <p>This is the exact work a scheduled tick performs, exposed so a test or operator can drive it
     * deterministically: it never sleeps, never queues and never overlaps another cycle.
     */
    public void runScheduledCycle() {
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant at = clock.instant();
            observability.recordCycle(safeRun(), at);
        } finally {
            cycleInProgress.set(false);
        }
    }

    /** True while the periodic task is started. */
    public boolean running() {
        return started.get();
    }

    // ------------------------------------------------------------------ lifecycle

    /** The scheduler auto-starts whenever the host has opted into scheduling. */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** A {@link SmartLifecycle} start is the same one bounded start; a second call is a no-op. */
    @Override
    public void start() {
        startScheduler();
    }

    /** A {@link SmartLifecycle} stop is the same graceful stop as {@link #close()}. */
    @Override
    public void stop() {
        close();
    }

    @Override
    public void stop(Runnable callback) {
        close();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running();
    }

    /** True while a cycle is in progress, so a scheduled tick would be skipped. */
    public boolean cycleInProgress() {
        return cycleInProgress.get();
    }

    private ProviderRuntimeCycleReport safeRun() {
        try {
            ProviderRuntimeCycleReport report = cycle.runCycle(workerId);
            return report == null
                    ? new ProviderRuntimeCycleReport(
                            ProviderRuntimeCycleStatus.FAILED, 0, 0, 0, 0, 0,
                            List.of(ProductionProviderRuntimeErrorCodes.CYCLE_ITEM_FAILED))
                    : report;
        } catch (RuntimeException failure) {
            return new ProviderRuntimeCycleReport(
                    ProviderRuntimeCycleStatus.FAILED, 0, 0, 0, 0, 0,
                    List.of(failure instanceof ContractException contract
                            ? contract.code()
                            : ProductionProviderRuntimeErrorCodes.CYCLE_ITEM_FAILED));
        }
    }

    /**
     * Stops gracefully: no further cycle starts, an in-flight cycle is allowed to finish, and only an owned
     * executor is shut down. A second call is a no-op.
     */
    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            observability.stop();
        }
        if (ownsExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    /** Redacted diagnostics: the cadence and a bounded worker identity only. */
    @Override
    public String toString() {
        return "ProviderRuntimeScheduler[cadence=" + cadence + ", workerId=" + workerId + ']';
    }
}