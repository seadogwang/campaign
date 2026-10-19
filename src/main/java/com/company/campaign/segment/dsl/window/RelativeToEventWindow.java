package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;

import java.util.List;

/** Per-subject interval anchored to a deterministically selected event. */
public record RelativeToEventWindow(
        String selectorId,
        List<MetricScopeFilter> selectorScopeFilters,
        int startOffset,
        int endOffset,
        WindowUnit unit,
        boolean excludeAnchorEvent) implements TimeWindow {

    public RelativeToEventWindow {
        selectorId = PredicateNodeIds.requireReferenceId(selectorId, "selectorId");
        selectorScopeFilters = SelectedEventWindow.filters(selectorScopeFilters);
        if (unit == null || startOffset < -com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_WINDOW_AMOUNT
                || startOffset > com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_WINDOW_AMOUNT
                || endOffset < -com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_WINDOW_AMOUNT
                || endOffset > com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_WINDOW_AMOUNT
                || startOffset >= endOffset) {
            throw new SegmentDslException(SegmentDslErrorCodes.TIME_WINDOW_INVALID,
                    "A relative event window requires ordered bounded offsets and a unit");
        }
    }

    @Override
    public WindowType type() {
        return WindowType.RELATIVE_TO_EVENT;
    }
}
