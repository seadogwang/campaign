package com.company.campaign.segment.dsl;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricExpressionDslCodecTest {

    private final SegmentDslCodec codec = new SegmentDslCodec();

    @Test
    void roundTripsTheClosedExpressionShapeDeterministically() {
        MetricExpressionPredicate predicate = ratio();

        String json = codec.writePredicateString(predicate);

        assertThat(codec.readPredicate(json)).isEqualTo(predicate);
        assertThat(codec.writePredicateString(codec.readPredicate(json))).isEqualTo(json);
        assertThat(json)
                .contains("\"type\":\"METRIC_EXPRESSION\"")
                .contains("\"zeroDenominator\":\"EXCLUDED\"");
    }

    @Test
    void rejectsUnknownExpressionFieldsAndMissingZeroDenominatorPolicy() {
        String valid = codec.writePredicateString(ratio());
        String unknown = valid.replace("\"metricId\":\"order.count\"",
                "\"metricId\":\"order.count\",\"sql\":\"select 1\"");
        String missingPolicy = valid.replace(",\"zeroDenominator\":\"EXCLUDED\"", "");

        assertThatThrownBy(() -> codec.readPredicate(unknown))
                .isInstanceOfSatisfying(SegmentDslException.class,
                        exception -> assertThat(exception.code()).isEqualTo(SegmentDslErrorCodes.UNKNOWN_FIELD));
        assertThatThrownBy(() -> codec.readPredicate(missingPolicy))
                .isInstanceOfSatisfying(SegmentDslException.class,
                        exception -> assertThat(exception.code()).isEqualTo(SegmentDslErrorCodes.SHAPE_INVALID));
    }

    private static MetricExpressionPredicate ratio() {
        return new MetricExpressionPredicate(
                "n-ratio",
                new MetricExpression.Divide(
                        new MetricExpression.MetricRef("order.count"),
                        new MetricExpression.MetricRef("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new DecimalValue("0.5")));
    }
}
