package com.company.campaign.runtime;

import java.util.List;
import java.util.Objects;

/**
 * The deterministic Execution aggregate: one closed terminal status and one counters projection over the
 * whole Participant set.
 *
 * <p>It is a pure function of the persisted Participants and of the Execution's frozen candidate and enrolled
 * counts, so the same Participant set always yields byte-identical counters and the same status. Both the
 * worker that commits a transition and the store that re-proves the commit inside its transaction call exactly
 * this, which is what makes "the aggregate agrees with the Participants" a fact rather than an intention.
 *
 * <p>The rules are the accepted v1.3 ones and are applied in this order:
 *
 * <ul>
 *   <li>any Participant needing review makes the Execution {@code NEEDS_REVIEW};</li>
 *   <li>while any Participant is runnable, waiting or otherwise unresolved the Execution stays
 *       {@code RUNNING} — an Execution must never complete over unresolved work;</li>
 *   <li>over an all-terminal set, a mixture of successful or exited and failed Participants yields
 *       {@code PARTIAL_FAILED};</li>
 *   <li>an all-failed set yields {@code FAILED};</li>
 *   <li>everything else, including the valid zero-Participant case, yields {@code COMPLETED}.</li>
 * </ul>
 */
public final class RuntimeAggregation {

    private RuntimeAggregation() {
    }

    /**
     * The deterministic aggregate status of one Participant set.
     *
     * <p>{@link ExecutionStatus#RUNNING} means "no terminal transition": the aggregate is not yet terminal
     * because some Participant is unresolved.
     */
    public static ExecutionStatus status(List<JourneyParticipant> participants) {
        List<JourneyParticipant> bounded = Objects.requireNonNull(participants, "participants");
        boolean needsReview = false;
        boolean unresolved = false;
        boolean failed = false;
        boolean succeeded = false;
        for (JourneyParticipant participant : bounded) {
            if (participant == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "participants");
            }
            switch (participant.status()) {
                case NEEDS_REVIEW -> needsReview = true;
                case COMPLETED, EXITED -> succeeded = true;
                case FAILED -> failed = true;
                default -> unresolved = true;
            }
        }
        if (needsReview) {
            return ExecutionStatus.NEEDS_REVIEW;
        }
        if (unresolved) {
            return ExecutionStatus.RUNNING;
        }
        if (failed) {
            return succeeded ? ExecutionStatus.PARTIAL_FAILED : ExecutionStatus.FAILED;
        }
        return ExecutionStatus.COMPLETED;
    }

    /** True when at least one Participant is runnable, waiting or otherwise unresolved. */
    public static boolean hasUnresolved(List<JourneyParticipant> participants) {
        for (JourneyParticipant participant : Objects.requireNonNull(participants, "participants")) {
            if (participant.status().isUnresolved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The deterministic counters projection over one Participant set.
     *
     * <p>The candidate and enrolled counts are restated from the Execution's frozen evidence rather than
     * recounted, because the audience is a fixed fact while the Participant counts move with each transition.
     */
    public static ExecutionCounters counters(
            CampaignExecution execution, List<JourneyParticipant> participants, long revision) {
        Objects.requireNonNull(execution, "execution");
        List<JourneyParticipant> bounded = Objects.requireNonNull(participants, "participants");
        int completed = 0;
        int exited = 0;
        int failed = 0;
        int needsReview = 0;
        int waiting = 0;
        int running = 0;
        for (JourneyParticipant participant : bounded) {
            if (participant == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "participants");
            }
            switch (participant.status()) {
                case COMPLETED -> completed++;
                case EXITED -> exited++;
                case FAILED -> failed++;
                case NEEDS_REVIEW -> needsReview++;
                case WAITING -> waiting++;
                default -> running++;
            }
        }
        return ExecutionCounters.aggregated(
                execution.tenantId(), execution.executionId(), execution.candidateCount(),
                execution.enrolledCount(), completed, exited, failed, needsReview, waiting, running, revision);
    }
}