package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkuFixedWindowAiToSqlTest {

    private static final String UTTERANCE =
            "找 2026 年 1 月 1 日到 3 月 31 日买过 SKU123、且累计数量至少 2 件的顾客";

    @Test
    void bareSkuShapedValueRequiresFieldClarificationBeforeCompilation() {
        SegmentIntentModelPort model = ignored -> intentJson().getBytes(StandardCharsets.UTF_8);
        var service = new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll());

        var request = CoreLoyaltyDevelopmentProfile.request(
                UTTERANCE, Instant.parse("2026-10-04T00:00:00Z"));
        var firstPass = service.invoke(request);

        assertThat(firstPass.isCompiled()).isFalse();
        assertThat(firstPass.clarification()).isNotNull();
        assertThat(firstPass.clarification().clauseId()).isEqualTo("c1/scope/f1");
        assertThat(firstPass.clarification().options())
                .extracting(option -> option.definitionId())
                .contains("order_item.product_name", "order_item.sku_code");

        ArrayDeque<String> selections = new ArrayDeque<>(List.of("order_item.sku_code"));
        var result = service.invoke(request, clarification -> clarification.options().stream()
                .filter(option -> option.definitionId().equals(selections.removeFirst()))
                .findFirst().orElseThrow().optionId());

        assertThat(result.isCompiled())
                .as("%s / %s / %s", result.disposition(), result.failureCode(), result.clarification())
                .isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.sql())
                .contains("\"public\".\"seg_order_item\"")
                .contains("\"sku_code\" = ?")
                .contains("\"order_type\" IN (?)")
                .contains(">= ?")
                .doesNotContain("SKU123");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains(
                        "2025-12-31T16:00:00Z",
                        "2026-03-31T16:00:00Z",
                        "POSITIVE",
                        "SKU123",
                        "2");
    }

    private static String intentJson() {
        int skuStart = UTTERANCE.indexOf("SKU123");
        int quantityStart = UTTERANCE.indexOf("累计数量至少 2 件");
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_QUANTITY\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"2\"},"
                + "\"window\":{\"kind\":\"FIXED\","
                + "\"start\":\"2026-01-01T00:00:00+08:00\","
                + "\"end\":\"2026-04-01T00:00:00+08:00\"},"
                + "\"aggregation\":\"SUM\",\"scopeFilters\":[{"
                + "\"filterId\":\"f1\",\"concept\":\"SKU_CODE\","
                + "\"domain\":\"UNSPECIFIED\",\"identifierKind\":\"UNSPECIFIED\","
                + "\"operator\":\"EQUAL\","
                + "\"value\":{\"kind\":\"STRING\",\"raw\":\"SKU123\"},"
                + span("SKU123", skuStart)
                + ",\"candidateHints\":[\"order_item.sku_code\"],\"assumptions\":[]}],"
                + span("累计数量至少 2 件", quantityStart)
                + ",\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String span(String text, int start) {
        return "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + text
                + "\",\"start\":" + start + ",\"end\":" + (start + text.length()) + "}";
    }
}
