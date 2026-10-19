package com.company.campaign.runtime;

import java.util.List;

/**
 * What one external-action node completion stored: the committed task, Participant, NodeExecution, Execution,
 * aggregate counters and successor tasks, or the already committed logical result when the same completion was
 * presented twice.
 *
 * <p>{@link #duplicate()} means the exact completion had already been committed and this call wrote nothing at
 * all, so a redelivered handoff can never produce a second local result and never a second remote effect.
 */
public record ExternalActionCompletionResult(
        RuntimeTask task,
        JourneyParticipant participant,
        NodeExecution nodeExecution,
        CampaignExecution execution,
        ExecutionCounters counters,
        List<RuntimeTask> nextTasks,
        boolean duplicate
) {

    public ExternalActionCompletionResult {
        if (task == null || participant == null || nodeExecution == null || execution == null
                || counters == null || nextTasks == null) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "externalActionCompletion");
        }
        nextTasks = List.copyOf(nextTasks);
    }
}