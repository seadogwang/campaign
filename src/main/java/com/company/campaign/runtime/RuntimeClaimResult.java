package com.company.campaign.runtime;

import java.util.Objects;

/** The safe outward result of one claim attempt: its closed outcome and, only when claimed, the leased task. */
public record RuntimeClaimResult(RuntimeClaimOutcome outcome, RuntimeTask task) {

    public RuntimeClaimResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == RuntimeClaimOutcome.CLAIMED) != (task != null)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "claimResult");
        }
        if (task != null && task.status() != RuntimeTaskStatus.LEASED) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "task");
        }
    }

    /** True when this attempt leased a task. */
    public boolean claimed() {
        return outcome == RuntimeClaimOutcome.CLAIMED;
    }

    /** Redacted diagnostics: no tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "RuntimeClaimResult[outcome=" + outcome
                + (task == null ? "" : ", task=" + task) + ']';
    }
}