package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 Condition semantics tests: the exact T19 typed vocabulary, and the rule that an unresolved
 * fact never becomes {@code FALSE}.
 */
class RuntimeConditionSemanticsTest {

    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");

    private final RuntimeConditionEvaluator evaluator = new RuntimeConditionEvaluator();

    private static RuntimeConditionPredicate predicate(
            String operator, String valueType, String value, String onUnknown) {
        return new RuntimeConditionPredicate("member.tier", operator, valueType, value, onUnknown);
    }

    private static RuntimeFactResult fact(String valueType, String value) {
        return RuntimeFactResult.known("member.tier", valueType, value, AT);
    }

    @Test
    void aKnownFactOfTheDeclaredKindSelectsItsExactArm() {
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_STRING, "GOLD")).verdict())
                .isEqualTo(RuntimeConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_STRING, "SILVER")).verdict())
                .isEqualTo(RuntimeConditionVerdict.FALSE);
    }

    @Test
    void orderedComparisonsUseTheExactTypedSemantics() {
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_GREATER_OR_EQUAL, FlowConditionPack.TYPE_INTEGER, "7",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_INTEGER, "7")).verdict())
                .isEqualTo(RuntimeConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_LESS_THAN, FlowConditionPack.TYPE_DECIMAL, "25",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_DECIMAL, "24.5")).verdict())
                .isEqualTo(RuntimeConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_NOT_EQUALS, FlowConditionPack.TYPE_BOOLEAN, "true",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_BOOLEAN, "false")).verdict())
                .isEqualTo(RuntimeConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_DATE, "2026-10-03",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_DATE, "2026-10-04")).verdict())
                .isEqualTo(RuntimeConditionVerdict.FALSE);
    }

    @Test
    void anUnknownFactIsNeverFalseAndFollowsTheFrozenPolicy() {
        RuntimeFactResult unknown = RuntimeFactResult.unknown("member.tier", AT);

        RuntimeConditionEvaluation lenient = evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                unknown);
        assertThat(lenient.verdict()).isEqualTo(RuntimeConditionVerdict.UNKNOWN);
        assertThat(lenient.verdict()).isNotEqualTo(RuntimeConditionVerdict.FALSE);
        assertThat(lenient.needsReview()).isTrue();

        RuntimeConditionEvaluation strict = evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN_ERROR),
                unknown);
        assertThat(strict.verdict()).isEqualTo(RuntimeConditionVerdict.ERROR);
        assertThat(strict.reasonCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_CONDITION_FAILED_CLOSED);
    }

    @Test
    void anErrorFactAndAnIncompatibleFactFailClosedRatherThanSelectingFalse() {
        RuntimeConditionEvaluation error = evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                RuntimeFactResult.error("member.tier", "HOST_FACT_UNAVAILABLE", AT));
        assertThat(error.verdict()).isEqualTo(RuntimeConditionVerdict.ERROR);
        assertThat(error.reasonCode()).isEqualTo("HOST_FACT_UNAVAILABLE");

        RuntimeConditionEvaluation mismatched = evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                fact(FlowConditionPack.TYPE_INTEGER, "1"));
        assertThat(mismatched.reasonCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_CONDITION_TYPE_MISMATCH);

        RuntimeConditionEvaluation otherFact = evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN_ERROR),
                RuntimeFactResult.known("member.country", FlowConditionPack.TYPE_STRING, "GOLD", AT));
        assertThat(otherFact.verdict()).isEqualTo(RuntimeConditionVerdict.ERROR);
    }

    @Test
    void aTechnicalFactOutcomeIsNotAValue() {
        assertThatThrownBy(() -> evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                RuntimeFactResult.timeout("member.tier", AT)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_FACT_TIMEOUT);
        assertThatThrownBy(() -> evaluator.evaluate(
                predicate(FlowConditionPack.OPERATOR_EQUALS, FlowConditionPack.TYPE_STRING, "GOLD",
                        FlowConditionPack.ON_UNKNOWN),
                RuntimeFactResult.unavailable("member.tier", AT)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE);
    }

    @Test
    void onlyTheCanonicalTextOfATypedValueIsRepresentable() {
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_INTEGER, "007")).isFalse();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_INTEGER, "7")).isTrue();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_DECIMAL, "25.00")).isFalse();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_DECIMAL, "25")).isTrue();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_DECIMAL, "2.5e1")).isFalse();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_BOOLEAN, "TRUE")).isFalse();
        assertThat(RuntimeTypedValues.isCanonical(
                FlowConditionPack.TYPE_DATETIME, "2026-10-03T08:00:00+08:00")).isFalse();
        assertThat(RuntimeTypedValues.isCanonical(
                FlowConditionPack.TYPE_DATETIME, "2026-10-03T00:00:00Z")).isTrue();
        assertThat(RuntimeTypedValues.isCanonical(FlowConditionPack.TYPE_DATE, "2026-1-3")).isFalse();
    }

    @Test
    void aFrozenConditionPayloadIsTheExactT19PredicateShape() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FlowConditionPack.FACT_CODE_FIELD, "member.tier");
        fields.put(FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.OPERATOR_EQUALS);
        fields.put(FlowConditionPack.VALUE_TYPE_FIELD, FlowConditionPack.TYPE_STRING);
        fields.put(FlowConditionPack.VALUE_FIELD, "GOLD");
        fields.put(FlowConditionPack.ON_UNKNOWN_FIELD, FlowConditionPack.ON_UNKNOWN);

        assertThat(RuntimeConditionPredicate.fromFields(fields).factCode()).isEqualTo("member.tier");

        Map<String, Object> missingPolicy = new LinkedHashMap<>(fields);
        missingPolicy.remove(FlowConditionPack.ON_UNKNOWN_FIELD);
        assertThatThrownBy(() -> RuntimeConditionPredicate.fromFields(missingPolicy))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue(
                        "code", RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID);

        Map<String, Object> orderedBoolean = new LinkedHashMap<>(fields);
        orderedBoolean.put(FlowConditionPack.VALUE_TYPE_FIELD, FlowConditionPack.TYPE_BOOLEAN);
        orderedBoolean.put(FlowConditionPack.VALUE_FIELD, "true");
        orderedBoolean.put(FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.OPERATOR_LESS_THAN);
        assertThatThrownBy(() -> RuntimeConditionPredicate.fromFields(orderedBoolean))
                .isInstanceOf(RuntimeBoundaryException.class);

        Map<String, Object> unboundedFact = new LinkedHashMap<>(fields);
        unboundedFact.put(FlowConditionPack.FACT_CODE_FIELD, "member.tier; DROP TABLE members");
        assertThatThrownBy(() -> RuntimeConditionPredicate.fromFields(unboundedFact))
                .isInstanceOf(RuntimeBoundaryException.class);
    }

    @Test
    void aConditionEvaluationCarriesAReasonOnlyWhenItFailsClosed() {
        assertThat(RuntimeConditionEvaluation.trueVerdict().reasonCode()).isNull();
        assertThat(RuntimeConditionEvaluation.unknown().needsReview()).isTrue();
        assertThat(RuntimeConditionEvaluation.error("BOUNDED_CODE").reasonCode())
                .isEqualTo("BOUNDED_CODE");
        assertThatThrownBy(() -> RuntimeConditionEvaluation.error("not upper"))
                .isInstanceOf(RuntimeBoundaryException.class);
        assertThatThrownBy(() -> new RuntimeConditionEvaluation(RuntimeConditionVerdict.TRUE, "BOUNDED"))
                .isInstanceOf(RuntimeBoundaryException.class);
    }
}