package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationEventTimeCapability;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlIdentifier;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.sql.TableRef;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedMetricAiToSqlClosedLoopTest {

    private static final String UTTERANCE =
            "等级为金卡，今年5月累积购买面膜数量大于5，并且生日月是5月的会员";
    private static final EntityVersionRef ITEM_VERSION =
            new EntityVersionRef(SubjectKind.EVENT, MetadataFixtures.schemaVersion());

    @Test
    void productFilterStaysInsideTheMayQuantityAggregate() {
        MetadataSnapshot snapshot = snapshot();
        String response = intentJson();
        SliceTestFixtures.StubModelPort model = SliceTestFixtures.StubModelPort.returning(response);
        ResolutionPolicy policy = ResolutionPolicy.publish(
                "scoped-metric-policy/1",
                null,
                Map.of(
                        "CURRENT_TIER", "member.current_tier",
                        "BIRTH_MONTH", "member.birth_month",
                        "PURCHASED_ITEM_QUANTITY", "order_item.quantity_sum",
                        "PRODUCT_NAME", "order_item.product_name"));
        DevelopmentAiToSqlRequest request = new DevelopmentAiToSqlRequest(
                UTTERANCE,
                "zh-CN",
                snapshot,
                policy,
                IntentFixtures.emptyRegistry(),
                MetadataFixtures.context(MetadataFixtures.allPermissions()),
                SegmentValidationPolicy.standard(),
                catalog(),
                SqlCompilePolicy.standard(),
                null);

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                model, SegmentValidationFixtures.allowAll()).invoke(request);

        assertThat(result.isCompiled())
                .as("%s / %s", result.disposition(), result.failureCode())
                .isTrue();
        assertThat(model.invocations()).isEqualTo(1);
        assertThat(model.lastRequest().permittedDefinitions().stream()
                .filter(definition -> definition.stableId().equals("order_item.quantity_sum"))
                .findFirst()
                .orElseThrow()
                .metricFilterDimensions())
                .singleElement()
                .satisfies(filter -> {
                    assertThat(filter.attributeId()).isEqualTo("order_item.product_name");
                    assertThat(filter.allowedOperators()).containsExactly(ComparisonOperator.EQUAL);
                });

        assertThat(result.sql())
                .contains("SUM(")
                .contains("\"public\".\"order_items\"")
                .contains("\"product_name\"")
                .contains("\"quantity\"")
                .contains("\"birth_month\"")
                .contains("\"current_tier\"")
                .doesNotContain("面膜")
                .doesNotContain("金卡");
        assertThat(result.parameters())
                .filteredOn(binding -> !binding.redacted())
                .extracting(DevelopmentAiToSqlResult.ParameterBinding::canonicalValue)
                .contains("GOLD", "面膜", "5", "2026-04-30T16:00:00Z", "2026-05-31T16:00:00Z");
        assertThat(result.parameters())
                .filteredOn(binding -> "面膜".equals(binding.canonicalValue()))
                .extracting(DevelopmentAiToSqlResult.ParameterBinding::type)
                .containsExactly(SqlParameterType.TEXT);
    }

    private static MetadataSnapshot snapshot() {
        AttributeDefinition currentTier = new AttributeDefinition(
                "member.current_tier",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "当前会员等级",
                SemanticDataType.ENUM,
                null,
                List.of("当前等级", "会员等级"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                List.of(
                        new com.company.campaign.metadata.domain.EnumValueDefinition(
                                "GOLD", List.of("金卡", "Gold")),
                        new com.company.campaign.metadata.domain.EnumValueDefinition(
                                "SILVER", List.of("银卡", "Silver")),
                        new com.company.campaign.metadata.domain.EnumValueDefinition(
                                "STANDARD", List.of("普卡", "普通会员"))),
                NullSemantics.EXCLUDED,
                CurrencySemantics.NOT_APPLICABLE,
                List.of("金卡"),
                List.of(),
                "会员当前生效等级，不是历史等级记录。");
        AttributeDefinition birthMonth = new AttributeDefinition(
                "member.birth_month",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "生日月份",
                SemanticDataType.INTEGER,
                null,
                List.of("生日月"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                List.of(),
                NullSemantics.EXCLUDED,
                CurrencySemantics.NOT_APPLICABLE,
                List.of("生日月是5月"),
                List.of(),
                "由会员生日日期派生的自然月，范围 1 到 12。");
        AttributeDefinition productName = new AttributeDefinition(
                "order_item.product_name",
                ITEM_VERSION,
                SubjectKind.EVENT,
                "商品名称",
                SemanticDataType.STRING,
                StringSemantics.localeNeutral(
                        StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE),
                List.of("商品", "购买商品"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                List.of(),
                NullSemantics.EXCLUDED,
                CurrencySemantics.NOT_APPLICABLE,
                List.of("面膜"),
                List.of(),
                "订单明细事实中的商品名称。");
        MetricDefinition quantity = new MetricDefinition(
                "order_item.quantity_sum",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "累计购买商品数量",
                SemanticDataType.INTEGER,
                null,
                List.of("购买数量", "商品数量"),
                List.of(ComparisonOperator.GREATER_THAN),
                AggregateFunction.SUM,
                "member.order_items",
                "order_item.paid_at",
                EventTimeSemantics.EVENT_TIME,
                List.of(new MetricFilterDimension(
                        "order_item.product_name", List.of(ComparisonOperator.EQUAL),
                        FilterDomain.PRODUCT, FilterIdentifierKind.NAME)),
                MetadataFilter.empty(),
                RefundTreatment.IGNORE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of("今年5月累积购买面膜数量大于5"),
                List.of(),
                "按订单明细数量求和；商品条件必须作为 Metric scopeFilters。");
        RelationDefinition relation = new RelationDefinition(
                "member.order_items",
                "会员购买明细事实",
                List.of("订单明细", "购买明细"),
                SubjectKind.MEMBER,
                MetadataFixtures.memberVersion(),
                SubjectKind.EVENT,
                ITEM_VERSION,
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "member.id",
                        "order_item.member_id"),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "order_item.paid_at"),
                NullSemantics.EXCLUDED,
                EmptySetBehavior.NULL,
                List.of("会员购买的订单明细"),
                List.of(),
                "面向会员聚合的订单明细事实投影。");
        return MetadataFixtures.snapshot(
                List.of(currentTier, birthMonth, productName),
                List.of(quantity),
                List.of(relation));
    }

    private static StorageCatalog catalog() {
        StorageCatalog.ContextStorage context = new StorageCatalog.ContextStorage(
                id("tenant_id"), id("program_id"));
        return StorageCatalog.publish(
                "scoped-metric-catalog-1",
                List.of(
                        new StorageCatalog.EntityStorage(
                                MetadataFixtures.memberVersion(), table("members"), id("member_id"), context),
                        new StorageCatalog.EntityStorage(
                                ITEM_VERSION, table("order_items"), id("order_item_id"), context)),
                List.of(
                        new StorageCatalog.AttributeStorage(
                                "member.current_tier", SubjectKind.MEMBER, MetadataFixtures.schemaVersion(),
                                SemanticDataType.ENUM, id("current_tier"), null),
                        new StorageCatalog.AttributeStorage(
                                "member.birth_month", SubjectKind.MEMBER, MetadataFixtures.schemaVersion(),
                                SemanticDataType.INTEGER, id("birth_month"), null),
                        new StorageCatalog.AttributeStorage(
                                "order_item.product_name", SubjectKind.EVENT, MetadataFixtures.schemaVersion(),
                                SemanticDataType.STRING, id("product_name"), null)),
                List.of(new StorageCatalog.MetricStorage(
                        "order_item.quantity_sum",
                        SubjectKind.MEMBER,
                        MetadataFixtures.schemaVersion(),
                        SemanticDataType.INTEGER,
                        "member.order_items",
                        "order_item.quantity",
                        id("quantity"),
                        null,
                        null,
                        "order_item.paid_at",
                        id("paid_at"),
                        Map.of(),
                        List.of(AggregateFunction.SUM),
                        List.of(EmptySetBehavior.ZERO),
                        false)),
                List.of(new StorageCatalog.RelationStorage(
                        "member.order_items",
                        SubjectKind.MEMBER,
                        MetadataFixtures.schemaVersion(),
                        SubjectKind.EVENT,
                        MetadataFixtures.schemaVersion(),
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "member.id",
                        id("member_id"),
                        "order_item.member_id",
                        id("member_id"),
                        "order_item.paid_at",
                        id("paid_at"))),
                new StorageCatalog.DataScopeStorage(
                        table("data_scope_members"), id("scope_id"), id("member_id"),
                        id("tenant_id"), id("program_id")),
                null,
                null);
    }

    private static String intentJson() {
        String tier = span("金卡");
        String metric = span("今年5月累积购买面膜数量大于5");
        String product = span("面膜");
        String birthday = span("生日月是5月");
        return "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":["
                + clause("tier", "CURRENT_TIER", "ATTRIBUTE", "EQUAL", "ENUM", "金卡", tier,
                        "member.current_tier", "") + ","
                + clause("quantity", "PURCHASED_ITEM_QUANTITY", "METRIC", "GREATER_THAN", "INTEGER", "5",
                        metric, "order_item.quantity_sum",
                        "\"window\":{\"kind\":\"FIXED\",\"start\":\"2026-04-30T16:00:00Z\","
                                + "\"end\":\"2026-05-31T16:00:00Z\"},\"aggregation\":\"SUM\","
                                + "\"scopeFilters\":[{\"filterId\":\"product\","
                                + "\"concept\":\"PRODUCT_NAME\","
                                + "\"domain\":\"PRODUCT\",\"identifierKind\":\"NAME\","
                                + "\"operator\":\"EQUAL\","
                                + "\"value\":{\"kind\":\"STRING\",\"raw\":\"面膜\"},"
                                + "\"provenance\":" + product + ","
                                + "\"candidateHints\":[\"order_item.product_name\"],\"assumptions\":[]}],") + ","
                + clause("birthday", "BIRTH_MONTH", "ATTRIBUTE", "EQUAL", "INTEGER", "5", birthday,
                        "member.birth_month", "")
                + "],\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"tier\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"quantity\"},"
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"birthday\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";
    }

    private static String clause(
            String id,
            String concept,
            String kind,
            String operator,
            String valueKind,
            String raw,
            String provenance,
            String candidate,
            String additionalSlots) {
        return "{\"clauseId\":\"" + id + "\",\"concept\":\"" + concept
                + "\",\"definitionKind\":\"" + kind + "\",\"negated\":false,"
                + "\"operator\":\"" + operator + "\",\"value\":{\"kind\":\"" + valueKind
                + "\",\"raw\":\"" + raw + "\"}," + additionalSlots
                + "\"provenance\":" + provenance + ",\"candidateHints\":[\"" + candidate
                + "\"],\"assumptions\":[]}";
    }

    private static String span(String text) {
        int start = UTTERANCE.indexOf(text);
        return "{\"type\":\"SOURCE_SPAN\",\"text\":\"" + text + "\",\"start\":" + start
                + ",\"end\":" + (start + text.length()) + "}";
    }

    private static SqlIdentifier id(String value) {
        return SqlIdentifier.of(value);
    }

    private static TableRef table(String value) {
        return new TableRef(id("public"), id(value));
    }
}
