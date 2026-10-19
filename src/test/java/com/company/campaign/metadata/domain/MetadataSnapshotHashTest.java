package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot hash must be reproducible, must depend only on semantic material (never on construction
 * order or the JVM locale) and must change when any semantic field changes.
 */
class MetadataSnapshotHashTest {

    private static final SchemaVersion SUPPORTED = MetadataSchemaVersions.SUPPORTED;

    @Test
    void isReproducibleAcrossDefinitionOrdering() {
        AttributeDefinition a = MetadataFixtures.tierAttribute();
        AttributeDefinition b = MetadataFixtures.snapshotAttribute("member.status", "Member Status");
        AttributeDefinition c = MetadataFixtures.snapshotAttribute("member.city", "Member City");

        MetadataSnapshot forward = MetadataFixtures.snapshot("metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM, List.of(a, b, c), List.of(), List.of());
        MetadataSnapshot reversed = MetadataFixtures.snapshot("metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM, List.of(c, b, a), List.of(), List.of());

        assertThat(reversed.contentHash()).isEqualTo(forward.contentHash());
        assertThat(reversed.hashMaterial()).isEqualTo(forward.hashMaterial());
    }

    @Test
    void isReproducibleAcrossTheProcessDefaultLocale() {
        MetadataSnapshotHashMaterial material = MetadataFixtures.standardSnapshot().hashMaterial();
        ContentHash baseline = MetadataSnapshotHasher.hash(material);

        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertThat(MetadataSnapshotHasher.hash(material)).isEqualTo(baseline);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void everySemanticFieldChangesTheHash() {
        MetadataSnapshotHashMaterial baseline = MetadataFixtures.standardSnapshot().hashMaterial();
        ContentHash baselineHash = MetadataSnapshotHasher.hash(baseline);

        Map<String, MetadataSnapshotHashMaterial> variants = new LinkedHashMap<>();
        addSnapshotIdentityVariants(variants, baseline);
        addAttributeVariants(variants);
        addMetricVariants(variants);
        addRelationVariants(variants);

        Map<String, ContentHash> hashes = new LinkedHashMap<>();
        variants.forEach((name, material) -> {
            ContentHash hash = MetadataSnapshotHasher.hash(material);
            assertThat(hash).as("variant %s must change the hash", name).isNotEqualTo(baselineHash);
            hashes.put(name, hash);
        });
        assertThat(hashes.values()).as("distinct variants must hash distinctly").doesNotHaveDuplicates();
    }

    // --- snapshot material fields -------------------------------------------------------------------

    private static MetadataSnapshotHashMaterial material(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return new MetadataSnapshotHashMaterial(
                SUPPORTED, "metadata-17", "1", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                attributes, metrics, relations);
    }

    private static void addSnapshotIdentityVariants(
            Map<String, MetadataSnapshotHashMaterial> variants, MetadataSnapshotHashMaterial baseline) {
        // Snapshot identity and owner pins are deliberately included in the hash.
        variants.put("snapshot.schemaVersion", new MetadataSnapshotHashMaterial(
                new SchemaVersion(1, 1), baseline.snapshotId(), baseline.snapshotVersion(),
                baseline.tenantId(), baseline.programId(),
                baseline.attributes(), baseline.metrics(), baseline.relations()));
        variants.put("snapshot.snapshotId", new MetadataSnapshotHashMaterial(
                baseline.schemaVersion(), "metadata-18", baseline.snapshotVersion(),
                baseline.tenantId(), baseline.programId(),
                baseline.attributes(), baseline.metrics(), baseline.relations()));
        variants.put("snapshot.snapshotVersion", new MetadataSnapshotHashMaterial(
                baseline.schemaVersion(), baseline.snapshotId(), "2",
                baseline.tenantId(), baseline.programId(),
                baseline.attributes(), baseline.metrics(), baseline.relations()));
        variants.put("snapshot.tenantId", new MetadataSnapshotHashMaterial(
                baseline.schemaVersion(), baseline.snapshotId(), baseline.snapshotVersion(),
                "tenant-002", baseline.programId(),
                baseline.attributes(), baseline.metrics(), baseline.relations()));
        variants.put("snapshot.programId", new MetadataSnapshotHashMaterial(
                baseline.schemaVersion(), baseline.snapshotId(), baseline.snapshotVersion(),
                baseline.tenantId(), "program-002",
                baseline.attributes(), baseline.metrics(), baseline.relations()));
    }

    private static MetadataSnapshotHashMaterial attrs(AttributeDefinition attribute) {
        return material(List.of(attribute), List.of(), List.of());
    }

    private static MetadataSnapshotHashMaterial metricOnly(MetricDefinition metric) {
        return material(List.of(), List.of(metric), List.of());
    }

    private static MetadataSnapshotHashMaterial relationOnly(RelationDefinition relation) {
        return material(List.of(), List.of(), List.of(relation));
    }

    private static void addAttributeVariants(Map<String, MetadataSnapshotHashMaterial> variants) {
        AttributeDefinition base = MetadataFixtures.tierAttribute();
        variants.put("attribute.id", attrs(RecordOverride.with(base, "id", "member.tier.revised")));
        variants.put("attribute.displayName", attrs(RecordOverride.with(base, "displayName", "Tier Level")));
        variants.put("attribute.entityVersion", attrs(RecordOverride.withAll(base, Map.of(
                "entityVersion", MetadataFixtures.orderVersion(), "subject", SubjectKind.ORDER))));
        variants.put("attribute.aliases", attrs(RecordOverride.with(
                base, "aliases", List.of("会员等级", "卡级", "level"))));
        variants.put("attribute.allowedOperators", attrs(RecordOverride.with(
                base, "allowedOperators", List.of(
                        ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.IS_NULL))));
        variants.put("attribute.enumValues", attrs(RecordOverride.with(base, "enumValues", List.of(
                new EnumValueDefinition("GOLD", List.of("Gold", "金卡")),
                new EnumValueDefinition("SILVER", List.of("Silver")),
                new EnumValueDefinition("BRONZE", List.of("铜卡"))))));
        variants.put("attribute.nullSemantics", attrs(RecordOverride.with(
                base, "nullSemantics", NullSemantics.MATCHES_NULL)));
        variants.put("attribute.positiveExamples", attrs(RecordOverride.with(
                base, "positiveExamples", List.of("Gold会员", "金卡会员"))));
        variants.put("attribute.negativeExamples", attrs(RecordOverride.with(
                base, "negativeExamples", List.of("高价值会员", "活跃会员"))));
        variants.put("attribute.disambiguation", attrs(RecordOverride.with(
                base, "disambiguation", "Tier only, not score band.")));

        // String semantics are hash material: each of the three declared decisions must change the hash.
        AttributeDefinition stringAttribute = MetadataFixtures.snapshotAttribute("member.city", "Member City");
        variants.put("attribute.stringSemantics.caseSensitivity", attrs(RecordOverride.with(
                stringAttribute, "stringSemantics", StringSemantics.localeNeutral(
                        StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NFKC))));
        variants.put("attribute.stringSemantics.normalization", attrs(RecordOverride.with(
                stringAttribute, "stringSemantics", StringSemantics.localeNeutral(
                        StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFC))));
        variants.put("attribute.stringSemantics.locale", attrs(RecordOverride.with(
                stringAttribute, "stringSemantics", StringSemantics.ofLocale(
                        StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC,
                        new LocaleTag("zh-CN")))));

        // Type isolation: a STRING and a BOOLEAN attribute that differ only in dataType remain valid.
        // A non-STRING type carries no string semantics, so the override clears it.
        Map<String, Object> booleanOverrides = new LinkedHashMap<>();
        booleanOverrides.put("dataType", SemanticDataType.BOOLEAN);
        booleanOverrides.put("stringSemantics", null);
        AttributeDefinition booleanAttribute = RecordOverride.withAll(stringAttribute, booleanOverrides);
        variants.put("attribute.dataType",
                material(List.of(booleanAttribute), List.of(), List.of()));
        // Money currency policy isolation.
        AttributeDefinition moneyAttribute = MetadataFixtures.moneyAttribute(
                "member.balance", "Member Balance", CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY);
        AttributeDefinition singleCurrencyAttribute = RecordOverride.with(
                moneyAttribute, "currencySemantics", CurrencySemantics.SINGLE_CURRENCY_ONLY);
        variants.put("attribute.currencySemantics",
                material(List.of(singleCurrencyAttribute), List.of(), List.of()));
    }

    private static void addMetricVariants(Map<String, MetadataSnapshotHashMaterial> variants) {
        MetricDefinition base = MetadataFixtures.netPaidAmountMetric();
        variants.put("metric.id", metricOnly(RecordOverride.with(base, "id", "order.net_paid_amount.revised")));
        variants.put("metric.displayName", metricOnly(RecordOverride.with(base, "displayName", "Net Spend")));
        variants.put("metric.entityVersion", metricOnly(RecordOverride.withAll(base, Map.of(
                "entityVersion", MetadataFixtures.orderVersion(), "subject", SubjectKind.ORDER))));
        variants.put("metric.aliases", metricOnly(RecordOverride.with(
                base, "aliases", List.of("消费", "净消费金额", "net spend"))));
        variants.put("metric.allowedOperators", metricOnly(RecordOverride.with(
                base, "allowedOperators", List.of(
                        ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.NOT_IN, ComparisonOperator.GREATER_THAN,
                        ComparisonOperator.GREATER_OR_EQUAL, ComparisonOperator.LESS_THAN,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL))));
        // AVG over no rows is NULL, so the aggregation switch must carry the matching empty-set behavior.
        Map<String, Object> averageOverrides = new LinkedHashMap<>();
        averageOverrides.put("aggregation", AggregateFunction.AVG);
        averageOverrides.put("emptySet", AggregateEmptySet.nullValue());
        variants.put("metric.aggregation", metricOnly(RecordOverride.withAll(base, averageOverrides)));
        variants.put("metric.relationId", metricOnly(RecordOverride.with(
                base, "relationId", "member.other_orders")));
        variants.put("metric.eventTimeField", metricOnly(RecordOverride.with(
                base, "eventTimeField", "order.created_at")));
        variants.put("metric.eventTimeSemantics", metricOnly(RecordOverride.with(
                base, "eventTimeSemantics", EventTimeSemantics.INGESTION_TIME)));
        variants.put("metric.filter", metricOnly(RecordOverride.with(base, "filter",
                new MetadataFilter(List.of(new MetadataFilterEntry("status", List.of("PAID")))))));
        variants.put("metric.refundTreatment", metricOnly(RecordOverride.with(
                base, "refundTreatment", RefundTreatment.IGNORE)));
        variants.put("metric.currencySemantics", metricOnly(RecordOverride.with(
                base, "currencySemantics", CurrencySemantics.SINGLE_CURRENCY_ONLY)));
        variants.put("metric.supportedWindows", metricOnly(RecordOverride.with(
                base, "supportedWindows", List.of(TimeWindowKind.FIXED, TimeWindowKind.LIFETIME))));
        variants.put("metric.windowBoundarySemantics", metricOnly(RecordOverride.with(
                base, "windowBoundarySemantics", WindowBoundarySemantics.INCLUSIVE_BOTH)));
        variants.put("metric.nullSemantics", metricOnly(RecordOverride.with(
                base, "nullSemantics", NullSemantics.MATCHES_NULL)));
        variants.put("metric.emptySet", metricOnly(RecordOverride.with(
                base, "emptySet", AggregateEmptySet.nullValue())));
        variants.put("metric.positiveExamples", metricOnly(RecordOverride.with(
                base, "positiveExamples", List.of("净消费金额", "净支付"))));
        variants.put("metric.negativeExamples", metricOnly(RecordOverride.with(
                base, "negativeExamples", List.of("总订单金额", "退款金额"))));
        variants.put("metric.disambiguation", metricOnly(RecordOverride.with(
                base, "disambiguation", "Net of refunds only.")));

        // Metric string semantics are hash material for a STRING result.
        MetricDefinition textMetric = MetadataFixtures.textMinMetric();
        variants.put("metric.stringSemantics.caseSensitivity", metricOnly(RecordOverride.with(
                textMetric, "stringSemantics", StringSemantics.localeNeutral(
                        StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NFKC))));
        variants.put("metric.stringSemantics.normalization", metricOnly(RecordOverride.with(
                textMetric, "stringSemantics", StringSemantics.localeNeutral(
                        StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFC))));
        variants.put("metric.stringSemantics.locale", metricOnly(RecordOverride.with(
                textMetric, "stringSemantics", StringSemantics.ofLocale(
                        StringCaseSensitivity.CASE_INSENSITIVE, StringNormalization.NFKC,
                        new LocaleTag("zh-CN")))));

        // Result-type isolation: MIN works for both STRING and DATE with the same non-currency shape.
        // A non-STRING result carries no string semantics, so the override clears it.
        Map<String, Object> dateOverrides = new LinkedHashMap<>();
        dateOverrides.put("resultType", SemanticDataType.DATE);
        dateOverrides.put("stringSemantics", null);
        MetricDefinition dateMetric = RecordOverride.withAll(textMetric, dateOverrides);
        variants.put("metric.resultType", metricOnly(dateMetric));
    }

    private static void addRelationVariants(Map<String, MetadataSnapshotHashMaterial> variants) {
        RelationDefinition base = MetadataFixtures.ordersRelation();
        variants.put("relation.id", relationOnly(RecordOverride.with(base, "id", "member.orders.revised")));
        variants.put("relation.displayName", relationOnly(RecordOverride.with(
                base, "displayName", "Purchases")));
        variants.put("relation.aliases", relationOnly(RecordOverride.with(
                base, "aliases", List.of("orders", "订单", "purchases"))));
        variants.put("relation.sourceEntity", relationOnly(RecordOverride.withAll(base, Map.of(
                "sourceEntity", SubjectKind.ORDER, "sourceVersion", MetadataFixtures.orderVersion()))));
        variants.put("relation.targetEntity", relationOnly(RecordOverride.withAll(base, Map.of(
                "targetEntity", SubjectKind.MEMBER, "targetVersion", MetadataFixtures.memberVersion()))));
        variants.put("relation.cardinality", relationOnly(RecordOverride.with(
                base, "cardinality", RelationCardinality.MANY_TO_MANY)));
        variants.put("relation.scope", relationOnly(RecordOverride.with(
                base, "scope", new RelationScope(RelationJoinKind.GLOBAL, null, null))));
        variants.put("relation.eventTimeCapability", relationOnly(RecordOverride.with(
                base, "eventTimeCapability", null)));
        variants.put("relation.targetNullSemantics", relationOnly(RecordOverride.with(
                base, "targetNullSemantics", NullSemantics.MATCHES_NULL)));
        variants.put("relation.emptyRelationSemantics", relationOnly(RecordOverride.with(
                base, "emptyRelationSemantics", EmptySetBehavior.UNDEFINED)));
        variants.put("relation.positiveExamples", relationOnly(RecordOverride.with(
                base, "positiveExamples", List.of("member orders", "member purchases"))));
        variants.put("relation.negativeExamples", relationOnly(RecordOverride.with(
                base, "negativeExamples", List.of("member profile", "member address"))));
        variants.put("relation.disambiguation", relationOnly(RecordOverride.with(
                base, "disambiguation", "Orders placed by the member.")));
    }
}