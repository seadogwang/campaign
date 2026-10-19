package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.window.TimeWindow;

/** A comparison against a closed arithmetic expression over atomic Metrics. */
public record MetricExpressionPredicate(
        String nodeId,
        MetricExpression expression,
        TimeWindow window,
        ComparisonOperator operator,
        ConditionValue condition
) implements Predicate {

    public MetricExpressionPredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        if (expression == null || window == null || condition == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID,
                    "A metric-expression predicate requires expression, window and condition");
        }
        validateComplexity(expression, 1, new int[] {0});
        OperatorValueRules.require(operator, condition);
    }

    @Override
    public PredicateType type() {
        return PredicateType.METRIC_EXPRESSION;
    }

    private static void validateComplexity(MetricExpression expression, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > SegmentDslLimits.MAX_METRIC_EXPRESSION_DEPTH
                || nodes[0] > SegmentDslLimits.MAX_METRIC_EXPRESSION_NODES) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "A metric expression exceeds its complexity limit");
        }
        switch (expression) {
            case MetricExpression.MetricRef ignored -> {
            }
            case MetricExpression.Add add ->
                    add.operands().forEach(child -> validateComplexity(child, depth + 1, nodes));
            case MetricExpression.Subtract subtract -> {
                validateComplexity(subtract.left(), depth + 1, nodes);
                validateComplexity(subtract.right(), depth + 1, nodes);
            }
            case MetricExpression.Multiply multiply -> {
                validateComplexity(multiply.left(), depth + 1, nodes);
                validateComplexity(multiply.right(), depth + 1, nodes);
            }
            case MetricExpression.Divide divide -> {
                validateComplexity(divide.left(), depth + 1, nodes);
                validateComplexity(divide.right(), depth + 1, nodes);
            }
        }
    }
}
