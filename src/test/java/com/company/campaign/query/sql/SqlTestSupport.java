package com.company.campaign.query.sql;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.fixture.testsupport.FixtureTestSupport;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic T09B fixtures.
 *
 * <p>They are built only from accepted T02/T04/T05/T07/T08 public contracts and from the T09A enhanced snapshot
 * fixture; no clock, network, database, corpus asset or random identifier is touched. The storage catalog is a
 * plain server-owned declaration, exactly as production code would publish one.
 */
public final class SqlTestSupport {

    public static final String CATALOG_VERSION = "storage-catalog-1";
    public static final SchemaVersion ENTITY_VERSION = MetadataSchemaVersions.SUPPORTED;
    public static final Instant RATE_TIMESTAMP = Instant.parse("2026-10-01T00:00:00Z");

    private static final SqlCompiler COMPILER = new SqlCompiler();

    private SqlTestSupport() {
    }

    // ------------------------------------------------------------------ identifiers and tables

    static SqlIdentifier id(String value) {
        return SqlIdentifier.of(value);
    }

    static TableRef table(String schema, String name) {
        return new TableRef(id(schema), id(name));
    }

    /** The mandatory tenant/program columns every standard fixture table binds as its own context. */
    static StorageCatalog.ContextStorage entityContext() {
        return new StorageCatalog.ContextStorage(id("tenant_id"), id("program_id"));
    }

    // ------------------------------------------------------------------ catalogs

    /** The complete catalog for the standard accepted T08 snapshot, with FX and refund support. */
    public static StorageCatalog standardCatalog() {
        return catalog(true);
    }

    /** The same catalog without any FX strategy, so a conversion must fail closed. */
    public static StorageCatalog catalogWithoutFx() {
        return catalog(false);
    }

    /** The standard catalog plus the server-owned audience membership binding. */
    public static StorageCatalog catalogWithAudienceMembership() {
        return catalog(true, true);
    }

    /** The server-owned audience membership table: one row per (tenant, program, audience, version, member). */
    public static StorageCatalog.AudienceMembershipStorage audienceMembership() {
        return new StorageCatalog.AudienceMembershipStorage(
                new EntityVersionRef(SubjectKind.MEMBER, ENTITY_VERSION),
                table("public", "audience_members"),
                id("audience_id"), id("version_id"), id("version_hash"), id("member_id"),
                entityContext());
    }

    private static StorageCatalog catalog(boolean withFx) {
        return catalog(withFx, false);
    }

    private static StorageCatalog catalog(boolean withFx, boolean withAudienceMembership) {
        EntityVersionRef memberVersion = new EntityVersionRef(SubjectKind.MEMBER, ENTITY_VERSION);
        EntityVersionRef orderVersion = new EntityVersionRef(SubjectKind.ORDER, ENTITY_VERSION);
        EntityVersionRef eventVersion = new EntityVersionRef(SubjectKind.EVENT, ENTITY_VERSION);

        List<StorageCatalog.EntityStorage> entities = List.of(
                new StorageCatalog.EntityStorage(
                        memberVersion, table("public", "members"), id("member_id"), entityContext()),
                new StorageCatalog.EntityStorage(
                        orderVersion, table("public", "orders"), id("order_id"), entityContext()),
                new StorageCatalog.EntityStorage(
                        eventVersion, table("public", "events"), id("event_id"), entityContext()));

        List<StorageCatalog.AttributeStorage> attributes = List.of(
                attribute("member.tier", SubjectKind.MEMBER, SemanticDataType.ENUM, "tier", null),
                attribute("member.name", SubjectKind.MEMBER, SemanticDataType.STRING, "name", null),
                attribute("member.balance", SubjectKind.MEMBER, SemanticDataType.MONEY, "balance", "balance_currency"),
                attribute("member.age", SubjectKind.MEMBER, SemanticDataType.INTEGER, "age", null),
                attribute("member.score", SubjectKind.MEMBER, SemanticDataType.DECIMAL, "score", null),
                attribute("member.joined_on", SubjectKind.MEMBER, SemanticDataType.DATE, "joined_on", null),
                attribute("member.last_seen", SubjectKind.MEMBER, SemanticDataType.TIMESTAMP, "last_seen", null),
                attribute("member.flag", SubjectKind.MEMBER, SemanticDataType.BOOLEAN, "flag", null),
                attribute("member.code", SubjectKind.MEMBER, SemanticDataType.STRING, "code", null),
                attribute("member.single_balance", SubjectKind.MEMBER, SemanticDataType.MONEY, "single_balance",
                        "single_balance_currency"),
                attribute("order.amount", SubjectKind.ORDER, SemanticDataType.MONEY, "amount", "currency"),
                attribute("order.channel", SubjectKind.ORDER, SemanticDataType.ENUM, "channel", null));

        List<StorageCatalog.MetricStorage> metrics = List.of(
                metric("order.net_paid_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        "order.paid_at", "paid_at", Map.of(),
                        List.of(AggregateFunction.SUM), List.of(EmptySetBehavior.ZERO), true),
                metric("order.count", SemanticDataType.INTEGER, "member.orders",
                        null, null, null, null,
                        null, null, Map.of(),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false),
                metric("order.avg_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        null, null, Map.of(),
                        List.of(AggregateFunction.AVG), List.of(EmptySetBehavior.NULL), true),
                metric("order.min_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        null, null, Map.of(),
                        List.of(AggregateFunction.MIN), List.of(EmptySetBehavior.NULL), true),
                metric("order.max_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        null, null, Map.of(),
                        List.of(AggregateFunction.MAX), List.of(EmptySetBehavior.NULL), true),
                metric("order.latest_channel", SemanticDataType.STRING, "member.orders",
                        "order.channel", "channel", null, null,
                        null, null, Map.of(),
                        List.of(AggregateFunction.MIN), List.of(EmptySetBehavior.NULL), false),
                metric("order.inclusive_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        "order.paid_at", "paid_at", Map.of(),
                        List.of(AggregateFunction.SUM), List.of(EmptySetBehavior.ZERO), true),
                metric("order.refunded_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        "order.paid_at", "paid_at", Map.of(),
                        List.of(AggregateFunction.SUM), List.of(EmptySetBehavior.ZERO), true),
                metric("order.filtered_amount", SemanticDataType.MONEY, "member.orders",
                        "order.amount", "amount", "order.currency", "currency",
                        "order.paid_at", "paid_at", Map.of("status", id("status")),
                        List.of(AggregateFunction.SUM), List.of(EmptySetBehavior.ZERO), true),
                metric("order.distinct_channel", SemanticDataType.INTEGER, "member.orders",
                        "order.channel", "channel", null, null,
                        "order.paid_at", "paid_at", Map.of(),
                        List.of(AggregateFunction.COUNT_DISTINCT), List.of(EmptySetBehavior.ZERO), false));

        List<StorageCatalog.RelationStorage> relations = List.of(
                new StorageCatalog.RelationStorage(
                        "member.orders", SubjectKind.MEMBER, ENTITY_VERSION, SubjectKind.ORDER, ENTITY_VERSION,
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", id("member_id"), "target.parent_id", id("member_id"),
                        "order.paid_at", id("paid_at")),
                // A nested ORDER-to-ORDER relation, so a nested EXISTS can be compiled over both shapes. Its
                // event-time field and column mirror the metadata relation exactly.
                new StorageCatalog.RelationStorage(
                        "order.lines", SubjectKind.ORDER, ENTITY_VERSION, SubjectKind.ORDER, ENTITY_VERSION,
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", id("order_id"), "target.parent_id", id("parent_order_id"),
                        "order.line_at", id("line_at")));

        StorageCatalog.DataScopeStorage dataScope = new StorageCatalog.DataScopeStorage(
                table("public", "data_scope_members"), id("scope_id"), id("member_id"),
                id("tenant_id"), id("program_id"));

        StorageCatalog.FxStrategy fx = withFx
                ? new StorageCatalog.FxStrategy(
                        table("public", "fx_rates"), id("currency"), id("rate"), id("snapshot_id"),
                        List.of("USD"))
                : null;

        StorageCatalog.RefundStorage refund = new StorageCatalog.RefundStorage(
                table("public", "refunds"), id("order_id"), id("amount"), entityContext());

        return StorageCatalog.publish(
                CATALOG_VERSION, entities, attributes, metrics, relations, dataScope,
                withAudienceMembership ? audienceMembership() : null, fx, refund);
    }

    private static StorageCatalog.AttributeStorage attribute(
            String definitionId, SubjectKind subject, SemanticDataType dataType, String column, String currency) {
        return new StorageCatalog.AttributeStorage(
                definitionId, subject, ENTITY_VERSION, dataType, id(column),
                currency == null ? null : id(currency));
    }

    private static StorageCatalog.MetricStorage metric(
            String definitionId,
            SemanticDataType resultType,
            String relationId,
            String inputField,
            String inputColumn,
            String currencyField,
            String currencyColumn,
            String eventTimeField,
            String eventTimeColumn,
            Map<String, SqlIdentifier> filterColumns,
            List<AggregateFunction> aggregates,
            List<EmptySetBehavior> emptySets,
            boolean refundSupported) {
        return new StorageCatalog.MetricStorage(
                definitionId, SubjectKind.MEMBER, ENTITY_VERSION, resultType, relationId,
                inputField, inputColumn == null ? null : id(inputColumn),
                currencyField, currencyColumn == null ? null : id(currencyColumn),
                eventTimeField, eventTimeColumn == null ? null : id(eventTimeColumn),
                filterColumns, aggregates, emptySets, refundSupported);
    }

    // ------------------------------------------------------------------ bindings and compilation

    /** The one exact CNY-reporting FX binding: a single USD rate observed before the evaluation instant. */
    public static FxRateBinding cnyFx() {
        return cnyFx("7.20");
    }

    /** The same binding with a chosen USD rate, for hash-sensitivity and rate-coverage tests. */
    public static FxRateBinding cnyFx(String usdRate) {
        return FxRateBinding.publish(
                "fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP, Map.of("USD", new BigDecimal(usdRate)));
    }

    /** Compiles against the standard catalog and the standard FX binding. */
    public static CompiledSql compile(SemanticQueryPlan plan) {
        return compile(plan, standardCatalog(), cnyFx());
    }

    /** Compiles with an explicit catalog and binding. */
    public static CompiledSql compile(SemanticQueryPlan plan, StorageCatalog catalog, FxRateBinding fx) {
        return COMPILER.compile(plan, catalog, SqlCompilePolicy.standard(), fx);
    }

    /** Compiles a reference-bearing plan with the mandatory host execution check and its catalog. */
    public static CompiledSql compileWithReferenceCheck(
            SemanticQueryPlan plan,
            StorageCatalog catalog,
            com.company.campaign.query.plan.ReferenceExecutionCheck referenceExecutionCheck) {
        return compileWithReferenceCheck(plan, catalog, null, referenceExecutionCheck);
    }

    /** Compiles with an explicit catalog, FX binding and mandatory host execution check. */
    public static CompiledSql compileWithReferenceCheck(
            SemanticQueryPlan plan,
            StorageCatalog catalog,
            FxRateBinding fx,
            com.company.campaign.query.plan.ReferenceExecutionCheck referenceExecutionCheck) {
        return COMPILER.compile(plan, catalog, SqlCompilePolicy.standard(), fx, referenceExecutionCheck);
    }

    // ------------------------------------------------------------------ plans

    /** Plans a predicate against the standard accepted T08 snapshot and trusted context. */
    public static SemanticQueryPlan plan(Predicate predicate) {
        return QueryPlanFixtures.plan(predicate);
    }

    /** Plans a predicate against an explicit snapshot and the standard trusted context. */
    public static SemanticQueryPlan plan(MetadataSnapshot snapshot, Predicate predicate) {
        return QueryPlanFixtures.plan(
                QueryPlanFixtures.document(snapshot, predicate), snapshot, SegmentValidationFixtures.context());
    }

    /** Plans a predicate against the standard snapshot under an explicit trusted context. */
    public static SemanticQueryPlan planWithContext(Predicate predicate, String dataScopeId) {
        return QueryPlanFixtures.plan(
                QueryPlanFixtures.document(SegmentValidationFixtures.snapshot(), predicate),
                SegmentValidationFixtures.snapshot(),
                QueryPlanFixtures.context(MetadataFixtures.TENANT, MetadataFixtures.PROGRAM, dataScopeId));
    }

    // ------------------------------------------------------------------ custom snapshots

    /**
     * A snapshot whose only definition is a case-sensitive, unnormalized STRING attribute, so LIKE and
     * equality can be compiled exactly instead of failing closed.
     */
    public static MetadataSnapshot identityStringSnapshot() {
        AttributeDefinition code = new AttributeDefinition(
                "member.code",
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                "Member Code",
                SemanticDataType.STRING,
                StringSemantics.localeNeutral(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE),
                List.of(),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.CONTAINS, ComparisonOperator.NOT_CONTAINS,
                        ComparisonOperator.STARTS_WITH, ComparisonOperator.ENDS_WITH,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
        return MetadataSnapshot.publish(
                MetadataSchemaVersions.SUPPORTED,
                QueryPlanFixtures.SNAPSHOT_ID,
                QueryPlanFixtures.SNAPSHOT_VERSION,
                MetadataFixtures.TENANT,
                MetadataFixtures.PROGRAM,
                List.of(code),
                List.of(),
                List.of());
    }

    /** The T09A enhanced snapshot, which carries the refund, filter and COUNT_DISTINCT metrics. */
    public static MetadataSnapshot enhancedSnapshot() {
        return FixtureTestSupport.enhancedSnapshot();
    }

    /** Compiles against the enhanced snapshot's catalog entries with the standard FX binding. */
    public static CompiledSql compileEnhanced(SemanticQueryPlan plan) {
        return compile(plan, standardCatalog(), cnyFx());
    }

    /** Runs an action that must fail with the one bounded {@link SqlException} type. */
    public static SqlException sqlFailure(org.junit.jupiter.api.function.Executable action) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(action::execute);
        org.assertj.core.api.Assertions.assertThat(thrown).isInstanceOf(SqlException.class);
        return (SqlException) thrown;
    }
}