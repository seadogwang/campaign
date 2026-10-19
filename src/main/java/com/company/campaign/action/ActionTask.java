package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound durable ActionTask: bounded submission or reconciliation work addressed to an
 * external-action worker.
 *
 * <p>Its queue state is deliberately separate from business action state: {@code DUE}, {@code LEASED},
 * {@code DEFERRED}, {@code COMPLETED} and {@code DEAD_LETTER} say what the queue holds, while
 * {@link ActionExecution} says what the Campaign's external action is doing. Its identity is derived
 * deterministically from the business key, the action and the task kind, so the same work can never be
 * enqueued twice under two identities.
 *
 * <p>{@link #availableAt()} is the eligibility instant, so a bounded backoff is a persisted fact rather than a
 * sleep. A leased task carries the explicit worker identity, an opaque lease token and the lease expiry, and
 * those three exist together or not at all: an unexpired lease is never stolen, and a reclaim after an expired
 * lease is a new attempt rather than a silent continuation. {@link #toString()} prints no member, tenant or
 * Execution identity.
 */
public record ActionTask(
        String formatVersion,
        String taskId,
        String tenantId,
        String actionId,
        String idempotencyKey,
        ActionTaskKind kind,
        int cycle,
        ActionTaskStatus status,
        String reasonCode,
        Instant availableAt,
        int attemptNumber,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String workerId,
        String leaseToken,
        Instant leaseExpiry,
        ContentHash taskHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionTask {
        if (!ActionVersions.ACTION_TASK_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(taskId, "taskId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(kind, "kind");
        ActionSupport.requireCount(cycle, ActionVersions.MAX_POLICY_ATTEMPTS, "cycle");
        Objects.requireNonNull(status, "status");
        ActionSupport.requireMachineCode(reasonCode, "reasonCode");
        Objects.requireNonNull(availableAt, "availableAt");
        ActionSupport.requireNonNegative(attemptNumber, "attemptNumber");
        ActionSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        boolean leased = status == ActionTaskStatus.LEASED;
        if (leased != (workerId != null)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_LEASE_INVALID, "lease");
        }
        if (leased) {
            ActionSupport.requireIdentifier(workerId, "workerId");
            ActionSupport.requireIdentifier(leaseToken, "leaseToken");
            Objects.requireNonNull(leaseExpiry, "leaseExpiry");
            if (!leaseExpiry.isAfter(availableAt)) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_LEASE_INVALID, "leaseExpiry");
            }
        }
        Objects.requireNonNull(taskHash, "taskHash");
        if (!taskHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, taskId, tenantId, actionId, idempotencyKey, kind, cycle, status, reasonCode,
                availableAt, attemptNumber, revision, createdAt, updatedAt, workerId, leaseToken,
                leaseExpiry)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "taskHash");
        }
    }

    /**
     * Enqueues one task of one kind and one cycle for one action.
     *
     * <p>The cycle is what lets a bounded safe retry enqueue fresh submission work after the previous
     * submission task completed, while a task that is merely rescheduled reuses its own identity.
     */
    public static ActionTask due(
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionTaskKind kind,
            int cycle,
            Instant availableAt) {
        Objects.requireNonNull(kind, "kind");
        String taskId = taskId(actionId, kind, cycle);
        return build(taskId, tenantId, actionId, idempotencyKey, kind, cycle, ActionTaskStatus.DUE,
                kind.reasonCode(), availableAt, 0, 0L, availableAt, availableAt, null, null, null);
    }

    /** The one deterministic task identity of one cycle of one kind of work addressed to one action. */
    public static String taskId(String actionId, ActionTaskKind kind, int cycle) {
        Objects.requireNonNull(kind, "kind");
        return ActionSupport.derivedId("acttask", List.of(
                ActionVersions.ACTION_TASK_ID_VERSION, actionId, kind.code(), cycle));
    }

    /** The one deterministic opaque lease token of one claim of one action task. */
    public static String leaseToken(
            String workerId, String taskId, int attemptNumber, Instant claimedAt, Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        return ActionSupport.derivedId("actlease", List.of(
                ActionVersions.ACTION_LEASE_TOKEN_VERSION, workerId, taskId, attemptNumber, claimedAt,
                leaseDuration.toString()));
    }

    /** True when this task is due at the given instant and a worker may claim it. */
    public boolean isClaimableAt(Instant claimInstant) {
        Objects.requireNonNull(claimInstant, "claimInstant");
        if (status == ActionTaskStatus.DUE || status == ActionTaskStatus.DEFERRED) {
            return !availableAt.isAfter(claimInstant);
        }
        if (status == ActionTaskStatus.LEASED) {
            return !leaseExpiry.isAfter(claimInstant);
        }
        return false;
    }

    /** The same task under one explicit lease, taken from an eligible state. */
    public ActionTask leased(String newWorkerId, String newLeaseToken, Instant newLeaseExpiry, Instant at) {
        if (!status.reaches(ActionTaskStatus.LEASED)) {
            throw conflict();
        }
        return revisioned(ActionTaskStatus.LEASED, reasonCode, availableAt, attemptNumber + 1, newWorkerId,
                newLeaseToken, newLeaseExpiry, at);
    }

    /** The same task re-leased after its previous lease expired, without changing the logical result. */
    public ActionTask reclaimed(
            String newWorkerId, String newLeaseToken, Instant newLeaseExpiry, Instant at) {
        if (status != ActionTaskStatus.LEASED || leaseExpiry == null) {
            throw conflict();
        }
        return revisioned(ActionTaskStatus.LEASED, reasonCode, availableAt, attemptNumber + 1, newWorkerId,
                newLeaseToken, newLeaseExpiry, at);
    }

    /** The same task completed once by the exact lease that owned it. */
    public ActionTask completed(Instant at) {
        requireLeaseOwner();
        return revisioned(ActionTaskStatus.COMPLETED, kind.completionReasonCode(), availableAt, attemptNumber,
                null, null, null, at);
    }

    /** The same task returned to {@code DUE} at one exact bounded retry instant, with its lease released. */
    public ActionTask retryDue(Instant newAvailableAt, String newReasonCode, Instant at) {
        requireLeaseOwner();
        Objects.requireNonNull(newAvailableAt, "newAvailableAt");
        return revisioned(ActionTaskStatus.DUE,
                ActionSupport.requireMachineCode(newReasonCode, "reasonCode"), newAvailableAt,
                attemptNumber, null, null, null, at);
    }

    /**
     * The same already-queued task moved to one exact new availability instant, without a lease.
     *
     * <p>It is the host-only path a reconcile or safe-retry command uses to make bounded work due at a known
     * instant, and it is legal only from an outstanding state: a lease that was taken is never silently
     * rewritten.
     */
    public ActionTask rescheduled(Instant newAvailableAt, String newReasonCode, Instant at) {
        if (status == ActionTaskStatus.LEASED || status == ActionTaskStatus.COMPLETED
                || status == ActionTaskStatus.DEAD_LETTER) {
            throw conflict();
        }
        Objects.requireNonNull(newAvailableAt, "newAvailableAt");
        return revisioned(ActionTaskStatus.DUE,
                ActionSupport.requireMachineCode(newReasonCode, "reasonCode"), newAvailableAt, attemptNumber,
                null, null, null, at);
    }

    /** The same task guarded as {@code DEFERRED} because its Campaign is paused. */
    public ActionTask deferred(Instant at) {
        if (!status.reaches(ActionTaskStatus.DEFERRED)) {
            throw conflict();
        }
        return revisioned(ActionTaskStatus.DEFERRED, reasonCode, availableAt, attemptNumber, null, null, null,
                at);
    }

    /** The same guarded task made eligible again by a resume, without shifting its availability instant. */
    public ActionTask released(Instant at) {
        if (status != ActionTaskStatus.DEFERRED) {
            throw conflict();
        }
        return revisioned(ActionTaskStatus.DUE, reasonCode, availableAt, attemptNumber, null, null, null, at);
    }

    /** The same task dead-lettered once every bounded attempt was spent, keeping its bounded reason. */
    public ActionTask deadLettered(String newReasonCode, Instant at) {
        requireLeaseOwner();
        return revisioned(ActionTaskStatus.DEAD_LETTER,
                ActionSupport.requireMachineCode(newReasonCode, "reasonCode"), availableAt, attemptNumber,
                null, null, null, at);
    }

    private void requireLeaseOwner() {
        if (status != ActionTaskStatus.LEASED || leaseToken == null) {
            throw conflict();
        }
    }

    private ActionBoundaryException conflict() {
        return new ActionBoundaryException(
                ActionErrorCodes.ACTION_TRANSITION_INVALID, "taskStatus",
                "An ActionTask transition is not one the frozen queue state machine allows.");
    }

    private static ActionTask build(
            String taskId,
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionTaskKind kind,
            int cycle,
            ActionTaskStatus status,
            String reasonCode,
            Instant availableAt,
            int attemptNumber,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            String workerId,
            String leaseToken,
            Instant leaseExpiry) {
        return new ActionTask(
                ActionVersions.ACTION_TASK_FORMAT_VERSION,
                taskId, tenantId, actionId, idempotencyKey, kind, cycle, status, reasonCode, availableAt,
                attemptNumber, revision, createdAt, updatedAt, workerId, leaseToken, leaseExpiry,
                CANONICAL_JSON.hash(material(
                        ActionVersions.ACTION_TASK_FORMAT_VERSION, taskId, tenantId, actionId, idempotencyKey,
                        kind, cycle, status, reasonCode, availableAt, attemptNumber, revision, createdAt,
                        updatedAt, workerId, leaseToken, leaseExpiry)));
    }

    private ActionTask revisioned(
            ActionTaskStatus target,
            String newReasonCode,
            Instant newAvailableAt,
            int newAttemptNumber,
            String newWorkerId,
            String newLeaseToken,
            Instant newLeaseExpiry,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return build(taskId, tenantId, actionId, idempotencyKey, kind, cycle, target, newReasonCode,
                newAvailableAt, newAttemptNumber, revision + 1L, createdAt, at, newWorkerId, newLeaseToken,
                newLeaseExpiry);
    }

    private static Material material(
            String formatVersion,
            String taskId,
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionTaskKind kind,
            int cycle,
            ActionTaskStatus status,
            String reasonCode,
            Instant availableAt,
            int attemptNumber,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            String workerId,
            String leaseToken,
            Instant leaseExpiry) {
        return new Material(formatVersion, taskId, tenantId, actionId, idempotencyKey, kind, cycle, status,
                reasonCode, availableAt, attemptNumber, revision, createdAt, updatedAt, workerId, leaseToken,
                leaseExpiry);
    }

    /** Redacted diagnostics: no member, tenant or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionTask[taskId=" + taskId
                + ", kind=" + kind
                + ", cycle=" + cycle
                + ", status=" + status
                + ", reasonCode=" + reasonCode
                + ", availableAt=" + availableAt
                + ", attemptNumber=" + attemptNumber
                + ", revision=" + revision
                + ", leaseExpiry=" + leaseExpiry
                + ", taskHash=" + taskHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String taskId,
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionTaskKind kind,
            int cycle,
            ActionTaskStatus status,
            String reasonCode,
            Instant availableAt,
            int attemptNumber,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            String workerId,
            String leaseToken,
            Instant leaseExpiry) {
    }
}