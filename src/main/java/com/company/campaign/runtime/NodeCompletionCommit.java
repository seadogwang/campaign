package com.company.campaign.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One transactional node-completion unit: the next Execution, Participant, NodeExecution, attempt, task, any
 * successor or handoff records, the aggregate counters and the audit records, together.
 *
 * <p>Either the whole unit commits or nothing does. That is what makes "node result, attempt, Participant
 * position, next task, audit and aggregate commit atomically" a property of the store rather than an ordering
 * a caller is trusted to respect.
 *
 * <p>The record re-proves the internal binding of the unit before a store ever sees it: every child record
 * belongs to the commit's own tenant, Execution and Participant, the attempt witnesses exactly the node
 * execution and attempt number the task was claimed at, and the counters agree with the Execution's frozen
 * audience. The store re-proves the things only it can see — the compare-and-set revisions, the exact lease
 * token, the uniqueness of every new identity and the deterministic aggregate over the whole Participant set.
 */
public record NodeCompletionCommit(
        String tenantId,
        String executionKey,
        String executionId,
        long expectedExecutionRevision,
        CampaignExecution execution,
        String participantId,
        long expectedParticipantRevision,
        String expectedParticipantNodeLogicalId,
        JourneyParticipant participant,
        String taskId,
        long expectedTaskRevision,
        String leaseToken,
        RuntimeTask task,
        NodeExecution nodeExecution,
        NodeAttempt attempt,
        List<RuntimeTask> nextTasks,
        List<ActionHandoff> handoffs,
        ExecutionCounters counters,
        List<RuntimeAuditRecord> audits
) {

    public NodeCompletionCommit {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireNonNegative(expectedExecutionRevision, "expectedExecutionRevision");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireNonNegative(expectedParticipantRevision, "expectedParticipantRevision");
        RuntimeSupport.requireIdentifier(expectedParticipantNodeLogicalId, "expectedParticipantNodeLogicalId");
        RuntimeSupport.requireIdentifier(taskId, "taskId");
        RuntimeSupport.requireNonNegative(expectedTaskRevision, "expectedTaskRevision");
        RuntimeSupport.requireIdentifier(leaseToken, "leaseToken");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(nodeExecution, "nodeExecution");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(counters, "counters");
        nextTasks = RuntimeSupport.immutable(
                nextTasks, RuntimeVersions.MAX_NEXT_TASKS_PER_COMMIT, "nextTasks");
        handoffs = RuntimeSupport.immutable(
                handoffs, RuntimeVersions.MAX_HANDOFFS_PER_COMMIT, "handoffs");
        audits = RuntimeSupport.immutable(audits, RuntimeVersions.MAX_AUDIT_RECORDS, "audits");

        if (!execution.tenantId().equals(tenantId) || !execution.executionKey().equals(executionKey)
                || !execution.executionId().equals(executionId)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "executionBinding");
        }
        if (!participant.tenantId().equals(tenantId) || !participant.executionId().equals(executionId)
                || !participant.participantId().equals(participantId)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "participantBinding");
        }
        if (!task.tenantId().equals(tenantId) || !task.executionId().equals(executionId)
                || !task.participantId().equals(participantId) || !task.taskId().equals(taskId)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "taskBinding");
        }
        if (!nodeExecution.tenantId().equals(tenantId)
                || !nodeExecution.executionId().equals(executionId)
                || !nodeExecution.participantId().equals(participantId)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "nodeBinding");
        }
        if (!attempt.tenantId().equals(tenantId) || !attempt.executionId().equals(executionId)
                || !attempt.participantId().equals(participantId)
                || !attempt.taskId().equals(taskId)
                || !attempt.nodeExecutionId().equals(nodeExecution.nodeExecutionId())
                || attempt.attemptNumber() != task.attemptNumber()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "attemptBinding");
        }
        if (!counters.tenantId().equals(tenantId) || !counters.executionId().equals(executionId)
                || counters.candidateCount() != execution.candidateCount()
                || counters.enrolledCount() != execution.enrolledCount()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "countersBinding");
        }
        Set<String> taskIds = new LinkedHashSet<>();
        for (RuntimeTask next : nextTasks) {
            if (!next.tenantId().equals(tenantId) || !next.executionId().equals(executionId)
                    || !next.participantId().equals(participantId) || !taskIds.add(next.taskId())
                    || next.taskId().equals(taskId)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "nextTaskBinding");
            }
        }
        Set<String> handoffIds = new LinkedHashSet<>();
        for (ActionHandoff handoff : handoffs) {
            if (!handoff.tenantId().equals(tenantId) || !handoff.executionId().equals(executionId)
                    || !handoff.participantId().equals(participantId)
                    || !handoffIds.add(handoff.handoffId())
                    || !handoff.nodeLogicalId().equals(nodeExecution.nodeLogicalId())) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "handoffBinding");
            }
        }
        for (RuntimeAuditRecord audit : audits) {
            if (!audit.tenantId().equals(tenantId)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "auditTenant");
            }
        }
    }
}
