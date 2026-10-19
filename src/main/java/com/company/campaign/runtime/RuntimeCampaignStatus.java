package com.company.campaign.runtime;

/**
 * The closed host-observed activation state of one Campaign's runtime work.
 *
 * <p>It is a projection fact, never a mutation of anything: {@link #PAUSED} records that no new local node and
 * no new external-action handoff may start, and {@link #ACTIVE} records that guarded work may proceed. Neither
 * ever rewrites a frozen definition, a committed task, a Wait deadline or an already-persisted node result.
 */
public enum RuntimeCampaignStatus {

    /** New local nodes and external-action handoffs may start. */
    ACTIVE,

    /** No new local node and no new external-action handoff may start. */
    PAUSED;

    /** True only for {@link #ACTIVE}, the single status that permits new work to start. */
    public boolean mayStartNewWork() {
        return this == ACTIVE;
    }
}