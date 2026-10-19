package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The pure, side-effect-free evaluator of one T19 Flow Condition predicate.
 *
 * <p>It reads exactly one explicit scenario fact for the predicate's fact code and returns one closed verdict.
 * It resolves no data source, reads no clock, consults no repository and calls no provider: the green thread of
 * a fact is its explicit scenario value, never an ambient lookup.
 *
 * <p>The reuse of the T19 contract is exact: the predicate's operators, value kinds, unknown-fact policies and
 * canonical text forms are the T19 Flow Condition ones, and there is no second condition vocabulary here. A
 * known fact produces {@code TRUE} or {@code FALSE} deterministically; a missing or unknown fact with the
 * unknown policy stays {@code UNKNOWN} and never becomes {@code FALSE}; a missing or unknown fact with the
 * fail-closed policy, and any explicit fact error, produce a fail-closed {@code ERROR} with a bounded reason
 * code.
 */
public final class ConditionEvaluator {

    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** Evaluates one predicate against a scenario and returns its closed verdict. */
    public ConditionEvaluation evaluate(ConditionPredicate predicate, ScenarioFacts facts) {
        if (predicate == null) {
            throw new SimulationException(SimulationErrorCodes.INPUT_MISSING, "predicate",
                    "A condition evaluation requires its predicate.");
        }
        ScenarioFact fact = facts == null ? null : facts.fact(predicate.factCode());
        if (fact == null) {
            return unresolved(predicate);
        }
        return switch (fact.outcome()) {
            case UNKNOWN -> unresolved(predicate);
            case ERROR -> ConditionEvaluation.error(fact.reasonCode());
            case KNOWN -> compare(predicate, fact);
        };
    }

    private static ConditionEvaluation unresolved(ConditionPredicate predicate) {
        if (predicate.failsClosedOnUnknown()) {
            return ConditionEvaluation.error(SimulationErrorCodes.CONDITION_FACT_UNKNOWN_FAIL_CLOSED);
        }
        return ConditionEvaluation.unknown();
    }

    private static ConditionEvaluation compare(ConditionPredicate predicate, ScenarioFact fact) {
        if (!predicate.valueType().equals(fact.valueType())) {
            return ConditionEvaluation.error(SimulationErrorCodes.CONDITION_FACT_TYPE_MISMATCH);
        }
        Comparable<Object> factValue = parse(predicate.valueType(), fact.value());
        if (factValue == null) {
            return ConditionEvaluation.error(SimulationErrorCodes.CONDITION_FACT_VALUE_INVALID);
        }
        Comparable<Object> predicateValue = parse(predicate.valueType(), predicate.value());
        if (predicateValue == null) {
            return ConditionEvaluation.error(SimulationErrorCodes.NODE_CONFIG_INVALID);
        }
        int comparison = factValue.compareTo(predicateValue);
        return switch (predicate.operator()) {
            case FlowConditionPack.OPERATOR_EQUALS -> verdict(comparison == 0);
            case FlowConditionPack.OPERATOR_NOT_EQUALS -> verdict(comparison != 0);
            case FlowConditionPack.OPERATOR_LESS_THAN -> verdict(comparison < 0);
            case FlowConditionPack.OPERATOR_LESS_OR_EQUAL -> verdict(comparison <= 0);
            case FlowConditionPack.OPERATOR_GREATER_THAN -> verdict(comparison > 0);
            case FlowConditionPack.OPERATOR_GREATER_OR_EQUAL -> verdict(comparison >= 0);
            default -> ConditionEvaluation.error(SimulationErrorCodes.NODE_CONFIG_INVALID);
        };
    }

    private static ConditionEvaluation verdict(boolean selected) {
        return selected ? ConditionEvaluation.trueVerdict() : ConditionEvaluation.falseVerdict();
    }

    /**
     * The typed canonical operand of the declared value kind, or null when the text is not its canonical form.
     *
     * <p>The kinds are the exact T19 Flow Condition value kinds; the returned operand is {@link Comparable} so
     * that every valid ordered comparison is deterministic and total within one kind.
     */
    @SuppressWarnings("unchecked")
    private static Comparable<Object> parse(String valueType, String value) {
        try {
            Object operand = switch (valueType) {
                case FlowConditionPack.TYPE_STRING -> value;
                case FlowConditionPack.TYPE_INTEGER -> new BigInteger(value);
                case FlowConditionPack.TYPE_DECIMAL -> new BigDecimal(value);
                case FlowConditionPack.TYPE_BOOLEAN -> switch (value) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> null;
                };
                case FlowConditionPack.TYPE_DATE -> LocalDate.parse(value);
                case FlowConditionPack.TYPE_DATETIME -> Instant.from(OffsetDateTime.parse(value, INSTANT));
                default -> null;
            };
            return operand == null ? null : (Comparable<Object>) operand;
        } catch (NumberFormatException | DateTimeParseException rejected) {
            return null;
        }
    }
}
