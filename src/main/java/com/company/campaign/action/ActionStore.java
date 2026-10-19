package com.company.campaign.action;

import com.company.campaign.runtime.ExternalActionCompletion;
import com.company.campaign.runtime.ExternalActionCompletionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The single host-only transactional boundary every external-action read and write crosses.
 *
 * <p>There is deliberately one boundary rather than separate read and write collaborators: a compare-and-set
 * that read a revision from one backend and committed it to another would be unprovable, so the revision a
 * caller observed is the revision this very backend re-proves at commit time. The same reasoning forbids
 * wiring an idempotency read from one store and an Action write to another.
 *
 * <p>Every multi-write operation is one method, so an adapter commits the whole unit — the action transition,
 * the attempt, the durable task change, the budget transition, the Runtime node/Participant/Execution
 * advancement and the audit records — or rolls all of it back. The invariants an adapter must re-prove inside
 * the transaction, rather than trusting the caller's prechecks, are:
 *
 * <ul>
 *   <li>business-key uniqueness on {@code (tenantId, idempotencyKey)}: an exact duplicate of the frozen
 *       material returns the reserved action and writes nothing, while the same key with different material is
 *       a bounded conflict that never reaches a Provider;</li>
 *   <li>budget uniqueness and the frozen grant/value limits, so two concurrent actions at the limit produce
 *       one deterministic acceptance and one deterministic refusal, never a truncation and never an
 *       overspend;</li>
 *   <li>compare-and-set on the Action revision, on the task revision and exact lease token, and on every
 *       Runtime revision the atomic node completion carries;</li>
 *   <li>at most one active lease per task, so an unexpired lease is never replaced and an expired one is
 *       reclaimed as a new attempt rather than a silent continuation;</li>
 *   <li>atomic rollback of every record and audit record when any collaborator fails.</li>
 * </ul>
 *
 * <p>The port is suitable for a future PostgreSQL implementation — each commit is exactly one transaction and
 * each lookup is index-backed by the same keys the uniqueness rules name — but this build wires no JPA, no
 * Flyway and no production adapter.
 */
public interface ActionStore {

    // ------------------------------------------------------------------ reads

    /** The idempotency record already bound to one business key inside one tenant, or empty. */
    Optional<ActionIdempotencyRecord> findIdempotencyRecord(String tenantId, String idempotencyKey);

    /** The action already reserved under one business key inside one tenant, or empty. */
    Optional<ActionExecution> findActionByKey(String tenantId, String idempotencyKey);

    /** The action of one tenant and Action identity, or empty when that tenant does not own it. */
    Optional<ActionExecution> findActionById(String tenantId, String actionId);

    /** The exact immutable request one action was reserved for, or empty. */
    Optional<ActionRequest> findRequest(String tenantId, String actionId);

    /** The durable task of one tenant and task identity, or empty when that tenant does not own it. */
    Optional<ActionTask> findActionTask(String tenantId, String taskId);

    /** The most recent cycle of one exact kind of task already addressing one action, or empty. */
    Optional<ActionTask> findActionTask(String tenantId, String actionId, ActionTaskKind kind);

    /** The exact cycle of one exact kind of task already addressing one action, or empty. */
    Optional<ActionTask> findActionTask(String tenantId, String actionId, ActionTaskKind kind, int cycle);

    /** Every durable task of one action, in deterministic task order. */
    List<ActionTask> findActionTasks(String tenantId, String actionId);

    /**
     * Every claimable task of one tenant at one claim instant, in deterministic {@code (availableAt, taskId)}
     * order and bounded by the given limit.
     *
     * <p>A task is claimable when it is due and is either eligible or holds an already expired lease. An
     * unexpired lease is never a candidate, which is what stops one worker from stealing another's in-flight
     * work.
     */
    List<ActionTask> findActionTaskCandidates(String tenantId, Instant claimInstant, int limit);

    /** Every actual Provider attempt of one action, in deterministic attempt order. */
    List<ActionAttempt> findActionAttempts(String tenantId, String actionId);

    /** The reward reservation of one action, or empty when that action reserves no reward budget. */
    Optional<BudgetReservation> findReservation(String tenantId, String actionId);

    /** The aggregate reward-budget ledger of one Execution, or empty when it owns no reward budget. */
    Optional<BudgetLedger> findBudgetLedger(String tenantId, String executionId);

    /** The aggregate action-result projection of one Execution, or empty when it has none yet. */
    Optional<ActionResultCounters> findActionResultCounters(String tenantId, String executionId);

    /** Every action Outbox/Audit record of one tenant, in commit order. */
    List<ActionAuditRecord> findAudits(String tenantId);

    // ------------------------------------------------------------------ writes

    /**
     * Commits one preparation unit — the idempotency record, the exact immutable request, the reserved action,
     * its reward reservation and ledger when it is a REWARD action, its first durable submission task and its
     * audit records — as one unit, or writes nothing.
     *
     * <p>An exact duplicate of the frozen material returns the already reserved action, its task and its ledger
     * and writes nothing; the same key carrying different frozen material is a bounded conflict that never
     * reaches a Provider. A reward reservation the frozen limits cannot cover is a bounded refusal and nothing
     * at all is written.
     */
    ActionPreparationResult commitPreparation(ActionPreparationCommit commit);

    /**
     * Commits one submission or verification claim — the task's next leased revision, the action's next
     * business state and its audit records — as one unit, or writes nothing.
     *
     * <p>The compare-and-set on the task revision, the eligibility of the task, the "an unexpired lease cannot
     * be stolen" rule and the compare-and-set on the action revision are all re-proved here rather than trusted
     * from the caller's read.
     */
    ActionTaskClaimResult claimActionTask(ActionTaskClaimCommit commit);

    /**
     * Commits one action completion — the action's next state, the actual attempt, the task's completion or
     * next bounded schedule, the budget transition, the Runtime node/Participant/Execution advancement and the
     * audit records — as one unit, or writes nothing.
     *
     * <p>It is the one unit that makes "Provider result, attempt, action transition, budget transition, Runtime
     * advancement, next task, counters and audit commit atomically" a property of the store rather than an
     * ordering a caller is trusted to respect. An exact duplicate of an already committed attempt returns the
     * committed logical result and writes nothing.
     */
    ActionCompletionResult commitActionCompletion(ActionCompletionCommit commit);

    /**
     * Commits one host-only schedule: the action's next business state and one durable queued task, together.
     *
     * <p>It is the unit a reconcile or safe-retry command uses. It is deliberately not a claim: no lease is
     * taken and no Provider is any closer to being called, because a host-only command may only make bounded
     * work due, never perform it.
     */
    ActionScheduleResult commitActionSchedule(ActionScheduleCommit commit);

    // ------------------------------------------------------------------ commit units and results

    /** One preparation unit: everything one first claim of an exact handoff writes, together. */
    record ActionPreparationCommit(
            ActionIdempotencyRecord idempotency,
            ActionRequest request,
            ActionExecution action,
            ActionTask submissionTask,
            BudgetReservation reservation,
            BudgetLedger ledger,
            List<ActionAuditRecord> audits
    ) {

        public ActionPreparationCommit {
            if (idempotency == null || request == null || action == null || submissionTask == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "preparation");
            }
            audits = ActionSupport.immutable(audits, ActionVersions.MAX_ACTION_RECORDS, "audits");
            requireBinding(idempotency.tenantId(), action, submissionTask);
            if (action.actionKind().reservesRewardBudget() != (reservation != null && ledger != null)) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "preparationBudget");
            }
        }

        private static void requireBinding(
                String tenantId, ActionExecution action, ActionTask task) {
            if (!action.tenantId().equals(tenantId) || !task.tenantId().equals(tenantId)
                    || !task.actionId().equals(action.actionId())
                    || !task.idempotencyKey().equals(action.idempotencyKey())
                    || task.kind() != ActionTaskKind.SUBMISSION) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "preparationBinding");
            }
        }
    }

    /** What one preparation committed: the reserved action, its first task, its ledger and whether it wrote. */
    record ActionPreparationResult(
            ActionExecution action,
            ActionTask submissionTask,
            BudgetLedger ledger,
            boolean duplicate
    ) {

        public ActionPreparationResult {
            if (action == null || submissionTask == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "preparationResult");
            }
        }
    }

    /** One task claim: the task's next leased revision, the action's next business state and the audits. */
    record ActionTaskClaimCommit(
            String tenantId,
            String taskId,
            long expectedTaskRevision,
            String workerId,
            Instant claimedAt,
            Duration leaseDuration,
            String leaseToken,
            Instant leaseExpiry,
            String actionId,
            long expectedActionRevision,
            ActionExecution action,
            List<ActionAuditRecord> audits
    ) {

        public ActionTaskClaimCommit {
            ActionSupport.requireIdentifier(tenantId, "tenantId");
            ActionSupport.requireIdentifier(taskId, "taskId");
            ActionSupport.requireNonNegative(expectedTaskRevision, "expectedTaskRevision");
            ActionSupport.requireIdentifier(workerId, "workerId");
            ActionSupport.requireIdentifier(actionId, "actionId");
            ActionSupport.requireNonNegative(expectedActionRevision, "expectedActionRevision");
            if (claimedAt == null || leaseDuration == null || leaseToken == null || leaseExpiry == null
                    || action == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "claim");
            }
            if (!action.tenantId().equals(tenantId) || !action.actionId().equals(actionId)) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "claimBinding");
            }
            audits = ActionSupport.immutable(audits, ActionVersions.MAX_ACTION_RECORDS, "audits");
        }
    }

    /** What one claim stored: the leased task, the action's committed state and whether it was a reclaim. */
    record ActionTaskClaimResult(
            ActionTask task,
            ActionExecution action,
            boolean reclaimed
    ) {

        public ActionTaskClaimResult {
            if (task == null || action == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "claimResult");
            }
        }
    }

    /** One host-only schedule unit: the action's next state and one durable queued task, together. */
    record ActionScheduleCommit(
            String tenantId,
            String actionId,
            long expectedActionRevision,
            ActionExecution action,
            ActionTask task,
            List<ActionAuditRecord> audits
    ) {

        public ActionScheduleCommit {
            ActionSupport.requireIdentifier(tenantId, "tenantId");
            ActionSupport.requireIdentifier(actionId, "actionId");
            ActionSupport.requireNonNegative(expectedActionRevision, "expectedActionRevision");
            if (action == null || task == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "schedule");
            }
            if (!action.tenantId().equals(tenantId) || !action.actionId().equals(actionId)
                    || !task.tenantId().equals(tenantId) || !task.actionId().equals(actionId)
                    || !task.idempotencyKey().equals(action.idempotencyKey())) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "scheduleBinding");
            }
            audits = ActionSupport.immutable(audits, ActionVersions.MAX_ACTION_RECORDS, "audits");
        }
    }

    /** What one host-only schedule stored. */
    record ActionScheduleResult(
            ActionExecution action,
            ActionTask task
    ) {

        public ActionScheduleResult {
            if (action == null || task == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "scheduleResult");
            }
        }
    }

    /** One completion unit: everything one Provider outcome writes, together. */
    record ActionCompletionCommit(
            String tenantId,
            String actionId,
            long expectedActionRevision,
            ActionExecution action,
            String taskId,
            long expectedTaskRevision,
            String leaseToken,
            ActionTask task,
            ActionAttempt attempt,
            ActionTask nextTask,
            BudgetReservation reservation,
            BudgetLedger ledger,
            ExternalActionCompletion runtimeCompletion,
            List<ActionAuditRecord> audits
    ) {

        public ActionCompletionCommit {
            ActionSupport.requireIdentifier(tenantId, "tenantId");
            ActionSupport.requireIdentifier(actionId, "actionId");
            ActionSupport.requireNonNegative(expectedActionRevision, "expectedActionRevision");
            ActionSupport.requireIdentifier(taskId, "taskId");
            ActionSupport.requireNonNegative(expectedTaskRevision, "expectedTaskRevision");
            if (action == null || task == null || attempt == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "completion");
            }
            if (task.status().isTerminal()) {
                ActionSupport.requireOptionalIdentifier(leaseToken, "leaseToken");
            } else {
                ActionSupport.requireIdentifier(leaseToken, "leaseToken");
            }
            if (!action.tenantId().equals(tenantId) || !action.actionId().equals(actionId)
                    || !task.tenantId().equals(tenantId) || !task.taskId().equals(taskId)
                    || !task.actionId().equals(actionId)
                    || !attempt.tenantId().equals(tenantId) || !attempt.actionId().equals(actionId)) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "completionBinding");
            }
            if (runtimeCompletion != null && !runtimeCompletion.tenantId().equals(tenantId)) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "runtimeBinding");
            }
            audits = ActionSupport.immutable(audits, ActionVersions.MAX_ACTION_RECORDS, "audits");
        }
    }

    /** What one completion stored, or the already committed logical result of an exact duplicate. */
    record ActionCompletionResult(
            ActionExecution action,
            ActionTask task,
            ActionAttempt attempt,
            BudgetReservation reservation,
            BudgetLedger ledger,
            ExternalActionCompletionResult runtimeCompletion,
            boolean duplicate
    ) {

        public ActionCompletionResult {
            if (action == null || task == null || attempt == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "completionResult");
            }
        }
    }
}
