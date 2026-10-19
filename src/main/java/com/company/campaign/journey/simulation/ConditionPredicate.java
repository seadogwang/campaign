package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;

import java.util.Map;

/**
 * One parsed, validated Flow Condition predicate of a compiled Journey node.
 *
 * <p>It reuses the exact T19 Flow Condition field names, operators, value kinds and unknown-fact policies: it
 * introduces no second condition vocabulary and no nested predicate tree. Construction reads exactly the five
 * declared fields and fails closed when one is absent, is not text, or falls outside the T19 closed sets —
 * including the T19 rule that a boolean may only be compared for equality.
 */
public record ConditionPredicate(
        String factCode,
        String operator,
        String valueType,
        String value,
        String onUnknown) {

    public ConditionPredicate {
        if (factCode == null || operator == null || valueType == null || value == null
                || onUnknown == null) {
            throw invalid("missingField");
        }
        if (!FlowConditionPack.OPERATORS.contains(operator)
                || !FlowConditionPack.VALUE_TYPES.contains(valueType)
                || !FlowConditionPack.ON_UNKNOWN_POLICIES.contains(onUnknown)) {
            throw invalid("closedValue");
        }
        if (FlowConditionPack.TYPE_BOOLEAN.equals(valueType)
                && !FlowConditionPack.OPERATOR_EQUALS.equals(operator)
                && !FlowConditionPack.OPERATOR_NOT_EQUALS.equals(operator)) {
            throw invalid("booleanOperator");
        }
        if (factCode.isEmpty() || factCode.length() > FlowConditionPack.MAX_FACT_CODE_LENGTH
                || value.isEmpty() || value.length() > FlowConditionPack.MAX_VALUE_LENGTH) {
            throw invalid("boundedField");
        }
    }

    /** Reads and validates the one predicate a Condition component payload declares. */
    public static ConditionPredicate fromFields(Map<String, Object> fields) {
        if (fields == null) {
            throw invalid("fields");
        }
        return new ConditionPredicate(
                text(fields, FlowConditionPack.FACT_CODE_FIELD),
                text(fields, FlowConditionPack.OPERATOR_FIELD),
                text(fields, FlowConditionPack.VALUE_TYPE_FIELD),
                text(fields, FlowConditionPack.VALUE_FIELD),
                text(fields, FlowConditionPack.ON_UNKNOWN_FIELD));
    }

    /** True when an unknown or missing fact must fail the predicate closed rather than stay unknown. */
    public boolean failsClosedOnUnknown() {
        return FlowConditionPack.ON_UNKNOWN_ERROR.equals(onUnknown);
    }

    private static String text(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        return value instanceof String string ? string : null;
    }

    private static SimulationException invalid(String invariant) {
        return new SimulationException(SimulationErrorCodes.NODE_CONFIG_INVALID, invariant,
                "A Condition node payload is not the exact Flow Condition predicate shape.");
    }
}
