package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 4: a bounded-window metric must reference a relation whose event-time capability exactly agrees,
 * and a {@code LIFETIME}-only metric may omit event time structurally but must not declare it partially.
 */
class MetadataEventTimeCompatibilityTest {

    @Test
    void boundedMetricRequiresAMatchingRelationEventTimeCapability() {
        // The standard fixture already proves the matching case.
        assertThat(MetadataFixtures.snapshot(
                List.of(), List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()))).isNotNull();

        // No event-time capability on the relation.
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(), List.of(MetadataFixtures.netPaidAmountMetric()),
                        List.of(MetadataFixtures.ordersRelationWithoutEventTime())),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);

        // Different event-time field.
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(), List.of(MetadataFixtures.netPaidAmountMetric()),
                        List.of(relationWith(EventTimeSemantics.EVENT_TIME, "order.created_at"))),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);

        // Different event-time semantics.
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(), List.of(MetadataFixtures.netPaidAmountMetric()),
                        List.of(relationWith(EventTimeSemantics.INGESTION_TIME, "order.paid_at"))),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);
    }

    @Test
    void boundedMetricMustInternallyPinEventTime() {
        Map<String, Object> missingField = new LinkedHashMap<>();
        missingField.put("eventTimeField", null);
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.netPaidAmountMetric(), missingField),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        Map<String, Object> missingSemantics = new LinkedHashMap<>();
        missingSemantics.put("eventTimeSemantics", null);
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.netPaidAmountMetric(), missingSemantics),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(MetadataFixtures.netPaidAmountMetric(),
                        "eventTimeSemantics", EventTimeSemantics.NOT_APPLICABLE),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void lifetimeOnlyMetricMayOmitEventTimeStructurally() {
        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("eventTimeField", null);
        omitted.put("eventTimeSemantics", null);
        MetricDefinition lifetime = RecordOverride.withAll(MetadataFixtures.textMinMetric(), omitted);
        assertThat(lifetime.eventTimeField()).isNull();
        assertThat(lifetime.eventTimeSemantics()).isNull();

        // Omission is allowed whether or not the relation happens to declare a capability.
        assertThat(MetadataFixtures.snapshot(
                List.of(), List.of(lifetime), List.of(MetadataFixtures.ordersRelation()))).isNotNull();
        assertThat(MetadataFixtures.snapshot(
                List.of(), List.of(lifetime),
                List.of(MetadataFixtures.ordersRelationWithoutEventTime()))).isNotNull();
    }

    @Test
    void omittedEventTimeRoundTripsThroughCanonicalJson() {
        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("eventTimeField", null);
        omitted.put("eventTimeSemantics", null);
        MetricDefinition lifetime = RecordOverride.withAll(MetadataFixtures.textMinMetric(), omitted);
        MetadataSnapshot snapshot = MetadataFixtures.snapshot(
                List.of(), List.of(lifetime), List.of(MetadataFixtures.ordersRelation()));

        CanonicalJson json = new CanonicalJson();
        String canonical = json.writeString(snapshot);
        MetadataSnapshot restored = json.read(canonical, MetadataSnapshot.class);

        assertThat(restored).isEqualTo(snapshot);
        assertThat(restored.metrics().get(0).eventTimeField()).isNull();
        assertThat(restored.metrics().get(0).eventTimeSemantics()).isNull();
    }

    @Test
    void lifetimeOnlyMetricRejectsPartialDeclarations() {
        Map<String, Object> fieldOnly = new LinkedHashMap<>();
        fieldOnly.put("eventTimeField", "order.paid_at");
        fieldOnly.put("eventTimeSemantics", null);
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.textMinMetric(), fieldOnly),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        Map<String, Object> semanticsOnly = new LinkedHashMap<>();
        semanticsOnly.put("eventTimeField", null);
        semanticsOnly.put("eventTimeSemantics", EventTimeSemantics.EVENT_TIME);
        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.withAll(MetadataFixtures.textMinMetric(), semanticsOnly),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> RecordOverride.with(MetadataFixtures.textMinMetric(),
                        "eventTimeSemantics", EventTimeSemantics.NOT_APPLICABLE),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }

    @Test
    void lifetimeOnlyMetricThatPinsEventTimeMustStillAgreeWithTheRelation() {
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(), List.of(MetadataFixtures.textMinMetric()),
                        List.of(relationWith(EventTimeSemantics.EVENT_TIME, "order.created_at"))),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);

        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(), List.of(MetadataFixtures.textMinMetric()),
                        List.of(MetadataFixtures.ordersRelationWithoutEventTime())),
                MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE);
    }

    private static RelationDefinition relationWith(EventTimeSemantics semantics, String field) {
        return RecordOverride.with(MetadataFixtures.ordersRelation(), "eventTimeCapability",
                new RelationEventTimeCapability(semantics, field));
    }
}