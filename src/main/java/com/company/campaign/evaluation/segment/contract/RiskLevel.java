package com.company.campaign.evaluation.segment.contract;

/**
 * How much a corpus case can be trusted to discriminate a wrong implementation.
 *
 * <p>The level is an explicit case property rather than something inferred from the category, because the
 * Gate applies the repeat-consistency requirement to high-risk cases only and that decision must be
 * reviewable in the corpus itself.
 *
 * <p>High-risk cases cover negation, grouping, boundaries, windows, currency, EXISTS scope and
 * multi-aggregate semantics: the areas where a plausible-looking but semantically different Answer would
 * still produce a member set that looks reasonable. Only {@link #HIGH} carries the repeat requirement.
 */
public enum RiskLevel {

    /** Semantics where a wrong interpretation is easy to miss; requires repeat consistency at the Gate. */
    HIGH,

    /** Semantics that are discriminating but not in the high-risk set. */
    MEDIUM,

    /** Presentation, phrasing and recoverable-typo cases. */
    LOW;

    /** True when the Gate must verify that repeated runs agree on disposition and canonical AST. */
    public boolean requiresRepeatConsistency() {
        return this == HIGH;
    }
}