package com.company.campaign.production.runtime;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * The bounded, in-process observability state of the production provider runtime host.
 *
 * <p>It owns the running flag and the cumulative aggregate counters of the cycles this host has run, and it reads
 * the four backlog/stall counts from the one narrow host {@link ProviderRuntimeObservabilityPort} when a snapshot
 * is requested. It never logs and never retains a tenant, member, recipient, Provider or work-item identity.
 *
 * <p>Every method is serialized on one monitor, so a snapshot is a consistent projection of the running state,
 * the last cycle and the cumulative counts. A host that cannot answer its backlog query is reported as zero
 * backlog rather than inventing a value, and the last cycle status is left unchanged.
 */
public final class ProviderRuntimeObservability {

    private final ProviderRuntimeObservabilityPort backlog;
    private final ContentHash configurationHash;

    private boolean running;
    private ProviderRuntimeCycleStatus lastCycleStatus = ProviderRuntimeCycleStatus.NEVER_RUN;
    private Instant lastCycleAt;
    private long tenantsScanned;
    private long runtimeProcessed;
    private long actionProcessed;
    private long runtimeFailed;
    private long actionFailed;

    public ProviderRuntimeObservability(
            ProviderRuntimeObservabilityPort backlog, ContentHash configurationHash) {
        this.backlog = Objects.requireNonNull(backlog, "backlog");
        this.configurationHash = Objects.requireNonNull(configurationHash, "configurationHash");
    }

    /** Marks the host running; called exactly once when the scheduler starts. */
    public synchronized void start() {
        running = true;
    }

    /** Marks the host stopped; called exactly once when the scheduler stops. */
    public synchronized void stop() {
        running = false;
    }

    /** Records the bounded result of one completed cycle and folds it into the cumulative counters. */
    public synchronized void recordCycle(ProviderRuntimeCycleReport report, Instant at) {
        Objects.requireNonNull(report, "report");
        lastCycleStatus = report.status();
        lastCycleAt = at;
        tenantsScanned = bounded(tenantsScanned + report.tenantsScanned());
        runtimeProcessed = bounded(runtimeProcessed + report.runtimeProcessed());
        actionProcessed = bounded(actionProcessed + report.actionProcessed());
        runtimeFailed = bounded(runtimeFailed + report.runtimeFailed());
        actionFailed = bounded(actionFailed + report.actionFailed());
    }

    /** The current bounded, redacted snapshot. */
    public synchronized ProviderRuntimeSnapshot snapshot() {
        ProviderRuntimeBacklog observed;
        try {
            ProviderRuntimeBacklog answer = backlog.totals();
            observed = answer == null ? ProviderRuntimeBacklog.empty() : answer;
        } catch (RuntimeException unavailable) {
            // A host that cannot answer is reported as no observed backlog, never as an invented value.
            observed = ProviderRuntimeBacklog.empty();
        }
        return new ProviderRuntimeSnapshot(
                ProviderRuntimeSnapshot.FORMAT_VERSION,
                running,
                lastCycleStatus,
                lastCycleAt,
                tenantsScanned,
                runtimeProcessed,
                actionProcessed,
                runtimeFailed,
                actionFailed,
                bounded(observed.backlog()),
                bounded(observed.leaseExpiries()),
                bounded(observed.unknownActions()),
                bounded(observed.needsReviewParticipants()),
                configurationHash);
    }

    private static long bounded(long value) {
        return Math.min(value, ProviderRuntimeSnapshot.MAX_COUNT);
    }

    /** Redacted diagnostics: no owner, member, recipient or provider value is printed. */
    @Override
    public String toString() {
        return "ProviderRuntimeObservability[configurationHash=" + configurationHash + ']';
    }
}