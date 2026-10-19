package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;

/**
 * The pure, side-effect-free evaluator of one T19 Flow Condition predicate against one host fact.
 *
 * <p>The reuse of the T19 contract is exact: the predicate's operators, value kinds, unknown-fact policies and
 * canonical text forms are the T19 {@link FlowConditionPack} ones, and the comparison is the same ordered,
 * typed comparison the accepted T20 simulator performs. There is no second condition vocabulary and no
 * executable expression anywhere here.
 *
 * <p>A known fact of the declared kind produces {@code TRUE} or {@code FALSE} deterministically. A missing or
 * unknown fact stays {@code UNKNOWN} — never {@code FALSE} — with the {@code onUnknown=UNKNOWN} policy and fails
 * closed with {@code onUnknown=ERROR}; an explicit fact error always fails closed with the fact's own bounded
 * reason code. It reads no clock, no repository, no provider and no ambient state.
 */
public final class RuntimeConditionEvaluator {

    /** Evaluates one predicate against one host fact result and returns its closed verdict. */
    public RuntimeConditionEvaluation evaluate(
            RuntimeConditionPredicate predicate, RuntimeFactResult fact) {
        if (predicate == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID, "predicate",
                    "A condition evaluation requires its predicate.");
        }
        if (fact == null) {
            return unresolved(predicate);
        }
        if (!predicate.factCode().equals(fact.factCode())) {
            // A host that answers about a different fact has not answered this predicate at all.
            return unresolved(predicate);
        }
        return switch (fact.outcome()) {
            case UNKNOWN -> unresolved(predicate);
            case ERROR -> RuntimeConditionEvaluation.error(fact.reasonCode());
            case KNOWN -> compare(predicate, fact);
            case TIMEOUT, UNAVAILABLE -> throw new RuntimeBoundaryException(
                    fact.outcome() == RuntimeFactOutcome.TIMEOUT
                            ? RuntimeErrorCodes.RUNTIME_FACT_TIMEOUT
                            : RuntimeErrorCodes.RUNTIME_FACT_UNAVAILABLE,
                    "fact",
                    "The host fact port did not answer this predicate.");
        };
    }

    private static RuntimeConditionEvaluation unresolved(RuntimeConditionPredicate predicate) {
        if (predicate.failsClosedOnUnknown()) {
            return RuntimeConditionEvaluation.error(RuntimeErrorCodes.RUNTIME_CONDITION_FAILED_CLOSED);
        }
        return RuntimeConditionEvaluation.unknown();
    }

    private static RuntimeConditionEvaluation compare(
            RuntimeConditionPredicate predicate, RuntimeFactResult fact) {
        if (!predicate.valueType().equals(fact.valueType())) {
            return RuntimeConditionEvaluation.error(RuntimeErrorCodes.RUNTIME_CONDITION_TYPE_MISMATCH);
        }
        Comparable<Object> factValue = RuntimeTypedValues.operand(fact.valueType(), fact.value());
        if (factValue == null) {
            return RuntimeConditionEvaluation.error(RuntimeErrorCodes.RUNTIME_CONDITION_VALUE_INVALID);
        }
        Comparable<Object> predicateValue =
                RuntimeTypedValues.operand(predicate.valueType(), predicate.value());
        if (predicateValue == null) {
            return RuntimeConditionEvaluation.error(
                    RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID);
        }
        int comparison = factValue.compareTo(predicateValue);
        return switch (predicate.operator()) {
            case FlowConditionPack.OPERATOR_EQUALS -> verdict(comparison == 0);
            case FlowConditionPack.OPERATOR_NOT_EQUALS -> verdict(comparison != 0);
            case FlowConditionPack.OPERATOR_LESS_THAN -> verdict(comparison < 0);
            case FlowConditionPack.OPERATOR_LESS_OR_EQUAL -> verdict(comparison <= 0);
            case FlowConditionPack.OPERATOR_GREATER_THAN -> verdict(comparison > 0);
            case FlowConditionPack.OPERATOR_GREATER_OR_EQUAL -> verdict(comparison >= 0);
            default -> RuntimeConditionEvaluation.error(
                    RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID);
        };
    }

    private static RuntimeConditionEvaluation verdict(boolean selected) {
        return selected
                ? RuntimeConditionEvaluation.trueVerdict()
                : RuntimeConditionEvaluation.falseVerdict();
    }

    /** Redacted diagnostics: the evaluator holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeConditionEvaluator[]";
    }
}