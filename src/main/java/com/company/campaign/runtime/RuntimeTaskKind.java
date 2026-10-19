package com.company.campaign.runtime;

/**
 * The closed kinds of RuntimeTask this build can create.
 *
 * <p>Phase A enqueued exactly one work item per newly enrolled Participant: the {@link #START} task that
 * requests local Journey advancement from the frozen Journey's one Start identity. Phase B adds the
 * {@link #NODE} task that requests advancement from one reached successor node, the {@link #WAIT_WAKE} task
 * that requests the single resume of a frozen Wait node at its own deadline, and the {@link #ACTION_READY}
 * handoff task that T23 consumes for a MESSAGE or REWARD node.
 *
 * <p>Only {@link #START}, {@link #NODE} and {@link #WAIT_WAKE} are locally claimable. An
 * {@link #ACTION_READY} task is deliberately <em>not</em>: it is the bounded handoff T23 owns, and a T22
 * worker must neither consume it nor claim success for it. Nothing here represents a Provider call, an
 * {@code ActionExecution} outcome, a budget reservation or a reconciliation.
 *
 * <p>A kind carries its own bounded reason code, so a persisted task states why it exists without a second
 * lookup and without echoing any member value.
 */
public enum RuntimeTaskKind {

    /** Advance one Participant from the frozen Journey's Start identity. */
    START("START", true),

    /** Advance one Participant from one reached successor node. */
    NODE("NODE", true),

    /** Resume the one Wait node a Participant is waiting at, at its original frozen deadline. */
    WAIT_WAKE("WAIT_WAKE", true),

    /** The bounded, hash-bound MESSAGE or REWARD handoff T23 consumes; never claimed in T22. */
    ACTION_READY("ACTION_READY", false);

    private final String reasonCode;
    private final boolean locallyClaimable;

    RuntimeTaskKind(String reasonCode, boolean locallyClaimable) {
        this.reasonCode = reasonCode;
        this.locallyClaimable = locallyClaimable;
    }

    /** The bounded machine reason code persisted with a task of this kind. */
    public String reasonCode() {
        return reasonCode;
    }

    /** True when a T22 local worker may claim and process a task of this kind. */
    public boolean locallyClaimable() {
        return locallyClaimable;
    }

    /**
     * The bounded machine reason code a completion records for this kind of work.
     *
     * <p>It is derived from the kind rather than accepted from a caller, so an audit record can never claim an
     * outcome the kind does not describe.
     */
    public String completionReasonCode() {
        return this == ACTION_READY ? "ACTION_HANDED_OFF" : reasonCode + "_COMPLETED";
    }
}