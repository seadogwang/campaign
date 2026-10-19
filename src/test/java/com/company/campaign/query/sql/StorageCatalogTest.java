package com.company.campaign.query.sql;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3: the catalog hash changes for every mapping semantic field and stays stable under equivalent
 * input reordering, duplicate keys fail closed and a catalog that references an entity with no storage entry
 * is rejected as contradictory.
 *
 * <p>The tenant/program context mapping is carried per tenant-owned table shape — entity and refund — so
 * changing any one of those explicit mappings must change the hash rather than being absorbed by a shared
 * catalog-level default.
 */
class StorageCatalogTest {

    private static final SchemaVersion V = MetadataSchemaVersions.SUPPORTED;

    @Test
    void reorderingEquivalentInputListsDoesNotChangeTheHash() {
        StorageCatalog base = base();
        StorageCatalog reordered = build(
                reversed(baseEntities()), reversed(baseAttributes()), reversed(baseMetrics()),
                reversed(baseRelations()), baseScope(), baseFx(), baseRefund());
        assertThat(reordered.contentHash()).isEqualTo(base.contentHash());
        assertThat(reordered).isEqualTo(base);
    }

    @Test
    void reorderingEquivalentFilterMapsDoesNotChangeTheHash() {
        Map<String, SqlIdentifier> forward = new java.util.LinkedHashMap<>();
        forward.put("status", id("status"));
        forward.put("channel", id("channel"));
        Map<String, SqlIdentifier> backward = new java.util.LinkedHashMap<>();
        backward.put("channel", id("channel"));
        backward.put("status", id("status"));
        assertThat(build(baseEntities(), baseAttributes(), List.of(filteredMetric(forward)), baseRelations(),
                baseScope(), baseFx(), baseRefund()).contentHash())
                .isEqualTo(build(baseEntities(), baseAttributes(), List.of(filteredMetric(backward)), baseRelations(),
                        baseScope(), baseFx(), baseRefund()).contentHash());
    }

    @Test
    void changingCatalogVersionChangesTheHash() {
        assertChanged(StorageCatalog.publish("catalog-v2", baseEntities(), baseAttributes(), baseMetrics(),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
    }

    @Test
    void changingAnyEntityStorageFactChangesTheHash() {
        assertChanged(build(
                List.of(new StorageCatalog.EntityStorage(
                                member(), table("public", "members_v2"), id("member_id"), baseContext()),
                        new StorageCatalog.EntityStorage(
                                order(), table("public", "orders"), id("order_id"), baseContext())),
                baseAttributes(), baseMetrics(), baseRelations(), baseScope(), baseFx(),
                baseRefund()));
        assertChanged(build(
                List.of(new StorageCatalog.EntityStorage(
                                member(), table("public", "members"), id("member_key"), baseContext()),
                        new StorageCatalog.EntityStorage(
                                order(), table("public", "orders"), id("order_id"), baseContext())),
                baseAttributes(), baseMetrics(), baseRelations(), baseScope(), baseFx(),
                baseRefund()));
        assertChanged(build(
                List.of(new StorageCatalog.EntityStorage(
                                member(), table("private", "members"), id("member_id"), baseContext()),
                        new StorageCatalog.EntityStorage(
                                order(), table("public", "orders"), id("order_id"), baseContext())),
                baseAttributes(), baseMetrics(), baseRelations(), baseScope(), baseFx(),
                baseRefund()));
        // Each entity carries its own explicit context mapping, so changing the root entity's tenant column
        // is a distinct execution fact rather than a shared catalog-level default.
        assertChanged(build(
                List.of(new StorageCatalog.EntityStorage(
                                member(), table("public", "members"), id("member_id"),
                                new StorageCatalog.ContextStorage(id("member_tenant"), id("program_id"))),
                        new StorageCatalog.EntityStorage(
                                order(), table("public", "orders"), id("order_id"), baseContext())),
                baseAttributes(), baseMetrics(), baseRelations(), baseScope(), baseFx(),
                baseRefund()));
        assertChanged(build(
                List.of(new StorageCatalog.EntityStorage(
                                member(), table("public", "members"), id("member_id"), baseContext()),
                        new StorageCatalog.EntityStorage(
                                order(), table("public", "orders"), id("order_id"),
                                new StorageCatalog.ContextStorage(id("tenant_id"), id("order_program")))),
                baseAttributes(), baseMetrics(), baseRelations(), baseScope(), baseFx(),
                baseRefund()));
    }

    @Test
    void changingAnyAttributeStorageFactChangesTheHash() {
        assertChanged(build(baseEntities(),
                List.of(new StorageCatalog.AttributeStorage(
                                "member.tier", SubjectKind.MEMBER, V, SemanticDataType.ENUM, id("tier_code"), null),
                        balanceAttribute()),
                baseMetrics(), baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(),
                List.of(tierAttribute(),
                        new StorageCatalog.AttributeStorage("member.balance", SubjectKind.MEMBER, V,
                                SemanticDataType.MONEY, id("balance"), id("balance_ccy"))),
                baseMetrics(), baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(),
                List.of(new StorageCatalog.AttributeStorage(
                                "member.tier", SubjectKind.MEMBER, V, SemanticDataType.STRING, id("tier"), null),
                        balanceAttribute()),
                baseMetrics(), baseRelations(), baseScope(), baseFx(), baseRefund()));
    }

    @Test
    void changingAnyMetricStorageFactChangesTheHash() {
        // The relation ID is a storage fact the later variants below do not otherwise alter.
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.purchases", null, null, "order.paid_at", id("paid_at"), Map.of(),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", "order.count", id("count_value"), "order.paid_at", id("paid_at"),
                        Map.of(), List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", null, null, "order.created_at", id("created_at"), Map.of(),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", null, null, "order.paid_at", id("paid_at"),
                        Map.of("status", id("status")),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", null, null, "order.paid_at", id("paid_at"), Map.of(),
                        List.of(AggregateFunction.SUM), List.of(EmptySetBehavior.ZERO), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", null, null, "order.paid_at", id("paid_at"), Map.of(),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.NULL), false)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(),
                List.of(countMetric("member.orders", null, null, "order.paid_at", id("paid_at"), Map.of(),
                        List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), true)),
                baseRelations(), baseScope(), baseFx(), baseRefund()));
    }

    @Test
    void changingAnyRelationStorageFactChangesTheHash() {
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(),
                List.of(new StorageCatalog.RelationStorage(
                        "member.orders", SubjectKind.MEMBER, V, SubjectKind.ORDER, V,
                        RelationJoinKind.GLOBAL, null, null, null, null, "order.paid_at", id("paid_at"))),
                baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(),
                List.of(new StorageCatalog.RelationStorage(
                        "member.orders", SubjectKind.MEMBER, V, SubjectKind.ORDER, V,
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", id("member_key"), "target.parent_id", id("member_id"),
                        "order.paid_at", id("paid_at"))),
                baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(),
                List.of(new StorageCatalog.RelationStorage(
                        "member.orders", SubjectKind.MEMBER, V, SubjectKind.ORDER, V,
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", id("member_id"), "target.parent_id", id("member_id"),
                        "order.created_at", id("created_at"))),
                baseScope(), baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(),
                List.of(new StorageCatalog.RelationStorage(
                        "member.orders", SubjectKind.MEMBER, V, SubjectKind.ORDER, V,
                        RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.key", id("member_id"), "target.parent_id", id("member_id"),
                        "order.paid_at", id("paid_at"))),
                baseScope(), baseFx(), baseRefund()));
    }

    @Test
    void changingAnyDataScopeFxOrRefundFactChangesTheHash() {
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(), baseRelations(),
                new StorageCatalog.DataScopeStorage(table("public", "data_scope_map"), id("scope_id"),
                        id("member_id"), id("tenant_id"), id("program_id")),
                baseFx(), baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(), baseRelations(),
                baseScope(),
                new StorageCatalog.FxStrategy(table("public", "fx_rate_map"), id("currency"), id("rate"),
                        id("snapshot_id"), List.of("USD")),
                baseRefund()));
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(), baseRelations(),
                baseScope(), baseFx(),
                new StorageCatalog.RefundStorage(
                        table("public", "refunds"), id("order_id"), id("refund_amount"), baseContext())));
        // The refund table's own context mapping is a distinct execution fact, not a shared default.
        assertChanged(build(baseEntities(), baseAttributes(), baseMetrics(), baseRelations(),
                baseScope(), baseFx(),
                new StorageCatalog.RefundStorage(
                        table("public", "refunds"), id("order_id"), id("amount"),
                        new StorageCatalog.ContextStorage(id("refund_tenant"), id("refund_program")))));
    }

    @Test
    void duplicateLookupKeysAndContradictoryEntriesFailClosed() {
        List<StorageCatalog.EntityStorage> duplicated = new ArrayList<>(baseEntities());
        duplicated.add(new StorageCatalog.EntityStorage(
                member(), table("public", "members_shadow"), id("member_id"), baseContext()));
        assertThat(SqlTestSupport.sqlFailure(() -> build(duplicated, baseAttributes(), baseMetrics(),
                baseRelations(), baseScope(), baseFx(), baseRefund())).code())
                .isEqualTo(SqlErrorCodes.SQL_MAPPING_CONFLICT);

        List<StorageCatalog.AttributeStorage> shadowed = new ArrayList<>(baseAttributes());
        shadowed.add(new StorageCatalog.AttributeStorage(
                "member.tier", SubjectKind.MEMBER, V, SemanticDataType.ENUM, id("tier_shadow"), null));
        assertThat(SqlTestSupport.sqlFailure(() -> build(baseEntities(), shadowed, baseMetrics(),
                baseRelations(), baseScope(), baseFx(), baseRefund())).code())
                .isEqualTo(SqlErrorCodes.SQL_MAPPING_CONFLICT);

        // An attribute pinned to an entity version the catalog never declares is contradictory.
        SchemaVersion other = new SchemaVersion(2, 0);
        List<StorageCatalog.AttributeStorage> uncovered = List.of(new StorageCatalog.AttributeStorage(
                "member.tier", SubjectKind.MEMBER, other, SemanticDataType.ENUM, id("tier"), null));
        assertThat(SqlTestSupport.sqlFailure(() -> build(baseEntities(), uncovered, baseMetrics(),
                baseRelations(), baseScope(), baseFx(), baseRefund())).code())
                .isEqualTo(SqlErrorCodes.SQL_CATALOG_INVALID);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertChanged(StorageCatalog variant) {
        assertThat(variant.contentHash()).isNotEqualTo(base().contentHash());
    }

    private static StorageCatalog base() {
        return build(baseEntities(), baseAttributes(), baseMetrics(), baseRelations(), baseScope(),
                baseFx(), baseRefund());
    }

    private static StorageCatalog build(
            List<StorageCatalog.EntityStorage> entities,
            List<StorageCatalog.AttributeStorage> attributes,
            List<StorageCatalog.MetricStorage> metrics,
            List<StorageCatalog.RelationStorage> relations,
            StorageCatalog.DataScopeStorage dataScope,
            StorageCatalog.FxStrategy fx,
            StorageCatalog.RefundStorage refund) {
        return StorageCatalog.publish("catalog-v1", entities, attributes, metrics, relations, dataScope,
                fx, refund);
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private static SchemaVersion version() {
        return V;
    }

    private static SqlIdentifier id(String value) {
        return SqlIdentifier.of(value);
    }

    private static TableRef table(String schema, String name) {
        return new TableRef(id(schema), id(name));
    }

    private static EntityVersionRef member() {
        return new EntityVersionRef(SubjectKind.MEMBER, version());
    }

    private static EntityVersionRef order() {
        return new EntityVersionRef(SubjectKind.ORDER, version());
    }

    private static List<StorageCatalog.EntityStorage> baseEntities() {
        return List.of(
                new StorageCatalog.EntityStorage(
                        member(), table("public", "members"), id("member_id"), baseContext()),
                new StorageCatalog.EntityStorage(
                        order(), table("public", "orders"), id("order_id"), baseContext()));
    }

    private static StorageCatalog.AttributeStorage tierAttribute() {
        return new StorageCatalog.AttributeStorage(
                "member.tier", SubjectKind.MEMBER, V, SemanticDataType.ENUM, id("tier"), null);
    }

    private static StorageCatalog.AttributeStorage balanceAttribute() {
        return new StorageCatalog.AttributeStorage(
                "member.balance", SubjectKind.MEMBER, V, SemanticDataType.MONEY, id("balance"),
                id("balance_currency"));
    }

    private static List<StorageCatalog.AttributeStorage> baseAttributes() {
        return List.of(tierAttribute(), balanceAttribute());
    }

    private static StorageCatalog.MetricStorage countMetric(
            String relationId,
            String inputField,
            SqlIdentifier inputColumn,
            String eventTimeField,
            SqlIdentifier eventTimeColumn,
            Map<String, SqlIdentifier> filterColumns,
            List<AggregateFunction> aggregates,
            List<EmptySetBehavior> emptySets,
            boolean refundSupported) {
        return new StorageCatalog.MetricStorage(
                "order.count", SubjectKind.MEMBER, V, SemanticDataType.INTEGER, relationId,
                inputField, inputColumn, null, null, eventTimeField, eventTimeColumn,
                filterColumns, aggregates, emptySets, refundSupported);
    }

    private static StorageCatalog.MetricStorage filteredMetric(Map<String, SqlIdentifier> filters) {
        return new StorageCatalog.MetricStorage(
                "order.count", SubjectKind.MEMBER, V, SemanticDataType.INTEGER, "member.orders",
                null, null, null, null, "order.paid_at", id("paid_at"),
                filters, List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false);
    }

    private static List<StorageCatalog.MetricStorage> baseMetrics() {
        return List.of(countMetric("member.orders", null, null, "order.paid_at", id("paid_at"), Map.of(),
                List.of(AggregateFunction.COUNT), List.of(EmptySetBehavior.ZERO), false));
    }

    private static List<StorageCatalog.RelationStorage> baseRelations() {
        return List.of(new StorageCatalog.RelationStorage(
                "member.orders", SubjectKind.MEMBER, V, SubjectKind.ORDER, V,
                RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                "source.id", id("member_id"), "target.parent_id", id("member_id"),
                "order.paid_at", id("paid_at")));
    }

    private static StorageCatalog.ContextStorage baseContext() {
        return new StorageCatalog.ContextStorage(id("tenant_id"), id("program_id"));
    }

    private static StorageCatalog.DataScopeStorage baseScope() {
        return new StorageCatalog.DataScopeStorage(table("public", "data_scope_members"), id("scope_id"),
                id("member_id"), id("tenant_id"), id("program_id"));
    }

    private static StorageCatalog.FxStrategy baseFx() {
        return new StorageCatalog.FxStrategy(
                table("public", "fx_rates"), id("currency"), id("rate"), id("snapshot_id"), List.of("USD"));
    }

    private static StorageCatalog.RefundStorage baseRefund() {
        return new StorageCatalog.RefundStorage(
                table("public", "refunds"), id("order_id"), id("amount"), baseContext());
    }
}