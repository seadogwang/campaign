package com.company.campaign.query.testsupport;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic T08 fixtures built only from accepted T02/T04/T05/T07 public contracts.
 *
 * <p>No clock, network, database, random identifier or T06 corpus asset is touched.
 */
public final class QueryPlanFixtures {

    public static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    public static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");
    public static final String SNAPSHOT_ID = SegmentValidationFixtures.SNAPSHOT_ID;
    public static final String SNAPSHOT_VERSION = SegmentValidationFixtures.SNAPSHOT_VERSION;

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final SegmentQueryPlanner PLANNER = new SegmentQueryPlanner(SegmentValidationFixtures.allowAll());

    private QueryPlanFixtures() {
    }

    // ------------------------------------------------------------------ planning

    public static SemanticQueryPlan plan(Predicate predicate) {
        return plan(SegmentValidationFixtures.publish(predicate));
    }

    public static SemanticQueryPlan plan(SegmentDsl document) {
        return plan(document, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context());
    }

    public static SemanticQueryPlan plan(SegmentDsl document, MetadataSnapshot snapshot, TrustedExecutionContext context) {
        return PLANNER.plan(validated(document, snapshot, context), snapshot, context);
    }

    public static SemanticQueryPlan plan(SegmentDsl document, MetadataSnapshot snapshot, TrustedExecutionContext context,
            SegmentQueryPlanner planner) {
        return planner.plan(validated(document, snapshot, context), snapshot, context);
    }

    public static SegmentValidationResult validated(
            SegmentDsl document, MetadataSnapshot snapshot, TrustedExecutionContext context) {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                document, snapshot, context, SegmentValidationFixtures.allowAll(), SegmentValidationFixtures.policy());
        if (!result.valid()) {
            throw new IllegalStateException("fixture document did not validate: " + SegmentValidationFixtures.codes(result));
        }
        return result;
    }

    public static SegmentQueryPlanner planner() {
        return planner(SegmentValidationFixtures.allowAll());
    }

    /** A planner that reauthorizes every dependency through the supplied T02 boundary. */
    public static SegmentQueryPlanner planner(AuthorizationService authorizationService) {
        return new SegmentQueryPlanner(authorizationService);
    }

    /** Builds a document whose Metadata binding pins the supplied snapshot exactly. */
    public static SegmentDsl document(MetadataSnapshot snapshot, Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED,
                SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(snapshot),
                predicate);
    }

    /** An attribute predicate for a NULL operator, which carries no operand value. */
    public static AttributePredicate attributeNoValue(
            String nodeId, String attributeId, com.company.campaign.metadata.domain.ComparisonOperator operator) {
        return new AttributePredicate(nodeId, attributeId, operator, ConditionValue.none());
    }

    /** Rebinds an existing document's predicate to another snapshot, changing only the Metadata pin. */
    public static SegmentDsl rebind(SegmentDsl source, MetadataSnapshot snapshot) {
        return SegmentDsl.publish(
                source.schemaVersion(), source.subject(), MetadataBinding.fromSnapshot(snapshot), source.predicate());
    }

    // ------------------------------------------------------------------ trusted contexts

    public static TrustedExecutionContext context(String tenantId, String programId, String dataScopeId) {
        return context(tenantId, programId, dataScopeId, "membership-1", "user-001",
                AS_OF, WATERMARK, ZoneId.of("Asia/Shanghai"), "CNY", MetadataFixtures.allPermissions());
    }

    public static TrustedExecutionContext context(
            String tenantId,
            String programId,
            String dataScopeId,
            String membershipId,
            String userId,
            Instant asOf,
            Instant dataWatermark,
            ZoneId timezone,
            String reportingCurrency,
            Set<CampaignPermission> permissions) {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity(userId, "https://issuer.example", AUTHENTICATED_AT),
                new PrincipalMembership(
                        membershipId, userId, tenantId, programId, dataScopeId,
                        MembershipStatus.ACTIVE, permissions),
                new TrustedExecutionContextRef(
                        tenantId, programId, userId, dataScopeId, SNAPSHOT_ID,
                        LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                        timezone,
                        reportingCurrency,
                        asOf,
                        dataWatermark));
    }

    // ------------------------------------------------------------------ custom snapshots

    /** A snapshot holding the standard definitions plus a metric with an INCLUSIVE_BOTH bounded window. */
    public static MetadataSnapshot inclusiveBothSnapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                SegmentValidationFixtures.attributes(),
                List.of(inclusiveBothMetric()),
                SegmentValidationFixtures.relations());
    }

    public static MetricDefinition inclusiveBothMetric() {
        return new MetricDefinition(
                "order.inclusive_amount",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Inclusive Amount",
                SemanticDataType.MONEY,
                null,
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                AggregateFunction.SUM,
                "member.orders",
                "order.paid_at",
                com.company.campaign.metadata.domain.EventTimeSemantics.EVENT_TIME,
                com.company.campaign.metadata.domain.MetadataFilter.empty(),
                RefundTreatment.IGNORE,
                com.company.campaign.metadata.domain.CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY,
                List.of(TimeWindowKind.ROLLING, TimeWindowKind.FIXED),
                WindowBoundarySemantics.INCLUSIVE_BOTH,
                com.company.campaign.metadata.domain.NullSemantics.UNKNOWN_VALUE,
                AggregateEmptySet.zero(SemanticDataType.MONEY),
                List.of(), List.of(), null);
    }

    /** A snapshot whose metric pins an immutable filter and a refund treatment, for exact-resolution tests. */
    public static MetadataSnapshot richSnapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.tierAttribute()),
                List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
    }

    /** A snapshot identical to the standard one except for one unreferenced attribute (metadata-content pin). */
    public static MetadataSnapshot snapshotWithExtraAttribute() {
        java.util.ArrayList<com.company.campaign.metadata.domain.AttributeDefinition> attributes =
                new java.util.ArrayList<>(SegmentValidationFixtures.attributes());
        attributes.add(MetadataFixtures.snapshotAttribute("member.extra", "Extra"));
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                attributes,
                SegmentValidationFixtures.metrics(),
                SegmentValidationFixtures.relations());
    }

    /** A trusted context pinning a chosen Metadata Snapshot ID, for context-pin mismatch tests. */
    public static TrustedExecutionContext contextPinning(
            String snapshotId, String tenantId, String programId, String dataScopeId) {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity("user-001", "https://issuer.example", AUTHENTICATED_AT),
                new PrincipalMembership(
                        "membership-1", "user-001", tenantId, programId, dataScopeId,
                        MembershipStatus.ACTIVE, MetadataFixtures.allPermissions()),
                new TrustedExecutionContextRef(
                        tenantId, programId, "user-001", dataScopeId, snapshotId,
                        LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                        ZoneId.of("Asia/Shanghai"),
                        "CNY",
                        AS_OF,
                        WATERMARK));
    }

    /** A snapshot whose only monetary attribute requires an already-single currency. */
    public static MetadataSnapshot singleCurrencySnapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(singleCurrencyAttribute()),
                List.of(),
                SegmentValidationFixtures.relations());
    }

    public static AttributeDefinition singleCurrencyAttribute() {
        return MetadataFixtures.moneyAttribute(
                "member.single_balance", "Single Balance",
                com.company.campaign.metadata.domain.CurrencySemantics.SINGLE_CURRENCY_ONLY);
    }

    /**
     * A snapshot holding two simultaneously usable monetary attributes with different policies — one
     * {@code CONVERT_TO_REPORTING_CURRENCY} and one {@code SINGLE_CURRENCY_ONLY} — plus a non-monetary one.
     */
    public static MetadataSnapshot mixedCurrencySnapshot() {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(SegmentValidationFixtures.tierAttribute(),
                        SegmentValidationFixtures.balanceAttribute(),
                        singleCurrencyAttribute()),
                SegmentValidationFixtures.metrics(),
                SegmentValidationFixtures.relations());
    }

    /** A snapshot owned by another tenant/program, for owner/hash-sensitivity tests. */
    public static MetadataSnapshot foreignOwnerSnapshot(String tenantId, String programId) {
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                SNAPSHOT_ID,
                SNAPSHOT_VERSION,
                tenantId,
                programId,
                SegmentValidationFixtures.attributes(),
                SegmentValidationFixtures.metrics(),
                SegmentValidationFixtures.relations());
    }

    // ------------------------------------------------------------------ AST helpers

    public static int countNodes(QueryAst node, Class<? extends QueryAst> type) {
        int count = type.isInstance(node) ? 1 : 0;
        return switch (node) {
            case QueryAst.Group group -> {
                int total = count;
                for (QueryAst child : group.children()) {
                    total += countNodes(child, type);
                }
                yield total;
            }
            case QueryAst.Negation negation -> count + countNodes(negation.child(), type);
            case QueryAst.Exists exists -> count + countNodes(exists.child(), type);
            case QueryAst.Attribute ignored -> count;
            case QueryAst.Metric ignored -> count;
            case QueryAst.MetricCalculation ignored -> count;
            case QueryAst.AudienceReference ignored -> count;
        };
    }

    public static RelationDefinition ordersRelation() {
        return SegmentValidationFixtures.ordersRelation();
    }
}
