package com.company.campaign.segment.dsl.predicate;

/**
 * The absent condition value carried by {@code IS_NULL} and {@code IS_NOT_NULL}.
 *
 * <p>A value-less operand is not a missing field to be defaulted: it is a distinct state, and the codec
 * emits no {@code value} field for it.
 */
public record NoValueCondition() implements ConditionValue {

    /** Single shared instance; the record is immutable and equal to any other empty instance. */
    public static final NoValueCondition INSTANCE = new NoValueCondition();
}