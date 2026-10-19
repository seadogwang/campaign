package com.company.campaign.builder.campaignagent;

/**
 * The closed, host-derived status of one Campaign component TODO.
 *
 * <p>Every status is a deterministic function of the accepted Campaign brief and the genuine T27 workspace
 * state. No status is read from model prose: a component is {@link #COMPLETE} only when its own requirement
 * facts are accepted and every Skill it requires has produced an accepted READY artifact, so a model claim of
 * completion can never assert a state the workspace does not hold.
 */
public enum CampaignTodoStatus {

    /** No fact of this component has been supplied yet. */
    NOT_STARTED,

    /** Some facts of this component are accepted but the component is not complete. */
    IN_PROGRESS,

    /** The component still needs a user fact; it carries the outstanding requirement codes. */
    NEEDS_INPUT,

    /** Every requirement fact of this component is accepted and a required Skill validation is still pending. */
    VALIDATING,

    /** Every requirement fact and every required Skill validation of this component is accepted. */
    COMPLETE,

    /** A required Skill of this component is invalid, or the workspace reports a missing dependency. */
    BLOCKED;

    /** The exact stable machine code. */
    public String code() {
        return name();
    }
}