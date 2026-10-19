package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PointsActivityAiToSqlTest {

    private static final String UTTERANCE =
            "找当前等级金卡以上、可用积分大于 5000、且近 30 天有过积分变动的顾客";

    @Test
    void tierBalanceAndRecentRedeemablePointActivityCompileWithoutDatabaseAccess() {
        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var service = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll());

        var result = service.invoke(CoreLoyaltyDevelopmentProfile.request(
                UTTERANCE, Instant.parse("2026-10-04T00:00:00Z")));

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.sql())
                .contains("\"current_tier_code\" IN (?, ?)")
                .contains("\"available_points\" > ?")
                .contains("\"public\".\"seg_point_entry\"")
                .contains("EXISTS (SELECT")
                .contains("\"point_type\" IN (?)")
                .doesNotContain("COUNT(");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("GOLD", "PLATINUM", "5000", "REDEEMABLE")
                .doesNotContain("0");
    }

    private static String intentJson() {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + clause("c1", "CURRENT_TIER", "ATTRIBUTE", "IN",
                        "{\"kind\":\"LIST\",\"items\":[{\"kind\":\"ENUM\",\"raw\":\"金卡\"},"
                                + "{\"kind\":\"ENUM\",\"raw\":\"白金卡\"}]}",
                        null, null, "当前等级金卡以上", "member.current_tier") + ","
                + clause("c2", "AVAILABLE_POINTS", "ATTRIBUTE", "GREATER_THAN",
                        "{\"kind\":\"INTEGER\",\"raw\":\"5000\"}",
                        null, null, "可用积分大于 5000", "member.available_points") + ","
                + clause("c3", "POINT_ACTIVITY_COUNT", "METRIC", "GREATER_THAN",
                        "{\"kind\":\"INTEGER\",\"raw\":\"0\"}",
                        "{\"kind\":\"ROLLING\",\"amount\":30,\"unit\":\"DAY\"}",
                        "\"aggregation\":\"COUNT\",", "近 30 天有过积分变动", "point.entry_count")
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c3\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String clause(
            String id,
            String concept,
            String kind,
            String operator,
            String value,
            String window,
            String additional,
            String source,
            String candidate) {
        StringBuilder json = new StringBuilder("{\"clauseId\":\"").append(id)
                .append("\",\"concept\":\"").append(concept)
                .append("\",\"definitionKind\":\"").append(kind)
                .append("\",\"negated\":false,\"operator\":\"").append(operator)
                .append("\",\"value\":").append(value).append(',');
        if (window != null) {
            json.append("\"window\":").append(window).append(',');
        }
        if (additional != null) {
            json.append(additional);
        }
        return json.append(span(source)).append(',')
                .append("\"candidateHints\":[\"").append(candidate)
                .append("\"],\"assumptions\":[]}").toString();
    }

    private static String span(String source) {
        int start = UTTERANCE.indexOf(source);
        return "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + source
                + "\",\"start\":" + start + ",\"end\":" + (start + source.length()) + "}";
    }
}
