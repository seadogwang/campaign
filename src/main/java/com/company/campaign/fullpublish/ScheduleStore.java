package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleAuditEvent;

import java.util.List;
import java.util.Optional;

/**
 * Host-only transactional port for the durable scheduling state a full publication projects and the bounded
 * scheduling operations a host applies to it.
 *
 * <p>It is deliberately narrow so a later PostgreSQL adapter can implement it directly: one read of one
 * Campaign's active projection, one read of its already-committed occurrences, and one compare-and-set write
 * that replaces the projection at its expected revision, commits the due decisions and records the audit event
 * as a single unit — or does none of it.
 *
 * <p>It carries no Execution, no Participant, no enrollment, no worker lease, no provider call and no message
 * delivery. There is no method that accepts a caller's Schedule, Journey, occurrence, readiness fact or
 * trusted context: the only write path takes server-produced material.
 */
public interface ScheduleStore {

    /** The active schedule projection of a Campaign, or empty when it holds none. */
    Optional<CampaignScheduleState> findScheduleState(String campaignId);

    /** Every occurrence already committed for a Campaign, in deterministic nominal-instant order. */
    List<ScheduledOccurrenceRecord> findCommittedOccurrences(String campaignId);

    /**
     * Replaces one Campaign's active schedule projection at its expected revision and commits the given
     * occurrence decisions, recording the audit event as one unit.
     *
     * <p>The compare-and-set is re-proved inside the transaction rather than trusting the caller's precheck: a
     * projection that moved under the operation is refused as a conflict with no mutation. An occurrence whose
     * execution key is already committed is idempotently skipped rather than duplicated or rewritten, and an
     * already-committed record is never reassigned to another published version.
     *
     * <p>{@code replacement} may be {@code null} when only occurrence decisions are committed, in which case
     * the stored projection is left exactly as it was. When the audit collaborator fails, nothing is written.
     *
     * @param expectedStateRevision the revision the caller read, or {@code 0} when it expects no projection
     * @return the stored projection after the commit, or empty when the Campaign has none
     */
    Optional<CampaignScheduleState> commitScheduleOperation(
            String campaignId,
            long expectedStateRevision,
            CampaignScheduleState replacement,
            List<ScheduledOccurrenceRecord> records,
            LifecycleAuditEvent auditEvent);
}