package com.company.campaign.query.sql;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2: catalog lookup is exact by ID, subject and entity/schema version, and every entity,
 * definition, relation and schema pin fails closed on a mismatch without echoing an identifier.
 */
class SqlCatalogLookupTest {

    private static final SchemaVersion OTHER = new SchemaVersion(2, 0);

    @Test
    void theStandardPlanCompilesAgainstTheStandardCatalog() {
        CompiledSql compiled = SqlTestSupport.compile(memberTierPlan());
        assertThat(compiled.sql()).startsWith("SELECT ");
    }

    @Test
    void aDefinitionPinnedToAnotherEntitySchemaVersionFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.EntityStorage> entities = new ArrayList<>(base.entities());
        entities.add(new StorageCatalog.EntityStorage(
                new EntityVersionRef(SubjectKind.MEMBER, OTHER),
                new TableRef(id("public"), id("members")), id("member_id"), SqlTestSupport.entityContext()));
        List<StorageCatalog.AttributeStorage> attributes = new ArrayList<>();
        for (StorageCatalog.AttributeStorage attribute : base.attributes()) {
            attributes.add(attribute.definitionId().equals("member.tier")
                    ? new StorageCatalog.AttributeStorage(
                            "member.tier", SubjectKind.MEMBER, OTHER, attribute.dataType(),
                            attribute.valueColumn(), attribute.currencyColumn())
                    : attribute);
        }
        StorageCatalog variant = rebuild(entities, attributes, base.metrics(), base.relations());
        assertMissing(SqlTestSupport.sqlFailure(
                () -> SqlTestSupport.compile(memberTierPlan(), variant, SqlTestSupport.cnyFx())));
    }

    @Test
    void aMismatchedAttributeDataTypeFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.AttributeStorage> attributes = new ArrayList<>();
        for (StorageCatalog.AttributeStorage attribute : base.attributes()) {
            attributes.add(attribute.definitionId().equals("member.tier")
                    ? new StorageCatalog.AttributeStorage(
                            "member.tier", SubjectKind.MEMBER, attribute.entitySchemaVersion(),
                            SemanticDataType.INTEGER, attribute.valueColumn(), null)
                    : attribute);
        }
        StorageCatalog variant = rebuild(base.entities(), attributes, base.metrics(), base.relations());
        assertMissing(SqlTestSupport.sqlFailure(
                () -> SqlTestSupport.compile(memberTierPlan(), variant, SqlTestSupport.cnyFx())));
    }

    @Test
    void aMismatchedMetricResultTypeOrRelationFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.MetricStorage> decimal = new ArrayList<>();
        for (StorageCatalog.MetricStorage metric : base.metrics()) {
            decimal.add(metric.definitionId().equals("order.count")
                    ? replaceResultType(metric, SemanticDataType.DECIMAL)
                    : metric);
        }
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                countPlan(), rebuild(base.entities(), base.attributes(), decimal, base.relations()),
                SqlTestSupport.cnyFx())));

        List<StorageCatalog.MetricStorage> foreignRelation = new ArrayList<>();
        for (StorageCatalog.MetricStorage metric : base.metrics()) {
            foreignRelation.add(metric.definitionId().equals("order.count")
                    ? withRelationId(metric, "member.other_orders")
                    : metric);
        }
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                countPlan(), rebuild(base.entities(), base.attributes(), foreignRelation, base.relations()),
                SqlTestSupport.cnyFx())));
    }

    @Test
    void aMissingMetricMappingFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.MetricStorage> metrics = new ArrayList<>();
        for (StorageCatalog.MetricStorage metric : base.metrics()) {
            if (!metric.definitionId().equals("order.count")) {
                metrics.add(metric);
            }
        }
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                countPlan(), rebuild(base.entities(), base.attributes(), metrics, base.relations()),
                SqlTestSupport.cnyFx())));
    }

    @Test
    void aMismatchedRelationJoinKindOrSchemaVersionFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.RelationStorage> global = List.of(new StorageCatalog.RelationStorage(
                "member.orders", SubjectKind.MEMBER, versionOf(base, SubjectKind.MEMBER),
                SubjectKind.ORDER, versionOf(base, SubjectKind.ORDER),
                RelationJoinKind.GLOBAL, null, null, null, null, "order.paid_at", id("paid_at")));
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                existsPlan(), rebuild(base.entities(), base.attributes(), base.metrics(), global),
                SqlTestSupport.cnyFx())));

        List<StorageCatalog.EntityStorage> entities = new ArrayList<>(base.entities());
        entities.add(new StorageCatalog.EntityStorage(
                new EntityVersionRef(SubjectKind.MEMBER, OTHER),
                new TableRef(id("public"), id("members")), id("member_id"), SqlTestSupport.entityContext()));
        List<StorageCatalog.RelationStorage> shifted = List.of(new StorageCatalog.RelationStorage(
                "member.orders", SubjectKind.MEMBER, OTHER, SubjectKind.ORDER,
                versionOf(base, SubjectKind.ORDER),
                RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                "source.id", id("member_id"), "target.parent_id", id("member_id"),
                "order.paid_at", id("paid_at")));
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                existsPlan(), rebuild(entities, base.attributes(), base.metrics(), shifted),
                SqlTestSupport.cnyFx())));
    }

    @Test
    void aMismatchedRelationJoinFieldFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.RelationStorage> renamed = List.of(new StorageCatalog.RelationStorage(
                "member.orders", SubjectKind.MEMBER, versionOf(base, SubjectKind.MEMBER),
                SubjectKind.ORDER, versionOf(base, SubjectKind.ORDER),
                RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                "source.key", id("member_id"), "target.parent_id", id("member_id"),
                "order.paid_at", id("paid_at")));
        assertMissing(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                existsPlan(), rebuild(base.entities(), base.attributes(), base.metrics(), renamed),
                SqlTestSupport.cnyFx())));
    }

    @Test
    void aRootEntityVersionTheCatalogDoesNotDeclareFailsClosed() {
        StorageCatalog shifted = shiftedToOtherVersion();
        assertMissing(SqlTestSupport.sqlFailure(
                () -> SqlTestSupport.compile(memberTierPlan(), shifted, SqlTestSupport.cnyFx())));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertMissing(SqlException failure) {
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_MAPPING_MISSING);
        assertThat(failure.getMessage())
                .doesNotContain("member.tier")
                .doesNotContain("member_id")
                .doesNotContain("members");
        assertThat(failure.details()).containsOnlyKeys("invariant");
    }

    private static StorageCatalog rebuild(
            List<StorageCatalog.EntityStorage> entities,
            List<StorageCatalog.AttributeStorage> attributes,
            List<StorageCatalog.MetricStorage> metrics,
            List<StorageCatalog.RelationStorage> relations) {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        return StorageCatalog.publish(base.catalogVersion(), entities, attributes, metrics, relations,
                base.dataScope(), base.fxStrategy(), base.refundStorage());
    }

    /** A catalog whose every pin is the next schema version, so a version-1.0 plan cannot resolve its root. */
    private static StorageCatalog shiftedToOtherVersion() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.EntityStorage> entities = new ArrayList<>();
        for (StorageCatalog.EntityStorage entity : base.entities()) {
            entities.add(new StorageCatalog.EntityStorage(
                    new EntityVersionRef(entity.entityVersion().entity(), OTHER), entity.table(),
                    entity.primaryKeyColumn(), entity.context()));
        }
        List<StorageCatalog.AttributeStorage> attributes = new ArrayList<>();
        for (StorageCatalog.AttributeStorage attribute : base.attributes()) {
            attributes.add(new StorageCatalog.AttributeStorage(
                    attribute.definitionId(), attribute.subject(), OTHER, attribute.dataType(),
                    attribute.valueColumn(), attribute.currencyColumn()));
        }
        List<StorageCatalog.MetricStorage> metrics = new ArrayList<>();
        for (StorageCatalog.MetricStorage metric : base.metrics()) {
            metrics.add(new StorageCatalog.MetricStorage(
                    metric.definitionId(), metric.subject(), OTHER, metric.resultType(), metric.relationId(),
                    metric.inputField(), metric.inputColumn(), metric.currencyField(), metric.currencyColumn(),
                    metric.eventTimeField(), metric.eventTimeColumn(), metric.filterColumns(),
                    metric.supportedAggregates(), metric.supportedEmptySetBehaviors(), metric.refundSupported()));
        }
        List<StorageCatalog.RelationStorage> relations = new ArrayList<>();
        for (StorageCatalog.RelationStorage relation : base.relations()) {
            relations.add(new StorageCatalog.RelationStorage(
                    relation.relationId(), relation.sourceEntity(), OTHER, relation.targetEntity(), OTHER,
                    relation.joinKind(), relation.sourceJoinField(), relation.sourceKeyColumn(),
                    relation.targetJoinField(), relation.targetKeyColumn(),
                    relation.targetEventTimeField(), relation.targetEventTimeColumn()));
        }
        return StorageCatalog.publish(base.catalogVersion(), entities, attributes, metrics, relations,
                base.dataScope(), base.fxStrategy(), base.refundStorage());
    }

    private static StorageCatalog.MetricStorage replaceResultType(
            StorageCatalog.MetricStorage metric, SemanticDataType resultType) {
        return new StorageCatalog.MetricStorage(
                metric.definitionId(), metric.subject(), metric.entitySchemaVersion(), resultType,
                metric.relationId(), metric.inputField(), metric.inputColumn(), metric.currencyField(),
                metric.currencyColumn(), metric.eventTimeField(), metric.eventTimeColumn(), metric.filterColumns(),
                metric.supportedAggregates(), metric.supportedEmptySetBehaviors(), metric.refundSupported());
    }

    private static StorageCatalog.MetricStorage withRelationId(
            StorageCatalog.MetricStorage metric, String relationId) {
        return new StorageCatalog.MetricStorage(
                metric.definitionId(), metric.subject(), metric.entitySchemaVersion(), metric.resultType(),
                relationId, metric.inputField(), metric.inputColumn(), metric.currencyField(),
                metric.currencyColumn(), metric.eventTimeField(), metric.eventTimeColumn(), metric.filterColumns(),
                metric.supportedAggregates(), metric.supportedEmptySetBehaviors(), metric.refundSupported());
    }

    private static SqlIdentifier id(String value) {
        return SqlIdentifier.of(value);
    }

    private static SchemaVersion versionOf(StorageCatalog catalog, SubjectKind entity) {
        for (StorageCatalog.EntityStorage storage : catalog.entities()) {
            if (storage.entityVersion().entity() == entity) {
                return storage.entityVersion().schemaVersion();
            }
        }
        throw new IllegalStateException("no entity storage for " + entity);
    }

    private static SemanticQueryPlan memberTierPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
    }

    private static SemanticQueryPlan countPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(),
                com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));
    }

    private static SemanticQueryPlan existsPlan() {
        Predicate child = SegmentValidationFixtures.attribute(
                "n-channel", "order.channel",
                com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")));
        return SqlTestSupport.plan(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(), child));
    }
}