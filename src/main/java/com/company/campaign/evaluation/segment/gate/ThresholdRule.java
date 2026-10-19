package com.company.campaign.evaluation.segment.gate;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * One required Segment specification threshold: the metric, the scope, the comparator and the exact value.
 *
 * <p>A rule is declared evidence, not configuration. It carries no observed value and no verdict, so it can
 * never be mistaken for a result, and the required value is a canonical exact decimal rather than a double.
 */
public record ThresholdRule(
        String metricId,
        ThresholdScope scope,
        ThresholdComparator comparator,
        String requiredValue
) {

    /** The bounded lowercase-hyphen metric-code grammar the T13 metric identifiers use. */
    static final Pattern METRIC_CODE = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public ThresholdRule {
        if (metricId == null || !METRIC_CODE.matcher(metricId).matches()) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, "A threshold rule requires a bounded metric id");
        }
        if (scope == null || comparator == null) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID,
                    "A threshold rule requires its scope and comparator");
        }
        requiredValue = GateDecimals.canonical(requiredValue, "threshold required value").stripTrailingZeros()
                .toPlainString();
    }

    /** The required value as an exact decimal. */
    public BigDecimal required() {
        return new BigDecimal(requiredValue);
    }
}