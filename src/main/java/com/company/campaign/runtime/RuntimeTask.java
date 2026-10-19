package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound RuntimeTask: bounded durable work addressed to a worker.
 *
 * <p>Its queue state is deliberately separate from business Execution state: {@code DUE}, {@code LEASED},
 * {@code DEFERRED}, {@code COMPLETED} and {@code DEAD_LETTER} say what the queue holds, while the Participant,
 * NodeExecution and Execution say what the Campaign is doing. A dead-lettered task keeps its source identity
 * and its bounded reason code, so abandoned work is attributable rather than merely lost.
 *
 * <p>Its identity is derived deterministically from the tenant, Execution, Participant, node and kind, so the
 * same work can never be enqueued twice under two identities. {@link #availableAt()} is the eligibility
 * instant: a claim considers only tasks whose availability has passed, in deterministic
 * {@code (availableAt, taskId)} order.
 *
 * <p>A leased task carries the explicit worker identity, an opaque lease token and the lease expiry, and those
 * three exist together or not at all. That is what makes "one active lease per task" and "expired leases are
 * reclaimed, unexpired ones are not stolen" checkable from the persisted record alone: a completion must name
 * the exact lease token it was claimed under, and an unexpired lease simply cannot be replaced.
 *
 * <p>{@link #attemptNumber()} counts actual claims, so a reclaim after an expired lease is a new attempt rather
 * than a silent continuation. {@link #toString()} prints no member, tenant, Campaign or Execution identity.
 */
public record RuntimeTask(
        String formatVersion,
        String taskId,
        String tenantId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        RuntimeTaskKind kind,
        RuntimeTaskStatus status,
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

    public RuntimeTask {
        if (!RuntimeVersions.TASK_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(taskId, "taskId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        RuntimeSupport.requireMachineCode(reasonCode, "reasonCode");
        Objects.requireNonNull(availableAt, "availableAt");
        RuntimeSupport.requireNonNegative(attemptNumber, "attemptNumber");
        RuntimeSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        boolean leased = status == RuntimeTaskStatus.LEASED;
        if (leased != (workerId != null)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "lease");
        }
        if (leased) {
            RuntimeSupport.requireIdentifier(workerId, "workerId");
            RuntimeSupport.requireIdentifier(leaseToken, "leaseToken");
            Objects.requireNonNull(leaseExpiry, "leaseExpiry");
            if (!leaseExpiry.isAfter(availableAt)) {
                // A lease must outlive the availability instant it was taken at; a zero or backwards lease is
                // not a lease at all.
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "leaseExpiry");
            }
        }
        Objects.requireNonNull(taskHash, "taskHash");
        if (!taskHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, taskId, tenantId, executionId, participantId, nodeLogicalId, kind, status,
                reasonCode, availableAt, attemptNumber, revision, createdAt, updatedAt, workerId, leaseToken,
                leaseExpiry)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "taskHash");
        }
    }

    /** Enqueues the one task a kind addresses to one Participant's frozen node. */
    public static RuntimeTask due(
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            RuntimeTaskKind kind,
            Instant availableAt) {
        Objects.requireNonNull(kind, "kind");
        String taskId = taskId(tenantId, executionId, participantId, nodeLogicalId, kind);
        return build(taskId, tenantId, executionId, participantId, nodeLogicalId, kind, RuntimeTaskStatus.DUE,
                kind.reasonCode(), availableAt, 0, 0L, availableAt, availableAt, null, null, null);
    }

    /** The one deterministic task identity of one kind of work addressed to one Participant's node. */
    public static String taskId(
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            RuntimeTaskKind kind) {
        Objects.requireNonNull(kind, "kind");
        return RuntimeSupport.derivedId("task", List.of(
                RuntimeVersions.TASK_ID_VERSION, tenantId, executionId, participantId, nodeLogicalId,
                kind.name()));
    }

    /** The one deterministic opaque lease token of one claim of one task. */
    public static String leaseToken(
            String workerId, String taskId, int attemptNumber, Instant claimedAt, Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        return RuntimeSupport.derivedId("lease", List.of(
                RuntimeVersions.LEASE_TOKEN_VERSION, workerId, taskId, attemptNumber, claimedAt,
                leaseDuration.toString()));
    }

    /** True when this task is eligible to be claimed or executed. */
    public boolean isEligible() {
        return status.isEligible();
    }

    /** True when this task is due at the given instant and a local worker may claim it. */
    public boolean isClaimableAt(Instant claimInstant) {
        Objects.requireNonNull(claimInstant, "claimInstant");
        if (!kind.locallyClaimable()) {
            return false;
        }
        if (status == RuntimeTaskStatus.DUE || status == RuntimeTaskStatus.DEFERRED) {
            return !availableAt.isAfter(claimInstant);
        }
        if (status == RuntimeTaskStatus.LEASED) {
            // Only an expired lease is reclaimable; an unexpired one is never stolen.
            return !leaseExpiry.isAfter(claimInstant);
        }
        return false;
    }

    /**
     * The same task under one explicit lease, taken from an eligible state.
     *
     * <p>The attempt number counts this claim, so a lease always witnesses which actual try it is.
     */
    public RuntimeTask leased(String newWorkerId, String newLeaseToken, Instant newLeaseExpiry, Instant at) {
        if (!status.reaches(RuntimeTaskStatus.LEASED)) {
            throw conflict();
        }
        return revisioned(RuntimeTaskStatus.LEASED, reasonCode, availableAt, attemptNumber + 1, newWorkerId,
                newLeaseToken, newLeaseExpiry, at);
    }

    /**
     * The same task re-leased after its previous lease expired, without changing the logical node result.
     *
     * <p>A reclaim is a real state change — a new attempt, a new token and a new expiry — even though the
     * closed queue status stays {@code LEASED}, so the frozen {@link RuntimeTaskStatus} machine is not widened
     * to represent it.
     */
    public RuntimeTask reclaimed(
            String newWorkerId, String newLeaseToken, Instant newLeaseExpiry, Instant at) {
        if (status != RuntimeTaskStatus.LEASED || leaseExpiry == null) {
            throw conflict();
        }
        return revisioned(RuntimeTaskStatus.LEASED, reasonCode, availableAt, attemptNumber + 1, newWorkerId,
                newLeaseToken, newLeaseExpiry, at);
    }

    /** The same task completed once by the exact lease that owned it. */
    public RuntimeTask completed(Instant at) {
        requireLeaseOwner();
        return revisioned(RuntimeTaskStatus.COMPLETED, completionReasonCode(), availableAt, attemptNumber,
                null, null, null, at);
    }

    /**
     * The same outstanding task retired by the host without a lease.
     *
     * <p>It exists for the one task kind no local worker may ever claim: the bounded {@code ACTION_READY}
     * handoff. The external-action layer is what finishes that work, and it never leases the handoff, so the
     * handoff is retired exactly once from its own eligible state instead of through a lease it cannot take.
     */
    public RuntimeTask retired(String newReasonCode, Instant at) {
        if (status != RuntimeTaskStatus.DUE && status != RuntimeTaskStatus.DEFERRED) {
            throw conflict();
        }
        Objects.requireNonNull(at, "at");
        return revisioned(RuntimeTaskStatus.COMPLETED,
                RuntimeSupport.requireMachineCode(newReasonCode, "reasonCode"), availableAt, attemptNumber,
                null, null, null, at);
    }

    /**
     * The same task returned to {@code DUE} at one exact bounded retry instant, with its lease released.
     *
     * <p>The bounded reason code the failure is retried under is persisted with it, so the retried work still
     * witnesses why it was deferred rather than merely that it was.
     */
    public RuntimeTask retryDue(Instant newAvailableAt, String newReasonCode, Instant at) {
        requireLeaseOwner();
        Objects.requireNonNull(newAvailableAt, "newAvailableAt");
        return revisioned(RuntimeTaskStatus.DUE,
                RuntimeSupport.requireMachineCode(newReasonCode, "reasonCode"), newAvailableAt,
                attemptNumber, null, null, null, at);
    }

    /** The same task guarded as {@code DEFERRED} because its Campaign is paused. */
    public RuntimeTask deferred(Instant at) {
        if (!status.reaches(RuntimeTaskStatus.DEFERRED)) {
            throw conflict();
        }
        return revisioned(RuntimeTaskStatus.DEFERRED, reasonCode, availableAt, attemptNumber, null, null,
                null, at);
    }

    /** The same guarded task made eligible again by a resume, without shifting its availability instant. */
    public RuntimeTask released(Instant at) {
        if (status != RuntimeTaskStatus.DEFERRED) {
            throw conflict();
        }
        return revisioned(RuntimeTaskStatus.DUE, reasonCode, availableAt, attemptNumber, null, null, null, at);
    }

    /** The same task dead-lettered once every bounded attempt was spent, keeping its bounded reason. */
    public RuntimeTask deadLettered(String newReasonCode, Instant at) {
        requireLeaseOwner();
        return revisioned(RuntimeTaskStatus.DEAD_LETTER,
                RuntimeSupport.requireMachineCode(newReasonCode, "reasonCode"), availableAt, attemptNumber,
                null, null, null, at);
    }

    /** The bounded machine reason code a terminal task of this kind records. */
    public String completionReasonCode() {
        return kind.completionReasonCode();
    }

    private void requireLeaseOwner() {
        if (status != RuntimeTaskStatus.LEASED || leaseToken == null) {
            throw conflict();
        }
    }

    private RuntimeBoundaryException conflict() {
        return new RuntimeBoundaryException(
                RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "taskStatus",
                "A RuntimeTask transition is not one the frozen queue state machine allows.");
    }

    private static RuntimeTask build(
            String taskId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            RuntimeTaskKind kind,
            RuntimeTaskStatus status,
            String reasonCode,
            Instant availableAt,
            int attemptNumber,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            String workerId,
            String leaseToken,
            Instant leaseExpiry) {
        return new RuntimeTask(
                RuntimeVersions.TASK_FORMAT_VERSION,
                taskId, tenantId, executionId, participantId, nodeLogicalId, kind, status, reasonCode,
                availableAt, attemptNumber, revision, createdAt, updatedAt, workerId, leaseToken, leaseExpiry,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.TASK_FORMAT_VERSION, taskId, tenantId, executionId, participantId,
                        nodeLogicalId, kind, status, reasonCode, availableAt, attemptNumber, revision, createdAt,
                        updatedAt, workerId, leaseToken, leaseExpiry)));
    }

    private RuntimeTask revisioned(
            RuntimeTaskStatus target,
            String newReasonCode,
            Instant newAvailableAt,
            int newAttemptNumber,
            String newWorkerId,
            String newLeaseToken,
            Instant newLeaseExpiry,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return build(taskId, tenantId, executionId, participantId, nodeLogicalId, kind, target, newReasonCode,
                newAvailableAt, newAttemptNumber, revision + 1L, createdAt, at, newWorkerId, newLeaseToken,
                newLeaseExpiry);
    }

    private static Material material(
            String formatVersion,
            String taskId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            RuntimeTaskKind kind,
            RuntimeTaskStatus status,
            String reasonCode,
            Instant availableAt,
            int attemptNumber,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            String workerId,
            String leaseToken,
            Instant leaseExpiry) {
        return new Material(formatVersion, taskId, tenantId, executionId, participantId, nodeLogicalId, kind,
                status, reasonCode, availableAt, attemptNumber, revision, createdAt, updatedAt, workerId,
                leaseToken, leaseExpiry);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "RuntimeTask[taskId=" + taskId
                + ", kind=" + kind
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
            String executionId,
            String participantId,
            String nodeLogicalId,
            RuntimeTaskKind kind,
            RuntimeTaskStatus status,
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