package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PositiveOrderWechatAiToSqlTest {

    private static final String UTTERANCE =
            "找当前是金卡或白金卡、近 90 天有正单、且已绑定微信的顾客。";

    @Test
    void compilesPositiveOrderAndWechatBindingWithoutDatabaseAccess() {
        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var service = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll());

        var result = service.invoke(CoreLoyaltyDevelopmentProfile.request(
                UTTERANCE, Instant.parse("2026-10-04T00:00:00Z")));

        assertThat(result.isCompiled())
                .as("%s / %s", result.disposition(), result.failureCode())
                .isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_member\"")
                .contains("\"public\".\"seg_order\"")
                .contains("\"current_tier_code\"")
                .contains("\"wechat_bound\"")
                .contains("\"order_type\"")
                .contains("EXISTS (SELECT")
                .doesNotContain("COUNT(")
                .doesNotContain("seg_order_item")
                .doesNotContain("fx_rates")
                .doesNotContain("金卡")
                .doesNotContain("白金卡")
                .doesNotContain("正单");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("GOLD", "PLATINUM", "POSITIVE", "true")
                .doesNotContain("0");
    }

    private static String intentJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + "{\"clauseId\":\"tier\",\"concept\":\"CURRENT_TIER\","
                + "\"definitionKind\":\"ATTRIBUTE\",\"negated\":false,\"operator\":\"IN\","
                + "\"value\":{\"kind\":\"LIST\",\"items\":["
                + "{\"kind\":\"ENUM\",\"raw\":\"金卡\"},{\"kind\":\"ENUM\",\"raw\":\"白金卡\"}]},"
                + provenance("当前是金卡或白金卡")
                + ",\"candidateHints\":[\"member.current_tier\"],\"assumptions\":[]},"
                + "{\"clauseId\":\"positive-order\",\"concept\":\"POSITIVE_ORDER_COUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                + "\"aggregation\":\"COUNT\"," + provenance("近 90 天有正单")
                + ",\"candidateHints\":[\"order.positive_count\"],\"assumptions\":[]},"
                + "{\"clauseId\":\"wechat\",\"concept\":\"WECHAT_BINDING\","
                + "\"definitionKind\":\"ATTRIBUTE\",\"negated\":false,\"operator\":\"EQUAL\","
                + "\"value\":{\"kind\":\"BOOLEAN\",\"raw\":true}," + provenance("已绑定微信")
                + ",\"candidateHints\":[\"member.wechat_bound\"],\"assumptions\":[]}"
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"tier\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"positive-order\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"wechat\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String provenance(String text) {
        int start = UTTERANCE.indexOf(text);
        return "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + text
                + "\",\"start\":" + start + ",\"end\":" + (start + text.length()) + "}";
    }
}
