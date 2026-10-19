package com.company.campaign.action;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed lifecycle of one reward BudgetReservation, bound to one ActionExecution.
 *
 * <p>{@code HELD -> FINALIZED | RELEASED}. A reservation is held for {@code RESERVED}, {@code IN_FLIGHT},
 * {@code UNKNOWN}, {@code VERIFYING} and every retryable safe retry; it is finalized exactly once on
 * {@code SUCCEEDED} and released exactly once only after definitive non-application or definitive failure. It
 * stays held for {@code NEEDS_REVIEW}, because a remote effect may exist and releasing would let the same
 * exposure be reserved twice.
 */
public enum BudgetReservationState {

    HELD(false),
    FINALIZED(true),
    RELEASED(true);

    private static final Set<BudgetReservationState> FROM_HELD =
            EnumSet.of(FINALIZED, RELEASED);

    private final boolean terminal;

    BudgetReservationState(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is representable from this state. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when this reservation still counts against the frozen reward budget. */
    public boolean isHeld() {
        return this == HELD;
    }

    /** True when one atomic commit may move a reservation from this state to {@code target}. */
    public boolean reaches(BudgetReservationState target) {
        if (target == null || target == this) {
            return false;
        }
        return this == HELD && FROM_HELD.contains(target);
    }
}
