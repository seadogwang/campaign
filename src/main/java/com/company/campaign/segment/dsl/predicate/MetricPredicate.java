package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.window.TimeWindow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An aggregate metric comparison over a required time window.
 *
 * <p>A metric always carries its window, because the same aggregate over a rolling, lifetime or fixed window
 * is a different question. Resolving the metric, its aggregate, empty-set behavior and window boundaries is
 * a T07/T08 concern; T05 fixes the reference grammar, the window structure and the operand shape.
 */
public record MetricPredicate(
        String nodeId,
        String metricId,
        TimeWindow window,
        List<MetricScopeFilter> scopeFilters,
        ComparisonOperator operator,
        ConditionValue condition
) implements Predicate {

    public MetricPredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        metricId = PredicateNodeIds.requireReferenceId(metricId, "metricId");
        if (window == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A metric predicate requires a time window");
        }
        if (condition == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, "A metric predicate requires a condition");
        }
        scopeFilters = validateScopeFilters(scopeFilters);
        java.util.Set<String> allFilterIds = new java.util.HashSet<>();
        scopeFilters.forEach(filter -> allFilterIds.add(filter.filterId()));
        java.util.List<MetricScopeFilter> selectorFilters = switch (window) {
            case com.company.campaign.segment.dsl.window.SelectedEventWindow selected ->
                    selected.selectorScopeFilters();
            case com.company.campaign.segment.dsl.window.RelativeToEventWindow relative ->
                    relative.selectorScopeFilters();
            default -> java.util.List.of();
        };
        for (MetricScopeFilter filter : selectorFilters) {
            if (!allFilterIds.add(filter.filterId())) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.SHAPE_INVALID,
                        "Metric and selector scope filter IDs must be unique together");
            }
        }
        OperatorValueRules.require(operator, condition);
    }

    public MetricPredicate(
            String nodeId,
            String metricId,
            TimeWindow window,
            ComparisonOperator operator,
            ConditionValue condition) {
        this(nodeId, metricId, window, List.of(), operator, condition);
    }

    @Override
    public PredicateType type() {
        return PredicateType.METRIC;
    }

    private static List<MetricScopeFilter> validateScopeFilters(List<MetricScopeFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        if (filters.size() > com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_METRIC_SCOPE_FILTERS) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "A metric has too many scope filters");
        }
        Set<String> ids = new HashSet<>();
        for (MetricScopeFilter filter : filters) {
            if (filter == null || !ids.add(filter.filterId())) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.SHAPE_INVALID,
                        "Metric scope filters must be non-null and unique by filter ID");
            }
        }
        return List.copyOf(filters);
    }
}
