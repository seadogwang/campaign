package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
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
import com.company.campaign.segment.intent.IntentAmbiguity;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentScopeFilter;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentWindowUnit;
import com.company.campaign.segment.intent.LogicalExpression;
import com.company.campaign.segment.intent.LogicalOperator;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests: product browse and favorite metadata registration, resolution, structural correctness,
 * and MISSING_METADATA_DEFINITION question-code stability.
 *
 * <p>No sports-shoe text, campaign example or test utterance appears in any production name, alias,
 * disambiguation or control flow.
 */
class ProductBrowseFavoriteMetadataRegistrationTest {

    static final String BROWSE_CONCEPT = "PRODUCT_BROWSE_COUNT";
    static final String FAVORITE_CONCEPT = "PRODUCT_FAVORITE_COUNT";
    static final String BROWSE_DEFINITION_ID = "product.browse_count";
    static final String FAVORITE_DEFINITION_ID = "product.favorite_count";
    static final String ORDER_METRIC_ID = "order.positive_count";

    // ------------------------------------------------------------------ fixtures

    private static MetricDefinition browseCountMetric() {
        return new MetricDefinition(
                BROWSE_DEFINITION_ID, MetadataFixtures.memberVersion(), SubjectKind.MEMBER,
                "Product Browse Count", SemanticDataType.INTEGER, null,
                List.of("浏览商品次数", "浏览过", "浏览次数"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.LESS_OR_EQUAL, ComparisonOperator.BETWEEN),
                AggregateFunction.COUNT, "member.product_browses",
                "product_browse.occurred_at", EventTimeSemantics.EVENT_TIME,
                List.of(new MetricFilterDimension("product_browse.product_name",
                        List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                        FilterDomain.PRODUCT, FilterIdentifierKind.NAME)),
                MetadataFilter.empty(), RefundTreatment.IGNORE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED,
                        TimeWindowKind.RELATIVE_TO_ATTRIBUTE, TimeWindowKind.RELATIVE_TO_EVENT,
                        TimeWindowKind.SELECTED_EVENT),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED, AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of(), List.of(), null);
    }

    private static MetricDefinition favoriteCountMetric() {
        return new MetricDefinition(
                FAVORITE_DEFINITION_ID, MetadataFixtures.memberVersion(), SubjectKind.MEMBER,
                "Product Favorite Count", SemanticDataType.INTEGER, null,
                List.of("收藏商品次数", "收藏过", "收藏次数"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.LESS_OR_EQUAL, ComparisonOperator.BETWEEN),
                AggregateFunction.COUNT, "member.product_favorites",
                "product_favorite.occurred_at", EventTimeSemantics.EVENT_TIME,
                List.of(new MetricFilterDimension("product_favorite.product_name",
                        List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                        FilterDomain.PRODUCT, FilterIdentifierKind.NAME)),
                MetadataFilter.empty(), RefundTreatment.IGNORE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED,
                        TimeWindowKind.RELATIVE_TO_ATTRIBUTE, TimeWindowKind.RELATIVE_TO_EVENT,
                        TimeWindowKind.SELECTED_EVENT),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED, AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of(), List.of(), null);
    }

    private static MetricDefinition positiveOrderCountMetric() {
        return new MetricDefinition(
                ORDER_METRIC_ID, MetadataFixtures.memberVersion(), SubjectKind.MEMBER,
                "Positive Order Count", SemanticDataType.INTEGER, null,
                List.of("下单", "正单数", "有正单", "下过正单"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.LESS_OR_EQUAL, ComparisonOperator.BETWEEN),
                AggregateFunction.COUNT, "member.orders",
                "order.paid_at", EventTimeSemantics.EVENT_TIME,
                List.of(),
                new MetadataFilter(List.of(
                        new com.company.campaign.metadata.domain.MetadataFilterEntry(
                                "order.order_type", List.of("POSITIVE")))),
                RefundTreatment.IGNORE, CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME,
                        TimeWindowKind.FIXED, TimeWindowKind.RELATIVE_TO_ATTRIBUTE),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED, AggregateEmptySet.zero(SemanticDataType.INTEGER),
                List.of(), List.of(), null);
    }

    private static RelationDefinition browseRelation() {
        return new RelationDefinition(
                "member.product_browses", "Product Browses",
                List.of("浏览记录", "商品浏览", "浏览过商品"),
                SubjectKind.MEMBER, MetadataFixtures.memberVersion(),
                SubjectKind.EVENT, new com.company.campaign.metadata.domain.EntityVersionRef(
                        SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.10")),
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "member.id", "product_browse.member_id"),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME,
                        "product_browse.occurred_at"),
                NullSemantics.EXCLUDED,
                com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                List.of("浏览行为"), List.of("订单购买"), null);
    }

    private static RelationDefinition favoriteRelation() {
        return new RelationDefinition(
                "member.product_favorites", "Product Favorites",
                List.of("收藏记录", "商品收藏", "收藏过商品"),
                SubjectKind.MEMBER, MetadataFixtures.memberVersion(),
                SubjectKind.EVENT, new com.company.campaign.metadata.domain.EntityVersionRef(
                        SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.11")),
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "member.id", "product_favorite.member_id"),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME,
                        "product_favorite.occurred_at"),
                NullSemantics.EXCLUDED,
                com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                List.of("收藏行为", "加收藏"), List.of("订单购买", "浏览"), null);
    }

    private static StringSemantics stringSemantics() {
        return StringSemantics.localeNeutral(StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC);
    }

    private static AttributeDefinition productBrowseNameAttribute() {
        return new AttributeDefinition(
                "product_browse.product_name",
                new EntityVersionRef(SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.10")),
                SubjectKind.EVENT,
                "浏览商品名称",
                SemanticDataType.STRING,
                stringSemantics(),
                List.of("浏览商品"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                "浏览行为商品名称");
    }

    private static AttributeDefinition productBrowseOccurredAtAttribute() {
        return new AttributeDefinition(
                "product_browse.occurred_at",
                new EntityVersionRef(SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.10")),
                SubjectKind.EVENT,
                "浏览行为发生时间",
                SemanticDataType.TIMESTAMP,
                null,
                List.of("浏览时间", "浏览发生时间"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL,
                        ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL,
                        ComparisonOperator.BETWEEN),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                "浏览行为发生时刻");
    }

    private static AttributeDefinition productFavoriteNameAttribute() {
        return new AttributeDefinition(
                "product_favorite.product_name",
                new EntityVersionRef(SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.11")),
                SubjectKind.EVENT,
                "收藏商品名称",
                SemanticDataType.STRING,
                stringSemantics(),
                List.of("收藏商品"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.IN),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                "收藏行为商品名称");
    }

    private static AttributeDefinition productFavoriteOccurredAtAttribute() {
        return new AttributeDefinition(
                "product_favorite.occurred_at",
                new EntityVersionRef(SubjectKind.EVENT,
                        com.company.campaign.contracts.SchemaVersion.parse("1.11")),
                SubjectKind.EVENT,
                "收藏行为发生时间",
                SemanticDataType.TIMESTAMP,
                null,
                List.of("收藏时间", "收藏发生时间"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL,
                        ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL,
                        ComparisonOperator.BETWEEN),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                "收藏行为发生时刻");
    }

    private static MetadataSnapshot browseAndFavoriteSnapshot() {
        return MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute(),
                        productBrowseNameAttribute(), productBrowseOccurredAtAttribute(),
                        productFavoriteNameAttribute(), productFavoriteOccurredAtAttribute()),
                List.of(browseCountMetric(), favoriteCountMetric(), positiveOrderCountMetric()),
                List.of(MetadataFixtures.ordersRelation(), browseRelation(), favoriteRelation()));
    }

    private static Map<String, String> browseAndFavoriteConceptMappings() {
        return Map.of(
                BROWSE_CONCEPT, BROWSE_DEFINITION_ID,
                FAVORITE_CONCEPT, FAVORITE_DEFINITION_ID,
                "POSITIVE_ORDER_COUNT", ORDER_METRIC_ID);
    }

    private static ResolutionPolicy browseFavoritePolicy() {
        return ResolutionPolicy.publish(
                "browse-favorite-resolution/1", null, browseAndFavoriteConceptMappings());
    }

    // ------------------------------------------------------------------ helpers

    private static IntentClause browseClause() {
        return new IntentClause(
                "c1", BROWSE_CONCEPT, MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Rolling(30, IntentWindowUnit.DAY),
                null, AggregateFunction.COUNT, null,
                IntentFixtures.span("最近30天浏览过商品", 0, 9),
                List.of(), List.of());
    }

    private static IntentClause favoriteClause() {
        return new IntentClause(
                "c2", FAVORITE_CONCEPT, MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Rolling(30, IntentWindowUnit.DAY),
                null, AggregateFunction.COUNT, null,
                IntentFixtures.span("最近30天收藏过商品", 0, 9),
                List.of(), List.of());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void browseCountResolvesToProductBrowseCountNeverToOrderMetric() {
        IntentClause clause = browseClause();
        SegmentIntent intent = IntentFixtures.intent(clause);
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        ClauseResolution resolved = result.clause("c1");
        assertThat(resolved.accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(resolved.accepted()).isNotEqualTo(ORDER_METRIC_ID);
        assertThat(resolved.accepted()).doesNotContain("order.");
    }

    @Test
    void favoriteCountResolvesToProductFavoriteCountNeverToOrderMetric() {
        IntentClause clause = favoriteClause();
        SegmentIntent intent = IntentFixtures.intent(clause);
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        ClauseResolution resolved = result.clause("c2");
        assertThat(resolved.accepted()).isEqualTo(FAVORITE_DEFINITION_ID);
        assertThat(resolved.accepted()).isNotEqualTo(ORDER_METRIC_ID);
        assertThat(resolved.accepted()).doesNotContain("order.");
    }

    @Test
    void browseAndFavoriteAreDistinctDefinitions() {
        assertThat(BROWSE_DEFINITION_ID).isNotEqualTo(FAVORITE_DEFINITION_ID);

        IntentClause browse = browseClause();
        IntentClause favorite = favoriteClause();
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult browseResult = resolver.resolve(IntentFixtures.intent(browse));
        ResolutionResult favoriteResult = resolver.resolve(IntentFixtures.intent(favorite));

        assertThat(browseResult.clause("c1").accepted())
                .isNotEqualTo(favoriteResult.clause("c2").accepted());
    }

    @Test
    void orGroupOfBrowseAndFavoriteRemainsStructurallyCorrect() {
        IntentClause browse = browseClause();
        IntentClause favorite = favoriteClause();
        LogicalExpression orExpression = new LogicalExpression.Group(LogicalOperator.ANY, List.of(
                new LogicalExpression.Clause("c1"),
                new LogicalExpression.Clause("c2")));
        SegmentIntent intent = new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT, SubjectKind.MEMBER,
                List.of(browse, favorite), orExpression, List.of(), List.of());
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.clause("c1").accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(result.clause("c2").accepted()).isEqualTo(FAVORITE_DEFINITION_ID);
    }

    @Test
    void andGroupWithWechatBindingAndConsentIsStructurallyCorrect() {
        IntentClause browse = browseClause();
        IntentClause favorite = favoriteClause();
        IntentClause wechatBound = new IntentClause(
                "c3", "WECHAT_BINDING", MetadataKind.ATTRIBUTE, false,
                ComparisonOperator.EQUAL, new IntentValue.BooleanValue(true),
                null, null, null, null,
                IntentFixtures.span("微信已绑定", 0, 5), List.of(), List.of());
        IntentClause consentGranted = new IntentClause(
                "c4", "CONSENT_GRANTED", MetadataKind.ATTRIBUTE, false,
                ComparisonOperator.EQUAL, new IntentValue.BooleanValue(true),
                null, null, null, null,
                IntentFixtures.span("同意微信触达", 0, 6), List.of(), List.of());

        LogicalExpression expression = new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                new LogicalExpression.Group(LogicalOperator.ANY, List.of(
                        new LogicalExpression.Clause("c1"),
                        new LogicalExpression.Clause("c2"))),
                new LogicalExpression.Clause("c3"),
                new LogicalExpression.Clause("c4")));
        SegmentIntent intent = new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT, SubjectKind.MEMBER,
                List.of(browse, favorite, wechatBound, consentGranted),
                expression, List.of(), List.of());
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.clause("c1").accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(result.clause("c2").accepted()).isEqualTo(FAVORITE_DEFINITION_ID);
        assertThat(result.clause("c1").accepted()).isNotEqualTo(ORDER_METRIC_ID);
        assertThat(result.clause("c2").accepted()).isNotEqualTo(ORDER_METRIC_ID);
        assertThat(result.clauses()).hasSize(4);
    }

    @Test
    void noPositiveOrderMetricIsSubstitutedForBrowseOrFavorite() {
        IntentClause browse = browseClause();
        SegmentIntent intent = IntentFixtures.intent(browse);
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.clause("c1").accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(result.clause("c1").accepted()).isNotEqualTo(ORDER_METRIC_ID);
    }

    @Test
    void missingMetadataDefinitionReturnsCorrectQuestionCode() {
        IntentClause unknownClause = new IntentClause(
                "c1", "UNREGISTERED_CONCEPT", MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Rolling(30, IntentWindowUnit.DAY),
                null, AggregateFunction.COUNT, null,
                IntentFixtures.span("未知概念", 0, 4),
                List.of(), List.of());
        SegmentIntent intent = new SegmentIntent(
                com.company.campaign.contracts.ContractVersions.CURRENT, SubjectKind.MEMBER,
                List.of(unknownClause),
                new LogicalExpression.Clause("c1"),
                List.of(IntentAmbiguity.MISSING_METADATA_DEFINITION),
                List.of());
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clarification()).isNotNull();
        assertThat(result.clarification().questionCode())
                .isEqualTo(QuestionCode.MISSING_METADATA_DEFINITION);
    }

    @Test
    void missingMetadataDefinitionCodeIsStable() {
        assertThat(QuestionCode.MISSING_METADATA_DEFINITION).isNotNull();
        assertThat(QuestionCode.MISSING_METADATA_DEFINITION.name())
                .isEqualTo("MISSING_METADATA_DEFINITION");
        assertThat(QuestionCode.MISSING_METADATA_DEFINITION.isDefinitionSelection()).isFalse();
        assertThat(QuestionCode.forAmbiguity(IntentAmbiguity.MISSING_METADATA_DEFINITION))
                .isEqualTo(QuestionCode.MISSING_METADATA_DEFINITION);
    }

    @Test
    void browseClauseWithProductNameScopeFilterResolvesCorrectly() {
        IntentScopeFilter scopeFilter = new IntentScopeFilter(
                "f1", "PRODUCT_NAME",
                com.company.campaign.metadata.domain.FilterDomain.PRODUCT,
                com.company.campaign.metadata.domain.FilterIdentifierKind.NAME,
                ComparisonOperator.EQUAL,
                new IntentValue.StringValue("cushioned-shoe"),
                IntentFixtures.span("cushioned-shoe", 0, 14),
                List.of("product_browse.product_name"), List.of());
        IntentClause clause = new IntentClause(
                "c1", BROWSE_CONCEPT, MetadataKind.METRIC, false,
                ComparisonOperator.GREATER_THAN, new IntentValue.IntegerValue("0"),
                new IntentWindow.Rolling(30, IntentWindowUnit.DAY),
                null, AggregateFunction.COUNT, null,
                List.of(scopeFilter),
                IntentFixtures.span("最近30天浏览过cushioned-shoe", 0, 18),
                List.of(), List.of());
        SegmentIntent intent = IntentFixtures.intent(clause);
        IntentResolver resolver = new IntentResolver(
                browseAndFavoriteSnapshot(), browseFavoritePolicy(), ResolutionFixtures.registry());

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.clause("c1").accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(result.clause("c1").accepted()).isNotEqualTo(ORDER_METRIC_ID);
        assertThat(result.clause("c1").scopeFilters()).isNotEmpty();
    }

    // ------------------------------------------------------------------ profile & storage assertions

    @Test
    void productionProfileContainsBrowseAndFavoriteDefinitions() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();

        var browseMetric = snapshot.metrics().stream()
                .filter(m -> m.id().equals(BROWSE_DEFINITION_ID))
                .findFirst();
        assertThat(browseMetric).as("product.browse_count must be in production snapshot").isPresent();
        assertThat(browseMetric.get().aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(browseMetric.get().resultType()).isEqualTo(SemanticDataType.INTEGER);
        assertThat(browseMetric.get().supportedWindows()).contains(
                TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME);

        var favoriteMetric = snapshot.metrics().stream()
                .filter(m -> m.id().equals(FAVORITE_DEFINITION_ID))
                .findFirst();
        assertThat(favoriteMetric).as("product.favorite_count must be in production snapshot").isPresent();
        assertThat(favoriteMetric.get().aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(favoriteMetric.get().resultType()).isEqualTo(SemanticDataType.INTEGER);

        var browseRelation = snapshot.relations().stream()
                .filter(r -> r.id().equals("member.product_browses"))
                .findFirst();
        assertThat(browseRelation).as("member.product_browses must be in production snapshot").isPresent();

        var favoriteRelation = snapshot.relations().stream()
                .filter(r -> r.id().equals("member.product_favorites"))
                .findFirst();
        assertThat(favoriteRelation).as("member.product_favorites must be in production snapshot").isPresent();
    }

    @Test
    void browseAndFavoriteAreSemanticallyDistinctInProductionProfile() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();

        var browseMetric = snapshot.metrics().stream()
                .filter(m -> m.id().equals(BROWSE_DEFINITION_ID))
                .findFirst().orElseThrow();
        var favoriteMetric = snapshot.metrics().stream()
                .filter(m -> m.id().equals(FAVORITE_DEFINITION_ID))
                .findFirst().orElseThrow();

        assertThat(browseMetric.id()).isNotEqualTo(favoriteMetric.id());
        assertThat(browseMetric.relationId()).isEqualTo("member.product_browses");
        assertThat(favoriteMetric.relationId()).isEqualTo("member.product_favorites");
        assertThat(browseMetric.relationId()).isNotEqualTo(favoriteMetric.relationId());

        // Neither maps to an order metric.
        assertThat(browseMetric.relationId()).doesNotContain("order");
        assertThat(favoriteMetric.relationId()).doesNotContain("order");
    }

    @Test
    void productionResolutionPolicyMapsBrowseAndFavoriteConcepts() {
        var policy = CoreLoyaltyDevelopmentProfile.resolutionPolicy();

        assertThat(policy.conceptDefinitionIds())
                .containsEntry(BROWSE_CONCEPT, BROWSE_DEFINITION_ID)
                .containsEntry(FAVORITE_CONCEPT, FAVORITE_DEFINITION_ID);

        // Browse and favorite concepts specifically map to their non-order definitions.
        assertThat(policy.conceptDefinitionIds().get(BROWSE_CONCEPT))
                .isEqualTo(BROWSE_DEFINITION_ID)
                .isNotEqualTo(ORDER_METRIC_ID);
        assertThat(policy.conceptDefinitionIds().get(FAVORITE_CONCEPT))
                .isEqualTo(FAVORITE_DEFINITION_ID)
                .isNotEqualTo(ORDER_METRIC_ID);
    }

    @Test
    void productionStorageCatalogContainsBrowseAndFavoriteMappings() {
        var storage = CoreLoyaltyDevelopmentProfile.storageCatalog();

        // Entity tables.
        var entities = storage.entities().stream()
                .map(e -> e.table().table().value())
                .collect(Collectors.toSet());
        assertThat(entities).contains("seg_product_browse", "seg_product_favorite");

        // Metric storage definitions.
        var storedMetricIds = storage.metrics().stream()
                .map(m -> m.definitionId())
                .collect(Collectors.toSet());
        assertThat(storedMetricIds).contains(BROWSE_DEFINITION_ID, FAVORITE_DEFINITION_ID);

        // Relation storage definitions.
        var storedRelationIds = storage.relations().stream()
                .map(r -> r.relationId())
                .collect(Collectors.toSet());
        assertThat(storedRelationIds).contains("member.product_browses", "member.product_favorites");

        // Attribute storage definitions.
        var storedAttrIds = storage.attributes().stream()
                .map(a -> a.definitionId())
                .collect(Collectors.toSet());
        assertThat(storedAttrIds).contains(
                "product_browse.product_name", "product_browse.occurred_at",
                "product_favorite.product_name", "product_favorite.occurred_at");
    }

    @Test
    void productionResolutionWithActualProfileResolvesBrowseCorrectly() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();
        var policy = CoreLoyaltyDevelopmentProfile.resolutionPolicy();
        var registry = ResolutionFixtures.registry();

        IntentClause clause = browseClause();
        SegmentIntent intent = IntentFixtures.intent(clause);
        IntentResolver resolver = new IntentResolver(snapshot, policy, registry);

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.clause("c1").accepted()).isEqualTo(BROWSE_DEFINITION_ID);
        assertThat(result.clause("c1").accepted()).isNotEqualTo(ORDER_METRIC_ID);
    }

    @Test
    void productionResolutionWithActualProfileResolvesFavoriteCorrectly() {
        var snapshot = CoreLoyaltyDevelopmentProfile.snapshot();
        var policy = CoreLoyaltyDevelopmentProfile.resolutionPolicy();
        var registry = ResolutionFixtures.registry();

        IntentClause clause = favoriteClause();
        SegmentIntent intent = IntentFixtures.intent(clause);
        IntentResolver resolver = new IntentResolver(snapshot, policy, registry);

        ResolutionResult result = resolver.resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.clause("c2").accepted()).isEqualTo(FAVORITE_DEFINITION_ID);
        assertThat(result.clause("c2").accepted()).isNotEqualTo(ORDER_METRIC_ID);
    }
}