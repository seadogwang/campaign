package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.TemporalGrammar;

import java.time.OffsetDateTime;

/**
 * A FIXED window: an explicit {@code [start, end)} interval on the instant timeline.
 *
 * <p>Both boundaries use the exact shared temporal grammar: they must carry an explicit numeric offset or
 * {@code Z}, with an uppercase {@code T}/{@code Z} and a bounded fractional part. {@code start} must be strictly
 * before {@code end} once each is resolved to an instant. Comparing instants is representation-intrinsic and
 * consults no clock or default timezone. The declared lexemes are preserved for round-tripping.
 */
public record FixedWindow(String start, String end) implements TimeWindow {

    public FixedWindow {
        OffsetDateTime startInstant = requireInstant(start, "start");
        OffsetDateTime endInstant = requireInstant(end, "end");
        if (!startInstant.toInstant().isBefore(endInstant.toInstant())) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID,
                    "A FIXED window start must be strictly before its end");
        }
    }

    private static OffsetDateTime requireInstant(String value, String field) {
        if (!TemporalGrammar.isExactOffsetDateTime(value)) {
            // No cause is attached: a parse exception would carry the rejected boundary text outward.
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID,
                    "A FIXED window " + field + " must be an exact offset date-time such as 2026-01-01T00:00:00Z");
        }
        return TemporalGrammar.parseExactOffsetDateTime(value);
    }

    @Override
    public WindowType type() {
        return WindowType.FIXED;
    }
}