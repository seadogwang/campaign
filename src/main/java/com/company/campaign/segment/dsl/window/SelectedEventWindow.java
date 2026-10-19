package com.company.campaign.segment.dsl.window;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Restricts a metric to the single event selected by governed Metadata. */
public record SelectedEventWindow(
        String selectorId,
        List<MetricScopeFilter> selectorScopeFilters) implements TimeWindow {

    public SelectedEventWindow {
        selectorId = PredicateNodeIds.requireReferenceId(selectorId, "selectorId");
        selectorScopeFilters = filters(selectorScopeFilters);
    }

    @Override
    public WindowType type() {
        return WindowType.SELECTED_EVENT;
    }

    static List<MetricScopeFilter> filters(List<MetricScopeFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        for (MetricScopeFilter filter : filters) {
            if (filter == null || !ids.add(filter.filterId())) {
                throw new SegmentDslException(SegmentDslErrorCodes.SHAPE_INVALID,
                        "Selector scope filters must be non-null and unique by filter ID");
            }
        }
        return List.copyOf(filters);
    }
}
