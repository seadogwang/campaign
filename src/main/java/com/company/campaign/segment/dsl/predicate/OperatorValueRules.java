package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.TypedValue;

/**
 * The structural operator/value matrix fixed by the Segment specification section 7.3.
 *
 * <p>Only shape is decided here:
 * <ul>
 *   <li>{@code IS_NULL}/{@code IS_NOT_NULL} require no value and reject any supplied value;</li>
 *   <li>{@code IN}/{@code NOT_IN} require a non-empty LIST;</li>
 *   <li>{@code BETWEEN} requires a RANGE;</li>
 *   <li>every other operator requires a scalar and rejects a LIST or RANGE.</li>
 * </ul>
 *
 * <p>Whether T04 Metadata permits a well-shaped operator for a particular definition — type compatibility,
 * NULL semantics, currency and enum membership — remains a T07 decision and is deliberately not decided
 * here.
 */
public final class OperatorValueRules {

    private OperatorValueRules() {
    }

    /** Validates the condition shape for the operator, or throws the stable operator/value failure. */
    public static void require(ComparisonOperator operator, ConditionValue condition) {
        if (operator == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A condition requires an operator");
        }
        if (condition == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, "A condition requires a value state");
        }
        if (operator.isNullOperator()) {
            if (!(condition instanceof NoValueCondition)) {
                throw shape("A NULL operator must not carry a value");
            }
            return;
        }
        if (condition instanceof NoValueCondition) {
            throw shape("Operator requires a value");
        }
        TypedValue value = ((TypedValueCondition) condition).value();
        switch (operator) {
            case IN, NOT_IN -> requireList(value);
            case BETWEEN -> requireRange(value);
            default -> requireScalar(value);
        }
    }

    private static void requireList(TypedValue value) {
        if (!(value instanceof ListValue)) {
            throw shape("Operator requires a non-empty LIST value");
        }
    }

    private static void requireRange(TypedValue value) {
        if (!(value instanceof RangeValue)) {
            throw shape("Operator requires a RANGE value");
        }
    }

    private static void requireScalar(TypedValue value) {
        if (!(value instanceof ScalarValue)) {
            throw shape("Operator requires a scalar value");
        }
    }

    private static SegmentDslException shape(String message) {
        return new SegmentDslException(SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, message);
    }
}