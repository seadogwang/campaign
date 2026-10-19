package com.company.campaign.runtime;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, hash-bound {@code ACTION_READY} handoff: the whole of what T22 produces for a MESSAGE or
 * REWARD node.
 *
 * <p>It names the tenant, Execution, Participant and frozen node the action belongs to, the exact frozen node
 * kind, the exact frozen payload hash of that node and a bounded reason code. It deliberately carries no
 * Provider result, no {@code ActionExecution} outcome, no message body, no reward description or secret, no
 * budget reservation and no reconciliation state: those belong to T23, and representing any of them here would
 * claim a reliability this slice does not have.
 *
 * <p>Its identity is derived deterministically from the tenant, Execution, Participant and node, so one frozen
 * action node produces exactly one handoff however often it is replayed. {@link #toString()} prints no member,
 * tenant, Campaign or Execution identity.
 */
public record ActionHandoff(
        String formatVersion,
        String handoffId,
        String tenantId,
        String executionId,
        String participantId,
        String nodeLogicalId,
        JourneyNodeKind actionKind,
        ContentHash nodePayloadHash,
        String reasonCode,
        Instant readyAt,
        ContentHash handoffHash
) {

    /** The bounded reason code every handoff records. */
    public static final String REASON_ACTION_READY = "ACTION_READY";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionHandoff {
        if (!RuntimeVersions.HANDOFF_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(handoffId, "handoffId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId");
        if (actionKind != JourneyNodeKind.MESSAGE && actionKind != JourneyNodeKind.REWARD) {
            // Only the two external-action node kinds may be handed off; nothing else is T23's work.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "actionKind");
        }
        Objects.requireNonNull(nodePayloadHash, "nodePayloadHash");
        if (!REASON_ACTION_READY.equals(reasonCode)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "reasonCode");
        }
        Objects.requireNonNull(readyAt, "readyAt");
        Objects.requireNonNull(handoffHash, "handoffHash");
        if (!handoffHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, handoffId, tenantId, executionId, participantId, nodeLogicalId, actionKind,
                nodePayloadHash, reasonCode, readyAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "handoffHash");
        }
    }

    /** Builds the one bounded handoff of one frozen MESSAGE or REWARD node. */
    public static ActionHandoff of(
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind actionKind,
            ContentHash nodePayloadHash,
            Instant readyAt) {
        String handoffId = handoffId(tenantId, executionId, participantId, nodeLogicalId);
        return new ActionHandoff(
                RuntimeVersions.HANDOFF_FORMAT_VERSION,
                handoffId, tenantId, executionId, participantId, nodeLogicalId, actionKind, nodePayloadHash,
                REASON_ACTION_READY, readyAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.HANDOFF_FORMAT_VERSION, handoffId, tenantId, executionId,
                        participantId, nodeLogicalId, actionKind, nodePayloadHash, REASON_ACTION_READY,
                        readyAt)));
    }

    /** The one deterministic handoff identity of one frozen action node inside one Participant. */
    public static String handoffId(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        return RuntimeSupport.derivedId("handoff", List.of(
                RuntimeVersions.HANDOFF_ID_VERSION, tenantId, executionId, participantId, nodeLogicalId));
    }

    private static Material material(
            String formatVersion,
            String handoffId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind actionKind,
            ContentHash nodePayloadHash,
            String reasonCode,
            Instant readyAt) {
        return new Material(formatVersion, handoffId, tenantId, executionId, participantId, nodeLogicalId,
                actionKind, nodePayloadHash, reasonCode, readyAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionHandoff[handoffId=" + handoffId
                + ", actionKind=" + actionKind
                + ", reasonCode=" + reasonCode
                + ", readyAt=" + readyAt
                + ", handoffHash=" + handoffHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String handoffId,
            String tenantId,
            String executionId,
            String participantId,
            String nodeLogicalId,
            JourneyNodeKind actionKind,
            ContentHash nodePayloadHash,
            String reasonCode,
            Instant readyAt) {
    }
}