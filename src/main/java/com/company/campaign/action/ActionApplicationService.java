package com.company.campaign.action;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.CampaignExecution;
import com.company.campaign.runtime.ExecutionCounters;
import com.company.campaign.runtime.ExecutionStatus;
import com.company.campaign.runtime.ExternalActionCompletion;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.runtime.NodeExecution;
import com.company.campaign.runtime.NodeExecutionStatus;
import com.company.campaign.runtime.ParticipantStatus;
import com.company.campaign.runtime.RuntimeAggregation;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeCampaignState;
import com.company.campaign.runtime.RuntimeTask;
import com.company.campaign.runtime.RuntimeTaskKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Message and Reward application service: the only place one external action is reserved, submitted,
 * reconciled and finished.
 *
 * <p>It exists because the accepted boundary forbids a Journey Runtime worker, a Builder path and any model
 * from reaching a Provider: the Runtime worker produces only a bounded {@code ACTION_READY} handoff, and
 * everything after that — the business idempotency key, the immutable request, the Provider SPI call, the
 * budget reservation and the reconciliation — happens here.
 *
 * <p>The ordering is fixed and is the point of the class. A first claim of one exact handoff re-proves every
 * owner, pin and hash, creates or reuses the idempotency record and the ActionExecution, reserves the
 * applicable reward budget and creates one durable submission task, all in one transaction. Only after that
 * transaction commits is a task claimed, and only after the claim transaction commits is any Provider called.
 * The Provider call, the recipient resolution and every other external port run with no store lock held. The
 * Provider outcome, the action transition, the attempt, the budget transition, the Runtime node/Participant
 * advancement, the next task, the counters and the audit batch then commit as one unit, or nothing does.
 *
 * <p>Nothing here reads a mutable latest catalog pointer, a Builder Session, a Draft, a Plan, a Proposal, a
 * Prompt, a Skill, a Tool, an Agent or an LLM, and nothing here accepts a caller-supplied tenant, recipient,
 * request hash, budget, Provider capability, version or success fact as authority.
 */
public final class ActionApplicationService {

    private final ActionRuntimePort runtime;
    private final ActionStore store;
    private final ActionRequestResolver resolver;
    private final ActionPolicies policies;
    private final MessageProvider messageProvider;
    private final RewardProvider rewardProvider;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int claimScanLimit;

    public ActionApplicationService(
            ActionRuntimePort runtime,
            ActionStore store,
            ActionRequestResolver resolver,
            ActionPolicies policies,
            MessageProvider messageProvider,
            RewardProvider rewardProvider,
            Clock clock,
            Duration leaseDuration,
            int claimScanLimit) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.messageProvider = Objects.requireNonNull(messageProvider, "messageProvider");
        this.rewardProvider = Objects.requireNonNull(rewardProvider, "rewardProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(ActionVersions.MIN_LEASE_DURATION) < 0
                || leaseDuration.compareTo(ActionVersions.MAX_LEASE_DURATION) > 0) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_LEASE_INVALID, "leaseDuration",
                    "A lease duration must lie inside its frozen bounded interval.");
        }
        if (claimScanLimit < 1 || claimScanLimit > ActionVersions.MAX_CLAIM_CANDIDATES) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, "claimScanLimit",
                    "A claim scan must be bounded and positive.");
        }
        this.leaseDuration = leaseDuration;
        this.claimScanLimit = claimScanLimit;
    }

    // ------------------------------------------------------------------ preparation

    /**
     * Claims one exact persisted handoff: everything a first claim must write, atomically.
     *
     * <p>An exact duplicate of the frozen material returns the already reserved action, writes nothing and never
     * reaches a Provider or a recipient resolver; the same business key carrying changed material is a bounded
     * conflict that also never reaches a Provider.
     */
    public ActionPreparation prepare(ActionHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        String tenantId = handoff.tenantId();
        String idempotencyKey = ActionExecution.idempotencyKey(
                tenantId, handoff.participantId(), handoff.nodeLogicalId(), ActionKind.from(handoff.actionKind()));
        Optional<ActionIdempotencyRecord> existing = store.findIdempotencyRecord(tenantId, idempotencyKey);
        Instant at = clock.instant();
        if (existing.isPresent()) {
            ActionMaterial frozen = resolver.resolveFrozen(handoff);
            if (!existing.get().frozenMaterialHash().equals(frozen.request().frozenMaterialHash())) {
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT, "idempotencyKey",
                        "A business idempotency key is already bound to different frozen material.");
            }
            ActionExecution stored = store.findActionById(tenantId, existing.get().actionId())
                    .orElseThrow(() -> ActionSupport.invalid(
                            ActionErrorCodes.ACTION_STORE_CONFLICT, "action"));
            ActionTask storedTask = store.findActionTask(tenantId, stored.actionId(), ActionTaskKind.SUBMISSION)
                    .orElseThrow(() -> ActionSupport.invalid(
                            ActionErrorCodes.ACTION_STORE_CONFLICT, "submissionTask"));
            return new ActionPreparation(
                    stored, storedTask,
                    store.findBudgetLedger(tenantId, stored.executionId()).orElse(null), true);
        }

        ActionMaterial material = resolver.resolve(handoff);
        ActionRequest request = material.request();
        ActionExecution action = ActionExecution.reserved(
                tenantId, handoff.executionId(), handoff.participantId(), handoff.nodeLogicalId(),
                request.actionKind(), idempotencyKey, request.requestHash(), at);
        ActionIdempotencyRecord idempotency = ActionIdempotencyRecord.of(
                tenantId, idempotencyKey, action.actionId(), request.frozenMaterialHash(),
                request.requestHash(), at);
        ActionTask submissionTask = ActionTask.due(
                tenantId, action.actionId(), idempotencyKey, ActionTaskKind.SUBMISSION, 0, at);
        BudgetReservation reservation = null;
        BudgetLedger ledger = null;
        List<ActionAuditRecord> audits = new ArrayList<>();
        if (request.actionKind().reservesRewardBudget()) {
            RewardBudgetLimits limits = material.budgetLimits();
            BudgetLedger base = store.findBudgetLedger(tenantId, handoff.executionId())
                    .orElseGet(() -> BudgetLedger.empty(
                            tenantId, handoff.executionId(), limits.currency(), limits.maxRewardGrants(),
                            limits.maxRewardFaceValue()));
            if (!base.currency().equals(limits.currency())
                    || base.maxRewardGrants() != limits.maxRewardGrants()
                    || base.maxRewardFaceValue().compareTo(limits.maxRewardFaceValue()) != 0) {
                // A stored ledger must restate exactly the frozen limits this action just resolved.
                throw new ActionBoundaryException(
                        ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledgerLimits",
                        "A stored budget ledger does not restate the frozen limits of this Execution.");
            }
            ledger = base.reserve(request.rewardQuantity(), request.rewardFaceValue()).orElseThrow(
                    () -> new ActionBoundaryException(
                            ActionErrorCodes.ACTION_BUDGET_LIMITED, "budget",
                            "The frozen reward budget would be exceeded, so the action is refused."));
            reservation = BudgetReservation.held(
                    tenantId, handoff.executionId(), action.actionId(), idempotencyKey,
                    request.rewardQuantity(), request.rewardCurrency(), request.rewardFaceValue(), at);
            audits.add(audit(tenantId, ActionAuditRecord.REASON_BUDGET_RESERVED, "ACTION",
                    action.actionId(), action.revision(), at));
        }
        audits.add(audit(tenantId, ActionAuditRecord.REASON_ACTION_RESERVED, "ACTION",
                action.actionId(), action.revision(), at));

        ActionStore.ActionPreparationResult result = store.commitPreparation(
                new ActionStore.ActionPreparationCommit(
                        idempotency, request, action, submissionTask, reservation, ledger, audits));
        return new ActionPreparation(result.action(), result.submissionTask(), result.ledger(),
                result.duplicate());
    }

    /**
     * True when one exact handoff already has an Action, so no second reservation is ever written for it.
     *
     * <p>It is the one bounded pre-check a host loop uses to avoid re-proving a spent handoff; the decision
     * itself is still made inside {@link #prepare} under the store's own monitor.
     */
    public boolean isPrepared(ActionHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        String idempotencyKey = ActionExecution.idempotencyKey(
                handoff.tenantId(), handoff.participantId(), handoff.nodeLogicalId(),
                ActionKind.from(handoff.actionKind()));
        return store.findIdempotencyRecord(handoff.tenantId(), idempotencyKey).isPresent();
    }

    // ------------------------------------------------------------------ claiming

    /** Claims the next due external-action task of one tenant for this worker. */
    public ActionClaimResult claimNext(String tenantId, String workerId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(workerId, "workerId");
        Instant now = clock.instant();
        List<ActionTask> candidates = store.findActionTaskCandidates(tenantId, now, claimScanLimit);
        if (candidates.isEmpty()) {
            return new ActionClaimResult(ActionClaimOutcome.NONE_DUE, null);
        }
        boolean paused = false;
        boolean unverified = false;
        boolean contended = false;
        for (ActionTask candidate : candidates) {
            ActionExecution action = store.findActionById(tenantId, candidate.actionId()).orElse(null);
            if (action == null) {
                unverified = true;
                continue;
            }
            CampaignExecution execution = runtime.findExecutionById(tenantId, action.executionId()).orElse(null);
            if (execution == null) {
                unverified = true;
                continue;
            }
            Optional<RuntimeCampaignState> state = runtime.findCampaignState(tenantId, execution.campaignId());
            if (state.isEmpty()) {
                unverified = true;
                continue;
            }
            if (!state.get().mayStartNewWork()) {
                // A pause stops new Provider submissions and verification calls from starting; nothing claimed.
                paused = true;
                continue;
            }
            try {
                return new ActionClaimResult(ActionClaimOutcome.CLAIMED, lease(candidate, action, workerId, now));
            } catch (ActionBoundaryException lost) {
                contended = true;
            }
        }
        if (paused) {
            return new ActionClaimResult(ActionClaimOutcome.PAUSED, null);
        }
        if (unverified) {
            return new ActionClaimResult(ActionClaimOutcome.UNVERIFIED, null);
        }
        return new ActionClaimResult(
                contended ? ActionClaimOutcome.CONTENDED : ActionClaimOutcome.NONE_DUE, null);
    }

    /**
     * Leases one due task and, for a submission, moves its action to {@code IN_FLIGHT} before any call.
     *
     * <p>The {@code IN_FLIGHT} state is persisted by this transaction and by no later step, so a crash between
     * the claim and the Provider call leaves an in-flight action and an expired lease rather than an invisible
     * one.
     */
    private ActionTask lease(ActionTask candidate, ActionExecution action, String workerId, Instant now) {
        int attemptNumber = candidate.attemptNumber() + 1;
        String token = ActionTask.leaseToken(workerId, candidate.taskId(), attemptNumber, now, leaseDuration);
        Instant expiry = now.plus(leaseDuration);
        ActionExecution moved;
        if (candidate.kind() == ActionTaskKind.SUBMISSION) {
            moved = action.status() == ActionStatus.RESERVED
                    ? action.submitted(now)
                    : action.recoveryVerificationStarted("ACTION_RECOVERY_VERIFYING", now);
        } else {
            moved = action.verificationStarted("ACTION_VERIFYING", now);
        }
        List<ActionAuditRecord> audits = List.of(audit(
                candidate.tenantId(), ActionAuditRecord.REASON_SUBMISSION_CLAIMED, "ACTION",
                action.actionId(), action.revision(), now));
        ActionStore.ActionTaskClaimResult result = store.claimActionTask(new ActionStore.ActionTaskClaimCommit(
                candidate.tenantId(), candidate.taskId(), candidate.revision(), workerId, now, leaseDuration,
                token, expiry, action.actionId(), action.revision(), moved, audits));
        return result.task();
    }

    // ------------------------------------------------------------------ processing

    /** Processes one already-leased external-action task and commits its outcome atomically. */
    public ActionOutcome process(ActionTask claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.status() != ActionTaskStatus.LEASED || claim.leaseToken() == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_LEASE_INVALID, "claim",
                    "Only a leased task may be processed.");
        }
        ActionExecution action = requireAction(claim.tenantId(), claim.actionId());
        return action.status() == ActionStatus.VERIFYING ? verify(claim) : submit(claim);
    }

    /**
     * Submits one exact request through the Provider SPI, after the claim transaction committed.
     *
     * <p>The immutable request is read back from the store rather than recomputed, so a restart or a duplicate
     * delivery can never produce a second, different request under the same key.
     */
    private ActionOutcome submit(ActionTask claim) {
        Instant at = clock.instant();
        ActionExecution action = requireAction(claim.tenantId(), claim.actionId());
        ActionRequest request = requireRequest(claim.tenantId(), claim.actionId());
        Pins pins = pins(action);
        if (action.status() != ActionStatus.IN_FLIGHT) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only an in-flight action may be submitted.");
        }
        ProviderCapability capability;
        try {
            capability = requireCapability(request.actionKind(), claim.tenantId());
        } catch (ActionBoundaryException unsupported) {
            return settleAsDefinitiveFailure(
                    claim, action, pins, at, unsupported.code(), unsupported.code());
        }
        ProviderResult result;
        try {
            // No store lock is held: this is the one remote call of a submission.
            result = request.actionKind() == ActionKind.MESSAGE
                    ? messageProvider.send(request.toProviderRequest(), action.idempotencyKey())
                    : rewardProvider.grant(request.toProviderRequest(), action.idempotencyKey());
        } catch (ActionProviderException failure) {
            result = ProviderResult.unknown(action.idempotencyKey(), failure.code());
        } catch (RuntimeException failure) {
            // A Provider that throws anything else still only means "we do not know": never a failure.
            result = ProviderResult.unknown(
                    action.idempotencyKey(), ActionErrorCodes.ACTION_PROVIDER_UNAVAILABLE);
        }
        if (result == null) {
            result = ProviderResult.unknown(
                    action.idempotencyKey(), ActionErrorCodes.ACTION_PROVIDER_OUTCOME_UNKNOWN);
        }
        return applyProviderOutcome(claim, action, pins, ActionAttemptKind.SUBMISSION, result, at);
    }

    /** Reconciles one possibly-applied effect by its original business key, before anything is ever retried. */
    private ActionOutcome verify(ActionTask claim) {
        Instant at = clock.instant();
        ActionExecution action = requireAction(claim.tenantId(), claim.actionId());
        Pins pins = pins(action);
        if (action.status() != ActionStatus.VERIFYING) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only a verifying action may be reconciled.");
        }
        ProviderResult result;
        try {
            result = action.actionKind() == ActionKind.MESSAGE
                    ? messageProvider.queryByKey(action.idempotencyKey())
                    : rewardProvider.queryByKey(action.idempotencyKey());
        } catch (ActionProviderException failure) {
            result = ProviderResult.unknown(action.idempotencyKey(), failure.code());
        } catch (RuntimeException failure) {
            result = ProviderResult.unknown(
                    action.idempotencyKey(), ActionErrorCodes.ACTION_PROVIDER_UNAVAILABLE);
        }
        if (result == null) {
            result = ProviderResult.unknown(
                    action.idempotencyKey(), ActionErrorCodes.ACTION_PROVIDER_OUTCOME_UNKNOWN);
        }
        return applyProviderOutcome(claim, action, pins, ActionAttemptKind.VERIFICATION, result, at);
    }

    // ------------------------------------------------------------------ outcome mapping

    private ActionOutcome applyProviderOutcome(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttemptKind attemptKind,
            ProviderResult result,
            Instant at) {
        ActionAttempt attempt = ActionAttempt.of(
                claim.tenantId(), action.actionId(), action.idempotencyKey(), attemptKind,
                attemptKind == ActionAttemptKind.SUBMISSION
                        ? action.submissionCount() : action.verificationCount(),
                result, result.reasonCode(), claim.updatedAt(), at);
        return switch (result.outcome()) {
            case SUCCEEDED -> succeed(claim, action, pins, attempt, result, at);
            case DEFINITELY_NOT_APPLIED -> definitelyNotApplied(claim, action, pins, attempt, result, at);
            case PENDING -> unresolved(
                    claim, action, pins, attempt, result, ActionAuditRecord.REASON_PROVIDER_PENDING, at);
            case UNKNOWN -> unresolved(
                    claim, action, pins, attempt, result, ActionAuditRecord.REASON_PROVIDER_UNKNOWN, at);
        };
    }

    /**
     * Settles one confirmed remote effect: the node, the Participant, the Execution, the budget and the next
     * Runtime task, together.
     */
    private ActionOutcome succeed(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            ProviderResult result,
            Instant at) {
        Instant accepted = result.acceptedInstant() != null ? result.acceptedInstant() : at;
        ActionExecution nextAction = action.succeeded(accepted, at);
        String successor = successor(pins.journey(), action.nodeLogicalId());
        List<JourneyParticipant> after = replace(
                pins.participants(), action.participantId(), advanced(pins.participant(), successor, accepted, at));
        CampaignExecution execution = aggregate(pins.execution(), after, at);
        ExecutionCounters counters =
                RuntimeAggregation.counters(execution, after, pins.counters().revision() + 1L);
        RuntimeTask next = RuntimeTask.due(
                claim.tenantId(), action.executionId(), action.participantId(), successor,
                RuntimeTaskKind.NODE, at);
        ExternalActionCompletion completion = runtimeCompletion(
                pins, action, after, execution, counters, List.of(next), at);
        BudgetTransition budget = budget(action, BudgetTransitionKind.FINALIZED, at);
        List<ActionAuditRecord> audits = audits(
                claim, action, attempt, ActionAuditRecord.REASON_ACTION_COMPLETED,
                ActionAuditRecord.REASON_PROVIDER_SUCCEEDED, budget, at);
        return commit(claim, new Next(
                nextAction, claim.completed(at), attempt, null, budget.reservation(), budget.ledger(),
                completion, audits), ActionDisposition.SUCCEEDED);
    }

    /**
     * Settles one definitive non-application: a bounded safe retry while submission attempts remain, or a
     * terminal definitive failure that releases the exact reservation it held.
     */
    private ActionOutcome definitelyNotApplied(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            ProviderResult result,
            Instant at) {
        if (policies.submissionAttemptsRemain(action.submissionCount())) {
            return scheduleSafeRetry(claim, action, pins, attempt, result.reasonCode(), at);
        }
        return settleAsDefinitiveFailure(claim, action, pins, attempt, result.reasonCode(), result.reasonCode(),
                at);
    }

    /**
     * Leaves one unresolved remote outcome verifying by its original key.
     *
     * <p>It never retries: the only thing a pending or unknown outcome permits is another bounded
     * reconciliation query under the same key, and the Runtime node and Participant are persisted as verifying
     * and waiting so the Execution can never complete over them.
     */
    private ActionOutcome unresolved(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            ProviderResult result,
            String auditReason,
            Instant at) {
        if (!policies.verificationAttemptsRemain(action.verificationCount())) {
            return review(claim, action, pins, attempt, result, auditReason, at);
        }
        ActionExecution verifying = action.status() == ActionStatus.VERIFYING
                ? action.verificationDeferred(result.reasonCode(), at)
                : action.verifying(at);
        Instant availableAt = at.plus(policies.delayBeforeVerification(action.verificationCount() + 1));
        ActionTask successor = null;
        ActionTask closed;
        if (claim.kind() == ActionTaskKind.SUBMISSION) {
            // The submission lane closes; a fresh durable reconciliation task carries the next work.
            closed = claim.completed(at);
            successor = scheduleVerification(
                    claim.tenantId(), action, availableAt, at);
        } else {
            // The one reconciliation slot simply becomes due again at the bounded backoff instant.
            closed = claim.retryDue(availableAt, auditReason, at);
        }
        List<JourneyParticipant> after = replace(
                pins.participants(), action.participantId(),
                waiting(pins.participant(), action, availableAt, at));
        CampaignExecution execution = aggregate(pins.execution(), after, at);
        ExecutionCounters counters =
                RuntimeAggregation.counters(execution, after, pins.counters().revision() + 1L);
        ExternalActionCompletion completion = runtimeCompletion(
                pins, action, after, execution, counters, List.of(), at);
        BudgetTransition budget = budget(action, BudgetTransitionKind.HELD, at);
        List<ActionAuditRecord> audits = audits(
                claim, action, attempt, ActionAuditRecord.REASON_ACTION_VERIFYING, auditReason, budget, at);
        return commit(claim, new Next(
                verifying, closed, attempt, successor, budget.reservation(), budget.ledger(), completion,
                audits), ActionDisposition.UNKNOWN_VERIFYING);
    }

    /** Leaves one unresolved action needing a human decision, with its exact reservation still held. */
    private ActionOutcome review(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            ProviderResult result,
            String auditReason,
            Instant at) {
        ActionExecution nextAction = action.needsReview(ActionErrorCodes.ACTION_VERIFICATION_EXHAUSTED, at);
        List<JourneyParticipant> after = replace(
                pins.participants(), action.participantId(), reviewing(pins.participant(), action, at));
        CampaignExecution execution = aggregate(pins.execution(), after, at);
        ExecutionCounters counters =
                RuntimeAggregation.counters(execution, after, pins.counters().revision() + 1L);
        ExternalActionCompletion completion = runtimeCompletion(
                pins, action, after, execution, counters, List.of(), at);
        BudgetTransition budget = budget(action, BudgetTransitionKind.HELD, at);
        List<ActionAuditRecord> audits = audits(
                claim, action, attempt, ActionAuditRecord.REASON_NEEDS_REVIEW, auditReason, budget, at);
        return commit(claim, new Next(
                nextAction, claim.completed(at), attempt, null, budget.reservation(), budget.ledger(),
                completion, audits), ActionDisposition.NEEDS_REVIEW);
    }

    /**
     * Schedules one bounded safe retry under the original action, key, request hash and reservation.
     *
     * <p>A rejected submission simply reuses its own durable slot at the bounded backoff instant. A verified
     * non-application closes the reconciliation lane and takes a fresh submission cycle, because the previous
     * submission slot legitimately completed.
     */
    private ActionOutcome scheduleSafeRetry(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            String reasonCode,
            Instant at) {
        ActionExecution nextAction = action.safeRetry(at);
        Instant availableAt = at.plus(policies.delayBeforeSubmission(action.submissionCount() + 1));
        ActionTask closed;
        ActionTask successor = null;
        if (claim.kind() == ActionTaskKind.SUBMISSION) {
            closed = claim.retryDue(availableAt, ActionAuditRecord.REASON_SAFE_RETRY, at);
        } else {
            closed = claim.completed(at);
            successor = scheduleSubmission(claim.tenantId(), action, availableAt, at);
        }
        BudgetTransition budget = budget(action, BudgetTransitionKind.HELD, at);
        List<ActionAuditRecord> audits = audits(
                claim, action, attempt, ActionAuditRecord.REASON_SAFE_RETRY, reasonCode, budget, at);
        return commit(claim, new Next(
                nextAction, closed, attempt, successor, budget.reservation(), budget.ledger(),
                null, audits), ActionDisposition.SAFE_RETRY_SCHEDULED);
    }

    private ActionOutcome settleAsDefinitiveFailure(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            Instant at,
            String reasonCode,
            String auditReason) {
        ProviderResult result = ProviderResult.definitivelyNotApplied(action.idempotencyKey(), reasonCode);
        ActionAttempt attempt = ActionAttempt.of(
                claim.tenantId(), action.actionId(), action.idempotencyKey(), ActionAttemptKind.SUBMISSION,
                action.submissionCount(), result, reasonCode, claim.updatedAt(), at);
        return settleAsDefinitiveFailure(claim, action, pins, attempt, reasonCode, auditReason, at);
    }

    /**
     * Settles one definitive failure by the frozen failure policy: the node and Participant fail, the Execution
     * aggregate follows its Participants, and the exact reservation is released exactly once.
     *
     * <p>It never claims message acceptance or reward success.
     */
    private ActionOutcome settleAsDefinitiveFailure(
            ActionTask claim,
            ActionExecution action,
            Pins pins,
            ActionAttempt attempt,
            String reasonCode,
            String auditReason,
            Instant at) {
        ActionExecution nextAction = action.definitelyFailed(reasonCode, at);
        List<JourneyParticipant> after = replace(
                pins.participants(), action.participantId(), failed(pins.participant(), action, at));
        CampaignExecution execution = aggregate(pins.execution(), after, at);
        ExecutionCounters counters =
                RuntimeAggregation.counters(execution, after, pins.counters().revision() + 1L);
        ExternalActionCompletion completion = runtimeCompletion(
                pins, action, after, execution, counters, List.of(), at);
        BudgetTransition budget = budget(action, BudgetTransitionKind.RELEASED, at);
        List<ActionAuditRecord> audits = audits(
                claim, action, attempt, ActionAuditRecord.REASON_ACTION_FAILED, auditReason, budget, at);
        return commit(claim, new Next(
                nextAction, claim.completed(at), attempt, null, budget.reservation(), budget.ledger(),
                completion, audits), ActionDisposition.DEFINITELY_FAILED);
    }

    // ------------------------------------------------------------------ operator operations

    /**
     * Makes one bounded reconciliation of one possibly-applied action due under explicit host authorization.
     *
     * <p>An absent authorization and an absent or cross-owner action answer with the same non-disclosing
     * failure, so no resource's existence is disclosed by a refusal.
     */
    public ActionOutcome reconcile(ActionOperatorCommand command) {
        Objects.requireNonNull(command, "command");
        requireAuthorized(command);
        Instant at = clock.instant();
        ActionExecution action = requireOwnedAction(command);
        if (action.status().isTerminal()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TERMINAL_IMMUTABLE, "actionStatus",
                    "A terminal action is immutable and cannot be reconciled.");
        }
        if (action.status() != ActionStatus.UNKNOWN && action.status() != ActionStatus.VERIFYING) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_RECONCILIATION_REQUIRED, "actionStatus",
                    "Only an unknown or verifying action may be reconciled.");
        }
        ActionExecution verifying =
                action.status() == ActionStatus.UNKNOWN ? action.verifying(at) : action;
        ActionTask scheduled = scheduleVerification(action.tenantId(), action, at, at);
        List<ActionAuditRecord> audits = List.of(audit(
                action.tenantId(), ActionAuditRecord.REASON_OPERATOR_RECONCILE, "ACTION", action.actionId(),
                action.revision(), at));
        ActionStore.ActionScheduleResult result = store.commitActionSchedule(
                new ActionStore.ActionScheduleCommit(
                        action.tenantId(), action.actionId(), action.revision(), verifying, scheduled, audits));
        return scheduledOutcome(
                ActionDisposition.VERIFICATION_SCHEDULED, result.action(), result.task());
    }

    /**
     * Performs one bounded manual safe retry, which only persisted evidence can authorize.
     *
     * <p>It is permitted only when the latest actual reconciliation proved the effect definitively not applied,
     * the action is still verifying and the bounded submission attempts remain. It can never override an
     * unknown or review-needing state, a changed request hash, the budget state or an attempt limit.
     */
    public ActionOutcome safeRetry(ActionOperatorCommand command) {
        Objects.requireNonNull(command, "command");
        requireAuthorized(command);
        Instant at = clock.instant();
        ActionExecution action = requireOwnedAction(command);
        if (action.status() == ActionStatus.UNKNOWN || action.status() == ActionStatus.NEEDS_REVIEW) {
            // A manual retry can never override an unknown or review-needing remote outcome.
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_RETRY_NOT_PERMITTED, "actionStatus",
                    "A manual retry cannot override an unresolved action.");
        }
        if (action.status().isTerminal()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TERMINAL_IMMUTABLE, "actionStatus",
                    "A terminal action is immutable and cannot be retried.");
        }
        if (!policies.submissionAttemptsRemain(action.submissionCount())) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_RETRY_NOT_PERMITTED, "submissionCount",
                    "A manual retry cannot exceed the bounded submission attempts.");
        }
        if (!latestVerificationProvedNotApplied(action)) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_RETRY_NOT_PERMITTED, "verification",
                    "A manual retry requires persisted, definitive non-application evidence.");
        }
        ActionExecution reserved = action.status() == ActionStatus.RESERVED ? action : action.safeRetry(at);
        ActionTask submission = scheduleSubmission(action.tenantId(), action, at, at);
        List<ActionAuditRecord> audits = List.of(audit(
                action.tenantId(), ActionAuditRecord.REASON_OPERATOR_RETRY, "ACTION", action.actionId(),
                action.revision(), at));
        ActionStore.ActionScheduleResult result = store.commitActionSchedule(
                new ActionStore.ActionScheduleCommit(
                        action.tenantId(), action.actionId(), action.revision(), reserved, submission, audits));
        return scheduledOutcome(
                ActionDisposition.SAFE_RETRY_SCHEDULED, result.action(), result.task());
    }

    private ActionOutcome scheduledOutcome(
            ActionDisposition disposition, ActionExecution action, ActionTask task) {
        return new ActionOutcome(
                disposition, action, task, null,
                store.findReservation(action.tenantId(), action.actionId()).orElse(null),
                store.findBudgetLedger(action.tenantId(), action.executionId()).orElse(null),
                null, null, false);
    }

    // ------------------------------------------------------------------ shared internals

    private void requireAuthorized(ActionOperatorCommand command) {
        if (!command.authorized()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_OPERATION_FORBIDDEN, "authorization",
                    "A host-only external-action operation requires campaign.operate.");
        }
    }

    /**
     * The exact action one operator command names inside its own tenant, or one non-disclosing not-found.
     *
     * <p>An absent action and one owned by another tenant produce byte-identical failures, so no cross-tenant
     * action's existence is disclosed. A stale expected revision is a bounded conflict.
     */
    private ActionExecution requireOwnedAction(ActionOperatorCommand command) {
        ActionExecution action = store.findActionById(command.tenantId(), command.actionId())
                .orElseThrow(() -> ActionSupport.invalid(ActionErrorCodes.ACTION_NOT_FOUND, "actionId"));
        if (action.revision() != command.expectedRevision()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_STORE_CONFLICT, "actionRevision",
                    "The action moved before this operation committed.");
        }
        return action;
    }

    private boolean latestVerificationProvedNotApplied(ActionExecution action) {
        ActionAttempt latest = null;
        for (ActionAttempt attempt : store.findActionAttempts(action.tenantId(), action.actionId())) {
            if (attempt.attemptKind() == ActionAttemptKind.VERIFICATION
                    && (latest == null || attempt.attemptNumber() > latest.attemptNumber())) {
                latest = attempt;
            }
        }
        return latest != null && latest.outcome().definitivelyNotApplied();
    }

    private ActionExecution requireAction(String tenantId, String actionId) {
        return store.findActionById(tenantId, actionId)
                .orElseThrow(() -> ActionSupport.invalid(ActionErrorCodes.ACTION_NOT_FOUND, "actionId"));
    }

    private ActionRequest requireRequest(String tenantId, String actionId) {
        return store.findRequest(tenantId, actionId)
                .orElseThrow(() -> ActionSupport.invalid(ActionErrorCodes.ACTION_STORE_CONFLICT, "request"));
    }

    /**
     * The exact Host-side capability evidence a submission is gated on, or a bounded refusal.
     *
     * <p>Missing, thrown, owner-mismatched, kind-mismatched, hash-mismatched or unsupported evidence all fail
     * closed before any Provider submission, because a local lock is never a substitute for a remote guarantee.
     */
    private ProviderCapability requireCapability(ActionKind kind, String tenantId) {
        ProviderCapability capability;
        try {
            capability = kind == ActionKind.MESSAGE
                    ? messageProvider.capability(tenantId)
                    : rewardProvider.capability(tenantId);
        } catch (RuntimeException thrown) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_PROVIDER_CAPABILITY_MISSING, "capability",
                    "The Provider stated no bounded capability evidence.");
        }
        if (capability == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_PROVIDER_CAPABILITY_MISSING, "capability",
                    "The Provider stated no bounded capability evidence.");
        }
        if (!capability.ownerTenantId().equals(tenantId) || capability.actionKind() != kind) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_PROVIDER_CAPABILITY_MISMATCH, "capability",
                    "The Provider capability evidence does not witness this owner and action kind.");
        }
        if (!capability.satisfiesSubmission()) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_PROVIDER_CAPABILITY_UNSUPPORTED, "capability",
                    "A Provider without business-key idempotency and reliable reconciliation may not be called.");
        }
        return capability;
    }

    private ActionOutcome commit(ActionTask claim, Next next, ActionDisposition disposition) {
        ActionExecution current = requireAction(claim.tenantId(), claim.actionId());
        ActionStore.ActionCompletionResult result = store.commitActionCompletion(
                new ActionStore.ActionCompletionCommit(
                        claim.tenantId(), claim.actionId(), current.revision(), next.action(), claim.taskId(),
                        claim.revision(), claim.leaseToken(), next.task(), next.attempt(), next.successorTask(),
                        next.reservation(), next.ledger(), next.runtime(), next.audits()));
        ActionAttempt attempt = result.attempt();
        return new ActionOutcome(
                result.duplicate() ? ActionDisposition.DUPLICATE_COMPLETION : disposition,
                result.action(), result.task(), attempt, result.reservation(), result.ledger(),
                attempt == null ? null : attempt.outcome(),
                result.runtimeCompletion(), result.duplicate());
    }

    // ------------------------------------------------------------------ budget

    private enum BudgetTransitionKind { CREATED, HELD, FINALIZED, RELEASED }

    private record BudgetTransition(BudgetReservation reservation, BudgetLedger ledger) {
    }

    /**
     * The exact budget move one action completion commits: created once, held for every retry and
     * reconciliation, finalized exactly once on success and released exactly once on definitive failure.
     *
     * <p>A MESSAGE action moves nothing at all, because it reserves no reward budget. A settled reservation is
     * restated exactly rather than moved again.
     */
    private BudgetTransition budget(
            ActionExecution action, BudgetTransitionKind kind, Instant at) {
        if (!action.actionKind().reservesRewardBudget()) {
            return new BudgetTransition(null, null);
        }
        BudgetReservation stored = store.findReservation(action.tenantId(), action.actionId())
                .orElseThrow(() -> ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservation"));
        BudgetLedger ledger = store.findBudgetLedger(action.tenantId(), action.executionId())
                .orElseThrow(() -> ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_CONFLICT, "ledger"));
        if (stored.state().isTerminal()) {
            return new BudgetTransition(stored, ledger);
        }
        return switch (kind) {
            case CREATED, HELD -> new BudgetTransition(stored, ledger);
            case FINALIZED -> new BudgetTransition(
                    stored.finalized(at), ledger.finalize(stored.grantCount(), stored.faceValue()));
            case RELEASED -> new BudgetTransition(
                    stored.released(at), ledger.release(stored.grantCount(), stored.faceValue()));
        };
    }

    // ------------------------------------------------------------------ runtime integration

    /** Everything one completion rests on, resolved before any commit begins. */
    private record Pins(
            CampaignExecution execution,
            List<JourneyParticipant> participants,
            JourneyParticipant participant,
            NodeExecution nodeExecution,
            ExecutionCounters counters,
            RuntimeCampaignState campaignState,
            FullPublishedDefinition definition,
            PublishedCampaignDefinition base,
            JourneyManifest journey) {
    }

    private Pins pins(ActionExecution action) {
        String tenantId = action.tenantId();
        CampaignExecution execution = runtime.findExecutionById(tenantId, action.executionId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_BINDING_MISMATCH, "execution"));
        List<JourneyParticipant> participants = runtime.findParticipants(tenantId, execution.executionId());
        JourneyParticipant participant = null;
        for (JourneyParticipant candidate : participants) {
            if (candidate.participantId().equals(action.participantId())) {
                participant = candidate;
            }
        }
        if (participant == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "participant");
        }
        NodeExecution node = runtime.findNodeExecution(
                        tenantId, execution.executionId(), action.participantId(), action.nodeLogicalId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_BINDING_MISMATCH, "nodeExecution"));
        ExecutionCounters counters = runtime.findCounters(tenantId, execution.executionId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_BINDING_MISMATCH, "counters"));
        RuntimeCampaignState state = runtime.findCampaignState(tenantId, execution.campaignId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_CAMPAIGN_STATE_UNVERIFIABLE, "campaignState"));
        FullPublishedDefinition definition = resolver.requireDefinition(execution);
        PublishedCampaignDefinition base = resolver.requireBase(definition);
        if (!definition.journey().contentHash().equals(execution.journeyHash())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_DEFINITION_HASH_MISMATCH, "journeyBinding");
        }
        return new Pins(execution, participants, participant, node, counters, state, definition, base,
                definition.journey());
    }

    /** The one exact frozen DEFAULT successor of one frozen node, never selected by array position. */
    private static String successor(JourneyManifest journey, String fromNodeLogicalId) {
        String match = null;
        for (JourneyTopologyEdge edge : journey.edges()) {
            if (edge.fromNodeLogicalId().equals(fromNodeLogicalId)
                    && edge.branch() == JourneyBranchKind.DEFAULT) {
                if (match != null) {
                    throw new ActionBoundaryException(
                            ActionErrorCodes.ACTION_BINDING_MISMATCH, "edge",
                            "A frozen Journey branch must not carry two successors.");
                }
                match = edge.toNodeLogicalId();
            }
        }
        if (match == null || journey.node(match) == null) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BINDING_MISMATCH, "edge",
                    "A frozen external-action node required an exact successor the topology does not carry.");
        }
        return match;
    }

    private static JourneyParticipant advanced(
            JourneyParticipant participant, String successor, Instant accepted, Instant at) {
        ParticipantStatus status = participant.status();
        if (status == ParticipantStatus.WAITING) {
            return participant.transitioned(ParticipantStatus.RUNNING, successor, null, null, accepted, at);
        }
        if (status == ParticipantStatus.CREATED || status == ParticipantStatus.RUNNING) {
            return participant.advanced(successor, accepted, at);
        }
        throw new ActionBoundaryException(
                ActionErrorCodes.ACTION_BINDING_MISMATCH, "participantStatus",
                "This Participant is not in a state that may accept an external action.");
    }

    private static JourneyParticipant waiting(
            JourneyParticipant participant, ActionExecution action, Instant deadline, Instant at) {
        if (participant.status() == ParticipantStatus.WAITING
                && ActionVersions.WAITING_REASON_ACTION_VERIFY.equals(participant.waitingReason())) {
            return participant;
        }
        return participant.transitioned(
                ParticipantStatus.WAITING, action.nodeLogicalId(), ActionVersions.WAITING_REASON_ACTION_VERIFY,
                deadline, participant.lastAcceptedActionInstant(), at);
    }

    private static JourneyParticipant failed(
            JourneyParticipant participant, ActionExecution action, Instant at) {
        if (participant.status() == ParticipantStatus.FAILED) {
            return participant;
        }
        return participant.transitioned(
                ParticipantStatus.FAILED, action.nodeLogicalId(), null, null,
                participant.lastAcceptedActionInstant(), at);
    }

    private static JourneyParticipant reviewing(
            JourneyParticipant participant, ActionExecution action, Instant at) {
        if (participant.status() == ParticipantStatus.NEEDS_REVIEW) {
            return participant;
        }
        return participant.transitioned(
                ParticipantStatus.NEEDS_REVIEW, action.nodeLogicalId(), null, null,
                participant.lastAcceptedActionInstant(), at);
    }

    private static List<JourneyParticipant> replace(
            List<JourneyParticipant> participants, String participantId, JourneyParticipant next) {
        List<JourneyParticipant> after = new ArrayList<>(participants.size());
        for (JourneyParticipant stored : participants) {
            after.add(stored.participantId().equals(participantId) ? next : stored);
        }
        return after;
    }

    /** The deterministic Execution aggregate over the whole Participant set. */
    private static CampaignExecution aggregate(
            CampaignExecution execution, List<JourneyParticipant> after, Instant at) {
        ExecutionStatus status = RuntimeAggregation.status(after);
        if (status == execution.status()) {
            return execution;
        }
        return execution.transitioned(
                status, execution.candidateCount(), execution.enrolledCount(), null, null, at);
    }

    /**
     * The atomic Runtime half of one completion, or {@code null} when nothing on the Runtime side moved.
     *
     * <p>It defers or retires the exact handed-off task and commits the frozen node, the Participant, the
     * Execution, the aggregate counters and any successor task together, inside the same store transaction as
     * the action and budget records.
     */
    private ExternalActionCompletion runtimeCompletion(
            Pins pins,
            ActionExecution action,
            List<JourneyParticipant> after,
            CampaignExecution execution,
            ExecutionCounters counters,
            List<RuntimeTask> nextTasks,
            Instant at) {
        JourneyParticipant participant = after.stream()
                .filter(candidate -> candidate.participantId().equals(action.participantId()))
                .findFirst()
                .orElseThrow();
        NodeExecution nextNode = nextNode(pins.nodeExecution(), action, participant, at);
        if (nextNode.equals(pins.nodeExecution()) && participant.equals(pins.participant())
                && nextTasks.isEmpty()) {
            // Nothing on the Runtime side moved, so this completion writes no Runtime record at all.
            return null;
        }
        String tenantId = pins.execution().tenantId();
        RuntimeTask handoff = runtime.findRuntimeTask(
                        tenantId,
                        RuntimeTask.taskId(
                                tenantId, pins.execution().executionId(), pins.participant().participantId(),
                                pins.participant().currentNodeLogicalId(), RuntimeTaskKind.ACTION_READY))
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_HANDOFF_NOT_FOUND, "handoffTask"));
        RuntimeTask retired = nextNode.status() == NodeExecutionStatus.VERIFYING
                ? handoff.deferred(at)
                : handoff.retired(RuntimeTaskKind.ACTION_READY.completionReasonCode(), at);
        List<RuntimeTask> guarded = new ArrayList<>(nextTasks.size());
        for (RuntimeTask next : nextTasks) {
            guarded.add(guard(pins, next, at));
        }
        String auditReason = nextNode.status() == NodeExecutionStatus.VERIFYING
                ? RuntimeAuditRecord.REASON_ACTION_NODE_VERIFYING
                : RuntimeAuditRecord.REASON_ACTION_NODE_COMPLETED;
        RuntimeAuditRecord audit = RuntimeAuditRecord.of(
                ActionSupport.derivedId("audit", List.of(
                        auditReason, pins.execution().executionId(), action.nodeLogicalId(), at)),
                tenantId, auditReason, "TASK", retired.taskId(),
                pins.execution().executionKey(), handoff.revision(), retired.revision(), at);
        return new ExternalActionCompletion(
                tenantId, pins.execution().executionKey(), pins.execution().executionId(),
                pins.execution().revision(), execution, pins.participant().participantId(),
                pins.participant().revision(), pins.participant().currentNodeLogicalId(), participant,
                retired.taskId(), handoff.revision(), retired, nextNode, guarded, counters, List.of(audit));
    }

    /**
     * The exact frozen node state one Participant state implies.
     *
     * <p>A Participant still at the action node is verifying, needing review or failed according to its own
     * state; a Participant that moved on means the node is exactly the successful one.
     */
    private static NodeExecution nextNode(
            NodeExecution node, ActionExecution action, JourneyParticipant participant, Instant at) {
        if (!participant.currentNodeLogicalId().equals(action.nodeLogicalId())) {
            return node.status() == NodeExecutionStatus.SUCCEEDED ? node : node.succeeded(at);
        }
        return switch (participant.status()) {
            case WAITING -> node.status() == NodeExecutionStatus.VERIFYING ? node : node.verifying(at);
            case NEEDS_REVIEW -> node.status() == NodeExecutionStatus.NEEDS_REVIEW
                    ? node : node.needsReview(at);
            case FAILED -> node.status() == NodeExecutionStatus.FAILED ? node : node.failed(at);
            default -> node;
        };
    }

    /** Guards one newly created next Runtime task as {@code DEFERRED} when no new work may start. */
    private static RuntimeTask guard(Pins pins, RuntimeTask task, Instant at) {
        return pins.campaignState().mayStartNewWork() ? task : task.deferred(at);
    }

    /**
     * The one durable submission slot of one action, made due at one exact instant.
     *
     * <p>A slot that is outstanding is reused and rescheduled; a slot that completed is never reused, so a
     * later bounded submission takes the next cycle identity instead.
     */
    private ActionTask scheduleSubmission(
            String tenantId, ActionExecution action, Instant availableAt, Instant at) {
        ActionTask stored = store.findActionTask(tenantId, action.actionId(), ActionTaskKind.SUBMISSION)
                .orElse(null);
        if (stored == null) {
            return ActionTask.due(
                    tenantId, action.actionId(), action.idempotencyKey(), ActionTaskKind.SUBMISSION, 0,
                    availableAt);
        }
        if (stored.status() == ActionTaskStatus.LEASED) {
            return stored.retryDue(availableAt, ActionAuditRecord.REASON_SAFE_RETRY, at);
        }
        if (stored.status() == ActionTaskStatus.DUE || stored.status() == ActionTaskStatus.DEFERRED) {
            return stored.rescheduled(availableAt, ActionAuditRecord.REASON_SAFE_RETRY, at);
        }
        return ActionTask.due(
                tenantId, action.actionId(), action.idempotencyKey(), ActionTaskKind.SUBMISSION,
                stored.cycle() + 1, availableAt);
    }

    /**
     * The one durable reconciliation slot of one action, made due at one exact instant.
     *
     * <p>A slot that is already leased is released back to {@code DUE} at the bounded backoff instant; a slot
     * that is already queued is rescheduled; a slot that completed is never reused, so a later bounded
     * reconciliation takes the next cycle identity instead.
     */
    private ActionTask scheduleVerification(
            String tenantId, ActionExecution action, Instant availableAt, Instant at) {
        ActionTask stored = store.findActionTask(tenantId, action.actionId(), ActionTaskKind.VERIFICATION)
                .orElse(null);
        if (stored == null) {
            return ActionTask.due(
                    tenantId, action.actionId(), action.idempotencyKey(), ActionTaskKind.VERIFICATION, 0,
                    availableAt);
        }
        if (stored.status() == ActionTaskStatus.LEASED) {
            return stored.retryDue(availableAt, "ACTION_VERIFICATION", at);
        }
        if (stored.status() == ActionTaskStatus.DUE || stored.status() == ActionTaskStatus.DEFERRED) {
            return stored.rescheduled(availableAt, "ACTION_VERIFICATION", at);
        }
        return ActionTask.due(
                tenantId, action.actionId(), action.idempotencyKey(), ActionTaskKind.VERIFICATION,
                stored.cycle() + 1, availableAt);
    }

    // ------------------------------------------------------------------ audit

    private List<ActionAuditRecord> audits(
            ActionTask claim,
            ActionExecution action,
            ActionAttempt attempt,
            String actionReason,
            String providerReason,
            BudgetTransition budget,
            Instant at) {
        List<ActionAuditRecord> records = new ArrayList<>();
        records.add(audit(claim.tenantId(), providerReason, "ATTEMPT", attempt.attemptId(),
                action.revision(), at));
        if (budget.reservation() != null && budget.reservation().state().isTerminal()) {
            records.add(audit(claim.tenantId(),
                    budget.reservation().state() == BudgetReservationState.FINALIZED
                            ? ActionAuditRecord.REASON_BUDGET_FINALIZED
                            : ActionAuditRecord.REASON_BUDGET_RELEASED,
                    "BUDGET", budget.reservation().reservationId(), action.revision(), at));
        }
        records.add(audit(claim.tenantId(), actionReason, "ACTION", action.actionId(), action.revision(), at));
        return List.copyOf(records);
    }

    private static ActionAuditRecord audit(
            String tenantId,
            String reasonCode,
            String subjectKind,
            String subjectId,
            long revision,
            Instant at) {
        return ActionAuditRecord.of(
                ActionSupport.derivedId("actaudit", List.of(reasonCode, tenantId, subjectId, revision, at)),
                tenantId, reasonCode, subjectKind, subjectId, revision, revision, at);
    }

    /** Everything one completion writes, gathered before the store sees it. */
    private record Next(
            ActionExecution action,
            ActionTask task,
            ActionAttempt attempt,
            ActionTask successorTask,
            BudgetReservation reservation,
            BudgetLedger ledger,
            ExternalActionCompletion runtime,
            List<ActionAuditRecord> audits) {
    }

    /** Redacted diagnostics: the service holds no owner value. */
    @Override
    public String toString() {
        return "ActionApplicationService[policies=" + policies + ']';
    }
}
