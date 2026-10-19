package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricExpressionSqlTest {

    @Test
    void compilesCountRatioWithDecimalDivisionAndExcludedZeroDenominator() {
        CompiledSql compiled = compile(
                new MetricExpression.Divide(
                        ref("order.count"),
                        ref("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                ConditionValue.of(new DecimalValue("0.5")));

        assertThat(compiled.sql())
                .contains("CAST(")
                .contains(" AS NUMERIC)")
                .contains(" / NULLIF(")
                .doesNotContain("0.5");
        assertThat(compiled.parameters())
                .extracting(SqlParameter::canonicalValue)
                .contains("0", "0.5");
    }

    @Test
    void compilesMoneyPerCountWithoutBusinessSpecificBranching() {
        CompiledSql compiled = compile(
                new MetricExpression.Divide(
                        ref("order.net_paid_amount"),
                        ref("order.count"),
                        ZeroDenominatorPolicy.EXCLUDED),
                ConditionValue.of(new MoneyValue("800", "CNY")));

        assertThat(compiled.sql())
                .contains("SUM(")
                .contains("COUNT(*)")
                .contains(" / NULLIF(")
                .doesNotContain("800");
        assertThat(compiled.parameters())
                .extracting(SqlParameter::canonicalValue)
                .contains("800", "CNY");
    }

    @Test
    void compilesSameUnitAdditionThroughTheGenericAddNode() {
        CompiledSql compiled = compile(
                new MetricExpression.Add(List.of(ref("order.count"), ref("order.count"))),
                ConditionValue.of(new IntegerValue(3)));

        assertThat(compiled.sql()).contains(" + ").doesNotContain(" 3");
        assertThat(compiled.parameters())
                .extracting(SqlParameter::canonicalValue)
                .contains("3");
    }

    private static CompiledSql compile(MetricExpression expression, ConditionValue condition) {
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-expression",
                expression,
                SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_OR_EQUAL,
                condition);
        return SqlTestSupport.compile(SqlTestSupport.plan(predicate));
    }

    private static MetricExpression.MetricRef ref(String metricId) {
        return new MetricExpression.MetricRef(metricId);
    }
}
