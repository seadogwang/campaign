package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirements 3, 4 and 5: definition/subject/operator rules, the type matrix and NULL distinctions. */
class SegmentValidationDefinitionRulesTest {

    private static SegmentValidationResult validate(Predicate root) {
        return SegmentValidationFixtures.validate(root);
    }

    private static String hash(Predicate root) {
        SegmentValidationResult result = validate(root);
        assertThat(result.valid()).as("expected valid document, issues: %s",
                SegmentValidationFixtures.codes(result)).isTrue();
        return result.canonicalAstHash().value();
    }

    // ------------------------------------------------------------------ existence and kind

    @Test
    void unknownAttributeIdIsRejected() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n-a", "member.unknown", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.DEFINITION_UNKNOWN);
    }

    @Test
    void metricReferencedAsAnAttributeIsAKindMismatch() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n-a", "order.count", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH);
    }

    @Test
    void attributeReferencedAsAMetricIsAKindMismatch() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.metric(
                "n-m", "member.tier", SegmentValidationFixtures.lifetime(), ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH);
    }

    @Test
    void relationReferencedAsAnAttributeIsAKindMismatch() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n-a", "member.orders", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH);
    }

    // ------------------------------------------------------------------ subject and operator

    @Test
    void rootAttributeSubjectMustMatchTheDslSubject() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n-a", "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.SUBJECT_MISMATCH);
    }

    @Test
    void anOperatorTheDefinitionDoesNotDeclareIsRejected() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n-a", "member.tier", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(3))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED);
    }

    // ------------------------------------------------------------------ type compatibility matrix

    @Test
    void eachScalarDataTypesAcceptsExactlyItsCompatibleValueKind() {
        List<Predicate> valid = List.of(
                SegmentValidationFixtures.attribute("n1", "member.name", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("Gold"))),
                SegmentValidationFixtures.attribute("n2", "member.flag", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.booleanValue(true))),
                SegmentValidationFixtures.attribute("n3", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))),
                SegmentValidationFixtures.attribute("n4", "member.score", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5"))),
                SegmentValidationFixtures.attribute("n5", "member.joined_on", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.dateValue("2026-01-01"))),
                SegmentValidationFixtures.attribute("n6", "member.last_seen", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(
                                SegmentValidationFixtures.dateTimeValue("2026-01-01T00:00:00+08:00"))),
                SegmentValidationFixtures.attribute("n7", "member.tier", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD"))),
                SegmentValidationFixtures.attribute("n8", "member.balance", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1.00", "CNY"))));

        for (Predicate predicate : valid) {
            assertThat(validate(predicate).valid())
                    .as("expected valid: %s", SegmentValidationFixtures.codes(validate(predicate)))
                    .isTrue();
        }
    }

    @Test
    void anIncompatibleScalarKindIsRejectedForEveryDataType() {
        List<Predicate> invalid = List.of(
                SegmentValidationFixtures.attribute("n1", "member.name", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))),
                SegmentValidationFixtures.attribute("n2", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("30"))),
                SegmentValidationFixtures.attribute("n3", "member.tier", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))),
                SegmentValidationFixtures.attribute("n4", "member.balance", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("1"))),
                SegmentValidationFixtures.attribute("n5", "member.joined_on", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(
                                SegmentValidationFixtures.dateTimeValue("2026-01-01T00:00:00Z"))));

        for (Predicate predicate : invalid) {
            assertThat(SegmentValidationFixtures.codes(validate(predicate)))
                    .as("expected a type rejection")
                    .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
        }
    }

    @Test
    void aHomogeneousListOperandIsAcceptedForEveryCompatibleKind() {
        assertThat(validate(SegmentValidationFixtures.attribute("n1", "member.tier", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new com.company.campaign.segment.dsl.value.EnumValue("GOLD"),
                        new com.company.campaign.segment.dsl.value.EnumValue("SILVER")))).valid()).isTrue();
        assertThat(validate(SegmentValidationFixtures.attribute("n2", "member.age", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new com.company.campaign.segment.dsl.value.IntegerValue(1),
                        new com.company.campaign.segment.dsl.value.IntegerValue(2)))).valid()).isTrue();
        assertThat(validate(SegmentValidationFixtures.attribute("n3", "member.balance", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new com.company.campaign.segment.dsl.value.MoneyValue("1.00", "CNY"),
                        new com.company.campaign.segment.dsl.value.MoneyValue("2.00", "CNY")))).valid()).isTrue();
    }

    @Test
    void aListOperandWithAnIncompatibleItemKindIsRejected() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n1", "member.age", ComparisonOperator.IN,
                ConditionValue.of(new com.company.campaign.segment.dsl.value.ListValue(List.of(
                        new com.company.campaign.segment.dsl.value.IntegerValue(1),
                        new com.company.campaign.segment.dsl.value.StringValue("two"))))));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
    }

    @Test
    void aCompatibleRangeOperandIsAcceptedAndAnIncompatibleOneIsRejected() {
        assertThat(validate(SegmentValidationFixtures.attribute("n1", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(
                        new com.company.campaign.segment.dsl.value.IntegerValue(18),
                        new com.company.campaign.segment.dsl.value.IntegerValue(65), true, false))).valid()).isTrue();

        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n2", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(
                        new com.company.campaign.segment.dsl.value.StringValue("18"),
                        new com.company.campaign.segment.dsl.value.IntegerValue(65), true, false)));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
    }

    @Test
    void theFullOrderedTypeMatrixAcceptsOnlyItsCompatibleRangeBounds() {
        record Case(String attributeId, com.company.campaign.segment.dsl.value.ScalarValue lower,
                    com.company.campaign.segment.dsl.value.ScalarValue upper) {
        }
        List<Case> ordered = List.of(
                new Case("member.age", new com.company.campaign.segment.dsl.value.IntegerValue(1),
                        new com.company.campaign.segment.dsl.value.IntegerValue(9)),
                new Case("member.score", new com.company.campaign.segment.dsl.value.DecimalValue("1.5"),
                        new com.company.campaign.segment.dsl.value.DecimalValue("9.5")),
                new Case("member.joined_on", new com.company.campaign.segment.dsl.value.DateValue("2026-01-01"),
                        new com.company.campaign.segment.dsl.value.DateValue("2026-06-01")),
                new Case("member.last_seen",
                        new com.company.campaign.segment.dsl.value.DateTimeValue("2026-01-01T00:00:00Z"),
                        new com.company.campaign.segment.dsl.value.DateTimeValue("2026-06-01T00:00:00Z")),
                new Case("member.balance", new com.company.campaign.segment.dsl.value.MoneyValue("1.00", "CNY"),
                        new com.company.campaign.segment.dsl.value.MoneyValue("9.00", "CNY")));

        for (Case testCase : ordered) {
            Predicate accepted = SegmentValidationFixtures.attribute(
                    "n", testCase.attributeId(), ComparisonOperator.BETWEEN,
                    SegmentValidationFixtures.range(testCase.lower(), testCase.upper(), true, true));
            assertThat(validate(accepted).valid())
                    .as("expected BETWEEN to be accepted for %s", testCase.attributeId()).isTrue();

            Predicate rejected = SegmentValidationFixtures.attribute(
                    "n", testCase.attributeId(), ComparisonOperator.BETWEEN,
                    SegmentValidationFixtures.range(
                            new com.company.campaign.segment.dsl.value.StringValue("a"),
                            new com.company.campaign.segment.dsl.value.StringValue("z"), true, true));
            assertThat(SegmentValidationFixtures.codes(validate(rejected)))
                    .as("expected a type rejection for %s", testCase.attributeId())
                    .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
        }
    }

    @Test
    void betweenIsRejectedForEveryUnorderedType() {
        List<String> unordered = List.of("member.name", "member.flag", "member.tier");

        for (String attributeId : unordered) {
            Predicate predicate = SegmentValidationFixtures.attribute(
                    "n", attributeId, ComparisonOperator.BETWEEN,
                    SegmentValidationFixtures.range(
                            new com.company.campaign.segment.dsl.value.IntegerValue(1),
                            new com.company.campaign.segment.dsl.value.IntegerValue(9), true, true));
            assertThat(SegmentValidationFixtures.codes(validate(predicate)))
                    .as("expected BETWEEN to be disallowed for %s", attributeId)
                    .contains(SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED);
        }
    }

    @Test
    void everyTypeAcceptsItsCompatibleListAndRejectsAnIncompatibleItem() {
        record Case(
                String attributeId,
                com.company.campaign.segment.dsl.value.ScalarValue compatible,
                com.company.campaign.segment.dsl.value.ScalarValue incompatible) {
        }
        List<Case> cases = List.of(
                new Case("member.name", new com.company.campaign.segment.dsl.value.StringValue("a"),
                        new com.company.campaign.segment.dsl.value.IntegerValue(1)),
                new Case("member.flag", new com.company.campaign.segment.dsl.value.BooleanValue(true),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.age", new com.company.campaign.segment.dsl.value.IntegerValue(1),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.score", new com.company.campaign.segment.dsl.value.DecimalValue("1.5"),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.joined_on", new com.company.campaign.segment.dsl.value.DateValue("2026-01-01"),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.last_seen",
                        new com.company.campaign.segment.dsl.value.DateTimeValue("2026-01-01T00:00:00Z"),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.tier", new com.company.campaign.segment.dsl.value.EnumValue("GOLD"),
                        new com.company.campaign.segment.dsl.value.StringValue("x")),
                new Case("member.balance", new com.company.campaign.segment.dsl.value.MoneyValue("1.00", "CNY"),
                        new com.company.campaign.segment.dsl.value.StringValue("x")));

        for (Case testCase : cases) {
            Predicate accepted = SegmentValidationFixtures.attribute(
                    "n", testCase.attributeId(), ComparisonOperator.IN,
                    SegmentValidationFixtures.list(testCase.compatible()));
            assertThat(SegmentValidationFixtures.validate(accepted).valid())
                    .as("expected a LIST to be accepted for %s", testCase.attributeId()).isTrue();

            Predicate rejected = SegmentValidationFixtures.attribute(
                    "n", testCase.attributeId(), ComparisonOperator.IN,
                    SegmentValidationFixtures.list(testCase.compatible(), testCase.incompatible()));
            assertThat(SegmentValidationFixtures.codes(SegmentValidationFixtures.validate(rejected)))
                    .as("expected a type rejection for %s", testCase.attributeId())
                    .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
        }
    }

    @Test
    void anEnumCodeNotDeclaredByTheDefinitionIsRejected() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.tierEquals("n-tier", "PLATINUM"));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.ENUM_CODE_UNKNOWN);
    }

    // ------------------------------------------------------------------ NULL distinctions

    @Test
    void isNullIsNotNullNotEqualAndUnaryNotAreAllSemanticallyDistinct() {
        Predicate isNull = SegmentValidationFixtures.attribute(
                "n", "member.tier", ComparisonOperator.IS_NULL, ConditionValue.none());
        Predicate isNotNull = SegmentValidationFixtures.attribute(
                "n", "member.tier", ComparisonOperator.IS_NOT_NULL, ConditionValue.none());
        Predicate notEqual = SegmentValidationFixtures.attribute(
                "n", "member.tier", ComparisonOperator.NOT_EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD")));
        Predicate notIsNull = SegmentValidationFixtures.not("n", SegmentValidationFixtures.attribute(
                "c", "member.tier", ComparisonOperator.IS_NULL, ConditionValue.none()));

        assertThat(List.of(hash(isNull), hash(isNotNull), hash(notEqual), hash(notIsNull)))
                .doesNotHaveDuplicates();
    }

    @Test
    void aNullInclusiveAnyIsDistinctFromAPlainNotEqual() {
        Predicate notEqual = SegmentValidationFixtures.attribute(
                "n", "member.tier", ComparisonOperator.NOT_EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD")));
        Predicate nullInclusive = SegmentValidationFixtures.any("n-any", List.of(
                notEqual,
                SegmentValidationFixtures.attribute("n-null", "member.tier", ComparisonOperator.IS_NULL,
                        ConditionValue.none())));

        assertThat(hash(nullInclusive)).isNotEqualTo(hash(notEqual));
    }

    @Test
    void aNullOperatorCarriesNoValueInTheCanonicalForm() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n", "member.tier", ComparisonOperator.IS_NULL, ConditionValue.none()));

        com.company.campaign.segment.canonical.CanonicalNode node = result.canonicalSegment().predicate();
        assertThat(node).isInstanceOf(com.company.campaign.segment.canonical.CanonicalNode.Attribute.class);
        assertThat(((com.company.campaign.segment.canonical.CanonicalNode.Attribute) node).value()).isNull();
    }
}