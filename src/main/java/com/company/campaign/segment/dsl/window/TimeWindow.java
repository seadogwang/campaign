package com.company.campaign.segment.dsl.window;

/**
 * The closed time-window union of the editable Segment DSL.
 *
 * <p>T05 fixes only the declared structure: which window kind, with which bounded amount and unit, and
 * which explicit boundary instants. Deriving concrete boundaries against {@code asOf}, applying the trusted
 * timezone policy and reporting the LIFETIME watermark limitation are T07 obligations, so nothing here
 * consults a clock, the JVM default timezone or a silent boundary default.
 */
public sealed interface TimeWindow permits RollingWindow, LifetimeWindow, FixedWindow, RelativeToAttributeWindow,
        SelectedEventWindow, RelativeToEventWindow {

    /** The exact wire discriminator for this window. */
    WindowType type();
}
