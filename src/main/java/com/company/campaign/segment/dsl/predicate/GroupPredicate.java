package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

import java.util.List;

/**
 * An {@code ALL} or {@code ANY} group with a non-empty, ordered, immutable child list.
 *
 * <p>Children keep the exact declared order: T05 never sorts, flattens or distributes them. The group type is
 * restricted to {@code ALL}/{@code ANY}; a {@code NOT} is always the singular {@link NotPredicate}.
 */
public record GroupPredicate(String nodeId, PredicateType type, List<Predicate> children) implements Predicate {

    public GroupPredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        if (type != PredicateType.ALL && type != PredicateType.ANY) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A group predicate must be ALL or ANY");
        }
        if (children == null || children.isEmpty()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A group predicate requires at least one child");
        }
        for (Predicate child : children) {
            if (child == null) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.SHAPE_INVALID, "A group predicate must not contain a null child");
            }
        }
        children = List.copyOf(children);
    }
}