package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * A unary {@code NOT} with exactly one child.
 *
 * <p>The singular {@code child} representation is deliberate: there is no {@code children} alternative to
 * accept, so the zero-child and multi-child forms are unrepresentable rather than merely validated away.
 * Double negation is preserved as written; simplifying it is a T07 canonicalization decision, not a T05 one.
 */
public record NotPredicate(String nodeId, Predicate child) implements Predicate {

    public NotPredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        if (child == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A NOT predicate requires exactly one child");
        }
    }

    @Override
    public PredicateType type() {
        return PredicateType.NOT;
    }
}