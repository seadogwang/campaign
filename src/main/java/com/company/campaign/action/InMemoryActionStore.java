package com.company.campaign.action;

import com.company.campaign.runtime.ExternalActionCompletionResult;
import com.company.campaign.runtime.RuntimeStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronized in-memory {@link ActionStore} for tests and local use.
 *
 * <p>Every method is serialized on one monitor, so an idempotency lookup, a uniqueness check, a compare-and-set
 * and the writes of one commit happen as one indivisible step: two concurrent preparations of one business key
 * create exactly one action, one reservation and one submission task; two concurrent claims of one task cannot
 * both lease it; and two concurrent actions at the frozen reward limit produce one deterministic acceptance and
 * one deterministic refusal rather than an overspend.
 *
 * <p>The Runtime half of one completion is committed through the injected {@link RuntimeStore} <em>inside</em>
 * this monitor and <em>after</em> every fallible collaborator, so the action records, the budget transition and
 * the Runtime node/Participant/Execution advancement become visible together or not at all. The commit ordering
 * is the accepted one: the injected {@link #beforeCommit()} seam runs first, then the whole audit batch is
 * recorded, then the one external store call, then the audit batch is committed at the final accepted boundary,
 * and only then are the in-memory maps mutated through infallible operations.
 *
 * <p>This is a test/local adapter only: T23 implements no JPA, no Flyway and no production PostgreSQL adapter.
 * Every lookup is keyed by the same identity the uniqueness rule names.
 */
public class InMemoryActionStore implements ActionStore {

    private final RuntimeStore runtimeStore;
    private final ActionAuditSink auditSink;

    private final Map<String, ActionIdempotencyRecord> idempotencyByKey = new LinkedHashMap<>();
    private final Map<String, ActionExecution> actionsById = new LinkedHashMap<>();
    private final Map<String, ActionRequest> requestsByAction = new LinkedHashMap<>();
    private final Map<String, ActionTask> tasksById = new LinkedHashMap<>();
    private final Map<String, ActionAttempt> attemptsById = new LinkedHashMap<>();
    private final Map<String, BudgetReservation> reservationsByAction = new LinkedHashMap<>();
    private final Map<String, BudgetLedger> ledgersByExecution = new LinkedHashMap<>();
    private final Map<String, ActionResultCounters> resultCountersByExecution = new LinkedHashMap<>();
    private final List<ActionAuditRecord> audits = new ArrayList<>();

    /** Creates a store over a local Runtime store and an in-memory audit sink. */
    public InMemoryActionStore(RuntimeStore runtimeStore) {
        this(runtimeStore, new InMemoryActionAuditSink());
    }

    /** Creates a store whose audit records are recorded through the supplied sink. */
    public InMemoryActionStore(RuntimeStore runtimeStore, ActionAuditSink auditSink) {
        this.runtimeStore = Objects.requireNonNull(runtimeStore, "runtimeStore");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    /** The audit collaborator every commit records through. */
    public ActionAuditSink auditSink() {
        return auditSink;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public synchronized Optional<ActionIdempotencyRecord> findIdempotencyRecord(
            String tenantId, String idempotencyKey) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        return Optional.ofNullable(idempotencyByKey.get(key(tenantId, idempotencyKey)));
    }

    @Override
    public synchronized Optional<ActionExecution> findActionByKey(String tenantId, String idempotencyKey) {
        ActionIdempotencyRecord record = findIdempotencyRecord(tenantId, idempotencyKey).orElse(null);
        return record == null ? Optional.empty() : Optional.ofNullable(actionsById.get(record.actionId()));
    }

    @Override
    public synchronized Optional<ActionExecution> findActionById(String tenantId, String actionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionExecution stored = actionsById.get(actionId);
        // An action identity is only resolvable inside its own tenant; another tenant sees nothing at all.
        return stored != null && stored.tenantId().equals(tenantId) ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public synchronized Optional<ActionRequest> findRequest(String tenantId, String actionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionExecution stored = actionsById.get(actionId);
        return stored != null && stored.tenantId().equals(tenantId)
                ? Optional.ofNullable(requestsByAction.get(actionId))
                : Optional.empty();
    }

    @Override
    public synchronized Optional<ActionTask> findActionTask(String tenantId, String taskId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(taskId, "taskId");
        ActionTask stored = tasksById.get(taskId);
        return stored != null && stored.tenantId().equals(tenantId) ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public synchronized Optional<ActionTask> findActionTask(
            String tenantId, String actionId, ActionTaskKind kind) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        Objects.requireNonNull(kind, "kind");
        ActionExecution stored = actionsById.get(actionId);
        if (stored == null || !stored.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        ActionTask latest = null;
        for (ActionTask task : tasksById.values()) {
            if (task.tenantId().equals(tenantId) && task.actionId().equals(actionId) && task.kind() == kind
                    && (latest == null || task.cycle() > latest.cycle())) {
                latest = task;
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public synchronized Optional<ActionTask> findActionTask(
            String tenantId, String actionId, ActionTaskKind kind, int cycle) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        Objects.requireNonNull(kind, "kind");
        ActionExecution stored = actionsById.get(actionId);
        if (stored == null || !stored.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasksById.get(ActionTask.taskId(actionId, kind, cycle)));
    }

    @Override
    public synchronized List<ActionTask> findActionTasks(String tenantId, String actionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionExecution stored = actionsById.get(actionId);
        if (stored == null || !stored.tenantId().equals(tenantId)) {
            return List.of();
        }
        List<ActionTask> matches = new ArrayList<>();
        for (ActionTask task : tasksById.values()) {
            if (task.tenantId().equals(tenantId) && task.actionId().equals(actionId)) {
                matches.add(task);
            }
        }
        matches.sort(Comparator.comparing(ActionTask::taskId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<ActionTask> findActionTaskCandidates(
            String tenantId, Instant claimInstant, int limit) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        Objects.requireNonNull(claimInstant, "claimInstant");
        requireScanBound(limit);
        List<ActionTask> matches = new ArrayList<>();
        for (ActionTask task : tasksById.values()) {
            if (task.tenantId().equals(tenantId) && task.isClaimableAt(claimInstant)) {
                matches.add(task);
            }
        }
        matches.sort(Comparator.comparing(ActionTask::availableAt).thenComparing(ActionTask::taskId));
        return List.copyOf(matches.subList(0, Math.min(matches.size(), limit)));
    }

    @Override
    public synchronized List<ActionAttempt> findActionAttempts(String tenantId, String actionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        List<ActionAttempt> matches = new ArrayList<>();
        for (ActionAttempt attempt : attemptsById.values()) {
            if (attempt.tenantId().equals(tenantId) && attempt.actionId().equals(actionId)) {
                matches.add(attempt);
            }
        }
        matches.sort(Comparator.comparing(ActionAttempt::attemptId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized Optional<BudgetReservation> findReservation(String tenantId, String actionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionExecution stored = actionsById.get(actionId);
        return stored != null && stored.tenantId().equals(tenantId)
                ? Optional.ofNullable(reservationsByAction.get(actionId))
                : Optional.empty();
    }

    @Override
    public synchronized Optional<BudgetLedger> findBudgetLedger(String tenantId, String executionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        return Optional.ofNullable(ledgersByExecution.get(key(tenantId, executionId)));
    }

    @Override
    public synchronized java.util.Optional<ActionResultCounters> findActionResultCounters(
            String tenantId, String executionId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        return Optional.ofNullable(resultCountersByExecution.get(key(tenantId, executionId)));
    }

    @Override
    public synchronized List<ActionAuditRecord> findAudits(String tenantId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        List<ActionAuditRecord> matches = new ArrayList<>();
        for (ActionAuditRecord record : audits) {
            if (record.tenantId().equals(tenantId)) {
                matches.add(record);
            }
        }
        return List.copyOf(matches);
    }

    // ------------------------------------------------------------------ preparation

    @Override
    public synchronized ActionPreparationResult commitPreparation(ActionPreparationCommit commit) {
        Objects.requireNonNull(commit, "commit");
        String idKey = key(commit.idempotency().tenantId(), commit.idempotency().idempotencyKey());
        ActionIdempotencyRecord existing = idempotencyByKey.get(idKey);
        if (existing != null) {
            if (!existing.frozenMaterialHash().equals(commit.idempotency().frozenMaterialHash())) {
                // The same business key under different frozen material is a conflict forever, and no Provider
                // is ever reached for it.
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT, "idempotencyKey",
                        "A business idempotency key is already bound to different frozen material.");
            }
            ActionExecution storedAction = actionsById.get(existing.actionId());
            ActionTask storedTask = latestTask(existing.actionId(), ActionTaskKind.SUBMISSION);
            if (storedAction == null || storedTask == null) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_STORE_CONFLICT, "preparation",
                        "A committed idempotency record is missing the action it reserved.");
            }
            return new ActionPreparationResult(
                    storedAction, storedTask,
                    ledgersByExecution.get(key(storedAction.tenantId(), storedAction.executionId())), true);
        }

        ActionExecution action = commit.action();
        ActionTask task = commit.submissionTask();
        if (actionsById.containsKey(action.actionId()) || tasksById.containsKey(task.taskId())
                || requestsByAction.containsKey(action.actionId())
                || reservationsByAction.containsKey(action.actionId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_IDENTITY_CONFLICT, "preparation",
                    "An action, task, request or reservation identity is already bound to different work.");
        }
        BudgetLedger nextLedger = null;
        if (commit.reservation() != null) {
            requireReservationBinding(action, commit.reservation());
            nextLedger = requireReservationMove(
                    ledgersByExecution.get(key(action.tenantId(), action.executionId())),
                    commit.reservation(), commit.ledger());
        }

        // The last fallible collaborators run before any state is written.
        beforeCommit();
        auditSink.recordAll(commit.audits());
        auditSink.commitAll(commit.audits());

        idempotencyByKey.put(idKey, commit.idempotency());
        actionsById.put(action.actionId(), action);
        requestsByAction.put(action.actionId(), commit.request());
        tasksById.put(task.taskId(), task);
        if (commit.reservation() != null && nextLedger != null) {
            reservationsByAction.put(action.actionId(), commit.reservation());
            ledgersByExecution.put(key(action.tenantId(), action.executionId()), nextLedger);
        }
        audits.addAll(commit.audits());
        return new ActionPreparationResult(action, task, nextLedger, false);
    }

    // ------------------------------------------------------------------ claim

    @Override
    public synchronized ActionTaskClaimResult claimActionTask(ActionTaskClaimCommit commit) {
        Objects.requireNonNull(commit, "commit");
        ActionTask stored = tasksById.get(commit.taskId());
        if (stored == null || !stored.tenantId().equals(commit.tenantId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TASK_NOT_FOUND, "taskId",
                    "No ActionTask is stored under this tenant and task identity.");
        }
        if (stored.revision() != commit.expectedTaskRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "taskRevision",
                    "The task moved before this claim committed.");
        }
        if (stored.availableAt().isAfter(commit.claimedAt())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "availableAt",
                    "The task is not due at this claim instant.");
        }
        boolean reclaimed = stored.status() == ActionTaskStatus.LEASED;
        if (reclaimed) {
            if (stored.leaseExpiry().isAfter(commit.claimedAt())) {
                // One task has one active lease: an unexpired one is never stolen.
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_LEASE_HELD, "leaseExpiry",
                        "The task already holds an unexpired lease.");
            }
        } else if (stored.status() != ActionTaskStatus.DUE && stored.status() != ActionTaskStatus.DEFERRED) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "taskStatus",
                    "This task is not in a state a claim may take a lease from.");
        }
        ActionExecution storedAction = actionsById.get(commit.actionId());
        if (storedAction == null || !storedAction.tenantId().equals(commit.tenantId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "action",
                    "No action is stored for this task inside this tenant.");
        }
        if (storedAction.revision() != commit.expectedActionRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "actionRevision",
                    "The action moved before this claim committed.");
        }
        requireActionMove(storedAction, commit.action());

        ActionTask leased = reclaimed
                ? stored.reclaimed(commit.workerId(), commit.leaseToken(), commit.leaseExpiry(), commit.claimedAt())
                : stored.leased(commit.workerId(), commit.leaseToken(), commit.leaseExpiry(), commit.claimedAt());

        beforeCommit();
        auditSink.recordAll(commit.audits());
        auditSink.commitAll(commit.audits());

        tasksById.put(leased.taskId(), leased);
        actionsById.put(commit.actionId(), commit.action());
        audits.addAll(commit.audits());
        return new ActionTaskClaimResult(leased, commit.action(), reclaimed);
    }

    // ------------------------------------------------------------------ host-only schedule

    @Override
    public synchronized ActionScheduleResult commitActionSchedule(ActionScheduleCommit commit) {
        Objects.requireNonNull(commit, "commit");
        ActionExecution storedAction = actionsById.get(commit.actionId());
        if (storedAction == null || !storedAction.tenantId().equals(commit.tenantId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "action",
                    "No action is stored under this tenant and Action identity.");
        }
        if (storedAction.revision() != commit.expectedActionRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "actionRevision",
                    "The action moved before this schedule committed.");
        }
        if (!storedAction.equals(commit.action())) {
            // A host-only command that only reschedules already-due work restates the action exactly.
            requireActionMove(storedAction, commit.action());
        }
        ActionTask stored = tasksById.get(commit.task().taskId());
        if (stored != null) {
            if (stored.revision() + 1L != commit.task().revision()
                    || stored.cycle() != commit.task().cycle()
                    || stored.kind() != commit.task().kind()
                    || (!stored.status().reaches(commit.task().status())
                            && stored.status() != commit.task().status())) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_STORE_CONFLICT, "task",
                        "A scheduled task must be restated one revision forward from its own state.");
            }
        } else if (commit.task().status() != ActionTaskStatus.DUE) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_INPUT_INVALID, "taskStatus",
                    "A newly scheduled task must be due.");
        }

        beforeCommit();
        auditSink.recordAll(commit.audits());
        auditSink.commitAll(commit.audits());

        actionsById.put(commit.actionId(), commit.action());
        countResult(storedAction, commit.action());
        tasksById.put(commit.task().taskId(), commit.task());
        audits.addAll(commit.audits());
        return new ActionScheduleResult(commit.action(), commit.task());
    }

    // ------------------------------------------------------------------ completion

    @Override
    public synchronized ActionCompletionResult commitActionCompletion(ActionCompletionCommit commit) {
        Objects.requireNonNull(commit, "commit");
        ActionExecution storedAction = actionsById.get(commit.actionId());
        if (storedAction == null || !storedAction.tenantId().equals(commit.tenantId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "action",
                    "No action is stored under this tenant and Action identity.");
        }
        ActionTask storedTask = tasksById.get(commit.taskId());
        if (storedTask == null || !storedTask.tenantId().equals(commit.tenantId())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TASK_NOT_FOUND, "taskId",
                    "No ActionTask is stored under this tenant and task identity.");
        }
        ActionAttempt existingAttempt = attemptsById.get(commit.attempt().attemptId());
        if (existingAttempt != null) {
            return duplicateCompletion(commit, existingAttempt, storedTask, storedAction);
        }
        if (storedTask.status() != ActionTaskStatus.LEASED || storedTask.leaseToken() == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "taskStatus",
                    "A completion requires a task that currently holds a lease.");
        }
        if (storedTask.revision() != commit.expectedTaskRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "taskRevision",
                    "The task moved before this completion committed.");
        }
        if (!storedTask.leaseToken().equals(commit.leaseToken())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_LEASE_TOKEN_MISMATCH, "leaseToken",
                    "A completion must name the exact lease token the task was claimed under.");
        }
        // The attempt number counts actual Provider calls of its own kind, so it is bound to the action's own
        // counter rather than to the queue's claim count: a reclaim before the call is not a new remote attempt.
        int expectedAttemptNumber = commit.attempt().attemptKind() == ActionAttemptKind.SUBMISSION
                ? commit.action().submissionCount()
                : commit.action().verificationCount();
        if (commit.attempt().attemptNumber() != expectedAttemptNumber) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_COMPLETION_CONFLICT, "attemptNumber",
                    "A completion must record the exact actual Provider attempt its action counter claims.");
        }
        if (storedAction.revision() != commit.expectedActionRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "actionRevision",
                    "The action moved before this completion committed.");
        }
        requireActionMove(storedAction, commit.action());
        BudgetReservation storedReservation = reservationsByAction.get(commit.actionId());
        BudgetLedger nextLedger = null;
        if (storedReservation != null) {
            nextLedger = requireReservationTransition(
                    storedReservation, commit.reservation(),
                    ledgersByExecution.get(key(commit.tenantId(), storedAction.executionId())), commit.ledger());
        } else if (commit.reservation() != null || commit.ledger() != null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservation",
                    "An action that reserves no reward budget cannot move one.");
        }
        if (commit.nextTask() != null) {
            ActionTask next = commit.nextTask();
            if (!next.tenantId().equals(commit.tenantId()) || !next.actionId().equals(commit.actionId())
                    || next.taskId().equals(commit.taskId())) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_STORE_IDENTITY_CONFLICT, "nextTask",
                        "A successor task identity is already bound to different work.");
            }
            ActionTask existing = tasksById.get(next.taskId());
            if (existing != null
                    && (existing.actionId().equals(commit.actionId())
                            ? existing.revision() + 1L != next.revision()
                                    || existing.kind() != next.kind()
                                    || existing.cycle() != next.cycle()
                                    || (!existing.status().reaches(next.status())
                                            && existing.status() != next.status())
                            : true)) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_STORE_IDENTITY_CONFLICT, "nextTask",
                        "A successor task identity is already bound to different work.");
            }
            if (existing == null && next.status() != ActionTaskStatus.DUE) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_INPUT_INVALID, "nextTaskStatus",
                        "A newly created successor task must be due.");
            }
        }

        beforeCommit();
        auditSink.recordAll(commit.audits());
        ExternalActionCompletionResult runtimeCompletion = null;
        if (commit.runtimeCompletion() != null) {
            // The one external store call, after every other fallible collaborator and before any state write.
            try {
                runtimeCompletion = runtimeStore.commitExternalActionCompletion(commit.runtimeCompletion());
            } catch (RuntimeException failure) {
                auditSink.rollbackAll(commit.audits());
                throw failure;
            }
        }
        // The final accepted boundary: every remaining fallible participant has succeeded, so the audit batch is
        // committed here and only infallible in-memory writes follow it.
        auditSink.commitAll(commit.audits());

        actionsById.put(commit.actionId(), commit.action());
        countResult(storedAction, commit.action());
        tasksById.put(commit.taskId(), commit.task());
        attemptsById.put(commit.attempt().attemptId(), commit.attempt());
        if (commit.nextTask() != null) {
            tasksById.put(commit.nextTask().taskId(), commit.nextTask());
        }
        if (commit.reservation() != null && nextLedger != null) {
            reservationsByAction.put(commit.actionId(), commit.reservation());
            ledgersByExecution.put(key(commit.tenantId(), storedAction.executionId()), nextLedger);
        }
        audits.addAll(commit.audits());
        return new ActionCompletionResult(
                commit.action(), commit.task(), commit.attempt(), commit.reservation(), nextLedger,
                runtimeCompletion, false);
    }

    /**
     * Answers an exact replay of an already committed attempt from the committed state.
     *
     * <p>The attempt identity is what makes this decidable: the same identity carrying byte-equal material is
     * the same logical Provider call and reuses the committed result, while the same identity carrying anything
     * else is a bounded conflict that writes nothing.
     */
    private ActionCompletionResult duplicateCompletion(
            ActionCompletionCommit commit,
            ActionAttempt existingAttempt,
            ActionTask storedTask,
            ActionExecution storedAction) {
        if (!existingAttempt.equals(commit.attempt())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_COMPLETION_CONFLICT, "attempt",
                    "A completed attempt identity is already bound to different material.");
        }
        return new ActionCompletionResult(
                storedAction, storedTask, existingAttempt,
                reservationsByAction.get(commit.actionId()),
                ledgersByExecution.get(key(commit.tenantId(), storedAction.executionId())), null, true);
    }

    // ------------------------------------------------------------------ budget re-proofs

    private static void requireReservationBinding(ActionExecution action, BudgetReservation reservation) {
        if (!reservation.tenantId().equals(action.tenantId())
                || !reservation.executionId().equals(action.executionId())
                || !reservation.actionId().equals(action.actionId())
                || !reservation.idempotencyKey().equals(action.idempotencyKey())
                || !reservation.state().isHeld()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_INVALID, "reservationBinding",
                    "A reward reservation does not witness the exact action it covers.");
        }
    }

    /**
     * Re-proves one durable ledger move from the stored base rather than trusting the caller's next ledger.
     *
     * <p>Only the held bucket may rise, and only by exactly the reservation's own grant count and face value.
     * The frozen limits and the finalized and released buckets must be restated exactly, so a caller cannot
     * slip an unreserved increase, a truncated amount or a lowered limit through as a reservation.
     */
    private static BudgetLedger requireReservationMove(
            BudgetLedger stored, BudgetReservation reservation, BudgetLedger next) {
        if (next == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_INVALID, "ledger",
                    "A reward reservation requires its next aggregate ledger.");
        }
        if (stored == null) {
            if (next.revision() != 1L || next.finalizedGrants() != 0 || next.releasedGrants() != 0
                    || next.heldGrants() != reservation.grantCount()
                    || next.heldFaceValue().compareTo(reservation.faceValue()) != 0
                    || next.heldFaceValue().compareTo(net(next)) != 0) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledger",
                        "The first reservation of one Execution must be its own empty ledger plus one grant.");
            }
            return next;
        }
        BigDecimal granted = stored.heldFaceValue().add(reservation.faceValue());
        if (stored.revision() + 1L != next.revision()
                || stored.heldGrants() + reservation.grantCount() != next.heldGrants()
                || granted.compareTo(next.heldFaceValue()) != 0
                || stored.finalizedGrants() != next.finalizedGrants()
                || stored.finalizedFaceValue().compareTo(next.finalizedFaceValue()) != 0
                || stored.releasedGrants() != next.releasedGrants()
                || stored.releasedFaceValue().compareTo(next.releasedFaceValue()) != 0
                || stored.maxRewardGrants() != next.maxRewardGrants()
                || stored.maxRewardFaceValue().compareTo(next.maxRewardFaceValue()) != 0
                || !stored.currency().equals(next.currency())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledger",
                    "A ledger move must restate every unchanged bucket and add exactly one reservation.");
        }
        return next;
    }

    /**
     * Re-proves one durable ledger move that finalizes or releases exactly one held reservation.
     *
     * <p>The held bucket must fall by exactly the reservation's own grant count and face value into the one
     * matching bucket, and the total exposure must not move at all.
     */
    private static BudgetLedger requireReservationTransition(
            BudgetReservation stored,
            BudgetReservation next,
            BudgetLedger storedLedger,
            BudgetLedger nextLedger) {
        if (next == null || nextLedger == null || storedLedger == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservation",
                    "A reserved action must restate its reservation and its aggregate ledger.");
        }
        if (next.state().isHeld()) {
            // A retry, a reclaim or another reconciliation reuses the exact reservation it already holds.
            if (!next.equals(stored)) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservation",
                        "A held reservation may only be restated exactly.");
            }
            if (!nextLedger.equals(storedLedger)) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledger",
                        "A ledger may only move when its reservation does.");
            }
            return nextLedger;
        }
        if (stored.state().isTerminal()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservationState",
                    "A finalized or released reservation is immutable.");
        }
        if (!stored.state().reaches(next.state())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservationState",
                    "A reservation transition is not one the frozen budget lifecycle allows.");
        }
        boolean finalized = next.state() == BudgetReservationState.FINALIZED;
        BigDecimal heldAfter = storedLedger.heldFaceValue().subtract(stored.faceValue());
        int grantsAfter = storedLedger.heldGrants() - stored.grantCount();
        boolean bucketsMatch = finalized
                ? storedLedger.finalizedGrants() + stored.grantCount() == nextLedger.finalizedGrants()
                        && storedLedger.finalizedFaceValue().add(stored.faceValue())
                                .compareTo(nextLedger.finalizedFaceValue()) == 0
                : storedLedger.releasedGrants() + stored.grantCount() == nextLedger.releasedGrants()
                        && storedLedger.releasedFaceValue().add(stored.faceValue())
                                .compareTo(nextLedger.releasedFaceValue()) == 0;
        if (storedLedger.revision() + 1L != nextLedger.revision()
                || grantsAfter != nextLedger.heldGrants()
                || heldAfter.compareTo(nextLedger.heldFaceValue()) != 0
                || !bucketsMatch
                || storedLedger.maxRewardGrants() != nextLedger.maxRewardGrants()
                || storedLedger.maxRewardFaceValue().compareTo(nextLedger.maxRewardFaceValue()) != 0
                || !storedLedger.currency().equals(nextLedger.currency())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledger",
                    "A finalize or release must move exactly one held reservation into its own bucket.");
        }
        return nextLedger;
    }

    /**
     * Re-proves one Action move, or one deliberate in-place restatement at a new revision.
     *
     * <p>A real status change must be one the frozen business machine allows. A same-status write is permitted
     * only as an in-place restatement — one revision forward, with every frozen identity field unchanged and no
     * counter decreasing — because starting another bounded reconciliation query moves the action's counters
     * while its business state legitimately stays {@code VERIFYING}. Anything else is refused, so a caller
     * cannot slip an unproved move, a rewound counter or a rewound revision through as "no transition".
     */
    private static void requireActionMove(ActionExecution stored, ActionExecution next) {
        if (stored.status() != next.status()) {
            if (!stored.status().reaches(next.status())) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                        "An Action transition is not one the frozen state machine allows.");
            }
            return;
        }
        if (next.revision() != stored.revision() + 1L
                || !next.actionId().equals(stored.actionId())
                || !next.tenantId().equals(stored.tenantId())
                || !next.executionId().equals(stored.executionId())
                || !next.participantId().equals(stored.participantId())
                || !next.nodeLogicalId().equals(stored.nodeLogicalId())
                || next.actionKind() != stored.actionKind()
                || !next.idempotencyKey().equals(stored.idempotencyKey())
                || !next.requestHash().equals(stored.requestHash())
                || !next.createdAt().equals(stored.createdAt())
                || next.submissionCount() < stored.submissionCount()
                || next.verificationCount() < stored.verificationCount()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "action",
                    "An unchanged Action status must be restated exactly, one revision forward.");
        }
    }

    private static BigDecimal net(BudgetLedger ledger) {
        return ledger.heldFaceValue().add(ledger.finalizedFaceValue()).add(ledger.releasedFaceValue());
    }

    // ------------------------------------------------------------------ bounded observation

    /** The number of stored actions. */
    public synchronized int actionCount() {
        return actionsById.size();
    }

    /** The number of stored durable action tasks. */
    public synchronized int taskCount() {
        return tasksById.size();
    }

    /** The number of stored actual attempt records. */
    public synchronized int attemptCount() {
        return attemptsById.size();
    }

    /** The number of stored action Outbox/Audit records. */
    public synchronized int auditCount() {
        return audits.size();
    }

    /**
     * Moves the aggregate action-result projection of one Execution by exactly one disposition.
     *
     * <p>It is what makes "a successful node does not automatically mean a reward was granted" a property of
     * the projection: only a confirmed applied MESSAGE action raises the accepted-message count and only a
     * confirmed applied REWARD action raises the granted-reward count.
     */
    private void countResult(ActionExecution stored, ActionExecution next) {
        String executionKey = key(stored.tenantId(), stored.executionId());
        ActionResultCounters counters = resultCountersByExecution
                .getOrDefault(executionKey, ActionResultCounters.empty(stored.tenantId(), stored.executionId()))
                .counted(stored.actionKind(), stored.status(), next.status());
        resultCountersByExecution.put(executionKey, counters);
    }

    private ActionTask latestTask(String actionId, ActionTaskKind kind) {
        ActionTask latest = null;
        for (ActionTask task : tasksById.values()) {
            if (task.actionId().equals(actionId) && task.kind() == kind
                    && (latest == null || task.cycle() > latest.cycle())) {
                latest = task;
            }
        }
        return latest;
    }

    private static void requireScanBound(int limit) {
        if (limit < 1 || limit > ActionVersions.MAX_CLAIM_CANDIDATES) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, "limit",
                    "A claim scan must be bounded and positive.");
        }
    }

    /**
     * The one extension seam of this local adapter: called immediately before any state is written.
     *
     * <p>It exists so a deterministic local test can prove that a failing store write rolls the whole commit
     * back. A production adapter has no reason to override it.
     */
    protected void beforeCommit() {
        // No-op by default.
    }

    private static String key(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            builder.append(part).append('|');
        }
        return builder.toString();
    }

    /** Redacted diagnostics: no tenant, Campaign or member value is printed. */
    @Override
    public String toString() {
        return "InMemoryActionStore[actions=" + actionCount()
                + ", tasks=" + taskCount()
                + ", attempts=" + attemptCount()
                + ", audits=" + auditCount() + ']';
    }
}
