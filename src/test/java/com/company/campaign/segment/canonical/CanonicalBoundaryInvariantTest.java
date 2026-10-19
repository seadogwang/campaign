package com.company.campaign.segment.canonical;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataScalarValue;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial direct-construction tests for every public canonical boundary.
 *
 * <p>Each case proves that a caller cannot build an artifact the canonicalizer would never emit: a forged
 * value form, an unsorted or repeated set, a mismatched operator/value shape, an inverted fixed window or an
 * arbitrary version pin. Every rejection must be the coded bounded failure and must not echo the rejected
 * payload.
 *
 * <p>The unforgeable {@code SegmentValidationResult} proof boundary is covered by
 * {@code com.company.campaign.segment.validation.SegmentValidationResultProofBoundaryTest}, which can reach the
 * package-private internal construction paths this class deliberately cannot.
 */
class CanonicalBoundaryInvariantTest {

    private static final String VERSION = CanonicalizerVersions.CANONICALIZER_VERSION;

    private static void assertRejected(Runnable construction, String expectedCode) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CanonicalInvariantException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    // ------------------------------------------------------------------ scalar forms

    @Test
    void nonCanonicalDecimalFormsAreRejected() {
        for (String value : List.of("1.00", "1.0", "0.0", "-0", "01", "+1", "1e2", " 1", "1 ")) {
            assertRejected(() -> new CanonicalValue.DecimalValue(value),
                    CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        }
    }

    @Test
    void canonicalDecimalFormsAreAccepted() {
        assertThat(new CanonicalValue.DecimalValue("1")).isEqualTo(new CanonicalValue.DecimalValue("1"));
        assertThat(new CanonicalValue.DecimalValue("0").value()).isEqualTo("0");
        assertThat(new CanonicalValue.DecimalValue("-1.5").value()).isEqualTo("-1.5");
        assertThat(new CanonicalValue.DecimalValue("100").value()).isEqualTo("100");
    }

    @Test
    void nonCanonicalInstantFormsAreRejected() {
        for (String value : List.of(
                "2026-01-01T08:00:00+08:00", "2026-01-01T00:00:00z", "2026-01-01T00:00:00",
                "2026-01-01", "2026-01-01T00:00Z")) {
            assertRejected(() -> new CanonicalValue.InstantValue(value),
                    CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        }
    }

    @Test
    void canonicalInstantFormsAreAccepted() {
        assertThat(new CanonicalValue.InstantValue("2026-01-01T00:00:00Z").instant())
                .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(new CanonicalValue.InstantValue("2026-01-01T00:00:00.250Z").instant())
                .isEqualTo("2026-01-01T00:00:00.250Z");
    }

    @Test
    void nonCanonicalDateFormsAreRejected() {
        for (String value : List.of("2026-1-1", "20260101", "2026-13-01", "2026-02-30", "2026-01-01T00:00:00Z")) {
            assertRejected(() -> new CanonicalValue.DateValue(value),
                    CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        }
        assertThat(new CanonicalValue.DateValue("2026-01-01").value()).isEqualTo("2026-01-01");
    }

    @Test
    void nonCanonicalEnumAndCurrencyFormsAreRejected() {
        for (String code : List.of("gold", "Gold", "1GOLD", "GOLD-1", "")) {
            assertRejected(() -> new CanonicalValue.EnumValue(code),
                    CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        }
        for (String currency : List.of("cny", "CN", "CNYY", "C1Y")) {
            assertRejected(() -> new CanonicalValue.MoneyValue("10", currency),
                    CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        }
        assertRejected(() -> new CanonicalValue.MoneyValue("10.00", "CNY"),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        assertThat(new CanonicalValue.EnumValue("GOLD_2").code()).isEqualTo("GOLD_2");
        assertThat(new CanonicalValue.MoneyValue("1000", "CNY").currency()).isEqualTo("CNY");
    }

    // ------------------------------------------------------------------ list and range shapes

    @Test
    void aCanonicalListIsSortedAndRejectsRepeatedItems() {
        CanonicalValue.ListValue reversed = new CanonicalValue.ListValue(
                List.of(new CanonicalValue.IntegerValue(2), new CanonicalValue.IntegerValue(1)));
        CanonicalValue.ListValue ordered = new CanonicalValue.ListValue(
                List.of(new CanonicalValue.IntegerValue(1), new CanonicalValue.IntegerValue(2)));

        assertThat(reversed).isEqualTo(ordered);
        assertRejected(() -> new CanonicalValue.ListValue(List.of(
                        new CanonicalValue.IntegerValue(1), new CanonicalValue.IntegerValue(1))),
                CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
    }

    @Test
    void aCanonicalListRejectsEmptyNestedOrHeterogeneousItems() {
        assertRejected(() -> new CanonicalValue.ListValue(List.of()),
                CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
        assertRejected(() -> new CanonicalValue.ListValue(List.of(
                        new CanonicalValue.ListValue(List.of(new CanonicalValue.IntegerValue(1))))),
                CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
        assertRejected(() -> new CanonicalValue.ListValue(List.of(
                        new CanonicalValue.IntegerValue(1), new CanonicalValue.TextValue("a"))),
                CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
    }

    @Test
    void aCanonicalListIsAnImmutableCopy() {
        List<CanonicalValue> source = new ArrayList<>(List.of(new CanonicalValue.IntegerValue(1)));
        CanonicalValue.ListValue list = new CanonicalValue.ListValue(source);
        source.add(new CanonicalValue.IntegerValue(2));

        assertThat(list.items()).hasSize(1);
        assertThatThrownBy(() -> list.items().add(new CanonicalValue.IntegerValue(3)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aCanonicalStringMustBeWellFormedUnicode() {
        assertRejected(() -> new CanonicalValue.TextValue("\uD800"),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        assertRejected(() -> new CanonicalValue.TextValue("a\uDC00b"),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        assertThat(new CanonicalValue.TextValue("gold").value()).isEqualTo("gold");
    }

    @Test
    void aCanonicalListExceedsTheDeclarableItemBoundIsRejected() {
        List<CanonicalValue> tooMany = new ArrayList<>();
        for (int index = 0; index <= com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_LIST_ITEMS;
                index++) {
            tooMany.add(new CanonicalValue.IntegerValue(index));
        }

        assertRejected(() -> new CanonicalValue.ListValue(tooMany),
                CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
    }

    @Test
    void aCanonicalRangeMustHonourIntrinsicOrderAndPointInclusion() {
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.IntegerValue(2), new CanonicalValue.IntegerValue(1), true, true),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.IntegerValue(1), new CanonicalValue.IntegerValue(1), true, false),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.InstantValue("2026-02-01T00:00:00Z"),
                        new CanonicalValue.InstantValue("2026-01-01T00:00:00Z"), true, true),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        // STRING order is Metadata-owned and deliberately not decided at the canonical boundary.
        assertThat(new CanonicalValue.RangeValue(
                new CanonicalValue.TextValue("z"), new CanonicalValue.TextValue("a"), true, false)).isNotNull();
        assertThat(new CanonicalValue.RangeValue(
                new CanonicalValue.IntegerValue(1), new CanonicalValue.IntegerValue(1), true, true)).isNotNull();
    }

    @Test
    void aCanonicalRangeRejectsIncompatibleBoundKinds() {
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.IntegerValue(1), new CanonicalValue.TextValue("2"), true, true),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.IntegerValue(1),
                        new CanonicalValue.ListValue(List.of(new CanonicalValue.IntegerValue(2))), true, true),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.MoneyValue("1", "CNY"), new CanonicalValue.MoneyValue("2", "USD"),
                        true, true),
                CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE);
        assertThat(new CanonicalValue.RangeValue(
                new CanonicalValue.IntegerValue(1), new CanonicalValue.IntegerValue(2), true, false).includeUpper())
                .isFalse();
    }

    // ------------------------------------------------------------------ groups

    @Test
    void aCanonicalGroupIsSortedAndRejectsRepeatedChildren() {
        CanonicalNode first = attribute(new CanonicalValue.EnumValue("GOLD"));
        CanonicalNode second = attribute(new CanonicalValue.EnumValue("SILVER"));

        assertThat(new CanonicalNode.Group(CanonicalNode.GroupKind.ALL, List.of(second, first)))
                .isEqualTo(new CanonicalNode.Group(CanonicalNode.GroupKind.ALL, List.of(first, second)));
        assertRejected(() -> new CanonicalNode.Group(CanonicalNode.GroupKind.ANY, List.of(first, first)),
                CanonicalErrorCodes.CANONICAL_GROUP_NOT_CANONICAL);
        assertRejected(() -> new CanonicalNode.Group(CanonicalNode.GroupKind.ALL, List.of()),
                CanonicalErrorCodes.CANONICAL_GROUP_NOT_CANONICAL);
    }

    // ------------------------------------------------------------------ operator/value shape

    @Test
    void aNullOperatorMustCarryNoValueAndAComparisonMustCarryOne() {
        assertRejected(() -> new CanonicalNode.Attribute(
                        attributeDefinition(), ComparisonOperator.IS_NULL, new CanonicalValue.IntegerValue(1)),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
        assertRejected(() -> new CanonicalNode.Attribute(
                        attributeDefinition(), ComparisonOperator.EQUAL, null),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
    }

    @Test
    void theOperatorMustCarryItsOwnValueShape() {
        assertRejected(() -> new CanonicalNode.Attribute(attributeDefinition(), ComparisonOperator.IN,
                        new CanonicalValue.IntegerValue(1)),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
        assertRejected(() -> new CanonicalNode.Attribute(attributeDefinition(), ComparisonOperator.BETWEEN,
                        new CanonicalValue.IntegerValue(1)),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
        assertRejected(() -> new CanonicalNode.Attribute(attributeDefinition(), ComparisonOperator.EQUAL,
                        new CanonicalValue.ListValue(List.of(new CanonicalValue.IntegerValue(1)))),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
        assertRejected(() -> new CanonicalNode.Metric(metricDefinition(), new CanonicalWindow.RollingWindow(7),
                        ComparisonOperator.GREATER_THAN, null),
                CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID);
    }

    @Test
    void anOperandMustMatchItsDefinitionDataType() {
        // member.tier is an ENUM attribute, so a STRING operand is a state the canonicalizer never emits.
        assertRejected(() -> new CanonicalNode.Attribute(
                        attributeDefinition(), ComparisonOperator.EQUAL, new CanonicalValue.TextValue("GOLD")),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        assertThat(new CanonicalNode.Attribute(attributeDefinition(), ComparisonOperator.IS_NULL, null))
                .isNotNull();
    }

    @Test
    void aMetricOperandMustMatchItsResultType() {
        assertRejected(() -> new CanonicalNode.Metric(metricDefinition(), new CanonicalWindow.RollingWindow(7),
                        ComparisonOperator.GREATER_THAN, new CanonicalValue.IntegerValue(1)),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
    }

    // ------------------------------------------------------------------ windows

    @Test
    void aCanonicalExistsMustNotContainAMetricAggregate() {
        CanonicalNode metric = new CanonicalNode.Metric(metricDefinition(), new CanonicalWindow.RollingWindow(7),
                ComparisonOperator.GREATER_THAN, new CanonicalValue.MoneyValue("10", "CNY"));
        CanonicalNode attribute = attribute(new CanonicalValue.EnumValue("GOLD"));

        assertRejected(() -> new CanonicalNode.Exists(relationReference(),
                        new CanonicalWindow.LifetimeWindow(), metric),
                CanonicalErrorCodes.CANONICAL_SCOPE_INVALID);
        assertRejected(() -> new CanonicalNode.Exists(relationReference(),
                        new CanonicalWindow.LifetimeWindow(),
                        new CanonicalNode.Group(CanonicalNode.GroupKind.ALL, List.of(metric))),
                CanonicalErrorCodes.CANONICAL_SCOPE_INVALID);
        assertThat(new CanonicalNode.Exists(relationReference(),
                new CanonicalWindow.LifetimeWindow(), attribute)).isNotNull();
    }

    @Test
    void aCanonicalRollingWindowIsABoundedPositiveDayCount() {
        assertRejected(() -> new CanonicalWindow.RollingWindow(0),
                CanonicalErrorCodes.CANONICAL_WINDOW_INVALID);
        assertRejected(() -> new CanonicalWindow.RollingWindow(-1),
                CanonicalErrorCodes.CANONICAL_WINDOW_INVALID);
        assertRejected(() -> new CanonicalWindow.RollingWindow(CanonicalWindow.RollingWindow.MAX_DAYS + 1),
                CanonicalErrorCodes.CANONICAL_WINDOW_INVALID);
        assertThat(new CanonicalWindow.RollingWindow(CanonicalWindow.RollingWindow.MAX_DAYS).days())
                .isEqualTo(700_000L);
    }

    @Test
    void aCanonicalFixedWindowNeedsCanonicalAscendingBoundaries() {
        assertRejected(() -> new CanonicalWindow.FixedWindow(
                        "2026-01-01T08:00:00+08:00", "2026-02-01T00:00:00Z"),
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID);
        assertRejected(() -> new CanonicalWindow.FixedWindow(
                        "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                CanonicalErrorCodes.CANONICAL_WINDOW_INVALID);
        assertRejected(() -> new CanonicalWindow.FixedWindow(
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                CanonicalErrorCodes.CANONICAL_WINDOW_INVALID);
        assertThat(new CanonicalWindow.FixedWindow(
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z").startInstant())
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    // ------------------------------------------------------------------ definitions and relations

    @Test
    void aCanonicalAttributeDefinitionMustBeAnAttributeWithConsistentSemantics() {
        assertRejected(() -> new CanonicalDefinition(MetadataKind.METRIC, "member.tier", SubjectKind.MEMBER,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.name", SubjectKind.MEMBER,
                        SemanticDataType.STRING, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", SubjectKind.MEMBER,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        com.company.campaign.metadata.testsupport.MetadataFixtures.stringSemantics()),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.balance", SubjectKind.MEMBER,
                        SemanticDataType.MONEY, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertThat(attributeDefinition()).isNotNull();
    }

    @Test
    void aCanonicalMetricDefinitionMustPinAConsistentEmptySet() {
        assertRejected(() -> new CanonicalMetricDefinition("order.count", SubjectKind.MEMBER,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, AggregateFunction.COUNT, EmptySetBehavior.UNDEFINED, null,
                        null, WindowBoundarySemantics.NOT_APPLICABLE),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertRejected(() -> new CanonicalMetricDefinition("order.count", SubjectKind.MEMBER,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, AggregateFunction.COUNT, EmptySetBehavior.ZERO, null,
                        null, WindowBoundarySemantics.NOT_APPLICABLE),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertRejected(() -> new CanonicalMetricDefinition("order.count", SubjectKind.MEMBER,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, AggregateFunction.COUNT, EmptySetBehavior.ZERO,
                        new MetadataScalarValue(SemanticDataType.MONEY, "0"),
                        null, WindowBoundarySemantics.NOT_APPLICABLE),
                CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID);
        assertThat(metricDefinition()).isNotNull();
    }

    @Test
    void aCanonicalRelationReferenceMustPinAConsistentJoinAndEventTime() {
        assertRejected(() -> new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, SubjectKind.ORDER,
                        RelationCardinality.ONE_TO_MANY, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        null, "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL, null, null),
                CanonicalErrorCodes.CANONICAL_RELATION_INVALID);
        assertRejected(() -> new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, SubjectKind.ORDER,
                        RelationCardinality.ONE_TO_MANY, RelationJoinKind.GLOBAL,
                        "source.id", null, NullSemantics.EXCLUDED, EmptySetBehavior.NULL, null, null),
                CanonicalErrorCodes.CANONICAL_RELATION_INVALID);
        assertRejected(() -> new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, SubjectKind.ORDER,
                        RelationCardinality.ONE_TO_MANY, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                        EventTimeSemantics.EVENT_TIME, null),
                CanonicalErrorCodes.CANONICAL_RELATION_INVALID);
        assertThat(relationReference()).isNotNull();
    }

    // ------------------------------------------------------------------ canonical root

    @Test
    void aCanonicalRootMustCarryTheSupportedVersions() {
        CanonicalSegment genuine = genuineSegment();

        assertRejected(() -> new CanonicalSegment("segment-canonicalizer/2.0", genuine.schemaVersion(),
                        genuine.subject(), genuine.metadata(), genuine.predicate()),
                CanonicalErrorCodes.CANONICALIZER_VERSION_UNSUPPORTED);
        assertRejected(() -> new CanonicalSegment(VERSION, new SchemaVersion(2, 0),
                        genuine.subject(), genuine.metadata(), genuine.predicate()),
                CanonicalErrorCodes.CANONICAL_SCHEMA_UNSUPPORTED);
        assertRejected(() -> new CanonicalSegment(VERSION, null,
                        genuine.subject(), genuine.metadata(), genuine.predicate()),
                CanonicalErrorCodes.CANONICAL_SCHEMA_UNSUPPORTED);
    }

    // ------------------------------------------------------------------ null inputs are stable coded failures

    private static final String NULL_INPUT = CanonicalErrorCodes.CANONICAL_INPUT_MISSING;

    @Test
    void nullValueInputsAreStableCodedFailures() {
        assertRejected(() -> new CanonicalValue.TextValue(null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.DecimalValue(null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.DateValue(null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.InstantValue(null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.EnumValue(null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.MoneyValue(null, "CNY"), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.MoneyValue("1", null), NULL_INPUT);
        assertRejected(() -> new CanonicalValue.ListValue(null), CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL);
    }

    @Test
    void nullListElementsAndRangeBoundsAreStableCodedFailures() {
        assertRejected(() -> new CanonicalValue.ListValue(Arrays.asList(
                        new CanonicalValue.IntegerValue(1), null)),
                NULL_INPUT);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        null, new CanonicalValue.IntegerValue(1), true, true),
                NULL_INPUT);
        assertRejected(() -> new CanonicalValue.RangeValue(
                        new CanonicalValue.IntegerValue(1), null, true, true),
                NULL_INPUT);
    }

    @Test
    void nullNodeChildrenOperandsDefinitionsOperatorsAndWindowsAreStableCodedFailures() {
        CanonicalNode attribute = attribute(new CanonicalValue.EnumValue("GOLD"));
        CanonicalWindow rolling = new CanonicalWindow.RollingWindow(7);

        assertRejected(() -> new CanonicalNode.Group(null, List.of(attribute)), NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Group(
                        CanonicalNode.GroupKind.ALL, Arrays.asList(attribute, null)),
                NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Negation(null), NULL_INPUT);

        assertRejected(() -> new CanonicalNode.Attribute(null, ComparisonOperator.IS_NULL, null), NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Attribute(attributeDefinition(), null, null), NULL_INPUT);

        assertRejected(() -> new CanonicalNode.Metric(null, rolling, ComparisonOperator.IS_NULL, null), NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Metric(
                        metricDefinition(), null, ComparisonOperator.IS_NULL, null),
                NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Metric(metricDefinition(), rolling, null, null), NULL_INPUT);

        assertRejected(() -> new CanonicalNode.Exists(null, lifetime(), attribute), NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Exists(relationReference(), null, attribute), NULL_INPUT);
        assertRejected(() -> new CanonicalNode.Exists(relationReference(), lifetime(), null), NULL_INPUT);
    }

    @Test
    void nullDefinitionRelationWindowAndRootFieldsAreStableCodedFailures() {
        assertRejected(() -> new CanonicalDefinition(null, "member.tier", SubjectKind.MEMBER,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                NULL_INPUT);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", null,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                NULL_INPUT);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", SubjectKind.MEMBER,
                        null, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                NULL_INPUT);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", SubjectKind.MEMBER,
                        SemanticDataType.ENUM, null, CurrencySemantics.NOT_APPLICABLE, null),
                NULL_INPUT);
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", SubjectKind.MEMBER,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, null, null),
                NULL_INPUT);
        // A missing reference id keeps its own documented "identifier invalid" code.
        assertRejected(() -> new CanonicalDefinition(MetadataKind.ATTRIBUTE, null, SubjectKind.MEMBER,
                        SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null),
                CanonicalErrorCodes.CANONICAL_REFERENCE_ID_INVALID);

        assertRejected(() -> new CanonicalMetricDefinition("order.count", null,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, AggregateFunction.COUNT, EmptySetBehavior.ZERO,
                        new MetadataScalarValue(SemanticDataType.INTEGER, "0"),
                        null, WindowBoundarySemantics.NOT_APPLICABLE),
                NULL_INPUT);
        assertRejected(() -> new CanonicalMetricDefinition("order.count", SubjectKind.MEMBER,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, null, EmptySetBehavior.ZERO, new MetadataScalarValue(SemanticDataType.INTEGER, "0"),
                        null, WindowBoundarySemantics.NOT_APPLICABLE),
                NULL_INPUT);
        assertRejected(() -> new CanonicalMetricDefinition("order.count", SubjectKind.MEMBER,
                        SemanticDataType.INTEGER, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE,
                        null, AggregateFunction.COUNT, EmptySetBehavior.ZERO,
                        new MetadataScalarValue(SemanticDataType.INTEGER, "0"),
                        null, null),
                NULL_INPUT);

        assertRejected(() -> new CanonicalRelationReference(null, SubjectKind.MEMBER, SubjectKind.ORDER,
                        RelationCardinality.ONE_TO_MANY, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                        EventTimeSemantics.EVENT_TIME, "order.paid_at"),
                CanonicalErrorCodes.CANONICAL_REFERENCE_ID_INVALID);
        assertRejected(() -> new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, null,
                        RelationCardinality.ONE_TO_MANY, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                        EventTimeSemantics.EVENT_TIME, "order.paid_at"),
                NULL_INPUT);
        assertRejected(() -> new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, SubjectKind.ORDER,
                        null, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                        EventTimeSemantics.EVENT_TIME, "order.paid_at"),
                NULL_INPUT);

        assertRejected(() -> new CanonicalWindow.FixedWindow(null, "2026-01-01T00:00:00Z"), NULL_INPUT);
        assertRejected(() -> new CanonicalWindow.FixedWindow("2026-01-01T00:00:00Z", null), NULL_INPUT);

        CanonicalSegment genuine = genuineSegment();
        assertRejected(() -> new CanonicalSegment(VERSION, genuine.schemaVersion(),
                        null, genuine.metadata(), genuine.predicate()),
                NULL_INPUT);
        assertRejected(() -> new CanonicalSegment(VERSION, genuine.schemaVersion(),
                        genuine.subject(), null, genuine.predicate()),
                NULL_INPUT);
        assertRejected(() -> new CanonicalSegment(VERSION, genuine.schemaVersion(),
                        genuine.subject(), genuine.metadata(), null),
                NULL_INPUT);
    }

    @Test
    void nullCodecEntryArgumentsAreStableCodedFailures() {
        CanonicalSegmentCodec codec = new CanonicalSegmentCodec();

        assertRejected(() -> codec.write(null), NULL_INPUT);
        assertRejected(() -> codec.writeString(null), NULL_INPUT);
        assertRejected(() -> codec.hash(null), NULL_INPUT);
        assertRejected(() -> codec.writeNode(null), NULL_INPUT);
        assertRejected(() -> codec.hashNode(null), NULL_INPUT);
        assertRejected(() -> codec.writeValue(null), NULL_INPUT);
        assertRejected(() -> codec.hashValue(null), NULL_INPUT);
        assertRejected(() -> codec.writeValueString(null), NULL_INPUT);
    }

    /**
     * No null public input may surface a raw runtime failure, and the bounded failure must not echo the
     * rejected form. This collects a representative call per boundary in one place.
     */
    @Test
    void noNullInputSurfacesARawRuntimeExceptionOrPayloadBearingCause() {
        List<Runnable> calls = List.of(
                () -> new CanonicalValue.TextValue(null),
                () -> new CanonicalValue.DecimalValue(null),
                () -> new CanonicalValue.DateValue(null),
                () -> new CanonicalValue.InstantValue(null),
                () -> new CanonicalValue.EnumValue(null),
                () -> new CanonicalValue.MoneyValue(null, null),
                () -> new CanonicalValue.ListValue(Arrays.asList((CanonicalValue) null)),
                () -> new CanonicalValue.RangeValue(null, null, true, true),
                () -> new CanonicalNode.Negation(null),
                () -> new CanonicalNode.Attribute(null, null, null),
                () -> new CanonicalNode.Metric(null, null, null, null),
                () -> new CanonicalNode.Exists(null, null, null),
                () -> new CanonicalWindow.FixedWindow(null, null),
                () -> new CanonicalSegmentCodec().write(null),
                () -> new CanonicalSegmentCodec().writeNode(null),
                () -> new CanonicalSegmentCodec().writeValue(null));

        for (Runnable call : calls) {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call::run);
            assertThat(thrown)
                    .as("null input must be a stable coded failure")
                    .isInstanceOf(CanonicalInvariantException.class)
                    .isNotInstanceOf(NullPointerException.class)
                    .isNotInstanceOf(ClassCastException.class);
            CanonicalInvariantException failure = (CanonicalInvariantException) thrown;
            assertThat(failure.getCause()).isNull();
            assertThat(failure.details()).containsOnlyKeys("invariant");
        }
    }

    @Test
    void aRejectedValueTextIsNeverEchoedInTheBoundedFailure() {
        String marker = "SECRET-PAYLOAD-42";
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> new CanonicalValue.DecimalValue(marker));
        assertThat(thrown).isInstanceOf(CanonicalInvariantException.class);
        assertThat(thrown.getMessage()).doesNotContain(marker);
        assertThat(((CanonicalInvariantException) thrown).details().toString()).doesNotContain(marker);
    }

    // ------------------------------------------------------------------ fixtures

    private static CanonicalDefinition attributeDefinition() {
        return new CanonicalDefinition(MetadataKind.ATTRIBUTE, "member.tier", SubjectKind.MEMBER,
                SemanticDataType.ENUM, NullSemantics.UNKNOWN_VALUE, CurrencySemantics.NOT_APPLICABLE, null);
    }

    private static CanonicalMetricDefinition metricDefinition() {
        return new CanonicalMetricDefinition("order.net_paid_amount", SubjectKind.MEMBER, SemanticDataType.MONEY,
                NullSemantics.UNKNOWN_VALUE, CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, null,
                AggregateFunction.SUM, EmptySetBehavior.ZERO,
                new MetadataScalarValue(SemanticDataType.MONEY, "0"), EventTimeSemantics.EVENT_TIME,
                WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END);
    }

    private static CanonicalRelationReference relationReference() {
        return new CanonicalRelationReference("member.orders", SubjectKind.MEMBER, SubjectKind.ORDER,
                RelationCardinality.ONE_TO_MANY, RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                "source.id", "target.parent_id", NullSemantics.EXCLUDED, EmptySetBehavior.NULL,
                EventTimeSemantics.EVENT_TIME, "order.paid_at");
    }

    private static CanonicalNode attribute(CanonicalValue value) {
        return new CanonicalNode.Attribute(attributeDefinition(), ComparisonOperator.EQUAL, value);
    }

    private static CanonicalWindow lifetime() {
        return new CanonicalWindow.LifetimeWindow();
    }

    private static CanonicalSegment genuineSegment() {
        return SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument()).canonicalSegment();
    }
}