package com.company.campaign.action;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed queue state machine of one durable ActionTask, kept strictly separate from business action state.
 *
 * <p>{@code DUE -> LEASED -> COMPLETED | DUE | DEAD_LETTER}, with {@code DEFERRED} as the guarded state a
 * paused Campaign leaves work in. A terminal queue state is never rewritten and never reused: a dead-lettered
 * work item keeps its source identity so an operator can see exactly what was abandoned.
 */
public enum ActionTaskStatus {

    DUE(false),
    LEASED(false),
    DEFERRED(false),
    COMPLETED(true),
    DEAD_LETTER(true);

    private static final Set<ActionTaskStatus> FROM_DUE =
            EnumSet.of(LEASED, DEFERRED, COMPLETED, DEAD_LETTER);
    private static final Set<ActionTaskStatus> FROM_LEASED =
            EnumSet.of(DUE, COMPLETED, DEAD_LETTER);
    private static final Set<ActionTaskStatus> FROM_DEFERRED =
            EnumSet.of(DUE, LEASED, DEAD_LETTER);

    private final boolean terminal;

    ActionTaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when one atomic commit may move a task from this state to {@code target}. */
    public boolean reaches(ActionTaskStatus target) {
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