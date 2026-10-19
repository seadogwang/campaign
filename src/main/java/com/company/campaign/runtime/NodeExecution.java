package com.company.campaign.runtime;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound NodeExecution: the durable local state of one Participant at one frozen Journey
 * node.
 *
 * <p>It pins the node by its stable logical identifier and kind together with the exact frozen payload hash the
 * compiler produced, so an edge or a node can never be selected by array position or by re-reading a mutable
 * catalog. {@link #entryAt()} freezes the instant this node was first entered, which is what a frozen Wait
 * node's {@code ENTRY} anchor measures from: a reclaim or a restart re-reads that instant rather than reading a
 * clock, so one Wait deadline is computed exactly once.
 *
 * <p>Its {@link #revision()} is the compare-and-set generation a phase-B worker refuses stale work against.
 * Every transition re-proves the frozen {@link NodeExecutionStatus} machine, so an illegal move is refused
 * rather than silently applied. {@link #toString()} prints no member, tenant, Campaign or Execution identity.
 */
public record NodeExecution(
        String formatVersion,
        String nodeExecutionId,
        String tenantId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        JourneyNodeKind nodeKind,
        ContentHash nodePayloadHash,
        Instant entryAt,
        NodeExecutionStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        ContentHash nodeExecutionHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public NodeExecution {
        if (!RuntimeVersions.NODE_EXECUTION_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(nodeExecutionId, "nodeExecutionId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(nodeKind, "nodeKind");
        Objects.requireNonNull(nodePayloadHash, "nodePayloadHash");
        Objects.requireNonNull(entryAt, "entryAt");
        Objects.requireNonNull(status, "status");
        RuntimeSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(nodeExecutionHash, "nodeExecutionHash");
        if (!nodeExecutionHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, nodeExecutionId, tenantId, executionId, participantId, nodeLogicalId, nodeKind,
                nodePayloadHash, entryAt, status, revision, createdAt, updatedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "nodeExecutionHash");
        }
    }

    /**
     * Creates the pending NodeExecution of one Participant at one frozen node, entered at the given instant.
     *
     * <p>The node-entry instant is frozen here and never rewritten, so a retry, a reclaim or a restart always
     * measures a frozen Wait from the first entry into this node.
     */
    public static NodeExecution pending(
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind nodeKind,
            ContentHash nodePayloadHash,
            Instant at) {
        return build(nodeExecutionId(tenantId, executionId, participantId, nodeLogicalId), tenantId,
                executionId, participantId, nodeLogicalId, nodeKind, nodePayloadHash, at,
                NodeExecutionStatus.PENDING, 0L, at, at);
    }

    /** The one deterministic NodeExecution identity of a node inside a Participant. */
    public static String nodeExecutionId(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        return RuntimeSupport.derivedId("node", List.of(
                RuntimeVersions.NODE_EXECUTION_FORMAT_VERSION, tenantId, executionId, participantId,
                nodeLogicalId));
    }

    /** The same NodeExecution in {@code RUNNING}. */
    public NodeExecution running(Instant at) {
        return transitioned(NodeExecutionStatus.RUNNING, at);
    }

    /**
     * The same NodeExecution in {@code VERIFYING}: one external action is possibly applied remotely and must be
     * reconciled before this node may move again.
     */
    public NodeExecution verifying(Instant at) {
        return transitioned(NodeExecutionStatus.VERIFYING, at);
    }

    /** The same NodeExecution {@code SUCCEEDED}; the node is completed exactly once. */
    public NodeExecution succeeded(Instant at) {
        return transitioned(NodeExecutionStatus.SUCCEEDED, at);
    }

    /** The same NodeExecution {@code WAITING} at its frozen Wait deadline. */
    public NodeExecution waiting(Instant at) {
        return transitioned(NodeExecutionStatus.WAITING, at);
    }

    /** The same NodeExecution {@code RETRY_PENDING} after one bounded technical failure. */
    public NodeExecution retryPending(Instant at) {
        return transitioned(NodeExecutionStatus.RETRY_PENDING, at);
    }

    /** The same NodeExecution {@code FAILED}. */
    public NodeExecution failed(Instant at) {
        return transitioned(NodeExecutionStatus.FAILED, at);
    }

    /** The same NodeExecution {@code NEEDS_REVIEW}; the local machine cannot decide it. */
    public NodeExecution needsReview(Instant at) {
        return transitioned(NodeExecutionStatus.NEEDS_REVIEW, at);
    }

    /** The same NodeExecution under one explicit, already-legal target state. */
    public NodeExecution transitioned(NodeExecutionStatus target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!status.reaches(target)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "nodeExecutionStatus",
                    "A NodeExecution transition is not one the frozen state machine allows.");
        }
        return build(nodeExecutionId, tenantId, executionId, participantId, nodeLogicalId, nodeKind,
                nodePayloadHash, entryAt, target, revision + 1L, createdAt, at);
    }

    private static NodeExecution build(
            String nodeExecutionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind nodeKind,
            ContentHash nodePayloadHash,
            Instant entryAt,
            NodeExecutionStatus status,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new NodeExecution(
                RuntimeVersions.NODE_EXECUTION_FORMAT_VERSION,
                nodeExecutionId, tenantId, executionId, participantId, nodeLogicalId, nodeKind, nodePayloadHash,
                entryAt, status, revision, createdAt, updatedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.NODE_EXECUTION_FORMAT_VERSION, nodeExecutionId, tenantId, executionId,
                        participantId, nodeLogicalId, nodeKind, nodePayloadHash, entryAt, status, revision,
                        createdAt, updatedAt)));
    }

    private static Material material(
            String formatVersion,
            String nodeExecutionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind nodeKind,
            ContentHash nodePayloadHash,
            Instant entryAt,
            NodeExecutionStatus status,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new Material(formatVersion, nodeExecutionId, tenantId, executionId, participantId, nodeLogicalId,
                nodeKind, nodePayloadHash, entryAt, status, revision, createdAt, updatedAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "NodeExecution[nodeExecutionId=" + nodeExecutionId
                + ", nodeLogicalId=" + nodeLogicalId
                + ", nodeKind=" + nodeKind
                + ", status=" + status
                + ", revision=" + revision
                + ", entryAt=" + entryAt
                + ", nodeExecutionHash=" + nodeExecutionHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String nodeExecutionId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind nodeKind,
            ContentHash nodePayloadHash,
            Instant entryAt,
            NodeExecutionStatus status,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}