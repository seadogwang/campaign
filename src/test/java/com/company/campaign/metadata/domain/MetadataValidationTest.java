package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic validation rejects invalid combinations with stable codes instead of publishing an ambiguous or
 * contradictory snapshot. Each assertion builds the offending value inside the assertion, so a record's
 * own constructor validation and a snapshot-level check are both covered.
 */
class MetadataValidationTest {

    // --- identity and normalized collisions ---------------------------------------------------------

    @Test
    void rejectsDuplicateDefinitionIdsAcrossKinds() {
        AttributeDefinition clash = RecordOverride.with(MetadataFixtures.tierAttribute(), "id", "member.orders");
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(clash), List.of(), List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_DUPLICATE_DEFINITION_ID);
    }

    @Test
    void rejectsDuplicateDefinitionIdsWithinAKind() {
        AttributeDefinition first = MetadataFixtures.tierAttribute();
        AttributeDefinition second = RecordOverride.with(first, "displayName", "Tier Duplicate");
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(first, second), List.of(), List.of()),
                MetadataErrorCodes.METADATA_DUPLICATE_DEFINITION_ID);
    }

    @Test
    void rejectsNormalizedDisplayNameCollisionsAcrossDefinitions() {
        AttributeDefinition first = MetadataFixtures.tierAttribute();
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(first, RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.other", "Other"),
                        "displayName", "member tier")), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsAnAliasThatCollidesWithItsOwnDisplayName() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "aliases", List.of("member city"))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsDuplicateAliasesWithinADefinition() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "aliases", List.of("City", "city"))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsDuplicateEnumCodesAndAliases() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(MetadataFixtures.tierAttribute(), "enumValues", List.of(
                        new EnumValueDefinition("GOLD", List.of("Gold")),
                        new EnumValueDefinition("GOLD", List.of("Golden"))))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);

        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(MetadataFixtures.tierAttribute(), "enumValues", List.of(
                        new EnumValueDefinition("GOLD", List.of("Gold")),
                        new EnumValueDefinition("SILVER", List.of("gold"))))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsPositiveAndNegativeExamplesThatCollideAfterNormalization() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.withAll(MetadataFixtures.tierAttribute(), Map.of(
                        "positiveExamples", List.of("Gold会员"),
                        "negativeExamples", List.of("GOLD会员")))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsBlankAndMalformedText() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "displayName", "   ")), List.of(), List.of()),
                MetadataErrorCodes.METADATA_TEXT_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "id", "not a valid id!")), List.of(), List.of()),
                MetadataErrorCodes.METADATA_ID_INVALID);
    }

    // --- shared vocabulary invariants ---------------------------------------------------------------

    @Test
    void rejectsOperatorsThatDoNotApplyToTheDataType() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "allowedOperators", List.of(ComparisonOperator.GREATER_THAN))), List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsEmptyOperatorList() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "allowedOperators", List.of())), List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsEnumDefinitionsWithoutValuesAndEnumValuesOnNonEnums() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.tierAttribute(), "enumValues", List.of())), List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(RecordOverride.with(
                        MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                        "enumValues", List.of(new EnumValueDefinition("GOLD", List.of("Gold"))))),
                        List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void orderedEnumRequiresUniqueRanksAndCanonicalizesByRank() {
        AttributeDefinition ordered = RecordOverride.withAll(MetadataFixtures.tierAttribute(), Map.of(
                "dataType", SemanticDataType.ORDERED_ENUM,
                "allowedOperators", List.of(
                        ComparisonOperator.EQUAL,
                        ComparisonOperator.GREATER_OR_EQUAL,
                        ComparisonOperator.LESS_OR_EQUAL,
                        ComparisonOperator.BETWEEN),
                "enumValues", List.of(
                        new EnumValueDefinition("HIGH", List.of("高"), 30),
                        new EnumValueDefinition("LOW", List.of("低"), 10),
                        new EnumValueDefinition("MEDIUM", List.of("中"), 20))));

        assertThat(ordered.enumValues())
                .extracting(EnumValueDefinition::code)
                .containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(ordered.allowedOperators()).contains(
                ComparisonOperator.EQUAL,
                ComparisonOperator.GREATER_OR_EQUAL,
                ComparisonOperator.LESS_OR_EQUAL,
                ComparisonOperator.BETWEEN);

        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.tierAttribute(), Map.of(
                        "dataType", SemanticDataType.ORDERED_ENUM,
                        "allowedOperators", List.of(ComparisonOperator.EQUAL),
                        "enumValues", List.of(new EnumValueDefinition("LOW", List.of("低"))))),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.tierAttribute(), Map.of(
                        "dataType", SemanticDataType.ORDERED_ENUM,
                        "allowedOperators", List.of(ComparisonOperator.EQUAL),
                        "enumValues", List.of(
                                new EnumValueDefinition("LOW", List.of("低"), 10),
                                new EnumValueDefinition("HIGH", List.of("高"), 10)))),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsMoneyWithoutAnExplicitCurrencyPolicy() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(),
                        "currencySemantics", CurrencySemantics.NOT_APPLICABLE)),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsACurrencyPolicyOnANonMoneyMetric() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.textMinMetric(),
                        "currencySemantics", CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY)),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsAnAggregateThatCannotProduceTheResultType() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(), "aggregation", AggregateFunction.COUNT)),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsWindowsWithoutEventTimeSemantics() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(),
                        "eventTimeSemantics", EventTimeSemantics.NOT_APPLICABLE)),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsBoundarySemanticsThatContradictTheWindowSet() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.withAll(
                        MetadataFixtures.netPaidAmountMetric(), Map.of(
                                "supportedWindows", List.of(TimeWindowKind.ROLLING),
                                "windowBoundarySemantics", WindowBoundarySemantics.NOT_APPLICABLE))),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.textMinMetric(),
                        "windowBoundarySemantics", WindowBoundarySemantics.INCLUSIVE_BOTH)),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsAnEmptyWindowSet() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(), "supportedWindows", List.of())),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsEmptySetValuesThatContradictTheirResultType() {
        ContractAssertions.assertFailsWithCode(
                () -> new AggregateEmptySet(EmptySetBehavior.ZERO, null),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new AggregateEmptySet(EmptySetBehavior.NULL, new MetadataScalarValue(SemanticDataType.MONEY, "0")),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(),
                        "emptySet", AggregateEmptySet.zero(SemanticDataType.INTEGER))),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    // --- filters ------------------------------------------------------------------------------------

    @Test
    void rejectsInvalidFilters() {
        ContractAssertions.assertFailsWithCode(
                () -> new MetadataFilter(List.of(
                        new MetadataFilterEntry("status", List.of("PAID")),
                        new MetadataFilterEntry("STATUS", List.of("COMPLETED")))),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
        ContractAssertions.assertFailsWithCode(
                () -> new MetadataFilterEntry("status", List.of("PAID", "paid")),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
        ContractAssertions.assertFailsWithCode(
                () -> new MetadataFilterEntry("status", List.of()),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
    }

    // --- relations ----------------------------------------------------------------------------------

    @Test
    void rejectsUnknownRelationReferences() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(RecordOverride.with(
                        MetadataFixtures.netPaidAmountMetric(),
                        "relationId", "member.missing_relation")),
                        List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_RELATION_REFERENCE_UNKNOWN);
    }

    @Test
    void rejectsRelationsIncompatibleWithTheMetricSubject() {
        ContractAssertions.assertFailsWithCode(
                () -> publish(List.of(), List.of(MetadataFixtures.netPaidAmountMetric()),
                        List.of(RecordOverride.withAll(MetadataFixtures.ordersRelation(), Map.of(
                                "sourceEntity", SubjectKind.ORDER,
                                "sourceVersion", MetadataFixtures.orderVersion())))),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);
    }

    @Test
    void rejectsRelationEntitiesThatDoNotMatchTheirVersions() {
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(MetadataFixtures.ordersRelation(),
                        "sourceVersion", MetadataFixtures.orderVersion()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void rejectsInconsistentRelationScopeAndEventTime() {
        ContractAssertions.assertFailsWithCode(
                () -> new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, null, "order.member_id"),
                MetadataErrorCodes.METADATA_ID_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new RelationScope(RelationJoinKind.GLOBAL, "member.id", null),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new RelationEventTimeCapability(EventTimeSemantics.NOT_APPLICABLE, "order.paid_at"),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    // --- schema version -----------------------------------------------------------------------------

    @Test
    void rejectsUnsupportedSchemaVersions() {
        ContractAssertions.assertFailsWithCode(
                () -> MetadataSnapshot.publish(
                        new SchemaVersion(1, 1), "metadata-17", "1",
                        MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                        List.of(), List.of(), List.of()),
                MetadataErrorCodes.METADATA_SCHEMA_VERSION_UNSUPPORTED);
    }

    @Test
    void publishedSnapshotRejectsAMismatchedDeclaredHash() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        ContractAssertions.assertFailsWithCode(
                () -> new MetadataSnapshot(
                        snapshot.schemaVersion(), snapshot.snapshotId(), snapshot.snapshotVersion(),
                        snapshot.tenantId(), snapshot.programId(),
                        ContentHash.sha256("wrong".getBytes()),
                        snapshot.attributes(), snapshot.metrics(), snapshot.relations()),
                MetadataErrorCodes.METADATA_CONTENT_HASH_MISMATCH);
        assertThat(snapshot.contentHash()).isNotNull();
    }

    private static MetadataSnapshot publish(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return MetadataFixtures.snapshot(attributes, metrics, relations);
    }
}
