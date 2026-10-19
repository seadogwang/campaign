package com.company.campaign.dev.segment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentMetricExpressionServiceTest {

    @Test
    void convertsAClarifiedDefinitionIntoValidatedParameterizedSql() {
        CapturingPort port = new CapturingPort(validRatio());
        DevelopmentMetricExpressionService service = new DevelopmentMetricExpressionService(
                port, CoreLoyaltyDevelopmentProfile.authorizationService());

        DevelopmentMetricExpressionResult result = service.compile(
                CoreLoyaltyDevelopmentProfile.request(
                        "找近180天退单数占订单总数不低于50%的顾客。",
                        Instant.parse("2026-10-05T04:00:00Z")),
                "订单总数等于正单数加退单数，结果为退单数除以订单总数。");

        assertThat(result.isCompiled()).isTrue();
        assertThat(result.sql()).contains(" / NULLIF(").contains(" + ").doesNotContain("0.5");
        assertThat(result.parameters())
                .filteredOn(binding -> !binding.redacted())
                .extracting(DevelopmentMetricExpressionResult.ParameterBinding::canonicalValue)
                .contains("POSITIVE", "RETURN", "0.5");
        assertThat(port.prompt)
                .contains("order.positive_count")
                .contains("order.return_count")
                .contains("clarifiedCalculation")
                .doesNotContain("seg_order");
    }

    @Test
    void inventedMetricIdsFailBeforePlanningOrSql() {
        DevelopmentMetricExpressionService service = new DevelopmentMetricExpressionService(
                prompt -> invalidMetric().getBytes(StandardCharsets.UTF_8),
                CoreLoyaltyDevelopmentProfile.authorizationService());

        DevelopmentMetricExpressionResult result = service.compile(
                CoreLoyaltyDevelopmentProfile.request(
                        "测试未知指标", Instant.parse("2026-10-05T04:00:00Z")),
                "使用不存在的指标。");

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.failureCode()).isEqualTo("METRIC_EXPRESSION_VALIDATION_FAILED");
        assertThat(result.sql()).isNull();
    }

    private static String validRatio() {
        return """
                {"nodeId":"n-metric-expression","type":"METRIC_EXPRESSION","expression":{"kind":"DIVIDE","left":{"kind":"METRIC_REF","metricId":"order.return_count"},"right":{"kind":"ADD","operands":[{"kind":"METRIC_REF","metricId":"order.positive_count"},{"kind":"METRIC_REF","metricId":"order.return_count"}]},"zeroDenominator":"EXCLUDED"},"window":{"type":"ROLLING","amount":180,"unit":"DAY"},"operator":"GREATER_OR_EQUAL","value":{"kind":"DECIMAL","value":"0.5"}}
                """;
    }

    private static String invalidMetric() {
        return """
                {"nodeId":"n-metric-expression","type":"METRIC_EXPRESSION","expression":{"kind":"DIVIDE","left":{"kind":"METRIC_REF","metricId":"invented.metric"},"right":{"kind":"METRIC_REF","metricId":"order.return_count"},"zeroDenominator":"EXCLUDED"},"window":{"type":"ROLLING","amount":180,"unit":"DAY"},"operator":"GREATER_OR_EQUAL","value":{"kind":"DECIMAL","value":"0.5"}}
                """;
    }

    private static final class CapturingPort implements DevelopmentStructuredModelPort {
        private final byte[] response;
        private String prompt;

        private CapturingPort(String response) {
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            this.prompt = prompt;
            return response;
        }
    }
}
