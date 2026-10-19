package com.company.campaign.builder.refine;

import java.util.List;

/**
 * The closed operation discriminator set of the versioned refinement patch.
 *
 * <p>The names are exactly the four operations Segment specification section 16 admits, in the same spelling
 * the T03 corpus refinement vocabulary reserves ({@code ADD_NODE}, {@code REMOVE_NODE}, {@code REPLACE_FIELD},
 * {@code MOVE_NODE}), so a corpus case and a patch name the same edit with the same word. There is deliberately
 * no generic {@code SET}, {@code PATCH} or array-index operation, and no Save/Apply/Publish operation: this
 * contract only edits an in-memory predicate tree.
 *
 * <p>Every operation addresses its target by stable identity. A patch that carries a positional path is
 * rejected by the codec rather than mapped onto some nearest node.
 */
public enum RefinementOpKind {

    /** Add one typed T05 Predicate subtree under an {@code ALL}/{@code ANY} group. */
    ADD_NODE,

    /** Remove one existing non-root node from an {@code ALL}/{@code ANY} group. */
    REMOVE_NODE,

    /** Replace one allowlisted semantic scalar/value field of one existing node. */
    REPLACE_FIELD,

    /** Move one existing non-root node to another position addressed by stable identity. */
    MOVE_NODE;

    private static final List<RefinementOpKind> ORDERED = List.of(
            ADD_NODE, REMOVE_NODE, REPLACE_FIELD, MOVE_NODE);

    /** Every operation kind, in the canonical declared order. */
    public static List<RefinementOpKind> ordered() {
        return ORDERED;
    }

    /**
     * Resolves a wire discriminator, or throws the stable refusal.
     *
     * <p>A discriminator that differs from a known kind only by case is reported as a casing failure rather
     * than as an unknown operation, so an alternate spelling is never silently accepted.
     */
    public static RefinementOpKind fromWire(String wire) {
        if (wire == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "An operation requires its discriminator");
        }
        for (RefinementOpKind kind : ORDERED) {
            if (kind.name().equals(wire)) {
                return kind;
            }
        }
        for (RefinementOpKind kind : ORDERED) {
            if (kind.name().equalsIgnoreCase(wire)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.OPERATION_CASING_INVALID,
                        "An operation discriminator must use its exact canonical casing");
            }
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.UNKNOWN_OPERATION, "Unknown refinement operation");
    }
}