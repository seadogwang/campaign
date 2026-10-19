package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.runner.EvaluationReport;

import java.math.BigDecimal;

/**
 * The bounded evidence of one threshold evaluation against one reported metric value.
 *
 * <p>It records everything the specification names for a threshold check: the metric identifier, the scope
 * and the required comparator and value, the observed numerator, denominator and invalid count, the exact
 * observed decimal when the metric is defined, and the result. It deliberately excludes member identifiers,
 * utterances, model output and Metadata labels.
 *
 * <p>An undefined, missing or inconsistent metric can never appear here as a pass: an undefined value is
 * recorded with no observed decimal and {@code passed=false}, and a declared decimal that disagrees with its
 * own numerator and denominator is rejected at construction.
 */
public record ThresholdCheck(
        String metricId,
        ThresholdScope scope,
        ThresholdComparator comparator,
        String requiredValue,
        int numerator,
        int denominator,
        int invalid,
        boolean defined,
        String observedValue,
        boolean passed
) {

    public ThresholdCheck {
        if (metricId == null || !ThresholdRule.METRIC_CODE.matcher(metricId).matches()) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, "A threshold check requires a bounded metric id");
        }
        if (scope == null || comparator == null) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                    "A threshold check requires its scope and comparator");
        }
        requiredValue = GateDecimals.canonical(requiredValue, "threshold required value")
                .stripTrailingZeros().toPlainString();
        if (numerator < 0 || denominator < 0 || invalid < 0) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, "A threshold count must not be negative");
        }
        if (numerator > denominator) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                    "A threshold numerator must not exceed its denominator");
        }
        if (defined != (denominator > 0)) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                    "A threshold check is defined exactly when its denominator is positive");
        }
        if (defined != (observedValue != null)) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                    "An undefined threshold check must not report a value, and a defined one must");
        }
        BigDecimal required = new BigDecimal(requiredValue);
        if (!defined) {
            if (passed) {
                throw GateException.of(
                        GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                        "An undefined threshold check can never pass: " + metricId);
            }
        } else {
            BigDecimal observed = GateDecimals.canonical(observedValue, "threshold observed value");
            if (!GateDecimals.ratio(numerator, denominator).equals(observedValue)) {
                throw GateException.of(
                        GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                        "A threshold observed value must agree with its own numerator and denominator: "
                                + metricId);
            }
            if (passed != comparator.satisfies(observed, required)) {
                throw GateException.of(
                        GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                        "A threshold result must agree with its own comparator: " + metricId);
            }
        }
    }

    /** Evaluates one rule against one reported metric value. */
    public static ThresholdCheck evaluate(ThresholdRule rule, EvaluationReport.MetricValue metric) {
        if (rule == null || metric == null) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_MISSING, "A threshold check requires its rule and metric");
        }
        return new ThresholdCheck(
                rule.metricId(), rule.scope(), rule.comparator(), rule.requiredValue(),
                metric.numerator(), metric.denominator(), metric.invalid(), metric.defined(), metric.value(),
                metric.defined()
                        && rule.comparator().satisfies(new BigDecimal(metric.value()), rule.required()));
    }

    /** True only when this check ran a defined metric and met its required comparator. */
    public boolean met() {
        return passed;
    }
}