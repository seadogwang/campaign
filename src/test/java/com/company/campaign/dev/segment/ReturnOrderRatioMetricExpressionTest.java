package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlRequest;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.SegmentValidator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnOrderRatioMetricExpressionTest {

    @Test
    void compilesReturnCountOverPositivePlusReturnCountFromTheRealDevelopmentMetadata() {
        DevelopmentAiToSqlRequest request = CoreLoyaltyDevelopmentProfile.request(
                "找近180天退单数占订单总数不低于50%的顾客。",
                Instant.parse("2026-10-05T04:00:00Z"));
        MetricExpression expression = new MetricExpression.Divide(
                ref("order.return_count"),
                new MetricExpression.Add(List.of(
                        ref("order.positive_count"),
                        ref("order.return_count"))),
                ZeroDenominatorPolicy.EXCLUDED);
        MetricExpressionPredicate predicate = new MetricExpressionPredicate(
                "n-return-ratio",
                expression,
                new RollingWindow(180, WindowUnit.DAY),
                ComparisonOperator.GREATER_OR_EQUAL,
                ConditionValue.of(new DecimalValue("0.5")));
        SegmentDsl document = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED,
                SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(request.permittedSnapshot()),
                predicate);

        var validation = new SegmentValidator(CoreLoyaltyDevelopmentProfile.authorizationService()).validate(
                document,
                request.permittedSnapshot(),
                request.trustedContext(),
                request.validationPolicy());
        assertThat(validation.valid()).isTrue();

        var plan = new SegmentQueryPlanner(CoreLoyaltyDevelopmentProfile.authorizationService()).plan(
                validation, request.permittedSnapshot(), request.trustedContext());
        var compiled = new SqlCompiler().compile(
                plan, request.storageCatalog(), request.sqlCompilePolicy(), request.fxRateBinding());

        assertThat(compiled.sql())
                .contains(" / NULLIF(")
                .contains(" + ")
                .contains("\"order_type\"")
                .doesNotContain("0.5");
        assertThat(compiled.parameters())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("POSITIVE", "RETURN", "0.5");
    }

    private static MetricExpression.MetricRef ref(String metricId) {
        return new MetricExpression.MetricRef(metricId);
    }
}
