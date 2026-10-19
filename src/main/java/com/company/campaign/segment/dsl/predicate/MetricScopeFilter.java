package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/** One allowlisted Attribute comparison evaluated inside a Metric aggregate's row scope. */
public record MetricScopeFilter(
        String filterId,
        String attributeId,
        ComparisonOperator operator,
        ConditionValue condition
) {

    public MetricScopeFilter {
        filterId = PredicateNodeIds.requireReferenceId(filterId, "scopeFilter.filterId");
        attributeId = PredicateNodeIds.requireReferenceId(attributeId, "scopeFilter.attributeId");
        if (condition == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID,
                    "A metric scope filter requires a condition");
        }
        OperatorValueRules.require(operator, condition);
    }
}
