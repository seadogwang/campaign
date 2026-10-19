package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.window.TimeWindow;

/**
 * A relation-scoped existence check with a required window and one nested predicate.
 *
 * <p>The nested predicate remains structurally beneath this node, so every condition inside it is understood
 * to apply to the same related row. Two separate EXISTS nodes are never combined or flattened. Which relation
 * is compatible with the root subject, and how the nested tree compiles, are T07/T08 concerns.
 */
public record ExistsPredicate(
        String nodeId,
        String relationId,
        TimeWindow window,
        Predicate predicate
) implements Predicate {

    public ExistsPredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        relationId = PredicateNodeIds.requireReferenceId(relationId, "relationId");
        if (window == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "An EXISTS predicate requires a time window");
        }
        if (predicate == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "An EXISTS predicate requires a nested predicate");
        }
    }

    @Override
    public PredicateType type() {
        return PredicateType.EXISTS;
    }
}