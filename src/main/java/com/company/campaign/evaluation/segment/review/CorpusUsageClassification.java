package com.company.campaign.evaluation.segment.review;

/**
 * What an exact loaded corpus may be used for, before any Gate exists.
 *
 * <p>The three verdicts are deliberately not a Gate result. A {@link #DEVELOPMENT_ONLY} corpus is
 * machine-complete but not human-approved, so later development code may use it while every report derived
 * from it stays diagnostic and every Segment-dependent capability stays {@code EXPERIMENTAL}. Only
 * {@link #GATE_ELIGIBLE} may feed an official Gate evaluation, and it exists only when candidate
 * completeness and full human review readiness both hold.
 */
public enum CorpusUsageClassification {

    /** Candidate validation failed: the corpus is structurally unusable and may not be used at all. */
    INVALID,

    /** Candidate validation passed but human review readiness did not: usable for development only. */
    DEVELOPMENT_ONLY,

    /** Candidate validation and full human review readiness passed: eligible for an official Gate run. */
    GATE_ELIGIBLE;

    /** True when later development code may consume the corpus, with development-only labelling. */
    public boolean isUsableForDevelopment() {
        return this != INVALID;
    }

    /** True only for the exact revision a Gate may evaluate. */
    public boolean isGateEligible() {
        return this == GATE_ELIGIBLE;
    }
}