package com.company.campaign.query.fixture;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.company.campaign.query.fixture.DatasetField.EVENT_MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_NAME;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_STATUS;
import static com.company.campaign.query.fixture.DatasetField.MEMBER_TIER;
import static com.company.campaign.query.fixture.DatasetField.ORDER_AMOUNT;
import static com.company.campaign.query.fixture.DatasetField.ORDER_CHANNEL;
import static com.company.campaign.query.fixture.DatasetField.ORDER_ID;
import static com.company.campaign.query.fixture.DatasetField.ORDER_MEMBER_ID;
import static com.company.campaign.query.fixture.DatasetField.ORDER_PAID_AT;
import static com.company.campaign.query.fixture.DatasetField.ORDER_STATUS;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMappingWithMetricInput;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09A-R6: the canonical mapping hash covers every execution-relevant fact of every binding.
 *
 * <p>Each test changes exactly one semantic component and asserts the advertised {@code mappingHash} moves.
 * The components exercised are precisely those an earlier revision left out of the ordered sort keys — a
 * binding's dataset field, a metric's relation/target/input/filter/event-time facts and a relation's join-key
 * fields — plus the ordering keys' own components for completeness. The reordering test proves the hash is a
 * function of mapping content alone, not of caller list or map iteration order, and the execution test proves
 * the same content hash reaches {@code FixtureExecutionResult} without changing result semantics.
 */
class FixtureMappingHashTest {

    private static final SchemaVersion MEMBER_VERSION = MetadataFixtures.memberVersion().schemaVersion();
    private static final SchemaVersion ORDER_VERSION = MetadataFixtures.orderVersion().schemaVersion();
    private static final SchemaVersion OTHER_VERSION = new SchemaVersion(9, 9);

    private static final List<FixtureMapping.FieldBinding> BASELINE_FIELDS =
            List.of(field("member.id", SubjectKind.MEMBER, MEMBER_ID));
    private static final List<FixtureMapping.AttributeBinding> BASELINE_ATTRIBUTES =
            List.of(attribute("member.tier", SubjectKind.MEMBER, MEMBER_VERSION, MEMBER_TIER));
    private static final List<FixtureMapping.MetricBinding> BASELINE_METRICS = List.of(
            metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION, "member.orders",
                    SubjectKind.ORDER, null, Map.of(), null));
    private static final List<FixtureMapping.RelationBinding> BASELINE_RELATIONS = List.of(
            relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                    SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_MEMBER_ID));

    @TempDir
    Path repositoryRoot;

    // ------------------------------------------------------------------ field bindings

    @Test
    void fieldBindingNameEntityAndDatasetFieldEachChangeTheHash() {
        FixtureMapping baseline = mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);

        assertThat(withFields(List.of(field("member.other_id", SubjectKind.MEMBER, MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withFields(List.of(field("order.member_id", SubjectKind.ORDER, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withFields(List.of(field("member.id", SubjectKind.MEMBER, MEMBER_NAME))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
    }

    // ------------------------------------------------------------------ attribute bindings

    @Test
    void attributeDefinitionSubjectVersionAndDatasetFieldEachChangeTheHash() {
        FixtureMapping baseline = mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);

        assertThat(withAttributes(List.of(
                attribute("member.other_tier", SubjectKind.MEMBER, MEMBER_VERSION, MEMBER_TIER))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        // A subject change forces the dataset field's entity to follow, so both move together here.
        assertThat(withAttributes(List.of(
                attribute("member.tier", SubjectKind.ORDER, ORDER_VERSION, ORDER_STATUS))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withAttributes(List.of(
                attribute("member.tier", SubjectKind.MEMBER, OTHER_VERSION, MEMBER_TIER))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withAttributes(List.of(
                attribute("member.tier", SubjectKind.MEMBER, MEMBER_VERSION, MEMBER_STATUS))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
    }

    // ------------------------------------------------------------------ metric bindings

    @Test
    void metricDefinitionSubjectVersionRelationAndTargetEachChangeTheHash() {
        FixtureMapping baseline = mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);

        assertThat(withMetrics(List.of(orderCount("order.other_count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER))).mappingHash()).isNotEqualTo(baseline.mappingHash());
        assertThat(withMetrics(List.of(orderCount("order.count", SubjectKind.ORDER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER))).mappingHash()).isNotEqualTo(baseline.mappingHash());
        assertThat(withMetrics(List.of(orderCount("order.count", SubjectKind.MEMBER, OTHER_VERSION,
                "member.orders", SubjectKind.ORDER))).mappingHash()).isNotEqualTo(baseline.mappingHash());
        assertThat(withMetrics(List.of(orderCount("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.other_orders", SubjectKind.ORDER))).mappingHash()).isNotEqualTo(baseline.mappingHash());
        assertThat(withMetrics(List.of(orderCount("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.EVENT))).mappingHash()).isNotEqualTo(baseline.mappingHash());
    }

    @Test
    void metricInputFilterAndEventTimeFactsEachChangeTheHash() {
        FixtureMapping baseline = mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);
        FixtureMapping metered = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_AMOUNT, Map.of("status", ORDER_STATUS), ORDER_PAID_AT)));

        assertThat(metered.mappingHash()).isNotEqualTo(baseline.mappingHash());
        // Nullable input field: absent, one field, and a different field are three distinct facts.
        FixtureMapping noInput = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, null, Map.of("status", ORDER_STATUS), ORDER_PAID_AT)));
        FixtureMapping otherInput = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_CHANNEL, Map.of("status", ORDER_STATUS), ORDER_PAID_AT)));
        assertThat(noInput.mappingHash()).isNotEqualTo(metered.mappingHash());
        assertThat(otherInput.mappingHash()).isNotEqualTo(metered.mappingHash());

        // Filter map: presence, key and bound value each move the hash.
        FixtureMapping noFilter = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_AMOUNT, Map.of(), ORDER_PAID_AT)));
        FixtureMapping otherFilterKey = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_AMOUNT, Map.of("channel", ORDER_STATUS), ORDER_PAID_AT)));
        FixtureMapping otherFilterValue = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_AMOUNT, Map.of("status", ORDER_CHANNEL), ORDER_PAID_AT)));
        assertThat(noFilter.mappingHash()).isNotEqualTo(metered.mappingHash());
        assertThat(otherFilterKey.mappingHash()).isNotEqualTo(metered.mappingHash());
        assertThat(otherFilterValue.mappingHash()).isNotEqualTo(metered.mappingHash());

        // Nullable event-time field: absent versus present.
        FixtureMapping noEventTime = withMetrics(List.of(metric("order.count", SubjectKind.MEMBER, MEMBER_VERSION,
                "member.orders", SubjectKind.ORDER, ORDER_AMOUNT, Map.of("status", ORDER_STATUS), null)));
        assertThat(noEventTime.mappingHash()).isNotEqualTo(metered.mappingHash());
    }

    // ------------------------------------------------------------------ relation bindings

    @Test
    void relationIdVersionPinsJoinKeyAndTargetFactsEachChangeTheHash() {
        FixtureMapping baseline = mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);

        assertThat(withRelations(List.of(relation("member.other_orders", SubjectKind.MEMBER, MEMBER_VERSION,
                SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.MEMBER, OTHER_VERSION,
                SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                SubjectKind.ORDER, OTHER_VERSION, MEMBER_ID, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        // The two join-key dataset fields were the omitted facts; each moves the hash on its own.
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                SubjectKind.ORDER, ORDER_VERSION, MEMBER_TIER, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        // A source/target entity change forces its key field's entity to follow.
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.EVENT, MEMBER_VERSION,
                SubjectKind.ORDER, ORDER_VERSION, EVENT_MEMBER_ID, ORDER_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
        assertThat(withRelations(List.of(relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                SubjectKind.EVENT, ORDER_VERSION, MEMBER_ID, EVENT_MEMBER_ID))).mappingHash())
                .isNotEqualTo(baseline.mappingHash());
    }

    // ------------------------------------------------------------------ order independence

    @Test
    void reorderingEquivalentInputListsAndFilterMapsProducesTheSameHash() {
        List<FixtureMapping.FieldBinding> fields = List.of(
                field("member.id", SubjectKind.MEMBER, MEMBER_ID),
                field("member.name", SubjectKind.MEMBER, MEMBER_NAME));
        List<FixtureMapping.AttributeBinding> attributes = List.of(
                attribute("member.tier", SubjectKind.MEMBER, MEMBER_VERSION, MEMBER_TIER),
                attribute("member.status", SubjectKind.MEMBER, MEMBER_VERSION, MEMBER_STATUS));
        List<FixtureMapping.RelationBinding> relations = List.of(
                relation("member.orders", SubjectKind.MEMBER, MEMBER_VERSION,
                        SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_MEMBER_ID),
                relation("member.refunds", SubjectKind.MEMBER, MEMBER_VERSION,
                        SubjectKind.ORDER, ORDER_VERSION, MEMBER_ID, ORDER_ID));

        Map<String, DatasetField> filtersForward = new LinkedHashMap<>();
        filtersForward.put("status", ORDER_STATUS);
        filtersForward.put("channel", ORDER_CHANNEL);
        Map<String, DatasetField> filtersBackward = new LinkedHashMap<>();
        filtersBackward.put("channel", ORDER_CHANNEL);
        filtersBackward.put("status", ORDER_STATUS);

        List<FixtureMapping.MetricBinding> metricsForward = List.of(
                metric("order.a", SubjectKind.MEMBER, MEMBER_VERSION, "member.orders",
                        SubjectKind.ORDER, ORDER_AMOUNT, filtersForward, ORDER_PAID_AT),
                metric("order.b", SubjectKind.MEMBER, MEMBER_VERSION, "member.orders",
                        SubjectKind.ORDER, null, Map.of(), null));
        List<FixtureMapping.MetricBinding> metricsBackward = List.of(
                metric("order.b", SubjectKind.MEMBER, MEMBER_VERSION, "member.orders",
                        SubjectKind.ORDER, null, Map.of(), null),
                metric("order.a", SubjectKind.MEMBER, MEMBER_VERSION, "member.orders",
                        SubjectKind.ORDER, ORDER_AMOUNT, filtersBackward, ORDER_PAID_AT));

        FixtureMapping forward = mapping(fields, attributes, metricsForward, relations);
        FixtureMapping reversed =
                mapping(reversed(fields), reversed(attributes), metricsBackward, reversed(relations));

        assertThat(reversed.mappingHash()).isEqualTo(forward.mappingHash());
    }

    // ------------------------------------------------------------------ execution result

    @Test
    void executionResultMappingHashTracksMappingContentWithoutChangingResultSemantics() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        SemanticQueryPlan plan = plan(predicate());
        FixtureMapping baseline = standardMapping();
        // Same mapping version, fields, attributes, relations and metrics except one unused metric's input
        // field: only the canonical mapping content differs.
        FixtureMapping variant = standardMappingWithMetricInput("order.latest_channel", ORDER_AMOUNT);
        assertThat(baseline.mappingHash()).isNotEqualTo(variant.mappingHash());

        FixtureExecutionResult baselineResult =
                EXECUTOR.execute(plan, dataset, baseline, standardScope(), cnyFx("7.00"));
        FixtureExecutionResult variantResult =
                EXECUTOR.execute(plan, dataset, variant, standardScope(), cnyFx("7.00"));

        assertThat(baselineResult.mappingHash()).isEqualTo(baseline.mappingHash());
        assertThat(variantResult.mappingHash()).isEqualTo(variant.mappingHash());
        assertThat(variantResult.mappingHash()).isNotEqualTo(baselineResult.mappingHash());
        // The swapped metric is not referenced by the plan, so the executed result semantics are identical;
        // only the mapping pin — and therefore the result hash — reflects the different mapping content.
        assertThat(variantResult.memberIdSet()).isEqualTo(baselineResult.memberIdSet());
        assertThat(variantResult.conditionStatistics()).isEqualTo(baselineResult.conditionStatistics());
        assertThat(variantResult.resultHash()).isNotEqualTo(baselineResult.resultHash());
    }

    // ------------------------------------------------------------------ helpers

    private static FixtureMapping mapping(
            List<FixtureMapping.FieldBinding> fields,
            List<FixtureMapping.AttributeBinding> attributes,
            List<FixtureMapping.MetricBinding> metrics,
            List<FixtureMapping.RelationBinding> relations) {
        return FixtureMapping.publish("fixture-mapping-v1", fields, attributes, metrics, relations);
    }

    private static FixtureMapping withFields(List<FixtureMapping.FieldBinding> fields) {
        return mapping(fields, BASELINE_ATTRIBUTES, BASELINE_METRICS, BASELINE_RELATIONS);
    }

    private static FixtureMapping withAttributes(List<FixtureMapping.AttributeBinding> attributes) {
        return mapping(BASELINE_FIELDS, attributes, BASELINE_METRICS, BASELINE_RELATIONS);
    }

    private static FixtureMapping withMetrics(List<FixtureMapping.MetricBinding> metrics) {
        return mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, metrics, BASELINE_RELATIONS);
    }

    private static FixtureMapping withRelations(List<FixtureMapping.RelationBinding> relations) {
        return mapping(BASELINE_FIELDS, BASELINE_ATTRIBUTES, BASELINE_METRICS, relations);
    }

    private static FixtureMapping.FieldBinding field(
            String fieldName, SubjectKind entity, DatasetField field) {
        return new FixtureMapping.FieldBinding(fieldName, entity, field);
    }

    private static FixtureMapping.AttributeBinding attribute(
            String definitionId, SubjectKind subject, SchemaVersion version, DatasetField field) {
        return new FixtureMapping.AttributeBinding(definitionId, subject, version, field);
    }

    private static FixtureMapping.MetricBinding metric(
            String definitionId,
            SubjectKind subject,
            SchemaVersion version,
            String relationId,
            SubjectKind targetEntity,
            DatasetField inputField,
            Map<String, DatasetField> filterFields,
            DatasetField eventTimeField) {
        return new FixtureMapping.MetricBinding(
                definitionId, subject, version, relationId, targetEntity, inputField, filterFields, eventTimeField);
    }

    private static FixtureMapping.MetricBinding orderCount(
            String definitionId, SubjectKind subject, SchemaVersion version, String relationId,
            SubjectKind targetEntity) {
        return metric(definitionId, subject, version, relationId, targetEntity, null, Map.of(), null);
    }

    private static FixtureMapping.RelationBinding relation(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceVersion,
            SubjectKind targetEntity,
            SchemaVersion targetVersion,
            DatasetField sourceKeyField,
            DatasetField targetKeyField) {
        return new FixtureMapping.RelationBinding(
                relationId, sourceEntity, sourceVersion, targetEntity, targetVersion, sourceKeyField, targetKeyField);
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }

    private static Predicate predicate() {
        return SegmentValidationFixtures.all("n-and", List.of(
                SegmentValidationFixtures.metric(
                        "n-count", "order.count", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))),
                SegmentValidationFixtures.attribute(
                        "n-balance", "member.balance", ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0.00", "CNY")))));
    }
}