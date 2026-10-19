package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.contract.SegmentDslReferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Node identity validation for the predicate tree.
 *
 * <p>Node IDs use the shared T01 bounded reference grammar, so one identifier is valid in every contract
 * rather than drifting per node type. Uniqueness is checked over the whole tree, including across separate
 * branches and inside an EXISTS node; duplicates are never silently deduplicated or renumbered, and the
 * nesting depth is bounded so a programmatically built tree is held to the same limit the parser enforces.
 */
public final class PredicateNodeIds {

    private PredicateNodeIds() {
    }

    /** Validates a stable node ID against the bounded reference grammar. */
    public static String requireNodeId(String nodeId) {
        return SegmentDslReferences.requireNodeId(nodeId);
    }

    /** Validates a bounded reference identifier such as an attribute, metric or relation ID. */
    public static String requireReferenceId(String value, String field) {
        return SegmentDslReferences.requireReferenceId(value, field);
    }

    /** Rejects duplicate node IDs and excessive nesting anywhere in the tree. */
    public static void requireUnique(Predicate root) {
        if (root == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A predicate tree requires a root predicate");
        }
        walk(root, new LinkedHashSet<>(), 1);
    }

    private static void walk(Predicate node, Set<String> seen, int depth) {
        if (depth > SegmentDslLimits.MAX_PREDICATE_DEPTH) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "Predicate nesting exceeds the supported depth");
        }
        if (!seen.add(node.nodeId())) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.DUPLICATE_NODE_ID, "Duplicate node ID in the predicate tree");
        }
        switch (node) {
            case GroupPredicate group -> group.children().forEach(child -> walk(child, seen, depth + 1));
            case NotPredicate not -> walk(not.child(), seen, depth + 1);
            case ExistsPredicate exists -> walk(exists.predicate(), seen, depth + 1);
            case AttributePredicate ignored -> {
            }
            case MetricPredicate ignored -> {
            }
            case MetricExpressionPredicate ignored -> {
            }
            // A reference is a leaf: it owns its own stable node identity and carries no nested predicate.
            case AudienceReferencePredicate ignored -> {
            }
        }
    }
}
