package com.company.campaign.evaluation.segment.gate;

/**
 * Which metric breakdown a threshold applies to.
 *
 * <p>The specification states two thresholds on the high-risk slice rather than on the whole run — the
 * high-risk member-set exact match and the high-risk repeat consistency — so the scope is an explicit part of
 * a threshold rule and of its evidence. A high-risk threshold can never be satisfied by the overall metric,
 * and vice versa.
 */
public enum ThresholdScope {

    /** The metric over every applicable attempt in the run. */
    OVERALL("overall"),

    /** The metric over the high-risk slice of the run. */
    HIGH_RISK("high-risk");

    private final String code;

    ThresholdScope(String code) {
        this.code = code;
    }

    /** The stable machine code of this scope. */
    public String code() {
        return code;
    }
}