package com.company.campaign.runtime;

import java.util.Objects;

/**
 * The safe outward result of one Runtime materialization.
 *
 * <p>It carries the Execution's committed state, its aggregate counters and the exact number of snapshot
 * members, enrollments, Participants and START tasks the transaction wrote. It carries no member identity, no
 * payload body and no host evidence value, so a host may log it safely.
 *
 * <p>The four counts are the whole observable effect of a materialization: a failed one reports zeros for
 * every record kind while still reporting the candidate count it evidenced, which is how "failed closed with
 * zero writes" is checkable from outside.
 */
public record MaterializationResult(
        CampaignExecution execution,
        ExecutionCounters counters,
        int snapshotMemberCount,
        int enrollmentCount,
        int participantCount,
        int taskCount
) {

    public MaterializationResult {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(counters, "counters");
    }

    /** True when the materialization failed closed and wrote no snapshot, enrollment, Participant or task. */
    public boolean failed() {
        return execution.hasFailed();
    }

    /** True when the frozen audience was empty; the Execution completed successfully with nothing to do. */
    public boolean emptyAudience() {
        return !failed() && execution.candidateCount() == 0;
    }

    /**
     * True when every candidate was already enrolled in this Campaign.
     *
     * <p>The materialization still snapshots the accepted audience in full and completes successfully, but
     * enrolls nobody and creates no Participant and no task.
     */
    public boolean allCandidatesAlreadyEnrolled() {
        return !failed() && execution.candidateCount() > 0 && enrollmentCount == 0;
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "MaterializationResult[status=" + execution.status()
                + ", candidates=" + execution.candidateCount()
                + ", snapshotMembers=" + snapshotMemberCount
                + ", enrollments=" + enrollmentCount
                + ", participants=" + participantCount
                + ", tasks=" + taskCount
                + ", failureCode=" + execution.failureCode() + ']';
    }
}