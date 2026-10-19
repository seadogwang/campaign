package com.company.campaign.segment.dsl.metric;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;

import java.util.List;

/** Closed arithmetic expression over Metadata Metric references. */
public sealed interface MetricExpression permits
        MetricExpression.MetricRef,
        MetricExpression.Add,
        MetricExpression.Subtract,
        MetricExpression.Multiply,
        MetricExpression.Divide {

    enum Kind {
        METRIC_REF,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE;

        public static Kind fromWire(String value) {
            for (Kind kind : values()) {
                if (kind.name().equals(value)) {
                    return kind;
                }
            }
            throw new SegmentDslException(
                    SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR,
                    "Unknown metric-expression kind");
        }
    }

    Kind kind();

    record MetricRef(String metricId) implements MetricExpression {
        public MetricRef {
            metricId = PredicateNodeIds.requireReferenceId(metricId, "metricId");
        }

        @Override
        public Kind kind() {
            return Kind.METRIC_REF;
        }
    }

    record Add(List<MetricExpression> operands) implements MetricExpression {
        public Add {
            operands = requireOperands(operands, "ADD");
        }

        @Override
        public Kind kind() {
            return Kind.ADD;
        }
    }

    record Subtract(MetricExpression left, MetricExpression right) implements MetricExpression {
        public Subtract {
            requireBinary(left, right, "SUBTRACT");
        }

        @Override
        public Kind kind() {
            return Kind.SUBTRACT;
        }
    }

    record Multiply(MetricExpression left, MetricExpression right) implements MetricExpression {
        public Multiply {
            requireBinary(left, right, "MULTIPLY");
        }

        @Override
        public Kind kind() {
            return Kind.MULTIPLY;
        }
    }

    record Divide(
            MetricExpression left,
            MetricExpression right,
            ZeroDenominatorPolicy zeroDenominator) implements MetricExpression {
        public Divide {
            requireBinary(left, right, "DIVIDE");
            if (zeroDenominator == null) {
                throw invalid("DIVIDE requires an explicit zero-denominator policy");
            }
        }

        @Override
        public Kind kind() {
            return Kind.DIVIDE;
        }
    }

    private static List<MetricExpression> requireOperands(List<MetricExpression> operands, String kind) {
        if (operands == null || operands.size() < 2) {
            throw invalid(kind + " requires at least two operands");
        }
        for (MetricExpression operand : operands) {
            if (operand == null) {
                throw invalid(kind + " operands must be non-null");
            }
        }
        return List.copyOf(operands);
    }

    private static void requireBinary(MetricExpression left, MetricExpression right, String kind) {
        if (left == null || right == null) {
            throw invalid(kind + " requires two operands");
        }
    }

    private static SegmentDslException invalid(String message) {
        return new SegmentDslException(SegmentDslErrorCodes.SHAPE_INVALID, message);
    }
}
