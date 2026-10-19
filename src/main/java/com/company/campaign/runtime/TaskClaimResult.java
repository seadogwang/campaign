package com.company.campaign.runtime;

import java.util.Objects;

/** What one claim stored: the leased task and whether it reclaimed an expired lease. */
public record TaskClaimResult(RuntimeTask task, boolean reclaimed) {

    public TaskClaimResult {
        Objects.requireNonNull(task, "task");
        if (task.status() != RuntimeTaskStatus.LEASED) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "task");
        }
    }
}