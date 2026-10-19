package com.company.campaign.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One transactional external-action node completion: the whole of what finishes a {@code MESSAGE} or
 * {@code REWARD} node, as one unit.
 *
 * <p>It is the additive T22 hook T23 needs and nothing more. A frozen external-action node is claimed by no
 * local Runtime worker — its {@code ACTION_READY} task is deliberately not locally claimable — so its
 * completion cannot go through {@link NodeCompletionCommit}, which requires a worker lease and a node attempt.
 * This unit instead defers or retires the exact handed-off task, moves the external-action
 * {@link NodeExecution} to its committed state, moves the Participant to its committed state, optionally
 * enqueues the exact frozen successor task, restates the aggregate counters and carries the audit records,
 * all in one store transaction.
 *
 * <p>It carries no Provider result, no action outcome, no budget reservation, no message body and no reward
 * secret: T23's own records hold those, and this unit only binds the Runtime side of the same atomic commit to
 * them. The Runtime layer re-proves everything it can see — the identity of every child record, the
 * compare-and-set revisions, the frozen task kind, the legality of the node transition and the deterministic
 * aggregate over the whole Participant set — and never trusts a caller's precheck.
 */
public record ExternalActionCompletion(
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
        RuntimeTask task,
        NodeExecution nodeExecution,
        List<RuntimeTask> nextTasks,
        ExecutionCounters counters,
        List<RuntimeAuditRecord> audits
) {

    private static final Set<NodeExecutionStatus> EXTERNAL_ACTION_NODE_STATES =
            Set.of(NodeExecutionStatus.SUCCEEDED, NodeExecutionStatus.VERIFYING,
                    NodeExecutionStatus.FAILED, NodeExecutionStatus.NEEDS_REVIEW);

    public ExternalActionCompletion {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireNonNegative(expectedExecutionRevision, "expectedExecutionRevision");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireNonNegative(expectedParticipantRevision, "expectedParticipantRevision");
        RuntimeSupport.requireIdentifier(expectedParticipantNodeLogicalId, "expectedParticipantNodeLogicalId");
        RuntimeSupport.requireIdentifier(taskId, "taskId");
        RuntimeSupport.requireNonNegative(expectedTaskRevision, "expectedTaskRevision");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(nodeExecution, "nodeExecution");
        Objects.requireNonNull(counters, "counters");
        nextTasks = RuntimeSupport.immutable(
                nextTasks, RuntimeVersions.MAX_NEXT_TASKS_PER_COMMIT, "nextTasks");
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
        if (task.kind() != RuntimeTaskKind.ACTION_READY) {
            // Only the bounded external-action handoff may be retired here; local Journey work has its own unit.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "taskKind");
        }
        if (!nodeExecution.tenantId().equals(tenantId)
                || !nodeExecution.executionId().equals(executionId)
                || !nodeExecution.participantId().equals(participantId)
                || !nodeExecution.nodeLogicalId().equals(task.nodeLogicalId())) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "nodeBinding");
        }
        if (!EXTERNAL_ACTION_NODE_STATES.contains(nodeExecution.status())) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "nodeStatus");
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
        for (RuntimeAuditRecord audit : audits) {
            if (!audit.tenantId().equals(tenantId)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "auditTenant");
            }
        }
    }
}
