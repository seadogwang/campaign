package com.company.campaign.fullpublish;

/**
 * The closed set of decisions one plannable schedule occurrence can take.
 *
 * <p>A decision is total: a due occurrence is {@link #DUE} and an older missed {@code DAILY} date that a more
 * recent due date superseded is {@link #SKIPPED}. A future occurrence takes no decision at all — it is
 * simply absent from the plan — so {@code PENDING} is deliberately not a member: a planner that emitted it
 * would be claiming knowledge about work that does not exist yet.
 */
public enum OccurrenceDecision {

    /** The occurrence is due: durable work may be committed for its execution key. */
    DUE,

    /**
     * The occurrence's nominal instant passed while the schedule was not planned, and a more recent
     * {@code DAILY} date became the single due occurrence instead.
     *
     * <p>It is an explicit, frozen decision rather than a silent omission, so an operator can always tell a
     * deliberately skipped date from a date that never existed.
     */
    SKIPPED;

    /** True only for {@link #DUE}, the one decision that may produce committed work. */
    public boolean due() {
        return this == DUE;
    }
}