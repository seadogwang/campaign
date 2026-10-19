package com.company.campaign.segment.canonical;

import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;

import java.util.List;

/** Closed canonical arithmetic tree whose leaves pin complete Metric definitions. */
public sealed interface CanonicalMetricExpression permits
        CanonicalMetricExpression.MetricRef,
        CanonicalMetricExpression.Add,
        CanonicalMetricExpression.Subtract,
        CanonicalMetricExpression.Multiply,
        CanonicalMetricExpression.Divide {

    CanonicalMetricExpressionType resultType();

    record MetricRef(
            CanonicalMetricDefinition definition,
            CanonicalMetricExpressionType resultType) implements CanonicalMetricExpression {
        public MetricRef {
            CanonicalInvariants.requirePresent(definition, "metricExpressionDefinition");
            CanonicalInvariants.requirePresent(resultType, "metricExpressionResultType");
            requireExpectedType(resultType, CanonicalMetricExpressionTypes.reference(definition));
        }
    }

    record Add(
            List<CanonicalMetricExpression> operands,
            CanonicalMetricExpressionType resultType) implements CanonicalMetricExpression {
        public Add {
            operands = requireOperands(operands, "metricExpressionAddOperands");
            CanonicalInvariants.requirePresent(resultType, "metricExpressionResultType");
            CanonicalMetricExpressionType inferred = operands.getFirst().resultType();
            for (int index = 1; index < operands.size(); index++) {
                inferred = CanonicalMetricExpressionTypes.addOrSubtract(
                        inferred, operands.get(index).resultType());
            }
            requireExpectedType(resultType, inferred);
        }
    }

    record Subtract(
            CanonicalMetricExpression left,
            CanonicalMetricExpression right,
            CanonicalMetricExpressionType resultType) implements CanonicalMetricExpression {
        public Subtract {
            requireBinary(left, right, resultType, "metricExpressionSubtract");
            requireExpectedType(resultType, CanonicalMetricExpressionTypes.addOrSubtract(
                    left.resultType(), right.resultType()));
        }
    }

    record Multiply(
            CanonicalMetricExpression left,
            CanonicalMetricExpression right,
            CanonicalMetricExpressionType resultType) implements CanonicalMetricExpression {
        public Multiply {
            requireBinary(left, right, resultType, "metricExpressionMultiply");
            requireExpectedType(resultType, CanonicalMetricExpressionTypes.multiply(
                    left.resultType(), right.resultType()));
        }
    }

    record Divide(
            CanonicalMetricExpression left,
            CanonicalMetricExpression right,
            ZeroDenominatorPolicy zeroDenominator,
            CanonicalMetricExpressionType resultType) implements CanonicalMetricExpression {
        public Divide {
            requireBinary(left, right, resultType, "metricExpressionDivide");
            CanonicalInvariants.requirePresent(zeroDenominator, "metricExpressionZeroDenominator");
            requireExpectedType(resultType, CanonicalMetricExpressionTypes.divide(
                    left.resultType(), right.resultType()));
        }
    }

    private static List<CanonicalMetricExpression> requireOperands(
            List<CanonicalMetricExpression> operands, String invariant) {
        if (operands == null || operands.size() < 2) {
            throw CanonicalInvariants.failure(
                    CanonicalErrorCodes.CANONICAL_SCOPE_INVALID, invariant,
                    "A canonical arithmetic expression requires at least two operands.");
        }
        for (CanonicalMetricExpression operand : operands) {
            CanonicalInvariants.requirePresent(operand, invariant);
        }
        return List.copyOf(operands);
    }

    private static void requireBinary(
            CanonicalMetricExpression left,
            CanonicalMetricExpression right,
            CanonicalMetricExpressionType resultType,
            String invariant) {
        CanonicalInvariants.requirePresent(left, invariant);
        CanonicalInvariants.requirePresent(right, invariant);
        CanonicalInvariants.requirePresent(resultType, "metricExpressionResultType");
    }

    private static void requireExpectedType(
            CanonicalMetricExpressionType actual, CanonicalMetricExpressionType expected) {
        if (!actual.equals(expected)) {
            throw CanonicalInvariants.failure(
                    CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID,
                    "metricExpressionResultType",
                    "A canonical Metric expression must carry its exactly inferred result type.");
        }
    }
}
