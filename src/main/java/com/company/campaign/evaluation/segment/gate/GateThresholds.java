package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.runner.MetricId;

import java.util.List;

/**
 * The exact Segment specification section 23.2 threshold set, and nothing more.
 *
 * <p>The rules are declared once, keyed on the stable T13 metric identifiers, so the Gate cannot drift from
 * the reported metrics or invent a bound the specification does not state. Every rule the specification
 * states is present; every metric the specification only requires to be reported — Intent slot accuracy,
 * First-pass success, One-repair success, tool resolution error rate and refinement target accuracy — is
 * deliberately absent from {@link #required()}.
 *
 * <p>The two high-risk rules are scoped to the high-risk breakdown because the specification states them on
 * the high-risk slice, not on the whole run.
 *
 * <p>The specification qualifies the two exact-match rates as "unambiguous". That qualification is already
 * carried by the T13 metric contract: the canonical-AST and member-set denominators include only the attempts
 * whose reviewed expectation requires an AST or a member set at all, so a clarification, unsupported, denied
 * or refinement attempt is excluded rather than counted as a wrong answer. The Gate therefore applies the
 * stated bound to the reported metric exactly, without inventing a second ambiguity flag or a per-Case filter
 * the report does not publish.
 */
public final class GateThresholds {

    private static final List<ThresholdRule> REQUIRED = List.of(
            new ThresholdRule(
                    MetricId.CANONICAL_AST_EXACT_MATCH.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.AT_LEAST, "0.95"),
            new ThresholdRule(
                    MetricId.MEMBER_SET_EXACT_MATCH.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.AT_LEAST, "0.95"),
            new ThresholdRule(
                    MetricId.MEMBER_SET_EXACT_MATCH.metricCode(), ThresholdScope.HIGH_RISK,
                    ThresholdComparator.EXACTLY, "1"),
            new ThresholdRule(
                    MetricId.CLARIFICATION_RECALL.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.AT_LEAST, "0.98"),
            new ThresholdRule(
                    MetricId.CLARIFICATION_PRECISION.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.AT_LEAST, "0.9"),
            new ThresholdRule(
                    MetricId.UNSUPPORTED_RECALL.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.EXACTLY, "1"),
            new ThresholdRule(
                    MetricId.DENIED_RECALL.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.EXACTLY, "1"),
            new ThresholdRule(
                    MetricId.SILENT_CRITICAL_ASSUMPTION_RATE.metricCode(), ThresholdScope.OVERALL,
                    ThresholdComparator.EXACTLY, "0"),
            new ThresholdRule(
                    MetricId.REPEAT_CONSISTENCY.metricCode(), ThresholdScope.HIGH_RISK,
                    ThresholdComparator.EXACTLY, "1"));

    /** The metrics the specification requires to be reported but states no threshold for. */
    private static final List<String> REPORTED_ONLY = List.of(
            MetricId.INTENT_SLOT_ACCURACY.metricCode(),
            MetricId.FIRST_PASS_SUCCESS.metricCode(),
            MetricId.ONE_REPAIR_SUCCESS.metricCode(),
            MetricId.TOOL_RESOLUTION_ERROR_RATE.metricCode(),
            MetricId.REFINEMENT_TARGET_ACCURACY.metricCode());

    private GateThresholds() {
    }

    /** Every required threshold rule, in deterministic evaluation order. */
    public static List<ThresholdRule> required() {
        return REQUIRED;
    }

    /** Every metric with no threshold, which must stay separately reported evidence. */
    public static List<String> reportedOnly() {
        return REPORTED_ONLY;
    }
}