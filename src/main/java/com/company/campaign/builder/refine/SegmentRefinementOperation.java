package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.predicate.Predicate;

/**
 * The closed operation union of the versioned refinement patch.
 *
 * <p>Four variants, one per {@link RefinementOpKind}. Every variant addresses its target by a stable
 * identifier — an operation id, a node id and, where a position is needed, a stable destination parent and/or
 * a stable sibling anchor. There is no positional field anywhere in the union, so an array index, a JSON
 * Pointer or a JSON Patch path cannot be expressed at all.
 *
 * <p>The union is sealed, so the editor switches over the closed set instead of casting, and it reuses the
 * accepted T05 {@link Predicate} subtree type for {@code ADD_NODE} rather than introducing a second DSL.
 */
public sealed interface SegmentRefinementOperation {

    /** Stable identity of this operation inside its patch; used for deterministic issue and audit references. */
    String operationId();

    /** The exact wire discriminator of this operation. */
    RefinementOpKind kind();

    /**
     * Add one typed T05 Predicate subtree under an {@code ALL}/{@code ANY} group.
     *
     * <p>{@code parentNodeId} names the destination group. {@code beforeNodeId} is the optional stable sibling
     * anchor; when it is null the subtree is appended. The subtree carries its own stable node ids and must not
     * collide with the current tree or with an earlier addition.
     */
    record AddNode(
            String operationId,
            String parentNodeId,
            String beforeNodeId,
            Predicate node
    ) implements SegmentRefinementOperation {

        public AddNode {
            operationId = RefinementIdentifiers.require(
                    operationId, SegmentRefinementErrorCodes.OPERATION_ID_INVALID, "opId");
            parentNodeId = RefinementIdentifiers.require(
                    parentNodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "parentNodeId");
            beforeNodeId = beforeNodeId == null ? null : RefinementIdentifiers.require(
                    beforeNodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "beforeNodeId");
            if (node == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NULL_FIELD, "ADD_NODE requires its typed predicate subtree");
            }
        }

        @Override
        public RefinementOpKind kind() {
            return RefinementOpKind.ADD_NODE;
        }
    }

    /**
     * Remove one existing non-root node.
     *
     * <p>Only removal from an {@code ALL}/{@code ANY} parent is representable as a supported case: removing the
     * sole child of a {@code NOT} or the body of an {@code EXISTS} would create an invalid transient structure,
     * so the editor refuses it with a stable code rather than producing a broken intermediate tree.
     */
    record RemoveNode(String operationId, String nodeId) implements SegmentRefinementOperation {

        public RemoveNode {
            operationId = RefinementIdentifiers.require(
                    operationId, SegmentRefinementErrorCodes.OPERATION_ID_INVALID, "opId");
            nodeId = RefinementIdentifiers.require(
                    nodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "nodeId");
        }

        @Override
        public RefinementOpKind kind() {
            return RefinementOpKind.REMOVE_NODE;
        }
    }

    /**
     * Replace one allowlisted semantic scalar/value field of one existing node.
     *
     * <p>The target {@code nodeId}, the node kind and every unrelated field and subtree are preserved. The
     * {@link RefinementField} and its {@link RefinementReplacement} form a closed typed pair, so an unsupported
     * or mismatched field/value/node combination fails with a stable code instead of a generic cast.
     */
    record ReplaceField(
            String operationId,
            String nodeId,
            RefinementField field,
            RefinementReplacement replacement
    ) implements SegmentRefinementOperation {

        public ReplaceField {
            operationId = RefinementIdentifiers.require(
                    operationId, SegmentRefinementErrorCodes.OPERATION_ID_INVALID, "opId");
            nodeId = RefinementIdentifiers.require(
                    nodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "nodeId");
            if (field == null || replacement == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NULL_FIELD, "REPLACE_FIELD requires its field and value");
            }
            if (field.valueKind() != replacement.valueKind()) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                        "A replacement value does not match the wire type its field requires");
            }
        }

        @Override
        public RefinementOpKind kind() {
            return RefinementOpKind.REPLACE_FIELD;
        }
    }

    /**
     * Move one existing non-root node to a position addressed by stable identity.
     *
     * <p>The node is first detached from its current parent and then inserted immediately before the optional
     * stable sibling anchor under the destination group, or appended when no anchor is named. Because both the
     * node and the anchor are located by identity after the detach, the result of a same-parent move never
     * depends on the original array index.
     */
    record MoveNode(
            String operationId,
            String nodeId,
            String parentNodeId,
            String beforeNodeId
    ) implements SegmentRefinementOperation {

        public MoveNode {
            operationId = RefinementIdentifiers.require(
                    operationId, SegmentRefinementErrorCodes.OPERATION_ID_INVALID, "opId");
            nodeId = RefinementIdentifiers.require(
                    nodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "nodeId");
            parentNodeId = RefinementIdentifiers.require(
                    parentNodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "parentNodeId");
            beforeNodeId = beforeNodeId == null ? null : RefinementIdentifiers.require(
                    beforeNodeId, SegmentRefinementErrorCodes.NODE_ID_INVALID, "beforeNodeId");
        }

        @Override
        public RefinementOpKind kind() {
            return RefinementOpKind.MOVE_NODE;
        }
    }
}