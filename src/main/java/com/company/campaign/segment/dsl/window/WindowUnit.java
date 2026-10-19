package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * The Phase A duration units allowed in a ROLLING window.
 *
 * <p>Only {@code DAY} (24 hours) and {@code WEEK} (7 days) are duration units. {@code MONTH} and
 * {@code YEAR} exist in Metadata window capabilities but are intentionally absent here: a calendar month
 * has no fixed duration, so accepting one as a rolling amount would silently pick a wrong interval.
 */
public enum WindowUnit {
    DAY,
    WEEK;

    /** Resolves a wire unit, rejecting every non-duration and unknown name with the stable window code. */
    public static WindowUnit fromWire(String wireUnit) {
        for (WindowUnit unit : values()) {
            if (unit.name().equals(wireUnit)) {
                return unit;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.TIME_WINDOW_INVALID,
                "Unsupported ROLLING window unit; Phase A allows DAY and WEEK only");
    }
}