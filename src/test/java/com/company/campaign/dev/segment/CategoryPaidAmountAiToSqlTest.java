package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult;
import com.company.campaign.builder.segment.DevelopmentAiToSqlService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPaidAmountAiToSqlTest {

    private static final Instant AS_OF = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void explicitCategoryNameCompilesWithoutDatabaseAccess() {
        String utterance = "找 2026 年 1 月 1 日到 6 月 30 日，购买商品品类名称为面霜并且累计金额 ≥ 2000 的顾客。";
        var result = invoke(utterance, "商品品类名称为面霜", "CATEGORY", "NAME");

        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.clarification()).isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_order_item\"")
                .contains("\"line_paid_amount\"")
                .contains("\"category_name\" = ?")
                .contains("\"order_type\" IN (?)")
                .contains("\"currency_code\" = ?")
                .contains(">= ?")
                .doesNotContain("面霜");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("面霜", "CNY", "2000", "0");
    }

    @Test
    void monetaryUnitPriceFilterCompilesAgainstTheOrderItemRow() {
        String utterance = "找买过单价 ≥ 1000 的商品、且累计金额 ≥ 3000 的顾客。";
        String filterSource = "单价 ≥ 1000";
        String metricSource = "累计金额 ≥ 3000";
        String intent = "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_PAID_AMOUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"3000\"},"
                + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"SUM\","
                + "\"scopeFilters\":[{\"filterId\":\"f1\","
                + "\"concept\":\"ORDER_ITEM_UNIT_PRICE\",\"domain\":\"UNSPECIFIED\","
                + "\"identifierKind\":\"UNSPECIFIED\",\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                + span(utterance, filterSource) + ","
                + "\"candidateHints\":[\"order_item.unit_price\"],\"assumptions\":[]}],"
                + span(utterance, metricSource) + ","
                + "\"candidateHints\":[\"order_item.line_paid_amount_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
        SegmentIntentModelPort model = ignored -> intent.getBytes(StandardCharsets.UTF_8);

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));

        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.clarification()).isTrue();
        assertThat(result.sql())
                .contains("\"public\".\"seg_order_item\"")
                .contains("\"unit_price\" >= ?")
                .contains("\"line_paid_amount\"")
                .contains("\"currency_code\" = ?")
                .doesNotContain("1000")
                .doesNotContain("3000");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("1000", "3000", "CNY");
    }

    @Test
    void ambiguousUnitPriceFilterScopeBlocksSqlUntilTheUserClarifies() {
        String utterance = "找买过单价 ≥ 1000 的商品、且累计金额 ≥ 3000 的顾客。";
        String intent = "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_QUANTITY\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},\"window\":{\"kind\":\"LIFETIME\"},"
                + "\"aggregation\":\"SUM\",\"scopeFilters\":[{\"filterId\":\"f1\","
                + "\"concept\":\"ORDER_ITEM_UNIT_PRICE\",\"domain\":\"UNSPECIFIED\","
                + "\"identifierKind\":\"UNSPECIFIED\",\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                + span(utterance, "单价 ≥ 1000") + ","
                + "\"candidateHints\":[\"order_item.unit_price\"],\"assumptions\":[]}],"
                + span(utterance, "买过单价 ≥ 1000 的商品") + ","
                + "\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]},{"
                + "\"clauseId\":\"c2\",\"concept\":\"PURCHASED_ITEM_PAID_AMOUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"3000\"},\"window\":{\"kind\":\"LIFETIME\"},"
                + "\"aggregation\":\"SUM\"," + span(utterance, "累计金额 ≥ 3000") + ","
                + "\"candidateHints\":[\"order_item.line_paid_amount_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]},"
                + "\"ambiguities\":[{\"code\":\"AMBIGUOUS_FILTER_SCOPE\","
                + "\"target\":\"c2.scopeFilters\",\"options\":["
                + "{\"kind\":\"INHERIT_SCOPE_FILTERS\",\"fromClauseId\":\"c1\"},"
                + "{\"kind\":\"INDEPENDENT\"}]}],\"unsupportedRequirements\":[]}";
        SegmentIntentModelPort model = ignored -> intent.getBytes(StandardCharsets.UTF_8);

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.sql()).isNull();
        assertThat(result.clarification().questionCode().name()).isEqualTo("AMBIGUOUS_FILTER_SCOPE");
        assertThat(result.clarification().clauseId()).isEqualTo("c2.scopeFilters");
        assertThat(result.clarification().questionText()).contains("筛选条件", "后续指标");
    }

    @Test
    void promotionalAndNonDiscountedProductsCompileFromTheReusableDiscountDefinition() {
        String utterance = "找买过促销品、但从未买过正品的顾客。";
        String intent = "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_QUANTITY\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},\"window\":{\"kind\":\"LIFETIME\"},"
                + "\"aggregation\":\"SUM\",\"scopeFilters\":[{\"filterId\":\"f1\","
                + "\"concept\":\"ORDER_ITEM_DISCOUNT_AMOUNT\",\"domain\":\"UNSPECIFIED\","
                + "\"identifierKind\":\"UNSPECIFIED\",\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"0\"},"
                + span(utterance, "促销品") + ",\"candidateHints\":[\"order_item.discount_amount\"],\"assumptions\":[]}],"
                + span(utterance, "买过促销品") + ","
                + "\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]},{"
                + "\"clauseId\":\"c2\",\"concept\":\"PURCHASED_ITEM_QUANTITY\","
                + "\"definitionKind\":\"METRIC\",\"negated\":true,\"operator\":\"GREATER_THAN\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"0\"},\"window\":{\"kind\":\"LIFETIME\"},"
                + "\"aggregation\":\"SUM\",\"scopeFilters\":[{\"filterId\":\"f2\","
                + "\"concept\":\"ORDER_ITEM_DISCOUNT_AMOUNT\",\"domain\":\"UNSPECIFIED\","
                + "\"identifierKind\":\"UNSPECIFIED\",\"operator\":\"EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"0\"},"
                + span(utterance, "正品") + ",\"candidateHints\":[\"order_item.discount_amount\"],\"assumptions\":[]}],"
                + span(utterance, "从未买过正品") + ","
                + "\"candidateHints\":[\"order_item.quantity_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
        SegmentIntentModelPort model = ignored -> intent.getBytes(StandardCharsets.UTF_8);

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));

        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.clarification()).isTrue();
        assertThat(result.sql())
                .contains("\"line_discount_amount\" > ?")
                .contains("\"line_discount_amount\" = ?")
                .contains("NOT ((SELECT COALESCE(SUM")
                .doesNotContain("促销品")
                .doesNotContain("正品");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("0", "CNY");
    }

    @Test
    void distinctPurchasedCategoriesCompileAsCountDistinctRatherThanQuantitySum() {
        String utterance = "找近 365 天购买过至少 3 个不同品类的顾客。";
        String source = "近 365 天购买过至少 3 个不同品类";
        String intent = "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_DISTINCT_CATEGORY_COUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"3\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":365,\"unit\":\"DAY\"},"
                + "\"aggregation\":\"COUNT_DISTINCT\"," + span(utterance, source) + ","
                + "\"candidateHints\":[\"order_item.distinct_category_count\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
        SegmentIntentModelPort model = ignored -> intent.getBytes(StandardCharsets.UTF_8);

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));

        assertThat(result.isCompiled()).as("%s / %s", result.disposition(), result.clarification()).isTrue();
        assertThat(result.sql())
                .contains("COUNT(DISTINCT \"r2\".\"category_code\")")
                .contains("\"order_type\" IN (?)")
                .contains(">= ?")
                .doesNotContain("SUM(\"r2\".\"quantity\")");
        assertThat(result.parameters())
                .filteredOn(parameter -> !parameter.redacted())
                .extracting(parameter -> parameter.canonicalValue())
                .contains("3", "POSITIVE");
    }

    @Test
    void categoryWithoutIdentifierKindRequiresNameOrCodeClarification() {
        String utterance = "找 2026 年 1 月 1 日到 6 月 30 日，购买面霜品类并且累计金额 ≥ 2000 的顾客。";
        var result = invoke(utterance, "面霜品类", "CATEGORY", "UNSPECIFIED");

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.clarification()).isNotNull();
        assertThat(result.clarification().options())
                .extracting(option -> option.definitionId())
                .containsExactlyInAnyOrder("order_item.category_name", "order_item.category_code");
    }

    @Test
    void bareProductValueRequiresProductOrCategoryAndNameOrCodeClarification() {
        String utterance = "找 2026 年 1 月 1 日到 6 月 30 日，购买过面霜并且累计金额 ≥ 2000 的顾客。";
        var result = invoke(utterance, "面霜", "UNSPECIFIED", "UNSPECIFIED");

        assertThat(result.isCompiled()).isFalse();
        assertThat(result.clarification()).isNotNull();
        assertThat(result.clarification().options())
                .extracting(option -> option.definitionId())
                .contains(
                        "order_item.product_name",
                        "order_item.sku_code",
                        "order_item.category_name",
                        "order_item.category_code");
    }

    private static DevelopmentAiToSqlResult invoke(
            String utterance, String filterSource, String domain, String identifierKind) {
        SegmentIntentModelPort model = ignored -> intentJson(
                utterance, filterSource, domain, identifierKind).getBytes(StandardCharsets.UTF_8);
        return new DevelopmentAiToSqlService(model, SegmentValidationFixtures.allowAll())
                .invoke(CoreLoyaltyDevelopmentProfile.request(utterance, AS_OF));
    }

    private static String intentJson(
            String utterance, String filterSource, String domain, String identifierKind) {
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                + "\"clauseId\":\"c1\",\"concept\":\"PURCHASED_ITEM_PAID_AMOUNT\","
                + "\"definitionKind\":\"METRIC\",\"negated\":false,"
                + "\"operator\":\"GREATER_OR_EQUAL\","
                + "\"value\":{\"kind\":\"MONEY\",\"raw\":\"2000\"},"
                + "\"window\":{\"kind\":\"FIXED\","
                + "\"start\":\"2026-01-01T00:00:00+08:00\","
                + "\"end\":\"2026-07-01T00:00:00+08:00\"},"
                + "\"aggregation\":\"SUM\",\"scopeFilters\":[{"
                + "\"filterId\":\"f1\",\"concept\":\"CATEGORY_NAME\","
                + "\"domain\":\"" + domain + "\",\"identifierKind\":\"" + identifierKind + "\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"raw\":\"面霜\"},"
                + span(utterance, filterSource) + ","
                + "\"candidateHints\":[\"order_item.category_name\"],\"assumptions\":[]}],"
                + span(utterance, "累计金额 ≥ 2000") + ","
                + "\"candidateHints\":[\"order_item.line_paid_amount_sum\"],\"assumptions\":[]}],"
                + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String span(String utterance, String text) {
        int start = utterance.indexOf(text);
        return "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + text
                + "\",\"start\":" + start + ",\"end\":" + (start + text.length()) + "}";
    }
}
