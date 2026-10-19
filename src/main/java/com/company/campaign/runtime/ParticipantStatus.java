package com.company.campaign.runtime;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed Journey Participant state machine.
 *
 * <p>{@code CREATED -> RUNNING <-> WAITING}, then {@code COMPLETED|EXITED|FAILED|NEEDS_REVIEW}. A terminal
 * Participant never moves again. Phase A creates Participants in {@link #CREATED}; every later transition is
 * phase B's local Journey progression, so this enum declares the whole accepted machine while phase A only
 * exercises the creation state.
 */
public enum ParticipantStatus {

    CREATED(false),
    RUNNING(false),
    WAITING(false),
    COMPLETED(true),
    EXITED(true),
    FAILED(true),
    NEEDS_REVIEW(true);

    private static final Set<ParticipantStatus> STARTABLE =
            EnumSet.of(RUNNING, FAILED, NEEDS_REVIEW);
    private static final Set<ParticipantStatus> FROM_RUNNING =
            EnumSet.of(WAITING, COMPLETED, EXITED, FAILED, NEEDS_REVIEW);
    private static final Set<ParticipantStatus> FROM_WAITING =
            EnumSet.of(RUNNING, EXITED, FAILED, NEEDS_REVIEW);

    private final boolean terminal;

    ParticipantStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when this state is still runnable, waiting or otherwise unresolved. */
    public boolean isUnresolved() {
        return !terminal;
    }

    /** True when one atomic commit may move a Participant from this state to {@code target}. */
    public boolean reaches(ParticipantStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case CREATED -> STARTABLE.contains(target);
            case RUNNING -> FROM_RUNNING.contains(target);
            case WAITING -> FROM_WAITING.contains(target);
            case COMPLETED, EXITED, FAILED, NEEDS_REVIEW -> false;
        };
    }
}