package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyNode;
import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The leased Runtime worker: it claims one due task, advances exactly one frozen Journey node and commits the
 * result atomically.
 *
 * <p>Claiming is deterministic — only locally claimable work in {@code (availableAt, taskId)} order, by explicit
 * worker identity, with an opaque lease token and a bounded lease — and an unexpired lease is never stolen. A
 * completion requires the exact lease token and a compare-and-set on both the Participant revision and its
 * current node, so stale work writes nothing. Duplicate hash-equal work reuses the committed result.
 *
 * <p>Only the exact frozen T20 {@link JourneyManifest} is progressed, and only by stable logical identity: the
 * matching component payload is resolved from the exact frozen base Manifest and its capability, contract
 * version and payload hash are re-proved before a node runs. No edge is selected by array position and no
 * mutable catalog is ever consulted.
 *
 * <p>The one external collaborator is the {@link RuntimeFactPort}, and it is called with no store transaction
 * held: the worker leases, releases the store, reads the fact, then re-acquires the store to commit. A
 * {@code START}, {@code END} and {@code EXIT} node needs no external call at all; a {@code MESSAGE} or
 * {@code REWARD} node produces only a bounded {@code ACTION_READY} handoff for T23 and stops, claiming no
 * Provider result, no message acceptance, no reward success and no budget reservation.
 *
 * <p>Every claim and every advancement rechecks the host-owned Campaign runtime/schedule projection. A pause
 * stops new nodes and handoffs from starting and guards their next task as {@code DEFERRED} without shifting
 * any Wait deadline; a resume releases exactly the guarded work that is already due.
 * {@link #toString()} prints no owner value.
 */
public final class RuntimeWorker {

    /** The bounded subject kind a Campaign-level runtime audit record names. */
    public static final String SUBJECT_CAMPAIGN = "CAMPAIGN";

    /** The bounded subject kind a task-level runtime audit record names. */
    public static final String SUBJECT_TASK = "TASK";

    private final RuntimeDefinitionLookupPort definitions;
    private final RuntimeCampaignStatePort campaignStates;
    private final RuntimeFactPort facts;
    private final RuntimeStore store;
    private final Clock clock;
    private final RuntimeRetryPolicy retryPolicy;
    private final Duration leaseDuration;
    private final long factTimeoutMillis;
    private final int claimScanLimit;
    private final RuntimeConditionEvaluator evaluator = new RuntimeConditionEvaluator();

    public RuntimeWorker(
            RuntimeDefinitionLookupPort definitions,
            RuntimeCampaignStatePort campaignStates,
            RuntimeFactPort facts,
            RuntimeStore store,
            Clock clock,
            RuntimeRetryPolicy retryPolicy,
            Duration leaseDuration,
            long factTimeoutMillis,
            int claimScanLimit) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.campaignStates = Objects.requireNonNull(campaignStates, "campaignStates");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(RuntimeVersions.MIN_LEASE_DURATION) < 0
                || leaseDuration.compareTo(RuntimeVersions.MAX_LEASE_DURATION) > 0) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "leaseDuration",
                    "A lease duration must lie inside its frozen bounded interval.");
        }
        if (factTimeoutMillis < 1 || factTimeoutMillis > RuntimeFactRequest.MAX_TIMEOUT_MILLIS) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "factTimeoutMillis");
        }
        if (claimScanLimit < 1 || claimScanLimit > RuntimeVersions.MAX_CLAIM_CANDIDATES) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "claimScanLimit",
                    "A claim scan must be bounded and positive.");
        }
        this.leaseDuration = leaseDuration;
        this.factTimeoutMillis = factTimeoutMillis;
        this.claimScanLimit = claimScanLimit;
    }

    // ------------------------------------------------------------------ claiming

    /** Claims the next due task of every tenant for this worker. */
    public RuntimeClaimResult claimNext(String workerId) {
        return claim(null, workerId);
    }

    /** Claims the next due task of one tenant for this worker. */
    public RuntimeClaimResult claimNext(String tenantId, String workerId) {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        return claim(tenantId, workerId);
    }

    private RuntimeClaimResult claim(String tenantId, String workerId) {
        RuntimeSupport.requireIdentifier(workerId, "workerId");
        Instant now = clock.instant();
        List<RuntimeTask> candidates = tenantId == null
                ? store.findClaimCandidates(now, claimScanLimit)
                : store.findClaimCandidates(tenantId, now, claimScanLimit);
        if (candidates.isEmpty()) {
            return new RuntimeClaimResult(RuntimeClaimOutcome.NONE_DUE, null);
        }
        boolean paused = false;
        boolean unverified = false;
        boolean contended = false;
        for (RuntimeTask candidate : candidates) {
            CampaignExecution execution =
                    store.findExecutionById(candidate.tenantId(), candidate.executionId()).orElse(null);
            if (execution == null) {
                unverified = true;
                continue;
            }
            Optional<RuntimeCampaignState> state = loadState(execution);
            if (state.isEmpty()) {
                unverified = true;
                continue;
            }
            if (!state.get().mayStartNewWork()) {
                // A pause stops new nodes and handoffs from starting; the task simply stays where it is.
                paused = true;
                continue;
            }
            try {
                return new RuntimeClaimResult(RuntimeClaimOutcome.CLAIMED, lease(candidate, workerId, now));
            } catch (RuntimeBoundaryException lost) {
                // Another worker took the lease first; the next candidate is considered instead.
                contended = true;
            }
        }
        if (paused) {
            return new RuntimeClaimResult(RuntimeClaimOutcome.PAUSED, null);
        }
        if (unverified) {
            return new RuntimeClaimResult(RuntimeClaimOutcome.UNVERIFIED, null);
        }
        return new RuntimeClaimResult(
                contended ? RuntimeClaimOutcome.CONTENDED : RuntimeClaimOutcome.NONE_DUE, null);
    }

    private RuntimeTask lease(RuntimeTask candidate, String workerId, Instant now) {
        int attemptNumber = candidate.attemptNumber() + 1;
        String token = RuntimeTask.leaseToken(workerId, candidate.taskId(), attemptNumber, now, leaseDuration);
        Instant expiry = now.plus(leaseDuration);
        String reason = candidate.status() == RuntimeTaskStatus.LEASED
                ? RuntimeAuditRecord.REASON_LEASE_RECLAIMED
                : RuntimeAuditRecord.REASON_LEASE_CLAIMED;
        RuntimeAuditRecord audit = audit(
                candidate.tenantId(), reason, SUBJECT_TASK, candidate.taskId(), null, attemptNumber, now);
        TaskClaimResult result = store.claimTask(new TaskClaimCommit(
                candidate.tenantId(), candidate.taskId(), candidate.revision(), workerId, now, leaseDuration,
                token, expiry, audit));
        return result.task();
    }

    /** Releases the guarded work of one resumed Campaign, without shifting any availability instant. */
    public int releaseGuarded(String tenantId, String campaignId) {
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        Instant at = clock.instant();
        Optional<RuntimeCampaignState> state = loadState(tenantId, campaignId);
        if (state.isEmpty() || !state.get().mayStartNewWork()) {
            // A paused or unverifiable Campaign releases nothing: guarded work stays exactly where it was.
            return 0;
        }
        RuntimeAuditRecord audit = audit(
                tenantId, RuntimeAuditRecord.REASON_RESUME_RELEASED, SUBJECT_CAMPAIGN, campaignId, null, null,
                at);
        return store.releaseGuardedTasks(tenantId, campaignId, at, audit);
    }

    // ------------------------------------------------------------------ processing

    /** Claims one due task and processes it; empty when nothing could be claimed. */
    public Optional<RuntimeNodeResult> runOnce(String workerId) {
        RuntimeClaimResult claim = claimNext(workerId);
        return claim.claimed() ? Optional.of(process(claim.task())) : Optional.empty();
    }

    /** Claims and processes one due task inside one tenant; empty when nothing could be claimed. */
    public Optional<RuntimeNodeResult> runOnce(String tenantId, String workerId) {
        RuntimeClaimResult claim = claimNext(tenantId, workerId);
        return claim.claimed() ? Optional.of(process(claim.task())) : Optional.empty();
    }

    /**
     * Advances exactly one node of one already-leased task and commits the result atomically.
     *
     * <p>The tenant is restored from the persisted task, never from a caller argument or a thread context: the
     * task is the authority for whose work this is, and every related record is re-proved to belong to it.
     */
    public RuntimeNodeResult process(RuntimeTask claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.status() != RuntimeTaskStatus.LEASED || claim.leaseToken() == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LEASE_INVALID, "claim",
                    "Only a leased task may be processed.");
        }
        Instant at = clock.instant();
        CampaignExecution execution = store.findExecutionById(claim.tenantId(), claim.executionId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND, "execution",
                        "No Execution is stored for this leased task."));
        NodeAttempt prior = store.findAttempt(
                claim.tenantId(),
                NodeAttempt.attemptId(
                        claim.taskId(),
                        NodeExecution.nodeExecutionId(
                                claim.tenantId(), claim.executionId(), claim.participantId(),
                                claim.nodeLogicalId()),
                        claim.attemptNumber()))
                .orElse(null);
        if (prior != null) {
            // This exact actual try was already committed, so this call is a redelivery: the committed
            // logical result is reused and nothing is written.
            return replay(claim, execution);
        }
        JourneyParticipant participant = requireParticipant(claim, execution);
        ExecutionCounters counters = store.findCounters(claim.tenantId(), claim.executionId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "counters",
                        "An Execution with leased work must carry its aggregate."));
        FrozenPins pins = loadFrozenPins(execution);
        JourneyNode node = pins.journey().node(claim.nodeLogicalId());
        if (node == null) {
            // The frozen Journey itself cannot witness this node, so no node-kind-bound record may be written
            // for it. The work fails closed and exactly nothing is committed.
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH, "node",
                    "The frozen Journey this Execution pins does not own this node identity.");
        }
        NodeExecution currentNode = store.findNodeExecution(
                claim.tenantId(), claim.executionId(), claim.participantId(), claim.nodeLogicalId())
                .orElse(null);
        NodeContext context = new NodeContext(
                claim, execution, participant, counters, pins, node, null, currentNode, true, at);
        try {
            context = context.withComponent(requireComponent(pins.manifest(), node));
        } catch (RuntimeBoundaryException unbound) {
            return failClosed(
                    context, unbound.code(), RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
        }
        Optional<RuntimeCampaignState> state = loadState(execution);
        if (state.isEmpty()) {
            return failClosed(
                    context, RuntimeErrorCodes.RUNTIME_CAMPAIGN_STATE_UNVERIFIABLE,
                    RuntimeAuditRecord.REASON_CAMPAIGN_STATE_UNVERIFIED);
        }
        context = context.withMayStart(state.get().mayStartNewWork());
        try {
            if (claim.kind() == RuntimeTaskKind.WAIT_WAKE) {
                return wake(context);
            }
            return switch (node.kind()) {
                case START, END, EXIT -> succeed(context);
                case CONDITION -> condition(context);
                case WAIT -> wait(context);
                case MESSAGE, REWARD -> handoff(context);
            };
        } catch (RuntimeBoundaryException frozenDefect) {
            if (!isFrozenDefect(frozenDefect.code())) {
                // A store compare-and-set conflict, a stale lease token or an illegal transition is not a
                // configuration defect: it must surface rather than be dressed up as a human decision.
                throw frozenDefect;
            }
            return failClosed(
                    context, frozenDefect.code(), RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
        }
    }

    /**
     * Reuses the committed logical result of an already-recorded actual try, writing nothing.
     *
     * <p>An attempt identity is unique per actual claim, so finding it proves this claim's result was already
     * committed; the stored task, Participant, NodeExecution, Execution and counters are returned unchanged.
     */
    private RuntimeNodeResult replay(RuntimeTask claim, CampaignExecution execution) {
        RuntimeTask stored = store.findTask(claim.tenantId(), claim.taskId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_TASK_NOT_FOUND, "taskId",
                        "A committed attempt is missing the task it belongs to."));
        JourneyParticipant participant = requireParticipant(claim, execution);
        NodeExecution nodeExecution = store.findNodeExecution(
                claim.tenantId(), claim.executionId(), claim.participantId(), claim.nodeLogicalId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "nodeExecution",
                        "A committed attempt is missing the NodeExecution it belongs to."));
        ExecutionCounters counters = store.findCounters(claim.tenantId(), claim.executionId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "counters",
                        "A committed attempt is missing its Execution aggregate."));
        return new RuntimeNodeResult(
                RuntimeNodeDisposition.DUPLICATE, execution, participant, nodeExecution, stored, counters,
                null, false, true);
    }

    /**
     * True when a bounded failure code names a frozen-material defect rather than transient work.
     *
     * <p>Only these stop the work with a human decision. Everything else — a lost compare-and-set, a stale
     * lease token, an illegal transition — is either transient or a caller defect and must propagate.
     */
    private static boolean isFrozenDefect(String code) {
        return RuntimeErrorCodes.RUNTIME_EDGE_MISSING.equals(code)
                || RuntimeErrorCodes.RUNTIME_EDGE_AMBIGUOUS.equals(code)
                || RuntimeErrorCodes.RUNTIME_WAIT_CONFIGURATION_INVALID.equals(code)
                || RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID.equals(code)
                || RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH.equals(code);
    }

    // ------------------------------------------------------------------ node kinds

    /**
     * Succeeds one {@code START}, {@code END} or {@code EXIT} node and enqueues its exact successor.
     *
     * <p>A {@code START} node advances the Participant to its one {@code DEFAULT} successor; an {@code END} or
     * {@code EXIT} node terminates it as {@code COMPLETED} or {@code EXITED} respectively and enqueues nothing.
     */
    private RuntimeNodeResult succeed(NodeContext ctx) {
        JourneyNode node = ctx.node();
        if (node.kind().isTerminal()) {
            ParticipantStatus target =
                    node.kind() == JourneyNodeKind.END ? ParticipantStatus.COMPLETED : ParticipantStatus.EXITED;
            JourneyParticipant terminated = ctx.participant().transitioned(
                    target, node.logicalId(), null, null, ctx.participant().lastAcceptedActionInstant(),
                    ctx.at());
            return commit(ctx, succeeded(ctx), terminated, ctx.claim().completed(ctx.at()), List.of(),
                    List.of(), RuntimeNodeDisposition.TERMINATED,
                    List.of(audit(ctx, RuntimeAuditRecord.REASON_NODE_SUCCEEDED)));
        }
        String successor = successor(ctx.pins().journey(), node.logicalId(), JourneyBranchKind.DEFAULT);
        JourneyParticipant advanced = ctx.participant().advanced(
                successor, ctx.participant().lastAcceptedActionInstant(), ctx.at());
        RuntimeTask next = guard(ctx, RuntimeTask.due(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(), successor,
                RuntimeTaskKind.NODE, ctx.at()));
        return commit(ctx, succeeded(ctx), advanced, ctx.claim().completed(ctx.at()), List.of(next),
                List.of(), RuntimeNodeDisposition.ADVANCED,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_NODE_SUCCEEDED)));
    }

    /**
     * Computes the one Wait deadline of a frozen {@code WAIT} node and stores it with one wake task.
     *
     * <p>The deadline is a pure function of the frozen duration and anchor: {@code ENTRY} measures from the
     * frozen node-entry instant and {@code PREVIOUS_ACCEPTED_ACTION} from the persisted accepted-action
     * instant, and the latter fails closed when the Participant carries none. Nothing reads a clock here, so a
     * retry, a reclaim or a restart cannot shift the deadline.
     */
    private RuntimeNodeResult wait(NodeContext ctx) {
        Duration duration;
        String anchor;
        try {
            duration = waitDuration(ctx.component());
            anchor = waitAnchor(ctx.component());
        } catch (RuntimeBoundaryException invalid) {
            return failClosed(ctx, invalid.code(), RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
        }
        Instant base;
        if (FlowWaitPack.ANCHOR_PREVIOUS_ACCEPTED_ACTION.equals(anchor)) {
            base = ctx.participant().lastAcceptedActionInstant();
            if (base == null) {
                return failClosed(
                        ctx, RuntimeErrorCodes.RUNTIME_WAIT_ANCHOR_MISSING,
                        RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
            }
        } else {
            base = ctx.currentNode() == null ? ctx.at() : ctx.currentNode().entryAt();
        }
        Instant deadline = base.plus(duration);
        JourneyParticipant participant = ctx.participant().transitioned(
                ParticipantStatus.WAITING, ctx.node().logicalId(), RuntimeVersions.WAIT_REASON_DURATION,
                deadline, ctx.participant().lastAcceptedActionInstant(), ctx.at());
        RuntimeTask wake = guard(ctx, RuntimeTask.due(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.node().logicalId(), RuntimeTaskKind.WAIT_WAKE, deadline));
        return commit(ctx, startNodeExecution(ctx).waiting(ctx.at()), participant,
                ctx.claim().completed(ctx.at()), List.of(wake), List.of(), RuntimeNodeDisposition.WAITING,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_NODE_WAITING)));
    }

    /**
     * Resumes one waiting Participant at its original deadline, exactly once.
     *
     * <p>The wake task's availability <em>is</em> the stored deadline, so a delayed delivery, a restart and a
     * lease reclaim all reuse the deadline rather than recomputing one, and a duplicate wake is a duplicate
     * completion that writes nothing.
     */
    private RuntimeNodeResult wake(NodeContext ctx) {
        if (ctx.node().kind() != JourneyNodeKind.WAIT
                || ctx.currentNode() == null
                || ctx.currentNode().status() != NodeExecutionStatus.WAITING) {
            return failClosed(
                    ctx, RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH,
                    RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
        }
        String successor = successor(ctx.pins().journey(), ctx.node().logicalId(), JourneyBranchKind.DEFAULT);
        JourneyParticipant resumed = ctx.participant().transitioned(
                ParticipantStatus.RUNNING, successor, null, null,
                ctx.participant().lastAcceptedActionInstant(), ctx.at());
        RuntimeTask next = guard(ctx, RuntimeTask.due(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(), successor,
                RuntimeTaskKind.NODE, ctx.at()));
        return commit(ctx, ctx.currentNode().succeeded(ctx.at()), resumed, ctx.claim().completed(ctx.at()),
                List.of(next), List.of(), RuntimeNodeDisposition.ADVANCED,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_WAIT_WOKEN)));
    }

    /**
     * Evaluates one frozen {@code CONDITION} node through the exact T19 typed semantics.
     *
     * <p>A {@code TRUE} or {@code FALSE} verdict selects only its own typed arm. An unresolved or fail-closed
     * verdict never becomes {@code FALSE}: it stops local advancement and leaves the Participant needing a
     * decision. A fact port that could not answer at all is a bounded technical failure, not a value.
     */
    private RuntimeNodeResult condition(NodeContext ctx) {
        RuntimeConditionPredicate predicate;
        try {
            predicate = RuntimeConditionPredicate.fromFields(ctx.component().value().fields());
        } catch (RuntimeBoundaryException invalid) {
            return failClosed(ctx, invalid.code(), RuntimeAuditRecord.REASON_CONFIGURATION_FAILURE);
        }
        RuntimeFactResult fact;
        try {
            fact = facts.readFact(RuntimeFactRequest.of(
                    ctx.tenantId(), ctx.execution().campaignId(), ctx.execution().executionId(),
                    ctx.participant().participantId(), ctx.node().logicalId(), predicate.factCode(),
                    predicate.valueType(), factTimeoutMillis));
        } catch (RuntimeException hostFailure) {
            // A host that cannot answer at all is a bounded technical failure, never an unrestricted message.
            return retry(ctx, RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE);
        }
        if (fact == null) {
            return retry(ctx, RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE);
        }
        if (fact.outcome().isTechnical()) {
            return retry(ctx, fact.outcome() == RuntimeFactOutcome.TIMEOUT
                    ? RuntimeErrorCodes.RUNTIME_FACT_TIMEOUT
                    : RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE);
        }
        RuntimeConditionEvaluation evaluation = evaluator.evaluate(predicate, fact);
        if (!evaluation.verdict().selectsEdge()) {
            // UNKNOWN and ERROR never select FALSE; the work stops and needs a decision, and the fact being
            // unavailable is a business outcome rather than a defect in the frozen Campaign.
            return failClosed(ctx, evaluation.reasonCode() == null
                    ? RuntimeErrorCodes.RUNTIME_CONDITION_UNKNOWN
                    : evaluation.reasonCode(), RuntimeAuditRecord.REASON_BUSINESS_FAILURE);
        }
        JourneyBranchKind branch = evaluation.verdict() == RuntimeConditionVerdict.TRUE
                ? JourneyBranchKind.TRUE
                : JourneyBranchKind.FALSE;
        String successor = successor(ctx.pins().journey(), ctx.node().logicalId(), branch);
        JourneyParticipant advanced = ctx.participant().advanced(
                successor, ctx.participant().lastAcceptedActionInstant(), ctx.at());
        RuntimeTask next = guard(ctx, RuntimeTask.due(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(), successor,
                RuntimeTaskKind.NODE, ctx.at()));
        return commit(ctx, succeeded(ctx), advanced, ctx.claim().completed(ctx.at()), List.of(next),
                List.of(), RuntimeNodeDisposition.ADVANCED,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_NODE_SUCCEEDED)));
    }

    /**
     * Creates only the bounded {@code ACTION_READY} handoff a {@code MESSAGE} or {@code REWARD} node owes T23.
     *
     * <p>The node is left exactly where it is — {@code RUNNING} — and local advancement stops: no Provider is
     * called, no {@code ActionExecution} outcome is written, no message is sent, no reward is granted and no
     * budget is reserved. The node is deliberately not marked {@code SUCCEEDED}: it is not successful merely
     * because a request was handed off, so only T23's atomic external-action completion may finish it. The
     * Participant likewise stays unresolved at this node, so the Execution can never complete over it.
     */
    private RuntimeNodeResult handoff(NodeContext ctx) {
        ActionHandoff handoff = ActionHandoff.of(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.node().logicalId(), ctx.node().kind(), ctx.node().payloadHash(), ctx.at());
        RuntimeTask ready = guard(ctx, RuntimeTask.due(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.node().logicalId(), RuntimeTaskKind.ACTION_READY, ctx.at()));
        return commit(ctx, startNodeExecution(ctx), ctx.participant(), ctx.claim().completed(ctx.at()),
                List.of(ready), List.of(handoff), RuntimeNodeDisposition.ACTION_READY,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_ACTION_READY)));
    }

    // ------------------------------------------------------------------ failure paths

    /**
     * Schedules the next bounded attempt of a technical failure, or dead-letters it once attempts are spent.
     *
     * <p>Backoff is a persisted fact, never a sleep: the task returns to {@code DUE} at the exact retry instant
     * the frozen policy names, and any worker may then claim it. When the policy's last attempt has been spent,
     * the work is dead-lettered and the Participant needs a review.
     */
    private RuntimeNodeResult retry(NodeContext ctx, String reasonCode) {
        int attemptNumber = ctx.claim().attemptNumber();
        if (retryPolicy.isLastAttempt(attemptNumber)) {
            JourneyParticipant review = ctx.participant().transitioned(
                    ParticipantStatus.NEEDS_REVIEW, ctx.participant().currentNodeLogicalId(), null, null,
                    ctx.participant().lastAcceptedActionInstant(), ctx.at());
            return commit(ctx, startNodeExecution(ctx).needsReview(ctx.at()), review,
                    ctx.claim().deadLettered(RuntimeErrorCodes.RUNTIME_ATTEMPTS_EXHAUSTED, ctx.at()),
                    List.of(), List.of(), RuntimeNodeDisposition.NEEDS_REVIEW,
                    List.of(audit(ctx, RuntimeAuditRecord.REASON_DEAD_LETTERED)));
        }
        Instant nextAvailableAt = ctx.at().plus(retryPolicy.delayBeforeAttempt(attemptNumber + 1));
        return commit(ctx, startNodeExecution(ctx).retryPending(ctx.at()), ctx.participant(),
                ctx.claim().retryDue(nextAvailableAt, reasonCode, ctx.at()), List.of(), List.of(),
                RuntimeNodeDisposition.RETRY_SCHEDULED,
                List.of(audit(ctx, RuntimeAuditRecord.REASON_RETRY_SCHEDULED)));
    }

    /**
     * Stops one non-retryable failure, leaving the work dead-lettered and the Participant needing a review.
     *
     * <p>A business or configuration failure is never retried: the same frozen input would fail identically, so
     * consuming the bounded attempts on it would only delay the human decision it needs.
     */
    private RuntimeNodeResult failClosed(NodeContext ctx, String reasonCode, String auditReasonCode) {
        JourneyParticipant review = ctx.participant().transitioned(
                ParticipantStatus.NEEDS_REVIEW, ctx.participant().currentNodeLogicalId(), null, null,
                ctx.participant().lastAcceptedActionInstant(), ctx.at());
        NodeExecution node = ctx.currentNode() == null
                ? startNodeExecution(ctx)
                : ctx.currentNode();
        return commit(ctx, node.needsReview(ctx.at()), review,
                ctx.claim().deadLettered(reasonCode, ctx.at()), List.of(), List.of(),
                RuntimeNodeDisposition.NEEDS_REVIEW, List.of(audit(ctx, auditReasonCode)));
    }

    // ------------------------------------------------------------------ commit

    private RuntimeNodeResult commit(
            NodeContext ctx,
            NodeExecution nodeExecution,
            JourneyParticipant participant,
            RuntimeTask task,
            List<RuntimeTask> nextTasks,
            List<ActionHandoff> handoffs,
            RuntimeNodeDisposition disposition,
            List<RuntimeAuditRecord> audits) {
        NodeAttempt attempt = attempt(ctx, task.reasonCode());
        List<RuntimeAuditRecord> allAudits = new ArrayList<>(audits);
        if (guarded(nextTasks)) {
            allAudits.add(audit(ctx, RuntimeAuditRecord.REASON_PAUSE_GUARDED));
        }
        CampaignExecution execution = ctx.execution();
        ExecutionCounters counters;
        if (disposition == RuntimeNodeDisposition.RETRY_SCHEDULED) {
            // Nothing about the Participant set moved, so the aggregate is restated exactly.
            counters = ctx.counters();
        } else {
            List<JourneyParticipant> after = after(ctx, participant);
            ExecutionStatus aggregate = RuntimeAggregation.status(after);
            if (aggregate != execution.status()) {
                execution = execution.transitioned(
                        aggregate, execution.candidateCount(), execution.enrolledCount(), null, null,
                        ctx.at());
                allAudits.add(audit(ctx, RuntimeAuditRecord.REASON_EXECUTION_AGGREGATED));
            }
            counters = RuntimeAggregation.counters(execution, after, ctx.counters().revision() + 1L);
        }
        NodeCompletionCommit commit = new NodeCompletionCommit(
                ctx.tenantId(), execution.executionKey(), execution.executionId(), ctx.execution().revision(),
                execution, ctx.participant().participantId(), ctx.participant().revision(),
                ctx.claim().nodeLogicalId(), participant, ctx.claim().taskId(), ctx.claim().revision(),
                ctx.claim().leaseToken(), task, nodeExecution, attempt, nextTasks, handoffs, counters,
                allAudits);
        NodeCompletionResult stored = store.commitNodeCompletion(commit);
        if (stored.duplicate()) {
            return new RuntimeNodeResult(
                    RuntimeNodeDisposition.DUPLICATE, stored.execution(), stored.participant(),
                    stored.nodeExecution(), stored.task(), stored.counters(), null, false, true);
        }
        ActionHandoff handoff = handoffs.isEmpty() ? null : handoffs.get(0);
        return new RuntimeNodeResult(
                disposition, stored.execution(), stored.participant(), stored.nodeExecution(), stored.task(),
                stored.counters(), handoff, guarded(nextTasks), false);
    }

    /** True when this completion created at least one next task guarded as {@code DEFERRED}. */
    private static boolean guarded(List<RuntimeTask> nextTasks) {
        for (RuntimeTask next : nextTasks) {
            if (next.status() == RuntimeTaskStatus.DEFERRED) {
                return true;
            }
        }
        return false;
    }

    private NodeAttempt attempt(NodeContext ctx, String outcomeCode) {
        return NodeAttempt.of(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.claim().taskId(), nodeExecutionId(ctx), ctx.claim().attemptNumber(), outcomeCode,
                ctx.claim().updatedAt(), ctx.at());
    }

    private static String nodeExecutionId(NodeContext ctx) {
        return NodeExecution.nodeExecutionId(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.claim().nodeLogicalId());
    }

    private static NodeExecution nodeExecution(NodeContext ctx) {
        return NodeExecution.pending(
                ctx.tenantId(), ctx.execution().executionId(), ctx.participant().participantId(),
                ctx.claim().nodeLogicalId(), ctx.node().kind(), ctx.node().payloadHash(), ctx.at());
    }

    private static NodeExecution startNodeExecution(NodeContext ctx) {
        return ctx.currentNode() == null
                ? nodeExecution(ctx).running(ctx.at())
                : ctx.currentNode().running(ctx.at());
    }

    private NodeExecution succeeded(NodeContext ctx) {
        return startNodeExecution(ctx).succeeded(ctx.at());
    }

    /** The Participant set this completion commits: the stored set with this one Participant replaced. */
    private List<JourneyParticipant> after(NodeContext ctx, JourneyParticipant participant) {
        List<JourneyParticipant> after = new ArrayList<>();
        for (JourneyParticipant stored : store.findParticipants(ctx.tenantId(), ctx.execution().executionId())) {
            after.add(stored.participantId().equals(participant.participantId()) ? participant : stored);
        }
        return after;
    }

    /** Guards one newly created next task as {@code DEFERRED} when no new work may start. */
    private RuntimeTask guard(NodeContext ctx, RuntimeTask task) {
        return ctx.mayStart() ? task : task.deferred(ctx.at());
    }

    // ------------------------------------------------------------------ frozen pins and edges

    /**
     * The one exact successor of one frozen node under one typed branch.
     *
     * <p>A missing successor and an ambiguous one both fail closed, so an edge is never selected by array
     * position and a Journey that declares two arms for one branch is refused rather than guessed at.
     */
    private static String successor(JourneyManifest journey, String fromNodeLogicalId, JourneyBranchKind branch) {
        String match = null;
        for (JourneyTopologyEdge edge : journey.edges()) {
            if (edge.fromNodeLogicalId().equals(fromNodeLogicalId) && edge.branch() == branch) {
                if (match != null) {
                    throw new RuntimeBoundaryException(
                            RuntimeErrorCodes.RUNTIME_EDGE_AMBIGUOUS, "edge",
                            "A frozen Journey branch must not carry two successors.");
                }
                match = edge.toNodeLogicalId();
            }
        }
        if (match == null || journey.node(match) == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_EDGE_MISSING, "edge",
                    "A frozen Journey node required an exact successor the topology does not carry.");
        }
        return match;
    }

    /**
     * The one frozen component matching a node's stable logical identity, with every pin re-proved.
     *
     * <p>It never selects by array position and never falls back to a similarly named component: the node's
     * capability code, contract version and payload hash must all witness the exact frozen component.
     */
    private static ResolvedComponent requireComponent(ResolvedCampaignManifest manifest, JourneyNode node) {
        ResolvedComponent component = manifest.component(node.logicalId());
        if (component == null
                || !component.capabilityCode().equals(node.capabilityCode())
                || !component.contractVersion().equals(node.contractVersion())
                || !component.value().contentHash().equals(node.payloadHash())
                || !node.kind().capabilityCode().equals(node.capabilityCode())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH, "node",
                    "A frozen Journey node does not witness the exact frozen component it names.");
        }
        return component;
    }

    private FrozenPins loadFrozenPins(CampaignExecution execution) {
        FullPublishedDefinition definition = definitions.findFullDefinition(execution.fullDefinitionId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_DEFINITION_NOT_FOUND, "fullDefinitionId",
                        "The frozen full definition this Execution names is not held by the host."));
        if (!definition.contentHash().equals(execution.fullDefinitionHash())
                || !definition.tenantId().equals(execution.tenantId())
                || !definition.campaignId().equals(execution.campaignId())
                || !definition.journeyHash().equals(execution.journeyHash())
                || !definition.scheduleHash().equals(execution.scheduleHash())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_DEFINITION_HASH_MISMATCH, "fullDefinitionBinding",
                    "The frozen full definition does not witness exactly this Execution's pins.");
        }
        PublishedCampaignDefinition base = definitions.findBaseDefinition(definition.basePublishedId())
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_BASE_DEFINITION_NOT_FOUND, "basePublishedId",
                        "The frozen base definition this full definition binds is not held by the host."));
        if (!base.contentChecksum().equals(definition.baseDefinitionChecksum())
                || !base.identity().equals(definition.baseIdentity())
                || !base.tenantId().equals(definition.tenantId())
                || !base.campaignId().equals(definition.campaignId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BASE_DEFINITION_HASH_MISMATCH, "baseDefinitionBinding",
                    "The frozen base definition does not witness exactly the checksum the full definition "
                            + "pinned.");
        }
        ResolvedCampaignManifest manifest = base.manifest();
        if (!manifest.manifestHash().equals(definition.manifestHash())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "manifestHash",
                    "The frozen base Manifest is not the Manifest the full definition froze.");
        }
        JourneyManifest journey = definition.journey();
        if (!journey.contentHash().equals(execution.journeyHash())
                || !RuntimeVersions.RUNTIME_SCHEMA_VERSION.equals(journey.runtimeSchemaVersion())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_DEFINITION_HASH_MISMATCH, "journeyBinding",
                    "The frozen Journey is not the exact version this Execution started with.");
        }
        return new FrozenPins(definition, manifest, journey);
    }

    /**
     * The exact frozen Wait duration of one Wait node.
     *
     * <p>A missing, zero, negative or non-ISO-8601 duration fails closed: a Wait is always a fact of the node's
     * own frozen configuration and never of a clock.
     */
    private static Duration waitDuration(ResolvedComponent component) {
        String text = text(component, FlowWaitPack.DURATION_FIELD);
        Duration duration;
        try {
            duration = text == null ? null : Duration.parse(text);
        } catch (DateTimeParseException rejected) {
            duration = null;
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_WAIT_CONFIGURATION_INVALID, "duration",
                    "A Wait node does not carry its exact frozen duration.");
        }
        return duration;
    }

    private static String waitAnchor(ResolvedComponent component) {
        String anchor = text(component, FlowWaitPack.ANCHOR_FIELD);
        if (anchor == null || !FlowWaitPack.ANCHORS.contains(anchor)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_WAIT_CONFIGURATION_INVALID, "anchor",
                    "A Wait node does not carry its exact frozen anchor.");
        }
        return anchor;
    }

    private static String text(ResolvedComponent component, String field) {
        if (component == null) {
            return null;
        }
        Object value = component.value().fields().get(field);
        return value instanceof String string ? string : null;
    }

    // ------------------------------------------------------------------ host state

    /**
     * The host-owned activation projection of one Execution's Campaign, or empty when it cannot be proved.
     *
     * <p>An absent projection, one owned by another tenant, program or Campaign and one whose host refused to
     * answer all yield empty: the caller fails closed identically in every case, so no resource's existence is
     * disclosed by the failure.
     */
    private Optional<RuntimeCampaignState> loadState(CampaignExecution execution) {
        Optional<RuntimeCampaignState> state = loadState(execution.tenantId(), execution.campaignId());
        if (state.isEmpty() || !state.get().programId().equals(execution.programId())) {
            return Optional.empty();
        }
        return state;
    }

    private Optional<RuntimeCampaignState> loadState(String tenantId, String campaignId) {
        RuntimeCampaignState state;
        try {
            state = campaignStates.findCampaignState(tenantId, campaignId).orElse(null);
        } catch (RuntimeException hostFailure) {
            return Optional.empty();
        }
        if (state == null || !state.tenantId().equals(tenantId) || !state.campaignId().equals(campaignId)) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    private JourneyParticipant requireParticipant(RuntimeTask claim, CampaignExecution execution) {
        for (JourneyParticipant participant
                : store.findParticipants(claim.tenantId(), execution.executionId())) {
            if (participant.participantId().equals(claim.participantId())) {
                return participant;
            }
        }
        throw new RuntimeBoundaryException(
                RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "participant",
                "No Participant is stored for this leased task.");
    }

    // ------------------------------------------------------------------ audit

    private static RuntimeAuditRecord audit(NodeContext ctx, String reasonCode) {
        return audit(
                ctx.tenantId(), reasonCode, SUBJECT_TASK, ctx.claim().taskId(), null,
                ctx.claim().attemptNumber(), ctx.at());
    }

    private static RuntimeAuditRecord audit(
            String tenantId,
            String reasonCode,
            String subjectKind,
            String subjectId,
            String executionKey,
            Integer attemptNumber,
            Instant at) {
        return RuntimeAuditRecord.of(
                RuntimeSupport.derivedId("audit", List.of(
                        reasonCode, tenantId, subjectId, executionKey == null ? "" : executionKey,
                        attemptNumber == null ? 0 : attemptNumber, at)),
                tenantId, reasonCode, subjectKind, subjectId, executionKey, null, null, at);
    }

    // ------------------------------------------------------------------ context

    /** Everything one node completion rests on, resolved before any commit begins. */
    private record NodeContext(
            RuntimeTask claim,
            CampaignExecution execution,
            JourneyParticipant participant,
            ExecutionCounters counters,
            FrozenPins pins,
            JourneyNode node,
            ResolvedComponent component,
            NodeExecution currentNode,
            boolean mayStart,
            Instant at
    ) {

        NodeContext withComponent(ResolvedComponent resolved) {
            return new NodeContext(claim, execution, participant, counters, pins, node, resolved, currentNode,
                    mayStart, at);
        }

        NodeContext withMayStart(boolean allowed) {
            return new NodeContext(claim, execution, participant, counters, pins, node, component, currentNode,
                    allowed, at);
        }

        String tenantId() {
            return claim.tenantId();
        }
    }

    /** The frozen pins one node completion rests on. */
    private record FrozenPins(
            FullPublishedDefinition definition,
            ResolvedCampaignManifest manifest,
            JourneyManifest journey) {
    }

    /** Redacted diagnostics: the worker holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeWorker[retryPolicy=" + retryPolicy + ']';
    }
}
