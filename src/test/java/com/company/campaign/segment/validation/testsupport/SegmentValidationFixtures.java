package com.company.campaign.segment.validation.testsupport;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataFilter;
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
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.contracts.Issue;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.security.authorization.DataScopePolicy;
import com.company.campaign.security.authorization.FailClosedAuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.List;
import java.util.Set;

/**
 * Deterministic T07 fixtures built only from accepted T02/T04/T05 public contracts.
 *
 * <p>No clock, network, database, random identifier or T06 corpus asset is touched.
 */
public final class SegmentValidationFixtures {

    public static final String SNAPSHOT_ID = "metadata-17";
    public static final String SNAPSHOT_VERSION = "1";

    private SegmentValidationFixtures() {
    }

    // ------------------------------------------------------------------ trusted context

    public static TrustedExecutionContext context() {
        return MetadataFixtures.context(MetadataFixtures.allPermissions());
    }

    public static TrustedExecutionContext context(Set<CampaignPermission> permissions) {
        return MetadataFixtures.context(permissions);
    }

    public static TrustedExecutionContext readOnlyContext() {
        return MetadataFixtures.context(Set.of(CampaignPermission.CAMPAIGN_READ));
    }

    public static TrustedExecutionContext noPermissionContext() {
        return MetadataFixtures.context(Set.of(CampaignPermission.CAMPAIGN_EDIT));
    }

    public static TrustedExecutionContext foreignOwnerContext() {
        return MetadataFixtures.context("tenant-999", "program-001", MetadataFixtures.allPermissions());
    }

    // ------------------------------------------------------------------ authorization services

    public static AuthorizationService allowAll() {
        return new FailClosedAuthorizationService(allowAllDataScope());
    }

    public static DataScopePolicy allowAllDataScope() {
        return query -> DataScopeDecision.allowed();
    }

    public static AuthorizationService denyAll() {
        return decision(AuthorizationDisposition.PERMISSION_DENIED, AuthorizationReason.MISSING_PERMISSION);
    }

    public static AuthorizationService notFound() {
        return decision(AuthorizationDisposition.NOT_FOUND, AuthorizationReason.UNKNOWN_RESOURCE);
    }

    public static AuthorizationService dataScopeDenied() {
        return decision(AuthorizationDisposition.DATASCOPE_DENIED, AuthorizationReason.DATASCOPE_DENIED);
    }

    public static AuthorizationService dataScopeUnavailable() {
        return decision(AuthorizationDisposition.DATASCOPE_UNAVAILABLE, AuthorizationReason.DATASCOPE_UNAVAILABLE);
    }

    public static AuthorizationService invalidRequest() {
        return decision(AuthorizationDisposition.INVALID_REQUEST, AuthorizationReason.INVALID_REQUEST);
    }

    public static AuthorizationService throwing() {
        return request -> {
            throw new IllegalStateException("boundary failure with secret-like text");
        };
    }

    private static AuthorizationService decision(
            AuthorizationDisposition disposition, AuthorizationReason reason) {
        return request -> AuthorizationDecision.deny(disposition, reason, ResourceKind.ATTRIBUTE, null);
    }

    // ------------------------------------------------------------------ validator and policy

    public static SegmentValidator validator() {
        return new SegmentValidator(allowAll());
    }

    public static SegmentValidationPolicy policy() {
        return SegmentValidationPolicy.standard();
    }

    // ------------------------------------------------------------------ snapshot

    public static MetadataSnapshot snapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                attributes(),
                metrics(),
                relations());
    }

    public static MetadataSnapshot snapshot(String snapshotId, String snapshotVersion) {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                snapshotId,
                snapshotVersion,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                attributes(),
                metrics(),
                relations());
    }

    public static MetadataBinding binding() {
        return MetadataBinding.fromSnapshot(snapshot());
    }

    public static MetadataBinding binding(String snapshotId) {
        return binding(snapshotId, SNAPSHOT_VERSION);
    }

    public static MetadataBinding binding(String snapshotId, String snapshotVersion) {
        return new MetadataBinding(
                snapshotId, snapshotVersion, MetadataSchemaVersions.SUPPORTED, snapshot().contentHash());
    }

    public static List<AttributeDefinition> attributes() {
        return List.of(
                tierAttribute(),
                nameAttribute(),
                balanceAttribute(),
                ageAttribute(),
                scoreAttribute(),
                joinedAttribute(),
                lastSeenAttribute(),
                flagAttribute(),
                orderAmountAttribute(),
                orderChannelAttribute());
    }

    public static List<MetricDefinition> metrics() {
        return List.of(
                netPaidAmountMetric(),
                latestChannelMetric(),
                countMetric(),
                avgAmountMetric(),
                minAmountMetric(),
                maxAmountMetric());
    }

    public static List<RelationDefinition> relations() {
        return List.of(ordersRelation(), ordersRelationWithoutEventTime(), orderLinesRelation());
    }

    private static StringSemantics stringSemantics() {
        return StringSemantics.localeNeutral(StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC);
    }

    public static AttributeDefinition tierAttribute() {
        return new AttributeDefinition(
                "member.tier", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Tier",
                SemanticDataType.ENUM, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(new EnumValueDefinition("GOLD", List.of()), new EnumValueDefinition("SILVER", List.of())),
                NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition nameAttribute() {
        return new AttributeDefinition(
                "member.name", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Name",
                SemanticDataType.STRING, stringSemantics(), List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.CONTAINS,
                        ComparisonOperator.NOT_CONTAINS, ComparisonOperator.STARTS_WITH, ComparisonOperator.ENDS_WITH,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition balanceAttribute() {
        return new AttributeDefinition(
                "member.balance", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Balance",
                SemanticDataType.MONEY, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.GREATER_THAN, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.BETWEEN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(), NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, List.of(), List.of(), null);
    }

    public static AttributeDefinition ageAttribute() {
        return new AttributeDefinition(
                "member.age", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Age",
                SemanticDataType.INTEGER, null, List.of(), orderedOperators(),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition scoreAttribute() {
        return new AttributeDefinition(
                "member.score", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Score",
                SemanticDataType.DECIMAL, null, List.of(), orderedOperators(),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition joinedAttribute() {
        return new AttributeDefinition(
                "member.joined_on", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Joined On",
                SemanticDataType.DATE, null, List.of(), orderedOperators(),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition lastSeenAttribute() {
        return new AttributeDefinition(
                "member.last_seen", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Last Seen",
                SemanticDataType.TIMESTAMP, null, List.of(), orderedOperators(),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition flagAttribute() {
        return new AttributeDefinition(
                "member.flag", MetadataFixtures.memberVersion(), SubjectKind.MEMBER, "Member Flag",
                SemanticDataType.BOOLEAN, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition orderAmountAttribute() {
        return new AttributeDefinition(
                "order.amount", MetadataFixtures.orderVersion(), SubjectKind.ORDER, "Order Amount",
                SemanticDataType.MONEY, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.LESS_THAN, ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(), NullSemantics.UNKNOWN_VALUE, CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                List.of(), List.of(), null);
    }

    public static AttributeDefinition orderChannelAttribute() {
        return new AttributeDefinition(
                "order.channel", MetadataFixtures.orderVersion(), SubjectKind.ORDER, "Order Channel",
                SemanticDataType.ENUM, null, List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(new EnumValueDefinition("ONLINE", List.of()), new EnumValueDefinition("STORE", List.of())),
                NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                List.of(), List.of(), null);
    }

    private static List<ComparisonOperator> stringOperators() {
        return List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.CONTAINS,
                ComparisonOperator.NOT_CONTAINS, ComparisonOperator.STARTS_WITH, ComparisonOperator.ENDS_WITH,
                ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL);
    }

    private static List<ComparisonOperator> orderedOperators() {
        return List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.GREATER_THAN,
                ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL,
                ComparisonOperator.IN, ComparisonOperator.NOT_IN, ComparisonOperator.BETWEEN,
                ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL);
    }

    public static RelationDefinition ordersRelation() {
        return relation("member.orders", SubjectKind.MEMBER, MetadataFixtures.memberVersion(),
                SubjectKind.ORDER, MetadataFixtures.orderVersion(),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "order.paid_at"), "Member Orders");
    }

    public static RelationDefinition orderLinesRelation() {
        return relation("order.lines", SubjectKind.ORDER, MetadataFixtures.orderVersion(),
                SubjectKind.ORDER, MetadataFixtures.orderVersion(),
                new RelationEventTimeCapability(EventTimeSemantics.EVENT_TIME, "order.line_at"), "Order Lines");
    }

    /** The standard relation with its event-time capability explicitly removed. */
    public static RelationDefinition ordersRelationWithoutEventTime() {
        return relation("member.orders_no_time", SubjectKind.MEMBER, MetadataFixtures.memberVersion(),
                SubjectKind.ORDER, MetadataFixtures.orderVersion(), null, "Member Orders No Time");
    }

    private static RelationDefinition relation(
            String id,
            SubjectKind source,
            EntityVersionRef sourceVersion,
            SubjectKind target,
            EntityVersionRef targetVersion,
            RelationEventTimeCapability capability,
            String displayName) {
        return new RelationDefinition(
                id, displayName, List.of(), source, sourceVersion, target, targetVersion,
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "source.id", "target.parent_id"),
                capability, NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                List.of(), List.of(), null);
    }

    // ------------------------------------------------------------------ metrics

    private static MetricDefinition metric(
            String id,
            String displayName,
            SemanticDataType resultType,
            AggregateFunction aggregation,
            List<TimeWindowKind> windows,
            AggregateEmptySet emptySet) {
        boolean bounded = windows.stream().anyMatch(TimeWindowKind::isBounded);
        return new MetricDefinition(
                id, MetadataFixtures.memberVersion(), SubjectKind.MEMBER, displayName, resultType,
                resultType == SemanticDataType.STRING ? stringSemantics() : null,
                List.of(), resultType == SemanticDataType.STRING ? stringOperators() : orderedOperators(),
                aggregation, "member.orders",
                bounded ? "order.paid_at" : null,
                bounded ? EventTimeSemantics.EVENT_TIME : null,
                MetadataFilter.empty(), RefundTreatment.IGNORE,
                resultType == SemanticDataType.MONEY
                        ? CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY
                        : CurrencySemantics.NOT_APPLICABLE,
                windows,
                bounded ? WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END
                        : WindowBoundarySemantics.NOT_APPLICABLE,
                NullSemantics.UNKNOWN_VALUE, emptySet, List.of(), List.of(), null);
    }

    public static MetricDefinition netPaidAmountMetric() {
        return metric("order.net_paid_amount", "Net Paid Amount", SemanticDataType.MONEY,
                AggregateFunction.SUM, List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED),
                AggregateEmptySet.zero(SemanticDataType.MONEY));
    }

    public static MetricDefinition countMetric() {
        return metric("order.count", "Order Count", SemanticDataType.INTEGER,
                AggregateFunction.COUNT, List.of(TimeWindowKind.LIFETIME),
                AggregateEmptySet.zero(SemanticDataType.INTEGER));
    }

    public static MetricDefinition avgAmountMetric() {
        return metric("order.avg_amount", "Average Amount", SemanticDataType.MONEY,
                AggregateFunction.AVG, List.of(TimeWindowKind.LIFETIME), AggregateEmptySet.nullValue());
    }

    public static MetricDefinition minAmountMetric() {
        return metric("order.min_amount", "Minimum Amount", SemanticDataType.MONEY,
                AggregateFunction.MIN, List.of(TimeWindowKind.LIFETIME), AggregateEmptySet.nullValue());
    }

    public static MetricDefinition maxAmountMetric() {
        return metric("order.max_amount", "Maximum Amount", SemanticDataType.MONEY,
                AggregateFunction.MAX, List.of(TimeWindowKind.LIFETIME), AggregateEmptySet.nullValue());
    }

    public static MetricDefinition latestChannelMetric() {
        return metric("order.latest_channel", "Latest Channel", SemanticDataType.STRING,
                AggregateFunction.MIN, List.of(TimeWindowKind.LIFETIME), AggregateEmptySet.nullValue());
    }

    // ------------------------------------------------------------------ documents

    public static SegmentDsl publish(Predicate predicate) {
        return SegmentDsl.publish(SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding(), predicate);
    }

    public static SegmentDsl publish(SubjectKind subject, Predicate predicate) {
        return SegmentDsl.publish(SegmentDslSchemaVersions.SUPPORTED, subject, binding(), predicate);
    }

    public static SegmentDsl publish(MetadataBinding binding, Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding, predicate);
    }

    // ------------------------------------------------------------------ predicate builders

    public static AttributePredicate tierEquals(String nodeId, String code) {
        return new AttributePredicate(
                nodeId, "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue(code)));
    }

    public static AttributePredicate attribute(
            String nodeId, String attributeId, ComparisonOperator operator, ConditionValue condition) {
        return new AttributePredicate(nodeId, attributeId, operator, condition);
    }

    public static MetricPredicate metric(
            String nodeId, String metricId, TimeWindow window, ComparisonOperator operator, ConditionValue condition) {
        return new MetricPredicate(nodeId, metricId, window, operator, condition);
    }

    public static GroupPredicate all(String nodeId, List<Predicate> children) {
        return new GroupPredicate(nodeId, PredicateType.ALL, children);
    }

    public static GroupPredicate any(String nodeId, List<Predicate> children) {
        return new GroupPredicate(nodeId, PredicateType.ANY, children);
    }

    public static NotPredicate not(String nodeId, Predicate child) {
        return new NotPredicate(nodeId, child);
    }

    public static ConditionValue value(ScalarValue scalar) {
        return ConditionValue.of(scalar);
    }

    public static ConditionValue list(ScalarValue... items) {
        return ConditionValue.of(new ListValue(List.of(items)));
    }

    public static ConditionValue range(ScalarValue lower, ScalarValue upper, boolean includeLower, boolean includeUpper) {
        return ConditionValue.of(new RangeValue(lower, upper, includeLower, includeUpper));
    }

    /** A small, fully valid document used as the success baseline. */
    public static SegmentDsl validDocument() {
        return publish(all("n-root", List.of(
                tierEquals("n-tier", "GOLD"),
                metric("n-spend", "order.net_paid_amount", new RollingWindow(90, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN, value(new MoneyValue("1000.00", "CNY"))))));
    }

    public static ExistsPredicate exists(
            String nodeId, String relationId, TimeWindow window, Predicate predicate) {
        return new ExistsPredicate(nodeId, relationId, window, predicate);
    }

    public static ScalarValue enumValue(String code) {
        return new EnumValue(code);
    }

    public static ScalarValue stringValue(String text) {
        return new StringValue(text);
    }

    public static ScalarValue integerValue(long value) {
        return new IntegerValue(value);
    }

    public static ScalarValue decimalValue(String value) {
        return new DecimalValue(value);
    }

    public static ScalarValue booleanValue(boolean value) {
        return new BooleanValue(value);
    }

    public static ScalarValue dateValue(String value) {
        return new DateValue(value);
    }

    public static ScalarValue dateTimeValue(String value) {
        return new DateTimeValue(value);
    }

    public static ScalarValue moneyValue(String amount, String currency) {
        return new MoneyValue(amount, currency);
    }

    public static FixedWindow fixedWindow(String start, String end) {
        return new FixedWindow(start, end);
    }

    public static LifetimeWindow lifetime() {
        return new LifetimeWindow();
    }

    public static RollingWindow rolling(int amount, WindowUnit unit) {
        return new RollingWindow(amount, unit);
    }

    public static SchemaVersion dslVersion() {
        return SegmentDslSchemaVersions.SUPPORTED;
    }

    // ------------------------------------------------------------------ validation helpers

    public static SegmentValidationResult validate(Predicate predicate) {
        return validate(publish(predicate), snapshot(), context(), allowAll(), policy());
    }

    public static SegmentValidationResult validate(SegmentDsl document) {
        return validate(document, snapshot(), context(), allowAll(), policy());
    }

    public static SegmentValidationResult validate(
            SegmentDsl document,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            AuthorizationService service,
            SegmentValidationPolicy policy) {
        return new SegmentValidator(service).validate(document, snapshot, context, policy);
    }

    public static List<String> codes(SegmentValidationResult result) {
        return result.issues().stream().map(Issue::code).toList();
    }

    public static byte[] canonicalBytes(SegmentValidationResult result) {
        return new CanonicalSegmentCodec().write(result.canonicalSegment());
    }

    public static String canonicalString(SegmentValidationResult result) {
        return new CanonicalSegmentCodec().writeString(result.canonicalSegment());
    }
}