package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T20 section-4 tests: the pure Flow Condition evaluator and its three-state outcome. */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private static ConditionPredicate predicate(
            String factCode, String operator, String valueType, String value, String onUnknown) {
        return ConditionPredicate.fromFields(Map.of(
                FlowConditionPack.FACT_CODE_FIELD, factCode,
                FlowConditionPack.OPERATOR_FIELD, operator,
                FlowConditionPack.VALUE_TYPE_FIELD, valueType,
                FlowConditionPack.VALUE_FIELD, value,
                FlowConditionPack.ON_UNKNOWN_FIELD, onUnknown));
    }

    private static ConditionPredicate stringEquals(String onUnknown) {
        return predicate("member.tier", FlowConditionPack.OPERATOR_EQUALS,
                FlowConditionPack.TYPE_STRING, "GOLD", onUnknown);
    }

    @Test
    void aKnownStringFactSelectsTrueOrFalseDeterministically() {
        ConditionPredicate predicate = stringEquals(FlowConditionPack.ON_UNKNOWN);

        assertThat(evaluator.evaluate(predicate,
                ScenarioFacts.of(ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD")))
                .verdict()).isEqualTo(ConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(predicate,
                ScenarioFacts.of(ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "SILVER")))
                .verdict()).isEqualTo(ConditionVerdict.FALSE);
    }

    @Test
    void orderedComparisonsReuseTheT19OperatorsExactly() {
        ConditionPredicate greaterOrEqual = predicate("order.count",
                FlowConditionPack.OPERATOR_GREATER_OR_EQUAL, FlowConditionPack.TYPE_INTEGER, "7",
                FlowConditionPack.ON_UNKNOWN);
        assertThat(evaluator.evaluate(greaterOrEqual,
                ScenarioFacts.of(ScenarioFact.known("order.count", FlowConditionPack.TYPE_INTEGER, "7")))
                .verdict()).isEqualTo(ConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(greaterOrEqual,
                ScenarioFacts.of(ScenarioFact.known("order.count", FlowConditionPack.TYPE_INTEGER, "6")))
                .verdict()).isEqualTo(ConditionVerdict.FALSE);

        ConditionPredicate lessThan = predicate("order.amount",
                FlowConditionPack.OPERATOR_LESS_THAN, FlowConditionPack.TYPE_DECIMAL, "10.5",
                FlowConditionPack.ON_UNKNOWN);
        assertThat(evaluator.evaluate(lessThan,
                ScenarioFacts.of(ScenarioFact.known("order.amount", FlowConditionPack.TYPE_DECIMAL, "10.4")))
                .verdict()).isEqualTo(ConditionVerdict.TRUE);
        assertThat(evaluator.evaluate(lessThan,
                ScenarioFacts.of(ScenarioFact.known("order.amount", FlowConditionPack.TYPE_DECIMAL, "10.5")))
                .verdict()).isEqualTo(ConditionVerdict.FALSE);
    }

    @Test
    void dateAndInstantsCompareWithinTheirOwnKind() {
        ConditionPredicate date = predicate("member.since", FlowConditionPack.OPERATOR_LESS_OR_EQUAL,
                FlowConditionPack.TYPE_DATE, "2026-01-01", FlowConditionPack.ON_UNKNOWN);
        assertThat(evaluator.evaluate(date,
                ScenarioFacts.of(ScenarioFact.known("member.since", FlowConditionPack.TYPE_DATE, "2025-12-31")))
                .verdict()).isEqualTo(ConditionVerdict.TRUE);

        ConditionPredicate instant = predicate("order.placed", FlowConditionPack.OPERATOR_EQUALS,
                FlowConditionPack.TYPE_DATETIME, "2026-01-01T00:00:00Z", FlowConditionPack.ON_UNKNOWN);
        assertThat(evaluator.evaluate(instant,
                ScenarioFacts.of(ScenarioFact.known("order.placed", FlowConditionPack.TYPE_DATETIME,
                        "2026-01-01T00:00:00Z"))).verdict()).isEqualTo(ConditionVerdict.TRUE);
    }

    @Test
    void booleanFactsOnlySupportEqualityAsTheT19ContractDeclares() {
        ConditionPredicate equals = predicate("member.active", FlowConditionPack.OPERATOR_EQUALS,
                FlowConditionPack.TYPE_BOOLEAN, "true", FlowConditionPack.ON_UNKNOWN);
        assertThat(evaluator.evaluate(equals,
                ScenarioFacts.of(ScenarioFact.known("member.active", FlowConditionPack.TYPE_BOOLEAN, "false")))
                .verdict()).isEqualTo(ConditionVerdict.FALSE);
        assertThatThrownBy(() -> predicate("member.active", FlowConditionPack.OPERATOR_LESS_THAN,
                FlowConditionPack.TYPE_BOOLEAN, "true", FlowConditionPack.ON_UNKNOWN))
                .isInstanceOf(SimulationException.class);
    }

    @Test
    void anUnknownFactNeverBecomesFalseWhenThePolicyIsUnknown() {
        ConditionPredicate predicate = stringEquals(FlowConditionPack.ON_UNKNOWN);

        assertThat(evaluator.evaluate(predicate, ScenarioFacts.empty()).verdict())
                .isEqualTo(ConditionVerdict.UNKNOWN);
        assertThat(evaluator.evaluate(predicate,
                ScenarioFacts.of(ScenarioFact.unknown("member.tier"))).verdict())
                .isEqualTo(ConditionVerdict.UNKNOWN);
    }

    @Test
    void anUnknownOrMissingFactFailsClosedWhenThePolicyIsError() {
        ConditionPredicate predicate = stringEquals(FlowConditionPack.ON_UNKNOWN_ERROR);

        ConditionEvaluation missing = evaluator.evaluate(predicate, ScenarioFacts.empty());
        assertThat(missing.verdict()).isEqualTo(ConditionVerdict.ERROR);
        assertThat(missing.reasonCode())
                .isEqualTo(SimulationErrorCodes.CONDITION_FACT_UNKNOWN_FAIL_CLOSED);

        ConditionEvaluation unknown = evaluator.evaluate(predicate,
                ScenarioFacts.of(ScenarioFact.unknown("member.tier")));
        assertThat(unknown.verdict()).isEqualTo(ConditionVerdict.ERROR);
    }

    @Test
    void anExplicitFactErrorAlwaysTerminatesWithItsOwnBoundedReasonCode() {
        ConditionEvaluation evaluation = evaluator.evaluate(stringEquals(FlowConditionPack.ON_UNKNOWN),
                ScenarioFacts.of(ScenarioFact.error("member.tier", "FACT_SOURCE_UNAVAILABLE")));

        assertThat(evaluation.verdict()).isEqualTo(ConditionVerdict.ERROR);
        assertThat(evaluation.reasonCode()).isEqualTo("FACT_SOURCE_UNAVAILABLE");
    }

    @Test
    void aTypeMismatchFailsClosedRatherThanComparingDifferentKinds() {
        ConditionEvaluation evaluation = evaluator.evaluate(
                predicate("order.count", FlowConditionPack.OPERATOR_EQUALS,
                        FlowConditionPack.TYPE_INTEGER, "7", FlowConditionPack.ON_UNKNOWN),
                ScenarioFacts.of(ScenarioFact.known("order.count", FlowConditionPack.TYPE_STRING, "7")));

        assertThat(evaluation.verdict()).isEqualTo(ConditionVerdict.ERROR);
        assertThat(evaluation.reasonCode()).isEqualTo(SimulationErrorCodes.CONDITION_FACT_TYPE_MISMATCH);
    }

    @Test
    void theSameFactAlwaysProducesTheSameVerdict() {
        ConditionPredicate predicate = stringEquals(FlowConditionPack.ON_UNKNOWN);
        ScenarioFacts facts = ScenarioFacts.of(
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD"));
        ConditionEvaluation first = evaluator.evaluate(predicate, facts);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(evaluator.evaluate(predicate, facts)).isEqualTo(first);
        }
    }
}
