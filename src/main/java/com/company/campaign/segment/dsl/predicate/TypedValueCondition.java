package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.value.TypedValue;

/**
 * A present typed operand, required by every operator except {@code IS_NULL}/{@code IS_NOT_NULL}.
 *
 * <p>Whether that operand must be a scalar, a LIST or a RANGE is decided by the operator and enforced by
 * {@link OperatorValueRules}.
 */
public record TypedValueCondition(TypedValue value) implements ConditionValue {

    public TypedValueCondition {
        if (value == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, "A condition value must not be null");
        }
    }
}