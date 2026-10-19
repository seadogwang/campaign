package com.company.campaign.metadata.testsupport;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
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
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.CampaignPermissions;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic metadata fixtures. No database, network, wall clock or random identifier is used.
 */
public final class MetadataFixtures {

    public static final String TENANT = "tenant-001";
    public static final String PROGRAM = "program-001";

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    private MetadataFixtures() {
    }

    public static TrustedExecutionContext context(Set<CampaignPermission> permissions) {
        return context(TENANT, PROGRAM, permissions);
    }

    public static TrustedExecutionContext context(String tenantId, String programId, Set<CampaignPermission> permissions) {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity("user-001", "https://issuer.example", AUTHENTICATED_AT),
                new PrincipalMembership(
                        "membership-1", "user-001", tenantId, programId, "scope-008",
                        MembershipStatus.ACTIVE, permissions),
                new TrustedExecutionContextRef(
                        tenantId, programId, "user-001", "scope-008", "metadata-17",
                        LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                        ZoneId.of("Asia/Shanghai"),
                        "CNY",
                        AS_OF,
                        WATERMARK));
    }

    public static EntityVersionRef memberVersion() {
        return new EntityVersionRef(SubjectKind.MEMBER, MetadataSchemaVersions.SUPPORTED);
    }

    public static EntityVersionRef orderVersion() {
        return new EntityVersionRef(SubjectKind.ORDER, MetadataSchemaVersions.SUPPORTED);
    }

    /** The deterministic string semantics used by every STRING fixture: locale-neutral, NFKC, case-insensitive. */
    public static StringSemantics stringSemantics() {
        return StringSemantics.localeNeutral(StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC);
    }

    public static RelationDefinition ordersRelation() {
        return new RelationDefinition(
                "member.orders",
                "Member Orders",
                List.of("orders", "订单"),
                SubjectKind.MEMBER,
                memberVersion(),
                SubjectKind.ORDER,
                orderVersion(),
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id"),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "order.paid_at"),
                NullSemantics.EXCLUDED,
                com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                List.of("member orders"),
                List.of("member profile"),
                "Atomic relation only; composition belongs to the planner.");
    }

    public static AttributeDefinition tierAttribute() {
        return new AttributeDefinition(
                "member.tier",
                memberVersion(),
                SubjectKind.MEMBER,
                "Member Tier",
                SemanticDataType.ENUM,
                null,
                List.of("会员等级", "卡级"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(
                        new EnumValueDefinition("GOLD", List.of("Gold", "金卡")),
                        new EnumValueDefinition("SILVER", List.of("Silver"))),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of("Gold会员"),
                List.of("高价值会员"),
                null);
    }

    public static AttributeDefinition snapshotAttribute(String id, String displayName) {
        return new AttributeDefinition(
                id,
                memberVersion(),
                SubjectKind.MEMBER,
                displayName,
                SemanticDataType.STRING,
                stringSemantics(),
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
    }

    public static AttributeDefinition moneyAttribute(
            String id, String displayName, CurrencySemantics currencySemantics) {
        return new AttributeDefinition(
                id,
                memberVersion(),
                SubjectKind.MEMBER,
                displayName,
                SemanticDataType.MONEY,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                currencySemantics,
                List.of(),
                List.of(),
                null);
    }

    /** An ordered-type attribute with ordering operators, for operator/type compatibility tests. */
    public static AttributeDefinition orderedAttribute(
            String id, String displayName, SemanticDataType dataType) {
        return new AttributeDefinition(
                id,
                memberVersion(),
                SubjectKind.MEMBER,
                displayName,
                dataType,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                dataType == SemanticDataType.MONEY
                        ? CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY
                        : CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
    }

    public static MetricDefinition textMinMetric() {
        return new MetricDefinition(
                "order.latest_channel",
                memberVersion(),
                SubjectKind.MEMBER,
                "Latest Order Channel",
                SemanticDataType.STRING,
                stringSemantics(),
                List.of("最近下单渠道"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.MIN,
                "member.orders",
                "order.paid_at",
                EventTimeSemantics.EVENT_TIME,
                List.of(),
                MetadataFilter.empty(),
                RefundTreatment.IGNORE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(TimeWindowKind.LIFETIME),
                WindowBoundarySemantics.NOT_APPLICABLE,
                NullSemantics.UNKNOWN_VALUE,
                AggregateEmptySet.nullValue(),
                List.of("最近下单渠道"),
                List.of("最近消费金额"),
                null);
    }

    public static MetricDefinition netPaidAmountMetric() {
        return new MetricDefinition(
                "order.net_paid_amount",
                memberVersion(),
                SubjectKind.MEMBER,
                "Net Paid Amount",
                SemanticDataType.MONEY,
                null,
                List.of("消费", "净消费金额"),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.LESS_OR_EQUAL, ComparisonOperator.IS_NULL,
                        ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.SUM,
                "member.orders",
                "order.paid_at",
                EventTimeSemantics.EVENT_TIME,
                List.of(),
                new MetadataFilter(List.of(
                        new MetadataFilterEntry("status", List.of("PAID", "COMPLETED")),
                        new MetadataFilterEntry("orderType", List.of("PURCHASE")))),
                RefundTreatment.SUBTRACT,
                CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                NullSemantics.EXCLUDED,
                AggregateEmptySet.zero(SemanticDataType.MONEY),
                List.of("净消费金额"),
                List.of("总订单金额"),
                "Do not use for gross order amount or single-order amount.");
    }

    /** The standard relation with its event-time capability explicitly removed. */
    public static RelationDefinition ordersRelationWithoutEventTime() {
        return new RelationDefinition(
                "member.orders",
                "Member Orders",
                List.of("orders", "订单"),
                SubjectKind.MEMBER,
                memberVersion(),
                SubjectKind.ORDER,
                orderVersion(),
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id"),
                null,
                NullSemantics.EXCLUDED,
                com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                List.of("member orders"),
                List.of("member profile"),
                "Atomic relation only; composition belongs to the planner.");
    }

    /**
     * Builds a metric over the standard relation with one aggregate/result/empty-set combination.
     *
     * <p>Used by the aggregate empty-set matrix. A bounded window pins the standard relation's event-time
     * field; a {@code LIFETIME}-only metric omits event time structurally.
     */
    public static MetricDefinition aggregateMetric(
            String id,
            SemanticDataType resultType,
            AggregateFunction aggregation,
            AggregateEmptySet emptySet,
            List<TimeWindowKind> windows) {
        boolean bounded = windows.stream().anyMatch(TimeWindowKind::isBounded);
        return new MetricDefinition(
                id,
                memberVersion(),
                SubjectKind.MEMBER,
                "Aggregate Metric " + id,
                resultType,
                resultType == SemanticDataType.STRING ? stringSemantics() : null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                aggregation,
                "member.orders",
                bounded ? "order.paid_at" : null,
                bounded ? EventTimeSemantics.EVENT_TIME : null,
                List.of(),
                MetadataFilter.empty(),
                RefundTreatment.IGNORE,
                resultType == SemanticDataType.MONEY
                        ? CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY
                        : CurrencySemantics.NOT_APPLICABLE,
                windows,
                bounded
                        ? WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END
                        : WindowBoundarySemantics.NOT_APPLICABLE,
                NullSemantics.UNKNOWN_VALUE,
                emptySet,
                List.of(),
                List.of(),
                null);
    }

    public static MetadataSnapshot snapshot(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return snapshot("metadata-17", "1", TENANT, PROGRAM, attributes, metrics, relations);
    }

    public static MetadataSnapshot snapshot(
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                snapshotId,
                snapshotVersion,
                tenantId,
                programId,
                attributes,
                metrics,
                relations);
    }

    public static MetadataSnapshot standardSnapshot() {
        return snapshot("metadata-17", "1", TENANT, PROGRAM,
                List.of(tierAttribute()), List.of(netPaidAmountMetric()), List.of(ordersRelation()));
    }

    public static Set<CampaignPermission> readPermissions() {
        return Set.of(CampaignPermission.CAMPAIGN_READ);
    }

    public static Set<CampaignPermission> allPermissions() {
        return CampaignPermissions.minimum();
    }

    public static SchemaVersion schemaVersion() {
        return MetadataSchemaVersions.SUPPORTED;
    }
}