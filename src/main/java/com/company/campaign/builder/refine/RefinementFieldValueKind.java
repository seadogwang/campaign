package com.company.campaign.builder.refine;

/**
 * The closed set of replacement value kinds a {@link RefinementField} may carry.
 *
 * <p>Pairing each field with exactly one value kind is what makes the field/value union closed: a
 * {@code window} replacement can only be a T05 {@code TimeWindow}, a {@code value} replacement can only be a
 * T05 condition operand and an {@code operator} replacement can only be a T04 operator. A mismatch is refused
 * with a stable code while the patch is read, so nothing is ever cast at apply time.
 */
public enum RefinementFieldValueKind {

    /** A T05 {@code TimeWindow} replacement. */
    WINDOW,

    /** A T05 condition operand replacement (a typed value, or the absent value of a NULL operator). */
    CONDITION,

    /** A T04 {@code ComparisonOperator} replacement. */
    OPERATOR,

    /** An exact immutable audience-version pin replacement, carrying both the expected and the new pin. */
    REFERENCE_PIN
}