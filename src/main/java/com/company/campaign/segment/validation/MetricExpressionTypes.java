package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.segment.canonical.CanonicalMetricExpressionType;
import com.company.campaign.segment.canonical.CanonicalMetricExpressionTypes;
import com.company.campaign.segment.canonical.MetricExpressionUnit;

/** Pure, business-name-independent type inference for Metric arithmetic. */
final class MetricExpressionTypes {

    static CanonicalMetricExpressionType reference(MetricDefinition metric) {
        MetricExpressionUnit unit;
        if (metric.aggregation() == AggregateFunction.COUNT
                || metric.aggregation() == AggregateFunction.COUNT_DISTINCT) {
            unit = MetricExpressionUnit.COUNT;
        } else if (metric.resultType() == SemanticDataType.MONEY) {
            unit = MetricExpressionUnit.MONEY;
        } else {
            unit = MetricExpressionUnit.NUMBER;
        }
        return new CanonicalMetricExpressionType(
                metric.resultType(), unit, metric.currencySemantics());
    }

    static CanonicalMetricExpressionType addOrSubtract(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        return CanonicalMetricExpressionTypes.addOrSubtract(left, right);
    }

    static CanonicalMetricExpressionType multiply(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        return CanonicalMetricExpressionTypes.multiply(left, right);
    }

    static CanonicalMetricExpressionType divide(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        return CanonicalMetricExpressionTypes.divide(left, right);
    }

    private MetricExpressionTypes() {
    }
}
