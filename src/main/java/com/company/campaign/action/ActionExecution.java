package com.company.campaign.action;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound ActionExecution: exactly one external action's worth of durable business state.
 *
 * <p>Its identity is unique on {@code (tenantId, idempotencyKey)} and the key is the exact business key
 * {@code tenantId:participantId:nodeLogicalId:actionKind}, which deliberately excludes every retry and
 * verification attempt number. One frozen action node therefore produces exactly one ActionExecution however
 * often it is replayed, reclaimed or reconciled, and {@link #requestHash()} pins the exact frozen request that
 * action was reserved for.
 *
 * <p>{@link #submissionCount()} and {@link #verificationCount()} count <em>actual</em> Provider calls, never
 * scheduled ones, so the bounded policies are enforced against a persisted fact rather than an intention.
 * A terminal action is immutable; {@link #toString()} prints no member, tenant, Campaign, Execution or
 * Provider identity.
 */
public record ActionExecution(
        String formatVersion,
        String actionId,
        String tenantId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        ActionKind actionKind,
        String idempotencyKey,
        ContentHash requestHash,
        ActionStatus status,
        String reasonCode,
        int submissionCount,
        int verificationCount,
        Instant acceptedInstant,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        ContentHash actionHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionExecution {
        if (!ActionVersions.ACTION_EXECUTION_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        ActionSupport.requireIdentifier(participantId, "participantId");
        ActionSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(actionKind, "actionKind");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(status, "status");
        ActionSupport.requireMachineCode(reasonCode, "reasonCode");
        ActionSupport.requireCount(submissionCount, ActionVersions.MAX_POLICY_ATTEMPTS, "submissionCount");
        ActionSupport.requireCount(verificationCount, ActionVersions.MAX_POLICY_ATTEMPTS, "verificationCount");
        if (status == ActionStatus.SUCCEEDED) {
            if (acceptedInstant == null) {
                // A success is exactly the case that has an accepted instant, local or Provider-evidenced.
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "acceptedInstant");
            }
        } else if (acceptedInstant != null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "acceptedInstant");
        }
        if (status != ActionStatus.RESERVED && submissionCount < 1) {
            // Nothing leaves the reserved state without one actual submission attempt.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "submissionCount");
        }
        ActionSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(actionHash, "actionHash");
        if (!actionHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind,
                idempotencyKey, requestHash, status, reasonCode, submissionCount, verificationCount,
                acceptedInstant, revision, createdAt, updatedAt)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "actionHash");
        }
    }

    /**
     * The one business idempotency key of one frozen action node.
     *
     * <p>It is exactly {@code tenantId:participantId:nodeLogicalId:actionKind} and carries no attempt number,
     * so a retry, a reclaim, a restart or a reconciliation can never mint a second key for one effect.
     */
    public static String idempotencyKey(
            String tenantId, String participantId, String nodeLogicalId, ActionKind actionKind) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(participantId, "participantId");
        ActionSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(actionKind, "actionKind");
        return tenantId + ':' + participantId + ':' + nodeLogicalId + ':' + actionKind.code();
    }

    /** The one deterministic ActionExecution identity of one business key inside one tenant. */
    public static String actionId(String tenantId, String idempotencyKey) {
        return ActionSupport.derivedId("act", List.of(
                ActionVersions.ACTION_ID_VERSION, tenantId, idempotencyKey));
    }

    /** The one freshly reserved ActionExecution of one frozen handoff. */
    public static ActionExecution reserved(
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String idempotencyKey,
            ContentHash requestHash,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return build(
                actionId(tenantId, idempotencyKey), tenantId, executionId, participantId, nodeLogicalId,
                actionKind, idempotencyKey, requestHash, ActionStatus.RESERVED, "ACTION_RESERVED", 0, 0, null,
                0L, at, at);
    }

    /** True when this action may still move. */
    public boolean isUnresolved() {
        return status.isUnresolved();
    }

    /** True when this action's exact business key has a definitively applied remote effect. */
    public boolean isApplied() {
        return status == ActionStatus.SUCCEEDED;
    }

    /** The Journey node kind this action's frozen node carried. */
    public JourneyNodeKind journeyNodeKind() {
        return actionKind == ActionKind.MESSAGE ? JourneyNodeKind.MESSAGE : JourneyNodeKind.REWARD;
    }

    /** The same action submitted once more: {@code RESERVED} or a safe retry moving into {@code IN_FLIGHT}. */
    public ActionExecution submitted(Instant at) {
        return moved(ActionStatus.IN_FLIGHT, "ACTION_SUBMITTED", submissionCount + 1, verificationCount, null, at);
    }

    /**
     * The same already in-flight action starting one more actual submission after its previous lease expired.
     *
     * <p>A reclaim is a real state change — a new actual attempt — even though the closed business state stays
     * {@code IN_FLIGHT}, so the frozen state machine is not widened to represent it.
     */
    public ActionExecution submissionReclaimed(Instant at) {
        if (status != ActionStatus.IN_FLIGHT) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only an in-flight action may start another submission attempt.");
        }
        return build(actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, ActionStatus.IN_FLIGHT, "ACTION_SUBMITTED", submissionCount + 1, verificationCount,
                null, revision + 1L, createdAt, at);
    }

    /** The same action whose submission outcome could not be established. */
    public ActionExecution unknown(Instant at) {
        return moved(ActionStatus.UNKNOWN, "ACTION_OUTCOME_UNKNOWN", submissionCount, verificationCount, null, at);
    }

    /**
     * The same action entering {@code VERIFYING}: a possibly-applied remote effect must be reconciled before
     * anything else may move it.
     *
     * <p>It deliberately does not count a query: the count moves only when one is actually started, so a
     * scheduled-but-never-run reconciliation can never consume the bounded verification policy.
     */
    public ActionExecution verifying(Instant at) {
        return moved(ActionStatus.VERIFYING, "ACTION_VERIFYING", submissionCount, verificationCount, null, at);
    }

    /**
     * The same {@code VERIFYING} action starting one more actual reconciliation query.
     *
     * <p>It stays in the same business state while the bounded verification policy is consumed, which is
     * exactly why the queue state and the business state are separate.
     */
    public ActionExecution verificationStarted(String reasonCode, Instant at) {
        if (status != ActionStatus.VERIFYING) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only a verifying action may start a reconciliation query.");
        }
        return build(actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, ActionStatus.VERIFYING, ActionSupport.requireMachineCode(reasonCode, "reasonCode"),
                submissionCount, verificationCount + 1, null, revision + 1L, createdAt, at);
    }

    /** Starts reconciliation after an in-flight submission lease expired with no committed result. */
    public ActionExecution recoveryVerificationStarted(String reasonCode, Instant at) {
        if (status != ActionStatus.IN_FLIGHT) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only an in-flight action may recover by reconciliation.");
        }
        return build(actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, ActionStatus.VERIFYING, ActionSupport.requireMachineCode(reasonCode, "reasonCode"),
                submissionCount, verificationCount + 1, null, revision + 1L, createdAt, at);
    }

    /** The same verifying action after one query remained unresolved. */
    public ActionExecution verificationDeferred(String reasonCode, Instant at) {
        if (status != ActionStatus.VERIFYING) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_TRANSITION_INVALID, "actionStatus",
                    "Only a verifying action may persist another unresolved verification result.");
        }
        return build(actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, ActionStatus.VERIFYING, ActionSupport.requireMachineCode(reasonCode, "reasonCode"),
                submissionCount, verificationCount, null, revision + 1L, createdAt, at);
    }

    /** The same action whose remote effect is confirmed applied at the given accepted instant. */
    public ActionExecution succeeded(Instant acceptedInstant, Instant at) {
        return moved(ActionStatus.SUCCEEDED, "ACTION_SUCCEEDED", submissionCount, verificationCount,
                acceptedInstant, at);
    }

    /** The same action whose remote effect is definitively absent or definitively rejected. */
    public ActionExecution definitelyFailed(String reasonCode, Instant at) {
        return moved(ActionStatus.DEFINITELY_FAILED, reasonCode, submissionCount, verificationCount, null, at);
    }

    /** The same action returned to bounded queued work for one safe retry under the same key and reservation. */
    public ActionExecution safeRetry(Instant at) {
        return moved(ActionStatus.RESERVED, "ACTION_SAFE_RETRY", submissionCount, verificationCount, null, at);
    }

    /** The same action that this build cannot settle and a human must decide. */
    public ActionExecution needsReview(String reasonCode, Instant at) {
        return moved(ActionStatus.NEEDS_REVIEW, reasonCode, submissionCount, verificationCount, null, at);
    }

    /** The same action under one explicit, already-legal target state. */
    public ActionExecution moved(
            ActionStatus target,
            String newReasonCode,
            int newSubmissionCount,
            int newVerificationCount,
            Instant newAcceptedInstant,
            Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!status.reaches(target)) {
            throw new ActionBoundaryException(
                    status.isTerminal()
                            ? ActionErrorCodes.ACTION_TERMINAL_IMMUTABLE
                            : ActionErrorCodes.ACTION_TRANSITION_INVALID,
                    "actionStatus",
                    "An Action transition is not one the frozen state machine allows.");
        }
        return build(actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, target, newReasonCode, newSubmissionCount, newVerificationCount, newAcceptedInstant,
                revision + 1L, createdAt, at);
    }

    private static ActionExecution build(
            String actionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String idempotencyKey,
            ContentHash requestHash,
            ActionStatus status,
            String reasonCode,
            int submissionCount,
            int verificationCount,
            Instant acceptedInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new ActionExecution(
                ActionVersions.ACTION_EXECUTION_FORMAT_VERSION,
                actionId, tenantId, executionId, participantId, nodeLogicalId, actionKind, idempotencyKey,
                requestHash, status, reasonCode, submissionCount, verificationCount, acceptedInstant, revision,
                createdAt, updatedAt,
                CANONICAL_JSON.hash(material(
                        ActionVersions.ACTION_EXECUTION_FORMAT_VERSION, actionId, tenantId, executionId,
                        participantId, nodeLogicalId, actionKind, idempotencyKey, requestHash, status, reasonCode,
                        submissionCount, verificationCount, acceptedInstant, revision, createdAt, updatedAt)));
    }

    private static Material material(
            String formatVersion,
            String actionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String idempotencyKey,
            ContentHash requestHash,
            ActionStatus status,
            String reasonCode,
            int submissionCount,
            int verificationCount,
            Instant acceptedInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new Material(formatVersion, actionId, tenantId, executionId, participantId, nodeLogicalId,
                actionKind, idempotencyKey, requestHash, status, reasonCode, submissionCount, verificationCount,
                acceptedInstant, revision, createdAt, updatedAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign, Execution or Provider identity is printed. */
    @Override
    public String toString() {
        return "ActionExecution[actionId=" + actionId
                + ", actionKind=" + actionKind
                + ", status=" + status
                + ", reasonCode=" + reasonCode
                + ", submissionCount=" + submissionCount
                + ", verificationCount=" + verificationCount
                + ", revision=" + revision
                + ", actionHash=" + actionHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String actionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String idempotencyKey,
            ContentHash requestHash,
            ActionStatus status,
            String reasonCode,
            int submissionCount,
            int verificationCount,
            Instant acceptedInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}
