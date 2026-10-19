package com.company.campaign.action;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed business state machine of one ActionExecution.
 *
 * <p>{@code RESERVED -> IN_FLIGHT -> SUCCEEDED | DEFINITELY_FAILED | UNKNOWN}, and
 * {@code UNKNOWN -> VERIFYING -> SUCCEEDED | DEFINITELY_FAILED | UNKNOWN | NEEDS_REVIEW}. A verified
 * {@code DEFINITELY_NOT_APPLIED} permits one bounded safe retry, which reuses the same Action, business
 * idempotency key, request hash and budget reservation and transitions back through the bounded
 * {@code RESERVED} queued state rather than through a second Action.
 *
 * <p>The three terminal states are immutable. {@code NEEDS_REVIEW} is terminal because a remote effect may
 * exist and this build cannot settle it: no automatic and no manual path may blindly submit it again.
 *
 * <p>This is deliberately <em>not</em> a queue state. {@link ActionTaskStatus} says what the durable queue
 * holds; this says what the Campaign's external action is doing.
 */
public enum ActionStatus {

    RESERVED(false),
    IN_FLIGHT(false),
    UNKNOWN(false),
    VERIFYING(false),
    SUCCEEDED(true),
    DEFINITELY_FAILED(true),
    NEEDS_REVIEW(true);

    private static final Set<ActionStatus> FROM_RESERVED = EnumSet.of(IN_FLIGHT);
    private static final Set<ActionStatus> FROM_IN_FLIGHT =
            EnumSet.of(SUCCEEDED, DEFINITELY_FAILED, UNKNOWN, VERIFYING, RESERVED);
    private static final Set<ActionStatus> FROM_UNKNOWN = EnumSet.of(VERIFYING);
    private static final Set<ActionStatus> FROM_VERIFYING =
            EnumSet.of(SUCCEEDED, DEFINITELY_FAILED, UNKNOWN, NEEDS_REVIEW, RESERVED);

    private final boolean terminal;

    ActionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when this action is still unresolved, so an Execution must never complete over it. */
    public boolean isUnresolved() {
        return !terminal;
    }

    /**
     * True when this state does not yet know whether a remote effect exists, so the reward budget must stay
     * held and a Provider must never be called again before reconciliation proves non-application.
     */
    public boolean holdsRewardBudget() {
        return this == RESERVED || this == IN_FLIGHT || this == UNKNOWN || this == VERIFYING;
    }

    /** True when one atomic commit may move an action from this state to {@code target}. */
    public boolean reaches(ActionStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case RESERVED -> FROM_RESERVED.contains(target);
            case IN_FLIGHT -> FROM_IN_FLIGHT.contains(target);
            case UNKNOWN -> FROM_UNKNOWN.contains(target);
            case VERIFYING -> FROM_VERIFYING.contains(target);
            case SUCCEEDED, DEFINITELY_FAILED, NEEDS_REVIEW -> false;
        };
    }
}
