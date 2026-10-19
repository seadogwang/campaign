package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable NodeAttempt: the durable record of one actual try at one NodeExecution.
 *
 * <p>The attempt number counts actual tries, never scheduled ones, and the attempt identity is derived from the
 * NodeExecution and that number, so a retry can never silently overwrite the evidence of an earlier try.
 * {@link #outcomeCode()} is a bounded machine code, so a technical failure, a business failure and a
 * successful result stay distinguishable without parsing a message.
 *
 * <p>Phase A defines the record because the runtime foundation must be representable; phase B is what actually
 * claims a lease, runs a node and writes attempts. {@link #toString()} prints no member, tenant, Campaign or
 * Execution identity.
 */
public record NodeAttempt(
        String formatVersion,
        String attemptId,
        String tenantId,
        String executionId,
        String participantId,
        String taskId,
        String nodeExecutionId,
        int attemptNumber,
        String outcomeCode,
        Instant startedAt,
        Instant completedAt,
        ContentHash attemptHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public NodeAttempt {
        if (!RuntimeVersions.NODE_ATTEMPT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(attemptId, "attemptId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireIdentifier(participantId, "participantId");
        RuntimeSupport.requireIdentifier(taskId, "taskId");
        RuntimeSupport.requireIdentifier(nodeExecutionId, "nodeExecutionId");
        if (attemptNumber < 1) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "attemptNumber");
        }
        RuntimeSupport.requireMachineCode(outcomeCode, "outcomeCode");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(attemptHash, "attemptHash");
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "completedAt");
        }
        if (!attemptHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, attemptId, tenantId, executionId, participantId, taskId, nodeExecutionId,
                attemptNumber, outcomeCode, startedAt, completedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "attemptHash");
        }
    }

    /** Records one actual attempt of one NodeExecution. */
    public static NodeAttempt of(
            String tenantId,
            String executionId,
            String participantId,
            String taskId,
            String nodeExecutionId,
            int attemptNumber,
            String outcomeCode,
            Instant startedAt,
            Instant completedAt) {
        String attemptId = attemptId(taskId, nodeExecutionId, attemptNumber);
        return new NodeAttempt(
                RuntimeVersions.NODE_ATTEMPT_FORMAT_VERSION,
                attemptId, tenantId, executionId, participantId, taskId, nodeExecutionId, attemptNumber,
                outcomeCode, startedAt, completedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.NODE_ATTEMPT_FORMAT_VERSION, attemptId, tenantId, executionId,
                        participantId, taskId, nodeExecutionId, attemptNumber, outcomeCode, startedAt,
                        completedAt)));
    }

    /** The one deterministic attempt identity of one actual try. */
    public static String attemptId(String taskId, String nodeExecutionId, int attemptNumber) {
        return RuntimeSupport.derivedId("att", List.of(
                RuntimeVersions.NODE_ATTEMPT_FORMAT_VERSION, taskId, nodeExecutionId, attemptNumber));
    }

    private static Material material(
            String formatVersion,
            String attemptId,
            String tenantId,
            String executionId,
            String participantId,
            String taskId,
            String nodeExecutionId,
            int attemptNumber,
            String outcomeCode,
            Instant startedAt,
            Instant completedAt) {
        return new Material(formatVersion, attemptId, tenantId, executionId, participantId, taskId,
                nodeExecutionId, attemptNumber, outcomeCode, startedAt, completedAt);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "NodeAttempt[attemptId=" + attemptId
                + ", attemptNumber=" + attemptNumber
                + ", outcomeCode=" + outcomeCode
                + ", completedAt=" + completedAt
                + ", attemptHash=" + attemptHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String attemptId,
            String tenantId,
            String executionId,
            String participantId,
            String taskId,
            String nodeExecutionId,
            int attemptNumber,
            String outcomeCode,
            Instant startedAt,
            Instant completedAt) {
    }
}
