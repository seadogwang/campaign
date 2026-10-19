package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.value.TypedValue;

/**
 * Distinguishes an operator's absent value from a present {@link TypedValue}.
 *
 * <p>The two cases are separate records rather than a nullable typed field, so a strict JSON document can
 * neither omit a value the operator requires nor inject one into {@code IS_NULL}/{@code IS_NOT_NULL}. There
 * is no {@code Object}, {@code JsonNode} or raw-map variant.
 */
public sealed interface ConditionValue permits NoValueCondition, TypedValueCondition {

    /** The value shared by {@code IS_NULL} and {@code IS_NOT_NULL}: none. */
    static NoValueCondition none() {
        return NoValueCondition.INSTANCE;
    }

    /** A present typed operand. */
    static TypedValueCondition of(TypedValue value) {
        return new TypedValueCondition(value);
    }
}