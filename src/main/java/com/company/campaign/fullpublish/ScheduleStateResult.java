package com.company.campaign.fullpublish;

import java.time.Instant;
import java.util.Objects;

/**
 * The safe outward result of one accepted pause or resume operation.
 *
 * <p>It carries the Campaign, the committed schedule state revision, the closed status and the instant. It
 * carries no occurrence, no Execution, no Participant, no member value and no trusted identifier.
 */
public record ScheduleStateResult(
        String campaignId,
        long stateRevision,
        String status,
        Instant updatedAt
) {

    public ScheduleStateResult {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (stateRevision < 1) {
            throw new IllegalArgumentException("a schedule state result requires a positive revision");
        }
    }

    /** Redacted diagnostics: only already-safe machine facts are printed. */
    @Override
    public String toString() {
        return "ScheduleStateResult[stateRevision=" + stateRevision
                + ", status=" + status
                + ", updatedAt=" + updatedAt + ']';
    }
}