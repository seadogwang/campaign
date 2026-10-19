package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The immutable, revisioned aggregate counters projection of one Campaign Execution.
 *
 * <p>It is a projection over the Execution's own records, kept separately from them so a terminal Execution
 * stays byte-identical while its aggregate is still updated by later terminal transitions. Every count is a
 * bounded non-negative whole number, and the sum of the terminal Participant counts can never exceed the
 * enrolled count.
 *
 * <p>It reports no message acceptance and no reward success, because phase A has neither: there is no
 * MESSAGE or REWARD side effect and no Provider result to count. {@link #toString()} prints no tenant,
 * Campaign or Execution identity.
 */
public record ExecutionCounters(
        String formatVersion,
        String tenantId,
        String executionId,
        int candidateCount,
        int enrolledCount,
        int completedCount,
        int exitedCount,
        int failedCount,
        int needsReviewCount,
        int waitingCount,
        int runningCount,
        long revision,
        ContentHash countersHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ExecutionCounters {
        if (!RuntimeVersions.COUNTERS_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(executionId, "executionId");
        RuntimeSupport.requireCount(candidateCount, RuntimeVersions.MAX_AUDIENCE_MEMBERS, "candidateCount");
        RuntimeSupport.requireCount(enrolledCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS,
                "enrolledCount");
        RuntimeSupport.requireCount(completedCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS,
                "completedCount");
        RuntimeSupport.requireCount(exitedCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "exitedCount");
        RuntimeSupport.requireCount(failedCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "failedCount");
        RuntimeSupport.requireCount(needsReviewCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS,
                "needsReviewCount");
        RuntimeSupport.requireCount(waitingCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "waitingCount");
        RuntimeSupport.requireCount(runningCount, RuntimeVersions.MAX_MATERIALIZED_PARTICIPANTS, "runningCount");
        RuntimeSupport.requireNonNegative(revision, "revision");
        long terminal = (long) completedCount + exitedCount + failedCount + needsReviewCount;
        if (terminal > enrolledCount
                || (long) waitingCount + runningCount > enrolledCount) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "counters");
        }
        Objects.requireNonNull(countersHash, "countersHash");
        if (!countersHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, executionId, candidateCount, enrolledCount, completedCount,
                exitedCount, failedCount, needsReviewCount, waitingCount, runningCount, revision)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "countersHash");
        }
    }

    /**
     * The counters a materialization commits: every freshly created Participant is unresolved, so the enrolled
     * count and the running count rise together and no terminal count is claimed.
     */
    public static ExecutionCounters materialized(
            String tenantId,
            String executionId,
            int candidateCount,
            int enrolledCount,
            long revision) {
        return new ExecutionCounters(
                RuntimeVersions.COUNTERS_FORMAT_VERSION,
                tenantId, executionId, candidateCount, enrolledCount, 0, 0, 0, 0, 0, enrolledCount, revision,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.COUNTERS_FORMAT_VERSION, tenantId, executionId, candidateCount,
                        enrolledCount, 0, 0, 0, 0, 0, enrolledCount, revision)));
    }

    /** The counters a failed materialization commits: candidates are evidenced, everything else is zero. */
    public static ExecutionCounters failed(String tenantId, String executionId, int candidateCount) {
        return new ExecutionCounters(
                RuntimeVersions.COUNTERS_FORMAT_VERSION,
                tenantId, executionId, candidateCount, 0, 0, 0, 0, 0, 0, 0, 0L,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.COUNTERS_FORMAT_VERSION, tenantId, executionId, candidateCount, 0, 0, 0,
                        0, 0, 0, 0, 0L)));
    }

    /**
     * The counters one deterministic terminal aggregation commits over the whole Participant set.
     *
     * <p>It re-states {@code candidateCount} and {@code enrolledCount} from the Execution rather than recounting
     * them, because the audience is frozen evidence while the Participant counts move with each transition. The
     * record's own construction re-proves that no terminal or unresolved count exceeds the enrolled count, so an
     * aggregate can never claim more Participants than the Execution enrolled.
     */
    public static ExecutionCounters aggregated(
            String tenantId,
            String executionId,
            int candidateCount,
            int enrolledCount,
            int completedCount,
            int exitedCount,
            int failedCount,
            int needsReviewCount,
            int waitingCount,
            int runningCount,
            long revision) {
        return new ExecutionCounters(
                RuntimeVersions.COUNTERS_FORMAT_VERSION,
                tenantId, executionId, candidateCount, enrolledCount, completedCount, exitedCount, failedCount,
                needsReviewCount, waitingCount, runningCount, revision,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.COUNTERS_FORMAT_VERSION, tenantId, executionId, candidateCount,
                        enrolledCount, completedCount, exitedCount, failedCount, needsReviewCount, waitingCount,
                        runningCount, revision)));
    }

    /** The same counters at a new revision; used by a later terminal aggregation. */
    public ExecutionCounters atRevision(
            int newCompleted,
            int newExited,
            int newFailed,
            int newNeedsReview,
            int newWaiting,
            int newRunning,
            long newRevision) {
        return new ExecutionCounters(
                RuntimeVersions.COUNTERS_FORMAT_VERSION,
                tenantId, executionId, candidateCount, enrolledCount, newCompleted, newExited, newFailed,
                newNeedsReview, newWaiting, newRunning, newRevision,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.COUNTERS_FORMAT_VERSION, tenantId, executionId, candidateCount,
                        enrolledCount, newCompleted, newExited, newFailed, newNeedsReview, newWaiting,
                        newRunning, newRevision)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String executionId,
            int candidateCount,
            int enrolledCount,
            int completedCount,
            int exitedCount,
            int failedCount,
            int needsReviewCount,
            int waitingCount,
            int runningCount,
            long revision) {
        return new Material(formatVersion, tenantId, executionId, candidateCount, enrolledCount, completedCount,
                exitedCount, failedCount, needsReviewCount, waitingCount, runningCount, revision);
    }

    /** Redacted diagnostics: no tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "ExecutionCounters[candidates=" + candidateCount
                + ", enrolled=" + enrolledCount
                + ", completed=" + completedCount
                + ", exited=" + exitedCount
                + ", failed=" + failedCount
                + ", needsReview=" + needsReviewCount
                + ", waiting=" + waitingCount
                + ", running=" + runningCount
                + ", revision=" + revision
                + ", countersHash=" + countersHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String executionId,
            int candidateCount,
            int enrolledCount,
            int completedCount,
            int exitedCount,
            int failedCount,
            int needsReviewCount,
            int waitingCount,
            int runningCount,
            long revision) {
    }
}