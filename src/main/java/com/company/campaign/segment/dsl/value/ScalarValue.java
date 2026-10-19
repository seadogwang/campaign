package com.company.campaign.segment.dsl.value;

/**
 * The scalar subset of {@link TypedValue}: everything except LIST and RANGE.
 *
 * <p>LIST items and RANGE bounds are typed as this interface, so a nested LIST, a nested RANGE or a {@code
 * null} element is not representable. Semantic element-type compatibility between a definition and its
 * operand remains a T07 decision.
 */
public sealed interface ScalarValue extends TypedValue
        permits StringValue, IntegerValue, DecimalValue, BooleanValue, DateValue, DateTimeValue, EnumValue,
        MoneyValue {
}