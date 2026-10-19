package com.company.campaign.runtime;

import java.util.List;
import java.util.Objects;

/**
 * What one node completion stored: the committed task, Participant, NodeExecution, Execution, counters and
 * handoffs, and whether the commit was an exact hash-equal duplicate that wrote nothing.
 *
 * <p>It is the addressee-safe outward form of the transaction: it carries no member identity, no fact value, no
 * template body and no Provider data, so a host may log it.
 */
public record NodeCompletionResult(
        RuntimeTask task,
        JourneyParticipant participant,
        NodeExecution nodeExecution,
        CampaignExecution execution,
        ExecutionCounters counters,
        List<ActionHandoff> handoffs,
        boolean duplicate
) {

    public NodeCompletionResult {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(nodeExecution, "nodeExecution");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(counters, "counters");
        handoffs = RuntimeSupport.immutable(
                handoffs, RuntimeVersions.MAX_HANDOFFS_PER_COMMIT, "handoffs");
    }

    /** True when the completion was an exact duplicate and reused the already committed result. */
    public boolean reused() {
        return duplicate;
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "NodeCompletionResult[taskStatus=" + task.status()
                + ", participantStatus=" + participant.status()
                + ", nodeStatus=" + nodeExecution.status()
                + ", executionStatus=" + execution.status()
                + ", handoffs=" + handoffs.size()
                + ", duplicate=" + duplicate + ']';
    }
}