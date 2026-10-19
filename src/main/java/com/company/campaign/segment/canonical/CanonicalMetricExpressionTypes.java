package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.SemanticDataType;

/** Canonical, business-name-independent type inference for Metric arithmetic. */
public final class CanonicalMetricExpressionTypes {

    public static CanonicalMetricExpressionType reference(CanonicalMetricDefinition metric) {
        MetricExpressionUnit unit;
        if (metric.aggregation() == AggregateFunction.COUNT
                || metric.aggregation() == AggregateFunction.COUNT_DISTINCT) {
            unit = MetricExpressionUnit.COUNT;
        } else if (metric.resultType() == SemanticDataType.MONEY) {
            unit = MetricExpressionUnit.MONEY;
        } else {
            unit = MetricExpressionUnit.NUMBER;
        }
        return new CanonicalMetricExpressionType(metric.resultType(), unit, metric.currencySemantics());
    }

    public static CanonicalMetricExpressionType addOrSubtract(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        requireSameUnitAndCurrency(left, right);
        SemanticDataType result = numericWiden(left.dataType(), right.dataType());
        return new CanonicalMetricExpressionType(result, left.unit(), left.currencySemantics());
    }

    public static CanonicalMetricExpressionType multiply(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        if (left.unit() == MetricExpressionUnit.RATIO) {
            return right;
        }
        if (right.unit() == MetricExpressionUnit.RATIO) {
            return left;
        }
        if (left.unit() == MetricExpressionUnit.NUMBER && right.unit() == MetricExpressionUnit.NUMBER) {
            return new CanonicalMetricExpressionType(
                    SemanticDataType.DECIMAL,
                    MetricExpressionUnit.NUMBER,
                    CurrencySemantics.NOT_APPLICABLE);
        }
        throw incompatible();
    }

    public static CanonicalMetricExpressionType divide(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        if (left.unit() == right.unit()) {
            if (left.unit() == MetricExpressionUnit.MONEY
                    && (left.currencySemantics() != right.currencySemantics()
                    || left.currencySemantics() != CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY)) {
                throw incompatible();
            }
            return new CanonicalMetricExpressionType(
                    SemanticDataType.DECIMAL,
                    MetricExpressionUnit.RATIO,
                    CurrencySemantics.NOT_APPLICABLE);
        }
        if (left.unit() == MetricExpressionUnit.MONEY && right.unit() == MetricExpressionUnit.COUNT) {
            return new CanonicalMetricExpressionType(
                    SemanticDataType.MONEY,
                    MetricExpressionUnit.MONEY_PER_COUNT,
                    left.currencySemantics());
        }
        if (left.unit() == MetricExpressionUnit.NUMBER && right.unit() == MetricExpressionUnit.COUNT) {
            return new CanonicalMetricExpressionType(
                    SemanticDataType.DECIMAL,
                    MetricExpressionUnit.NUMBER_PER_COUNT,
                    CurrencySemantics.NOT_APPLICABLE);
        }
        throw incompatible();
    }

    private static void requireSameUnitAndCurrency(
            CanonicalMetricExpressionType left, CanonicalMetricExpressionType right) {
        if (left.unit() != right.unit() || left.currencySemantics() != right.currencySemantics()) {
            throw incompatible();
        }
    }

    private static SemanticDataType numericWiden(SemanticDataType left, SemanticDataType right) {
        if (!left.isNumeric() || !right.isNumeric()) {
            throw incompatible();
        }
        if (left == SemanticDataType.MONEY || right == SemanticDataType.MONEY) {
            if (left != SemanticDataType.MONEY || right != SemanticDataType.MONEY) {
                throw incompatible();
            }
            return SemanticDataType.MONEY;
        }
        return left == SemanticDataType.DECIMAL || right == SemanticDataType.DECIMAL
                ? SemanticDataType.DECIMAL
                : SemanticDataType.INTEGER;
    }

    private static IllegalArgumentException incompatible() {
        return new IllegalArgumentException("Metric expression types are incompatible");
    }

    private CanonicalMetricExpressionTypes() {
    }
}
