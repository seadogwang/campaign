package com.company.campaign.builder.campaignagent;

/**
 * The closed, stable set of missing-requirement and unsupported-state codes of a Campaign brief.
 *
 * <p>These are server-owned machine codes, never model copy: a client branches on the exact code, so a
 * clarification that asks for a field must carry exactly the code of that field. Completeness is a
 * deterministic function of a brief's accepted facts, and each missing fact maps to exactly one code here.
 *
 * <p>{@link #CAMPAIGN_TYPE_REQUIRED} is the same stable question code the top-level routing contract already
 * carries, so the type question has one identifier across both slices.
 */
public enum CampaignRequirementCode {

    /** The Campaign type has not been decided yet. */
    CAMPAIGN_TYPE_REQUIRED,

    /** The Campaign type is recognized but deliberately unsupported in this phase. */
    CAMPAIGN_TYPE_UNSUPPORTED,

    /** An event-triggered Campaign has no selected registered trigger event yet. */
    EVENT_REQUIRED,

    /** The audience requirement text has not been supplied yet. */
    AUDIENCE_REQUIRED,

    /** The Offer decision has not been supplied yet. */
    OFFER_DECISION_REQUIRED,

    /** An Offer is defined but its bounded description has not been supplied yet. */
    OFFER_DETAIL_REQUIRED,

    /** No delivery channel has been supplied yet. */
    CHANNEL_REQUIRED,

    /** The message content/brief has not been supplied yet. */
    MESSAGE_REQUIRED,

    /** The branch decision has not been supplied yet. */
    BRANCH_DECISION_REQUIRED,

    /** Branching is required but its bounded description has not been supplied yet. */
    BRANCH_DETAIL_REQUIRED,

    /** Branching is required but no complete evaluable condition-to-outcome rule was supplied. */
    BRANCH_RULE_REQUIRED,

    /** The execution cadence has not been supplied yet. */
    CADENCE_REQUIRED,

    /** The execution timezone has not been supplied yet. */
    EXECUTION_TIMEZONE_REQUIRED,

    /** A single execution's instant, or a repeated execution's local time, has not been supplied yet. */
    EXECUTION_TIME_REQUIRED,

    /** A repeated execution's inclusive start date has not been supplied yet. */
    SCHEDULE_START_DATE_REQUIRED,

    /** A repeated execution's inclusive end date has not been supplied yet. */
    SCHEDULE_END_DATE_REQUIRED;

    /** The exact stable machine code. */
    public String code() {
        return name();
    }
}
