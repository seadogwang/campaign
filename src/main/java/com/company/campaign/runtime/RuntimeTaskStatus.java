package com.company.campaign.runtime;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed RuntimeTask queue state machine, kept strictly separate from business Execution state.
 *
 * <p>{@code DUE -> LEASED -> COMPLETED|DUE|DEAD_LETTER}, with {@code DEFERRED} as the guarded state a paused
 * Campaign leaves work in. A terminal queue state is never rewritten and never reused: a dead-lettered work
 * item keeps its source identity so an operator can see exactly what was abandoned.
 *
 * <p>Phase A creates tasks in {@link #DUE}; the leased, deferred and dead-letter transitions belong to phase B
 * and are declared here because the record must be representable and the state must be closed from the start.
 */
public enum RuntimeTaskStatus {

    DUE(false),
    LEASED(false),
    DEFERRED(false),
    COMPLETED(true),
    DEAD_LETTER(true);

    private static final Set<RuntimeTaskStatus> FROM_DUE =
            EnumSet.of(LEASED, DEFERRED, COMPLETED, DEAD_LETTER);
    private static final Set<RuntimeTaskStatus> FROM_LEASED =
            EnumSet.of(DUE, COMPLETED, DEAD_LETTER);
    private static final Set<RuntimeTaskStatus> FROM_DEFERRED =
            EnumSet.of(DUE, LEASED, COMPLETED, DEAD_LETTER);

    private final boolean terminal;

    RuntimeTaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when this state is eligible to be claimed or executed by a worker. */
    public boolean isEligible() {
        return this == DUE;
    }

    /** True when one atomic commit may move a task from this state to {@code target}. */
    public boolean reaches(RuntimeTaskStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case DUE -> FROM_DUE.contains(target);
            case LEASED -> FROM_LEASED.contains(target);
            case DEFERRED -> FROM_DEFERRED.contains(target);
            case COMPLETED, DEAD_LETTER -> false;
        };
    }
}
