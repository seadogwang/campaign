package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SharedWindowAmbiguityAiToSqlTest {

    private static final String UTTERANCE = "找近 90 天订单数 ≥ 3、且累计实付 ≥ 2000 的顾客。";

    @Test
    void structuredAlternativesBlockSqlAndExposeBothTimeMeanings() {
        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var result = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(
                        UTTERANCE, Instant.parse("2026-10-05T04:00:00Z")));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.sql()).isNull();
        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.SELECT_WINDOW);
        assertThat(result.clarification().clauseId()).isEqualTo("c2.window");
        assertThat(result.clarification().options())
                .extracting(option -> option.label())
                .containsExactly("沿用前述时间范围（最近90天）", "历史累计");
    }

    private static String intentJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + clause("c1", "POSITIVE_ORDER_COUNT", "INTEGER", "3", "COUNT",
                "{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"}",
                "近 90 天订单数 ≥ 3", "order.positive_count") + ","
                + clause("c2", "PAID_PURCHASE_AMOUNT", "MONEY", "2000", "SUM",
                "{\"kind\":\"LIFETIME\"}", "累计实付 ≥ 2000", "order.paid_amount_sum")
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]},"
                + "\"ambiguities\":[{\"code\":\"AMBIGUOUS_WINDOW\",\"target\":\"c2.window\","
                + "\"options\":[{\"kind\":\"INHERIT_WINDOW\",\"fromClauseId\":\"c1\"},"
                + "{\"kind\":\"LIFETIME\"}]}],\"unsupportedRequirements\":[]}";
    }

    private static String clause(
            String clauseId,
            String concept,
            String valueKind,
            String raw,
            String aggregation,
            String window,
            String source,
            String candidate) {
        int start = UTTERANCE.indexOf(source);
        return "{\"clauseId\":\"" + clauseId + "\",\"concept\":\"" + concept
                + "\",\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_OR_EQUAL\",\"value\":{\"kind\":\"" + valueKind
                + "\",\"raw\":\"" + raw + "\"},\"window\":" + window
                + ",\"aggregation\":\"" + aggregation + "\",\"provenance\":{"
                + "\"type\":\"SOURCE_SPAN\",\"text\":\"" + source + "\",\"start\":" + start
                + ",\"end\":" + (start + source.length()) + "},\"candidateHints\":[\"" + candidate
                + "\"],\"assumptions\":[]}";
    }
}
