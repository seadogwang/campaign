package com.company.campaign.segment.dsl.value;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

import java.util.List;

/**
 * A non-empty, immutable list of scalar values.
 *
 * <p>Elements are typed as {@link ScalarValue}, so a nested LIST or RANGE and a {@code null} entry cannot
 * even be constructed. The declared order is preserved because it is part of the editable document. Whether
 * the elements are compatible with the referenced definition is a T07 decision.
 */
public record ListValue(List<ScalarValue> items) implements TypedValue {

    public ListValue {
        if (items == null || items.isEmpty()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A LIST value must be non-empty");
        }
        if (items.size() > SegmentDslLimits.MAX_LIST_ITEMS) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "A LIST value exceeds the item-count bound");
        }
        for (ScalarValue item : items) {
            if (item == null) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A LIST value must not contain a null entry");
            }
        }
        // Defensive copy: a caller that keeps and mutates its own list cannot change the published value.
        items = List.copyOf(items);
    }

    @Override
    public ValueKind kind() {
        return ValueKind.LIST;
    }
}