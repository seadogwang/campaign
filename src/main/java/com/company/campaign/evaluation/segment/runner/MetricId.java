package com.company.campaign.evaluation.segment.runner;

/**
 * The immutable identifier of each separately reported T13 metric.
 *
 * <p>The thirteen metrics below are never combined into a single score: each has its own identifier,
 * numerator, denominator, excluded and invalid counts, and its own exact decimal value. The identifier is the
 * stable machine name a T14 threshold rule keys on, so a metric can never be silently replaced by another.
 */
public enum MetricId {

    INTENT_SLOT_ACCURACY("intent-slot-accuracy"),
    CANONICAL_AST_EXACT_MATCH("canonical-ast-exact-match"),
    MEMBER_SET_EXACT_MATCH("member-set-exact-match"),
    CLARIFICATION_RECALL("clarification-recall"),
    CLARIFICATION_PRECISION("clarification-precision"),
    UNSUPPORTED_RECALL("unsupported-recall"),
    DENIED_RECALL("denied-recall"),
    SILENT_CRITICAL_ASSUMPTION_RATE("silent-critical-assumption-rate"),
    FIRST_PASS_SUCCESS("first-pass-success"),
    ONE_REPAIR_SUCCESS("one-repair-success"),
    REPEAT_CONSISTENCY("repeat-consistency"),
    TOOL_RESOLUTION_ERROR_RATE("tool-resolution-error-rate"),
    REFINEMENT_TARGET_ACCURACY("refinement-target-accuracy");

    private final String metricCode;

    MetricId(String metricCode) {
        this.metricCode = metricCode;
    }

    /** The stable machine code reported for this metric. */
    public String metricCode() {
        return metricCode;
    }

    /** Every metric identifier in the deterministic declaration order. */
    public static MetricId[] ordered() {
        return values();
    }
}