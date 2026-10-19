package com.company.campaign.runtime;

import java.util.Objects;

/**
 * The safe outward result of one processed node task.
 *
 * <p>It carries the closed disposition, the committed Execution, Participant, NodeExecution, task and
 * counters, the handoff when one was created, whether the next task was guarded by a pause, and whether the
 * call was an exact duplicate that wrote nothing. It carries no member identity, no fact value, no template
 * body and no reward description, so a host may log it.
 */
public record RuntimeNodeResult(
        RuntimeNodeDisposition disposition,
        CampaignExecution execution,
        JourneyParticipant participant,
        NodeExecution nodeExecution,
        RuntimeTask task,
        ExecutionCounters counters,
        ActionHandoff handoff,
        boolean nextTaskGuarded,
        boolean duplicate
) {

    public RuntimeNodeResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(nodeExecution, "nodeExecution");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(counters, "counters");
        if ((disposition == RuntimeNodeDisposition.ACTION_READY) != (handoff != null)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "handoff");
        }
        if (duplicate && disposition != RuntimeNodeDisposition.DUPLICATE) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "duplicate");
        }
    }

    /** True when a bounded {@code ACTION_READY} handoff was committed for T23. */
    public boolean handedOff() {
        return handoff != null;
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "RuntimeNodeResult[disposition=" + disposition
                + ", participantStatus=" + participant.status()
                + ", nodeStatus=" + nodeExecution.status()
                + ", executionStatus=" + execution.status()
                + ", taskStatus=" + task.status()
                + ", nextTaskGuarded=" + nextTaskGuarded
                + ", duplicate=" + duplicate + ']';
    }
}