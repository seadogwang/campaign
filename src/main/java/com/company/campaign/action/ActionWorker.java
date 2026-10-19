package com.company.campaign.action;

import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.RuntimeTask;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The external-action worker: it ingests outstanding T22 handoffs and drives one durable action task at a time.
 *
 * <p>It is deliberately not a Journey Runtime worker and never claims one. It consumes only the bounded
 * {@code ACTION_READY} handoff a T22 worker left behind, and it claims only the durable action tasks its own
 * application service creates, so the two layers can never take each other's work.
 *
 * <p>Every claim rechecks the host-owned Campaign activation projection, so a pause stops a new Provider
 * submission or reconciliation from starting while a call already made still finishes and persists. A restart
 * is invisible here: the worker re-reads the persisted handoff, request, action and task, and never recomputes a
 * request or mints a second identity.
 *
 * <p>{@link #toString()} prints no owner value.
 */
public final class ActionWorker {

    private final ActionRuntimePort runtime;
    private final ActionApplicationService service;
    private final Clock clock;
    private final int ingestLimit;

    public ActionWorker(
            ActionRuntimePort runtime,
            ActionApplicationService service,
            Clock clock,
            int ingestLimit) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (ingestLimit < 1 || ingestLimit > ActionVersions.MAX_CLAIM_CANDIDATES) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, "ingestLimit",
                    "An ingest scan must be bounded and positive.");
        }
        this.ingestLimit = ingestLimit;
    }

    /**
     * Reserves every outstanding handoff of one tenant that has no action yet.
     *
     * <p>It is idempotent: a handoff whose action already exists is an exact duplicate that writes nothing and
     * never reaches a Provider.
     *
     * @return the number of handoffs this call reserved
     */
    public int ingest(String tenantId, String workerId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(workerId, "workerId");
        List<RuntimeTask> outstanding =
                runtime.findActionReadyTasks(tenantId, clock.instant(), ingestLimit);
        int reserved = 0;
        for (RuntimeTask task : outstanding) {
            Optional<ActionHandoff> handoff = runtime.findHandoff(
                    tenantId, task.executionId(), task.participantId(), task.nodeLogicalId());
            if (handoff.isPresent() && !service.isPrepared(handoff.get())) {
                ActionPreparation preparation = service.prepare(handoff.get());
                if (!preparation.duplicate()) {
                    reserved++;
                }
            }
        }
        return reserved;
    }

    /**
     * Ingests any outstanding handoff, then claims and processes one due action task.
     *
     * @return the processed outcome, or empty when nothing could be claimed
     */
    public Optional<ActionOutcome> runOnce(String tenantId, String workerId) {
        ingest(tenantId, workerId);
        ActionClaimResult claim = service.claimNext(tenantId, workerId);
        if (!claim.claimed()) {
            return Optional.empty();
        }
        return Optional.of(service.process(claim.task()));
    }

    /** Processes one already-leased action task. */
    public ActionOutcome process(ActionTask task) {
        return service.process(task);
    }

    /** Redacted diagnostics: the worker holds no owner value. */
    @Override
    public String toString() {
        return "ActionWorker[ingestLimit=" + ingestLimit + ']';
    }
}