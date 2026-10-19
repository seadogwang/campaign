package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;

import java.util.Map;

/**
 * One parsed, validated Flow Condition predicate of a compiled Journey node.
 *
 * <p>It reuses the exact T19 {@link FlowConditionPack} field names, operators, value kinds and unknown-fact
 * policies: it introduces no second condition vocabulary and no nested predicate tree. Construction reads
 * exactly the five declared fields and fails closed when one is absent, is not text, or falls outside the T19
 * closed sets — including the T19 rule that a boolean may only be compared for equality.
 */
public record RuntimeConditionPredicate(
        String factCode,
        String operator,
        String valueType,
        String value,
        String onUnknown) {

    public RuntimeConditionPredicate {
        if (factCode == null || operator == null || valueType == null || value == null
                || onUnknown == null) {
            throw invalid("missingField");
        }
        if (!RuntimeTypedValues.isFactCode(factCode)) {
            throw invalid("factCode");
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
        if (!RuntimeTypedValues.isCanonical(valueType, value)) {
            throw invalid("value");
        }
    }

    /** Reads and validates the one predicate a Condition component payload declares. */
    public static RuntimeConditionPredicate fromFields(Map<String, Object> fields) {
        if (fields == null) {
            throw invalid("fields");
        }
        return new RuntimeConditionPredicate(
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

    private static RuntimeBoundaryException invalid(String invariant) {
        return new RuntimeBoundaryException(
                RuntimeErrorCodes.RUNTIME_CONDITION_NODE_CONFIG_INVALID, invariant,
                "A Condition node payload is not the exact Flow Condition predicate shape.");
    }
}