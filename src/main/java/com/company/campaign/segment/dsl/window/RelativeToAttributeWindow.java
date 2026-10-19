package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

/** A subject-relative {@code [anchor + startOffset, anchor + endOffset)} window. */
public record RelativeToAttributeWindow(
        String anchorDefinitionId,
        int startOffset,
        int endOffset,
        WindowUnit unit) implements TimeWindow {

    public RelativeToAttributeWindow {
        if (anchorDefinitionId == null || anchorDefinitionId.isBlank() || anchorDefinitionId.length() > 128) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID,
                    "A relative window requires a bounded anchor definition ID");
        }
        if (unit == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A relative window requires an offset unit");
        }
        int limit = SegmentDslLimits.MAX_WINDOW_AMOUNT;
        if (startOffset < -limit || startOffset > limit || endOffset < -limit || endOffset > limit
                || startOffset >= endOffset) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A relative window requires ordered bounded offsets");
        }
    }

    @Override
    public WindowType type() {
        return WindowType.RELATIVE_TO_ATTRIBUTE;
    }
}
