package com.company.campaign.action;

/**
 * What one first claim of an exact handoff produced: the reserved action, its durable submission task, its
 * aggregate reward ledger and whether the claim was an exact duplicate that wrote nothing.
 */
public record ActionPreparation(
        ActionExecution action,
        ActionTask submissionTask,
        BudgetLedger ledger,
        boolean duplicate
) {

    public ActionPreparation {
        if (action == null || submissionTask == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "preparation");
        }
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionPreparation[actionStatus=" + action.status()
                + ", taskStatus=" + submissionTask.status()
                + ", duplicate=" + duplicate + ']';
    }
}