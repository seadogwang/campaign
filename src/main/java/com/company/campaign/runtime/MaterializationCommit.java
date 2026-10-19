package com.company.campaign.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One transactional materialization unit: the Execution's next state and every record it creates, together.
 *
 * <p>Either the whole unit commits — the Execution transition, the audience snapshot members, the new Campaign
 * enrollments, the Participants, their START tasks, the aggregate counters and the audit records — or nothing
 * does. A failed materialization is expressed as a commit with an empty record set and a {@code FAILED}
 * Execution, so "zero snapshot, enrollment, Participant and task writes" is the same transaction shape as a
 * success rather than a special case.
 *
 * <p>The record re-proves the internal binding of the unit before a store ever sees it: every child record
 * belongs to the commit's own tenant and Execution, every snapshot member, enrollment and Participant identity
 * is distinct, every Participant was newly enrolled by this commit, every task addresses a Participant this
 * commit created, and the counters agree with the Execution it commits. The store re-proves uniqueness against
 * already-stored state, which only the store can see.
 */
public record MaterializationCommit(
        String tenantId,
        String executionKey,
        long expectedExecutionRevision,
        CampaignExecution execution,
        List<AudienceSnapshotMember> snapshotMembers,
        List<CampaignMemberEnrollment> enrollments,
        List<JourneyParticipant> participants,
        List<RuntimeTask> tasks,
        ExecutionCounters counters,
        List<RuntimeAuditRecord> audits
) {

    public MaterializationCommit {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        RuntimeSupport.requireNonNegative(expectedExecutionRevision, "expectedExecutionRevision");
        Objects.requireNonNull(execution, "execution");
        snapshotMembers = RuntimeSupport.immutable(
                snapshotMembers, RuntimeVersions.MAX_AUDIENCE_MEMBERS, "snapshotMembers");
        enrollments = RuntimeSupport.immutable(
                enrollments, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "enrollments");
        participants = RuntimeSupport.immutable(
                participants, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "participants");
        tasks = RuntimeSupport.immutable(tasks, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "tasks");
        Objects.requireNonNull(counters, "counters");
        audits = RuntimeSupport.immutable(audits, RuntimeVersions.MAX_AUDIT_RECORDS, "audits");

        if (!execution.tenantId().equals(tenantId) || !execution.executionKey().equals(executionKey)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "executionBinding");
        }
        if (!counters.tenantId().equals(tenantId)
                || !counters.executionId().equals(execution.executionId())) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "countersBinding");
        }
        for (AudienceSnapshotMember member : snapshotMembers) {
            if (!member.tenantId().equals(tenantId) || !member.executionId().equals(execution.executionId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "snapshotBinding");
            }
        }
        Set<String> enrolledMemberIds = new LinkedHashSet<>();
        for (CampaignMemberEnrollment enrollment : enrollments) {
            if (!enrollment.tenantId().equals(tenantId)
                    || !enrollment.campaignId().equals(execution.campaignId())
                    || !enrollment.executionId().equals(execution.executionId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "enrollmentBinding");
            }
            if (!enrolledMemberIds.add(enrollment.memberId())) {
                throw RuntimeSupport.invalid(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "enrollments");
            }
        }
        Set<String> participantIds = new LinkedHashSet<>();
        for (JourneyParticipant participant : participants) {
            if (!participant.tenantId().equals(tenantId)
                    || !participant.executionId().equals(execution.executionId())
                    || !enrolledMemberIds.contains(participant.memberId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "participantBinding");
            }
            if (!participantIds.add(participant.participantId())) {
                throw RuntimeSupport.invalid(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "participants");
            }
        }
        Set<String> taskIds = new LinkedHashSet<>();
        for (RuntimeTask task : tasks) {
            if (!task.tenantId().equals(tenantId)
                    || !task.executionId().equals(execution.executionId())
                    || !participantIds.contains(task.participantId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "taskBinding");
            }
            if (!taskIds.add(task.taskId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "tasks");
            }
        }
        for (RuntimeAuditRecord audit : audits) {
            if (!audit.tenantId().equals(tenantId)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "auditTenant");
            }
        }
        if (counters.enrolledCount() != participants.size()
                || counters.candidateCount() != execution.candidateCount()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "counters");
        }
        if (execution.hasFailed()) {
            // A failed materialization writes no snapshot member, no enrollment, no Participant and no task.
            if (!snapshotMembers.isEmpty() || !enrollments.isEmpty() || !participants.isEmpty()
                    || !tasks.isEmpty()) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "failedUnit");
            }
        } else {
            // A successful materialization snapshots the whole accepted audience, never part of it. An audience
            // whose every candidate was already enrolled still snapshots in full and enrolls nobody.
            if (snapshotMembers.size() != execution.candidateCount()) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "snapshotCount");
            }
            if (participants.size() != enrollments.size() || tasks.size() != enrollments.size()) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "materializationShape");
            }
        }
    }

    /** True when this commit claims no materialized member and no enrollment at all. */
    public boolean isEmptyUnit() {
        return snapshotMembers.isEmpty() && enrollments.isEmpty() && participants.isEmpty()
                && tasks.isEmpty();
    }
}
