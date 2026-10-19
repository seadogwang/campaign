package com.company.campaign.action;

import java.util.Objects;

/**
 * The safe outward result of one external-action claim attempt.
 *
 * <p>It mirrors the Runtime claim vocabulary deliberately rather than collapsing the cases: {@code PAUSED}
 * means the host-owned Campaign projection stopped new Provider work, {@code UNVERIFIED} means a related
 * resource could not be re-proved at all, and {@code CONTENDED} means another worker took the lease first.
 * None of those is "nothing to do".
 */
public record ActionClaimResult(
        ActionClaimOutcome outcome,
        ActionTask task
) {

    public ActionClaimResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == ActionClaimOutcome.CLAIMED) != (task != null)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "task");
        }
    }

    /** True when a task was actually leased. */
    public boolean claimed() {
        return outcome == ActionClaimOutcome.CLAIMED;
    }

    /** Redacted diagnostics: no member, tenant or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionClaimResult[outcome=" + outcome + ']';
    }
}