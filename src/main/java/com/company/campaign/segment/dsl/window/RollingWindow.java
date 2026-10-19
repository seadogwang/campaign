package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

/**
 * A ROLLING window: a duration counted back from {@code asOf}.
 *
 * <p>The amount is a positive, bounded integer in its declared unit. The interval {@code [asOf - duration,
 * asOf)} is computed later by T07 from the trusted execution context; this record never resolves it.
 */
public record RollingWindow(int amount, WindowUnit unit) implements TimeWindow {

    public RollingWindow {
        if (unit == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A ROLLING window requires a unit");
        }
        if (amount <= 0) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A ROLLING window amount must be positive");
        }
        if (amount > SegmentDslLimits.MAX_WINDOW_AMOUNT) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "A ROLLING window amount exceeds the supported bound");
        }
    }

    @Override
    public WindowType type() {
        return WindowType.ROLLING;
    }
}