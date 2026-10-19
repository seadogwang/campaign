package com.company.campaign.builder.campaignagent;

/**
 * The closed phase of one Campaign conversation session.
 *
 * <p>The phase is derived by the host from accepted turns and the T27 workspace alone. No phase names a
 * saved, applied, published or live outcome: {@link #PROPOSAL_READY} means one host-produced Proposal is
 * available for the existing explicit Plan action, and nothing else.
 */
public enum CampaignAgentPhase {

    /** No accepted requirement exists yet. */
    AWAITING_GOAL,

    /** A typed clarification is pending and no downstream Skill may run until it is answered. */
    AWAITING_CLARIFICATION,

    /** At least one Skill is still being assembled for the current goal. */
    BUILDING,

    /** The audience was produced and the user has not yet said whether the Campaign continues. */
    AUDIENCE_READY,

    /** Accepted components exist and the Campaign could be finalized. */
    READY_TO_FINALIZE,

    /** The T27 workspace emitted one Proposal; the existing Plan action is the only handoff. */
    PROPOSAL_READY,

    /** A bounded terminal (denied, unsupported or invalid) ended the conversation. */
    TERMINAL_INVALID;

    /** True only for the one phase a useful turn can no longer leave. */
    public boolean isTerminal() {
        return this == TERMINAL_INVALID;
    }
}