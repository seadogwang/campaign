package com.company.campaign.runtime;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed Campaign Execution state machine.
 *
 * <p>{@code CREATED -> EVALUATING -> RUNNING -> COMPLETED|PARTIAL_FAILED|FAILED|NEEDS_REVIEW}. {@code FAILED}
 * is additionally reachable from a not-yet-running Execution, because a materialization can fail closed before
 * any Participant exists. Nothing is reachable from a terminal state.
 *
 * <p>The machine is closed in both directions: {@link #reaches(ExecutionStatus)} answers whether one atomic
 * commit may move an Execution from this state to another, so an illegal transition is refused rather than
 * silently applied. Phase A commits a materialization as one transaction and therefore uses the transitive
 * reachability, while still refusing every genuinely illegal move such as a terminal state becoming running
 * again.
 */
public enum ExecutionStatus {

    CREATED(false),
    EVALUATING(false),
    RUNNING(false),
    COMPLETED(true),
    PARTIAL_FAILED(true),
    FAILED(true),
    NEEDS_REVIEW(true);

    private static final Set<ExecutionStatus> FROM_CREATED =
            EnumSet.of(EVALUATING, RUNNING, COMPLETED, FAILED);
    private static final Set<ExecutionStatus> FROM_EVALUATING =
            EnumSet.of(RUNNING, FAILED);

    private final boolean terminal;

    ExecutionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when an atomic commit may move an Execution from this state to {@code target}. */
    public boolean reaches(ExecutionStatus target) {
        if (target == null) {
            return false;
        }
        if (target == this) {
            // Re-stating the same state is not an advancement; a commit must name a real transition.
            return false;
        }
        return switch (this) {
            case CREATED -> FROM_CREATED.contains(target);
            case EVALUATING -> FROM_EVALUATING.contains(target);
            case RUNNING -> target.isTerminal();
            case COMPLETED, PARTIAL_FAILED, FAILED, NEEDS_REVIEW -> false;
        };
    }

    /** True when this state is one a phase-A materialization may start from. */
    public boolean isMaterializable() {
        return this == CREATED;
    }
}
