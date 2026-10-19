package com.company.campaign.fullpublish;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The safe outward result of one accepted due-occurrence planning operation.
 *
 * <p>It carries the Campaign, the schedule state revision the operation committed against, the evaluation
 * instant and the exact execution keys that became due or were explicitly skipped. It carries no occurrence
 * body, no Execution, no Participant, no member value and no trusted identifier.
 */
public record ScheduleDueResult(
        String campaignId,
        long stateRevision,
        Instant asOf,
        List<String> alreadyCommittedExecutionKeys,
        List<String> dueExecutionKeys,
        List<String> skippedExecutionKeys
) {

    public ScheduleDueResult {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(asOf, "asOf");
        alreadyCommittedExecutionKeys =
                List.copyOf(Objects.requireNonNull(alreadyCommittedExecutionKeys, "alreadyCommitted"));
        dueExecutionKeys = List.copyOf(Objects.requireNonNull(dueExecutionKeys, "dueExecutionKeys"));
        skippedExecutionKeys = List.copyOf(Objects.requireNonNull(skippedExecutionKeys, "skipped"));
    }

    /** Redacted diagnostics: only already-safe machine facts are printed. */
    @Override
    public String toString() {
        return "ScheduleDueResult[stateRevision=" + stateRevision
                + ", asOf=" + asOf
                + ", due=" + dueExecutionKeys.size()
                + ", skipped=" + skippedExecutionKeys.size() + ']';
    }
}