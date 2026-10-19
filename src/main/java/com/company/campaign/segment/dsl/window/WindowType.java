package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * The closed discriminator set for the {@code TimeWindow} union.
 *
 * <p>Calendar windows are a Phase B capability, so no fourth value exists here and an unknown type is
 * rejected as an unknown discriminator rather than mapped onto a duration window.
 */
public enum WindowType {
    ROLLING,
    LIFETIME,
    FIXED,
    RELATIVE_TO_ATTRIBUTE,
    SELECTED_EVENT,
    RELATIVE_TO_EVENT;

    /** Resolves a wire window type, or throws the stable unknown-discriminator failure. */
    public static WindowType fromWire(String wireType) {
        for (WindowType type : values()) {
            if (type.name().equals(wireType)) {
                return type;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR, "Unknown TimeWindow type");
    }
}
