package com.company.campaign.evaluation.segment.contract;

/**
 * The terminal verdict of an evaluation run, as defined by Segment specification section 23.
 *
 * <p>{@link #INVALID} is not a soft failure. Infrastructure or fixture problems must never be reported as
 * {@link #FAILED} and must never be dropped: a run whose inputs, versions or fixtures were not sound has
 * no verdict at all, and only the latest approved {@link #PASSED} result can promote a rule-based segment
 * capability from {@code EXPERIMENTAL} to {@code ACTIVE}.
 *
 * <p>T03 defines the enum so report metadata can reserve the field; it does not run a Gate and produces no
 * verdict.
 */
public enum GateOutcome {

    /** Every required threshold was met by a sound run. */
    PASSED,

    /** The run was sound and at least one threshold was missed. */
    FAILED,

    /** The run itself was unsound, so no threshold could be judged. */
    INVALID
}