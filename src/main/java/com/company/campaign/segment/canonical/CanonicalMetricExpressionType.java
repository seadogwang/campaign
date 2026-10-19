package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.SemanticDataType;

/** Fully inferred result type and unit of a canonical Metric expression. */
public record CanonicalMetricExpressionType(
        SemanticDataType dataType,
        MetricExpressionUnit unit,
        CurrencySemantics currencySemantics
) {
    public CanonicalMetricExpressionType {
        CanonicalInvariants.requirePresent(dataType, "metricExpressionDataType");
        CanonicalInvariants.requirePresent(unit, "metricExpressionUnit");
        CanonicalInvariants.requirePresent(currencySemantics, "metricExpressionCurrencySemantics");
        if ((dataType == SemanticDataType.MONEY) != currencySemantics.isExplicitPolicy()) {
            throw CanonicalInvariants.failure(
                    CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID,
                    "metricExpressionCurrencySemantics",
                    "A Metric expression's monetary type and currency semantics must agree.");
        }
    }
}
