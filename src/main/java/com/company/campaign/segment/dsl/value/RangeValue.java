package com.company.campaign.segment.dsl.value;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

import java.util.Optional;

/**
 * A bounded range with explicit inclusion flags.
 *
 * <p>Both bounds are typed as {@link ScalarValue}, so a LIST or RANGE bound and a {@code null} bound are not
 * representable. Same-type and semantic ordering are a T07 concern, with one documented exception: when both
 * bounds share a kind whose order is intrinsic to its representation, an inverted or empty range is
 * incoherent at the representation level and is rejected here. Cross-kind bound pairs are preserved exactly
 * as declared and left for T07.
 */
public record RangeValue(
        ScalarValue lower,
        ScalarValue upper,
        boolean includeLower,
        boolean includeUpper
) implements TypedValue {

    public RangeValue {
        if (lower == null || upper == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A RANGE value requires non-null lower and upper bounds");
        }
        Optional<Integer> order = IntrinsicOrder.compare(lower, upper);
        if (order.isPresent()) {
            int comparison = order.get();
            if (comparison > 0) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A RANGE value has inverted bounds");
            }
            if (comparison == 0 && !(includeLower && includeUpper)) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A RANGE value is empty at a single point");
            }
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.RANGE;
    }
}