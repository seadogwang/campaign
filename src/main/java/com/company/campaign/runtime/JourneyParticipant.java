package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound Journey Participant: one member's position inside one Execution's frozen Journey.
 *
 * <p>It pins the exact Execution, frozen full definition and Journey content hash it belongs to, so a newer
 * publication never moves an existing Participant: an Execution continues the exact version it started with.
 * It carries the member identity, the current node's stable logical identifier — never an array position — the
 * closed status, the bounded waiting reason and deadline when it is waiting, and the last accepted action
 * instant when one exists.
 *
 * <p>{@link #revision()} is the compare-and-set generation phase B uses to refuse stale work; its
 * {@link #participantHash()} covers the whole record including that revision. {@link #toString()} prints no
 * member, tenant, Campaign or Execution identity.
 */
public record JourneyParticipant(
        String formatVersion,
        String participantId,
        String tenantId,
        String executionId,
        String campaignId,
        String fullDefinitionId,
        ContentHash fullDefinitionHash,
        ContentHash journeyHash,
        String memberId,
        String currentNodeLogicalId,
        ParticipantStatus status,
        String waitingReason,
        Instant waitingDeadline,
        Instant lastAcceptedActionInstant,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        ContentHash participantHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public JourneyParticipant {
        if (!RuntimeVersions.PARTICIPANT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(journeyHash, "journeyHash");
        RuntimeSupport.requireIdentifier(memberId, "memberId");
        RuntimeSupport.requireIdentifier(currentNodeLogicalId, "currentNodeLogicalId");
        Objects.requireNonNull(status, "status");
        String boundedReason = RuntimeSupport.requireOptionalMachineCode(waitingReason, "waitingReason");
        if ((boundedReason == null) != (waitingDeadline == null)) {
            // A wait is a reason and a deadline together, or neither: a half-stated wait is not representable.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "waitingDeadline");
        }
        RuntimeSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(participantHash, "participantHash");
        if (!participantHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, participantId, tenantId, executionId, campaignId, fullDefinitionId,
                fullDefinitionHash, journeyHash, memberId, currentNodeLogicalId, status, boundedReason,
                waitingDeadline, lastAcceptedActionInstant, revision, createdAt, updatedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "participantHash");
        }
    }

    /**
     * Creates the Participant one newly enrolled member starts as: revision zero, {@code CREATED}, positioned at
     * the frozen Journey's one Start identity, not waiting and with no accepted action yet.
     */
    public static JourneyParticipant created(
            String tenantId,
            String executionId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash journeyHash,
            String memberId,
            String startNodeLogicalId,
            Instant at) {
        String participantId = participantId(tenantId, executionId, memberId);
        return build(participantId, tenantId, executionId, campaignId, fullDefinitionId, fullDefinitionHash,
                journeyHash, memberId, startNodeLogicalId, ParticipantStatus.CREATED, null, null, null, 0L, at,
                at);
    }

    /** The one deterministic Participant identity of a member inside an Execution. */
    public static String participantId(String tenantId, String executionId, String memberId) {
        return RuntimeSupport.derivedId("ptp", List.of(
                RuntimeVersions.PARTICIPANT_ID_VERSION, tenantId, executionId, memberId));
    }

    /** The same Participant at a new revisioned state, preserving every frozen pin. */
    public JourneyParticipant transitioned(
            ParticipantStatus target,
            String currentNode,
            String newWaitingReason,
            Instant newWaitingDeadline,
            Instant newLastAcceptedActionInstant,
            Instant at) {
        Objects.requireNonNull(target, "target");
        if (!status.reaches(target)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "participantStatus",
                    "A Participant transition is not one the frozen state machine allows.");
        }
        return build(participantId, tenantId, executionId, campaignId, fullDefinitionId, fullDefinitionHash,
                journeyHash, memberId, currentNode, target, newWaitingReason, newWaitingDeadline,
                newLastAcceptedActionInstant, revision + 1L, createdAt, at);
    }

    /**
     * The same Participant moved on to one exact successor node of its frozen Journey, still unresolved.
     *
     * <p>Local Journey advancement changes the position without changing that the Participant is running, so it
     * cannot be expressed as a status transition: {@link ParticipantStatus#RUNNING} does not reach itself. It is
     * therefore its own bounded move, and it is legal only from {@code CREATED} or {@code RUNNING} — never from a
     * waiting or terminal state, which is exactly what stops a duplicate wake or a stale reclaim from moving a
     * Participant twice.
     */
    public JourneyParticipant advanced(
            String nextNodeLogicalId, Instant newLastAcceptedActionInstant, Instant at) {
        RuntimeSupport.requireIdentifier(nextNodeLogicalId, "currentNodeLogicalId");
        Objects.requireNonNull(at, "at");
        if (status != ParticipantStatus.CREATED && status != ParticipantStatus.RUNNING) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "participantStatus",
                    "This Participant is not in a state that may advance to a successor node.");
        }
        if (currentNodeLogicalId.equals(nextNodeLogicalId)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "currentNodeLogicalId",
                    "A Participant advancement must name a successor node rather than the node it is on.");
        }
        return build(participantId, tenantId, executionId, campaignId, fullDefinitionId, fullDefinitionHash,
                journeyHash, memberId, nextNodeLogicalId, ParticipantStatus.RUNNING, null, null,
                newLastAcceptedActionInstant, revision + 1L, createdAt, at);
    }

    private static JourneyParticipant build(
            String participantId,
            String tenantId,
            String executionId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash journeyHash,
            String memberId,
            String currentNodeLogicalId,
            ParticipantStatus status,
            String waitingReason,
            Instant waitingDeadline,
            Instant lastAcceptedActionInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new JourneyParticipant(
                RuntimeVersions.PARTICIPANT_FORMAT_VERSION,
                participantId, tenantId, executionId, campaignId, fullDefinitionId, fullDefinitionHash,
                journeyHash, memberId, currentNodeLogicalId, status, waitingReason, waitingDeadline,
                lastAcceptedActionInstant, revision, createdAt, updatedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.PARTICIPANT_FORMAT_VERSION, participantId, tenantId, executionId,
                        campaignId, fullDefinitionId, fullDefinitionHash, journeyHash, memberId,
                        currentNodeLogicalId, status, waitingReason, waitingDeadline, lastAcceptedActionInstant,
                        revision, createdAt, updatedAt)));
    }

    private static Material material(
            String formatVersion,
            String participantId,
            String tenantId,
            String executionId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash journeyHash,
            String memberId,
            String currentNodeLogicalId,
            ParticipantStatus status,
            String waitingReason,
            Instant waitingDeadline,
            Instant lastAcceptedActionInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new Material(formatVersion, participantId, tenantId, executionId, campaignId, fullDefinitionId,
                fullDefinitionHash, journeyHash, memberId, currentNodeLogicalId, status, waitingReason,
                waitingDeadline, lastAcceptedActionInstant, revision, createdAt, updatedAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "JourneyParticipant[participantId=" + participantId
                + ", status=" + status
                + ", revision=" + revision
                + ", currentNodeLogicalId=" + currentNodeLogicalId
                + ", waitingReason=" + waitingReason
                + ", participantHash=" + participantHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String participantId,
            String tenantId,
            String executionId,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash journeyHash,
            String memberId,
            String currentNodeLogicalId,
            ParticipantStatus status,
            String waitingReason,
            Instant waitingDeadline,
            Instant lastAcceptedActionInstant,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}