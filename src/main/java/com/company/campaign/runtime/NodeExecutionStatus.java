package com.company.campaign.runtime;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed NodeExecution state machine.
 *
 * <p>{@code PENDING -> RUNNING -> SUCCEEDED|WAITING|RETRY_PENDING|VERIFYING|FAILED|NEEDS_REVIEW}, with a
 * pending {@code RETRY_PENDING -> RUNNING} retry edge and a {@code VERIFYING -> RUNNING} safe-retry edge.
 * Nothing is reachable from a terminal state. Phase A defines the whole accepted machine because a
 * NodeExecution record must be representable, but phase A never progresses a node: it only creates
 * Participants and their first task.
 *
 * <p>{@link #VERIFYING} is the additive T23 state of one external-action node whose Provider outcome is not yet
 * settled: the request may or may not have applied remotely, so the node is neither successful nor failed and
 * must be reconciled before anything else may move it. It is reachable only from {@code RUNNING}, and a
 * MESSAGE or REWARD node is left {@code RUNNING} — never {@code SUCCEEDED} — by the T22 handoff, because a node
 * is not successful merely because a request was submitted.
 */
public enum NodeExecutionStatus {

    PENDING(false),
    RUNNING(false),
    SUCCEEDED(true),
    WAITING(false),
    RETRY_PENDING(false),
    VERIFYING(false),
    FAILED(true),
    NEEDS_REVIEW(true);

    private static final Set<NodeExecutionStatus> FROM_PENDING =
            EnumSet.of(RUNNING, FAILED, NEEDS_REVIEW);
    private static final Set<NodeExecutionStatus> FROM_RUNNING =
            EnumSet.of(SUCCEEDED, WAITING, RETRY_PENDING, VERIFYING, FAILED, NEEDS_REVIEW);
    private static final Set<NodeExecutionStatus> FROM_WAITING =
            EnumSet.of(RUNNING, SUCCEEDED, FAILED, NEEDS_REVIEW);
    private static final Set<NodeExecutionStatus> FROM_RETRY =
            EnumSet.of(RUNNING, FAILED, NEEDS_REVIEW);
    private static final Set<NodeExecutionStatus> FROM_VERIFYING =
            EnumSet.of(RUNNING, SUCCEEDED, FAILED, NEEDS_REVIEW);

    private final boolean terminal;

    NodeExecutionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when one atomic commit may move a NodeExecution from this state to {@code target}. */
    public boolean reaches(NodeExecutionStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(target);
            case RUNNING -> FROM_RUNNING.contains(target);
            case WAITING -> FROM_WAITING.contains(target);
            case RETRY_PENDING -> FROM_RETRY.contains(target);
            case VERIFYING -> FROM_VERIFYING.contains(target);
            case SUCCEEDED, FAILED, NEEDS_REVIEW -> false;
        };
    }
}