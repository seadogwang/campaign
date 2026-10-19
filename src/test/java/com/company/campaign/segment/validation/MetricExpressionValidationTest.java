package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricExpressionValidationTest {

    @Test
    void validatesCountRatioAndInfersDecimalRatio() {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-ratio",
                new MetricExpression.Divide(
                        ref("order.count"),
                        ref("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new DecimalValue("0.5")));

        SegmentValidationResult result = SegmentValidationFixtures.validate(predicate);

        assertThat(result.valid()).isTrue();
        assertThat(result.canonicalSegment().predicate())
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalNode.MetricCalculation.class);
    }

    @Test
    void validatesMoneyPerCountAgainstMoneyThreshold() {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-average",
                new MetricExpression.Divide(
                        ref("order.net_paid_amount"),
                        ref("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new MoneyValue("800", "CNY")));

        assertThat(SegmentValidationFixtures.validate(predicate).valid()).isTrue();
    }

    @Test
    void rejectsAdditionOfMoneyAndCount() {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-invalid",
                new MetricExpression.Add(List.of(
                        ref("order.net_paid_amount"),
                        ref("order.count"))),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new MoneyValue("800", "CNY")));

        SegmentValidationResult result = SegmentValidationFixtures.validate(predicate);

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.METRIC_EXPRESSION_TYPE_INCOMPATIBLE);
    }

    @Test
    void deniesEveryExpressionLeafWhenAuthorizationIsUnavailable() {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-denied",
                new MetricExpression.Divide(
                        ref("order.net_paid_amount"),
                        ref("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new MoneyValue("800", "CNY")));

        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(predicate),
                SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(),
                SegmentValidationFixtures.denyAll(),
                SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsOnly(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    @Test
    void canonicalBoundaryRejectsAForgedExpressionResultType() {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-ratio",
                new MetricExpression.Divide(
                        ref("order.count"), ref("order.count"), ZeroDenominatorPolicy.EXCLUDED),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new DecimalValue("0.5")));
        var calculation = (com.company.campaign.segment.canonical.CanonicalNode.MetricCalculation)
                SegmentValidationFixtures.validate(predicate).canonicalSegment().predicate();
        var divide = (com.company.campaign.segment.canonical.CanonicalMetricExpression.Divide)
                calculation.expression();
        var forged = new com.company.campaign.segment.canonical.CanonicalMetricExpressionType(
                com.company.campaign.metadata.domain.SemanticDataType.INTEGER,
                com.company.campaign.segment.canonical.MetricExpressionUnit.NUMBER,
                com.company.campaign.metadata.domain.CurrencySemantics.NOT_APPLICABLE);

        assertThatThrownBy(() -> new com.company.campaign.segment.canonical.CanonicalMetricExpression.Divide(
                divide.left(), divide.right(), divide.zeroDenominator(), forged))
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalInvariantException.class);
    }

    private static MetricExpression.MetricRef ref(String metricId) {
        return new MetricExpression.MetricRef(metricId);
    }
}
