package com.company.campaign.action;

import com.company.campaign.runtime.ExternalActionCompletionResult;

import java.util.Objects;

/**
 * The safe outward result of one processed external-action operation.
 *
 * <p>It carries the closed disposition, the committed action, its task, the actual attempt when one was made,
 * the committed budget reservation and ledger, the actual bounded Provider outcome, the committed Runtime node
 * completion when one was written, and whether the call was an exact duplicate that wrote nothing. It carries
 * no member identity, no recipient reference, no template body, no reward secret and no Provider reference, so
 * a host may log it.
 */
public record ActionOutcome(
        ActionDisposition disposition,
        ActionExecution action,
        ActionTask task,
        ActionAttempt attempt,
        BudgetReservation reservation,
        BudgetLedger ledger,
        ProviderOutcome providerOutcome,
        ExternalActionCompletionResult runtimeCompletion,
        boolean duplicate
) {

    public ActionOutcome {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(task, "task");
        if (duplicate && disposition != ActionDisposition.DUPLICATE_COMPLETION) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "duplicate");
        }
    }

    /** True when this outcome settled the exact remote effect as applied. */
    public boolean applied() {
        return disposition == ActionDisposition.SUCCEEDED;
    }

    /** Redacted diagnostics: no member, tenant, Campaign, Execution or Provider identity is printed. */
    @Override
    public String toString() {
        return "ActionOutcome[disposition=" + disposition
                + ", actionStatus=" + action.status()
                + ", taskStatus=" + task.status()
                + ", providerOutcome=" + providerOutcome
                + ", duplicate=" + duplicate + ']';
    }
}