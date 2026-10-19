package com.company.campaign.segment.dsl.value;

/**
 * The closed, typed value union of the editable Segment DSL.
 *
 * <p>The union is exhaustive and intentionally narrow: there is no {@code value:any}, no raw JSON object or
 * array member and no catch-all subtype. A JSON shape that is not one of the ten declared kinds cannot be
 * represented here, and a declared kind is stored as its own typed record rather than as generic text.
 *
 * <p>{@link ScalarValue} is the subset admissible as a LIST item or a RANGE bound. Keeping it a sealed
 * supertype means a nested LIST or RANGE bound is rejected by the type system before any runtime check.
 */
public sealed interface TypedValue permits ScalarValue, ListValue, RangeValue {

    /** The exact wire discriminator for this value. */
    ValueKind kind();
}