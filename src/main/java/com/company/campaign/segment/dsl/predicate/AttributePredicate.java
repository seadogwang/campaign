package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/**
 * An attribute comparison: a T04 attribute reference, a T04 operator and a shape-checked condition.
 *
 * <p>Whether the operator is allowed for the referenced attribute and whether the operand type is compatible
 * are T07 decisions against the bound Metadata Snapshot; T05 fixes only the identifier grammar and the
 * operator/value shape.
 */
public record AttributePredicate(
        String nodeId,
        String attributeId,
        ComparisonOperator operator,
        ConditionValue condition
) implements Predicate {

    public AttributePredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        attributeId = PredicateNodeIds.requireReferenceId(attributeId, "attributeId");
        if (condition == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, "An attribute predicate requires a condition");
        }
        OperatorValueRules.require(operator, condition);
    }

    @Override
    public PredicateType type() {
        return PredicateType.ATTRIBUTE;
    }
}