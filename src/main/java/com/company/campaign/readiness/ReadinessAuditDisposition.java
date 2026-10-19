package com.company.campaign.readiness;

/**
 * The closed disposition vocabulary of a readiness audit record.
 *
 * <p>Every value is an <em>append</em> outcome, because that is what an audit record states: what the store
 * actually did with the unit it was offered. The verdict of an assessment is carried separately, in the
 * record's bounded {@code statusTransition}, so a reader can tell "this was committed" apart from "this was
 * committed and the level was READY" without the two ever being conflated.
 */
public enum ReadinessAuditDisposition {

    /** The record, its audit row and the revision bump were committed as one unit. */
    RECORDED,

    /** The record was an exact replay of an already-committed identity; nothing was written. */
    DUPLICATE,

    /** The identity already existed with different material; nothing was written. */
    CONFLICT,

    /** The optimistic revision was stale; nothing was written. */
    STALE_REVISION,

    /** The record was refused before reaching the store; nothing was written. */
    REJECTED;

    /** True only for {@link #RECORDED}, the single outcome that changed committed state. */
    public boolean committed() {
        return this == RECORDED;
    }

    /** True for every outcome that wrote nothing. */
    public boolean wroteNothing() {
        return !committed();
    }
}