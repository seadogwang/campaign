package com.company.campaign.fullpublish;

/**
 * The closed operational status of one Campaign's active published schedule.
 *
 * <p>It is a projection fact, never a mutation of published bytes: pausing a schedule records that no new due
 * occurrence may be committed for it, and resuming it records that planning may proceed again. Neither ever
 * rewrites the frozen definition, the Journey, the Schedule or an already-committed occurrence.
 */
public enum ScheduleStatus {

    /** Planning may commit due occurrences for the active published version. */
    ACTIVE,

    /**
     * No new due occurrence may be committed for the active published version.
     *
     * <p>Resuming does not replay every missed date: the accepted most-recent-due {@code DAILY} catch-up
     * applies, so a paused Campaign resumes rather than flooding the schedule with a backlog.
     */
    PAUSED;

    /** True only for {@link #ACTIVE}, the single status that permits new due work. */
    public boolean planningAllowed() {
        return this == ACTIVE;
    }
}