package com.company.campaign.evaluation.segment.contract;

/**
 * The decision recorded for one owner role on one case.
 *
 * <p>The set is closed and deliberately small. {@link #PENDING} is the only status a system-generated
 * ledger may use: the automation that produces the candidate corpus records that review is <em>due</em>,
 * and only a human owner may move an entry to {@link #APPROVED} or {@link #REJECTED}. Confusing the two
 * is the failure mode this contract exists to prevent, so a pending entry is forbidden from carrying any
 * field that would make it look like a real decision.
 */
public enum ReviewStatus {

    /** Review is due. No human decision exists yet, so no decision fields may be present. */
    PENDING,

    /** A named reviewer approved this exact case and expected-result revision. */
    APPROVED,

    /** A named reviewer rejected this exact case and expected-result revision. */
    REJECTED;

    /** True when the status records a real human decision, which therefore requires decision fields. */
    public boolean isDecided() {
        return this != PENDING;
    }
}