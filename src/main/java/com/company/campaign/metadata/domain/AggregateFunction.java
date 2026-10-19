package com.company.campaign.metadata.domain;

/**
 * Closed aggregate functions a metric may declare.
 *
 * <p>The function constrains the metric result type: SUM/AVG need a numeric input, COUNT always yields
 * INTEGER, and MIN/MAX are defined only for ordered or textual types.
 */
public enum AggregateFunction {

    SUM,
    AVG,
    MIN,
    MAX,
    COUNT,
    COUNT_DISTINCT;

    /** True when this function can produce the supplied result type. */
    public boolean supports(SemanticDataType resultType) {
        if (resultType == null) {
            return false;
        }
        return switch (this) {
            case SUM, AVG -> resultType.isNumeric();
            case MIN, MAX -> resultType.isOrdered() || resultType == SemanticDataType.STRING;
            case COUNT, COUNT_DISTINCT -> resultType == SemanticDataType.INTEGER;
        };
    }
}