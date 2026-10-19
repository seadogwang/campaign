package com.company.campaign.segment.dsl.window;

/**
 * A LIFETIME window: all retained history strictly before the exclusive {@code dataWatermark} boundary.
 *
 * <p>It carries no amount, unit, start or end. Deriving the effective boundary and reporting the retention
 * limitation are T07 responsibilities, so the record deliberately has no fields to misinterpret.
 */
public record LifetimeWindow() implements TimeWindow {

    @Override
    public WindowType type() {
        return WindowType.LIFETIME;
    }
}
