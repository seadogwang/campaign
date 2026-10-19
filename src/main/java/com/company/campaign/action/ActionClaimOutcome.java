package com.company.campaign.action;

/** The closed outcomes of one external-action claim scan. */
public enum ActionClaimOutcome {

    /** One due task was leased and, for a submission, its action moved to {@code IN_FLIGHT}. */
    CLAIMED,

    /** No due external-action work exists for this tenant. */
    NONE_DUE,

    /** Every candidate belongs to a paused Campaign, so no Provider call may start. */
    PAUSED,

    /** A candidate's Execution or Campaign projection could not be re-proved, so nothing was claimed. */
    UNVERIFIED,

    /** Every candidate was taken by another worker first. */
    CONTENDED
}