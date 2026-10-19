package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult;
import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderedTierAiToSqlTest {

    @Test
    void goldOrAboveExpandsByRank() {
        var result = invoke("找当前等级金卡以上的顾客。", "当前等级金卡以上", "GREATER_OR_EQUAL", "金卡");

        assertTierCodes(result, "GOLD", "PLATINUM");
    }

    @Test
    void platinumOrAboveContainsOnlyPlatinum() {
        var result = invoke("找当前等级白金卡以上的顾客。", "当前等级白金卡以上", "GREATER_OR_EQUAL", "白金卡");

        assertTierCodes(result, "PLATINUM");
    }

    @Test
    void silverOrBelowExpandsInTheOppositeDirection() {
        var result = invoke("找当前等级银卡以下的顾客。", "当前等级银卡以下", "LESS_OR_EQUAL", "银卡");

        assertTierCodes(result, "STANDARD", "SILVER");
    }

    private static DevelopmentAiToSqlResult invoke(
            String utterance, String source, String operator, String boundary) {
        SegmentIntentModelPort model = ignored -> intentJson(
                utterance, source, operator, boundary).getBytes(StandardCharsets.UTF_8);
        return new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(
                        utterance, Instant.parse("2026-10-05T04:00:00Z")));
    }

    private static void assertTierCodes(DevelopmentAiToSqlResult result, String... expected) {
        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.failureCode()).isTrue();
        assertThat(result.sql()).contains("\"current_tier_code\" IN (");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .containsExactlyInAnyOrder(expected);
    }

    private static String intentJson(
            String utterance, String source, String operator, String boundary) {
        int start = utterance.indexOf(source);
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"tier\",\"concept\":\"CURRENT_TIER\","
                + "\"definitionKind\":\"ATTRIBUTE\",\"negated\":false,"
                + "\"operator\":\"" + operator + "\","
                + "\"value\":{\"kind\":\"ENUM\",\"raw\":\"" + boundary + "\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "},"
                + "\"candidateHints\":[\"member.current_tier\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"tier\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }
}
