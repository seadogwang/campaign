package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AverageOrderValueAiToSqlTest {

    private static final String UTTERANCE = "找近 45 天平均订单实付金额低于 600 元的顾客。";

    @Test
    void registeredAverageOrderValueSupportsArbitraryWindowAndThreshold() {
        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var result = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(
                        UTTERANCE, Instant.parse("2026-10-05T04:00:00Z")));

        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.clarification()).isTrue();
        assertThat(result.sql())
                .contains("AVG(")
                .contains("\"paid_amount\"")
                .contains("\"order_type\" IN (?)")
                .contains("\"currency_code\" = ?")
                .contains("< ?")
                .doesNotContain("COALESCE(");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("600", "CNY", "POSITIVE");
    }

    private static String intentJson() {
        String source = "近 45 天平均订单实付金额低于 600 元";
        int start = UTTERANCE.indexOf(source);
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"AVERAGE_ORDER_VALUE\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"LESS_THAN\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"600\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":45,\"unit\":\"DAY\"},"
                + "\"aggregation\":\"AVG\",\"provenance\":{"
                + "\"type\":\"SOURCE_SPAN\",\"text\":\"" + source + "\",\"start\":" + start
                + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"order.avg_paid_amount\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }
}
