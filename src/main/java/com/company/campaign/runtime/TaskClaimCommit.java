package com.company.campaign.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One transactional claim unit: the exact task revision a worker read, its explicit lease and its audit record.
 *
 * <p>The store re-proves inside the transaction what the worker could only observe outside it: that the task is
 * still at the expected revision, that it is genuinely due, and that any existing lease has expired. An
 * unexpired lease is never replaced, so this commit is the whole of the "one active lease per task" rule.
 *
 * <p>The lease expiry is not taken on trust: it must be exactly the claim instant plus the declared duration,
 * and that duration must lie inside the frozen bounded interval, so a lease can be neither instantaneous nor
 * unbounded.
 */
public record TaskClaimCommit(
        String tenantId,
        String taskId,
        long expectedTaskRevision,
        String workerId,
        Instant claimedAt,
        Duration leaseDuration,
        String leaseToken,
        Instant leaseExpiry,
        RuntimeAuditRecord audit
) {

    public TaskClaimCommit {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(taskId, "taskId");
        RuntimeSupport.requireNonNegative(expectedTaskRevision, "expectedTaskRevision");
        RuntimeSupport.requireIdentifier(workerId, "workerId");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(RuntimeVersions.MIN_LEASE_DURATION) < 0
                || leaseDuration.compareTo(RuntimeVersions.MAX_LEASE_DURATION) > 0) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "leaseDuration",
                    "A lease duration must lie inside its frozen bounded interval.");
        }
        RuntimeSupport.requireIdentifier(leaseToken, "leaseToken");
        Objects.requireNonNull(leaseExpiry, "leaseExpiry");
        if (!leaseExpiry.equals(claimedAt.plus(leaseDuration))) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "leaseExpiry",
                    "A lease expiry must be exactly the claim instant plus the declared duration.");
        }
        Objects.requireNonNull(audit, "audit");
        if (!audit.tenantId().equals(tenantId) || !audit.subjectId().equals(taskId)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "claimAudit");
        }
    }
}